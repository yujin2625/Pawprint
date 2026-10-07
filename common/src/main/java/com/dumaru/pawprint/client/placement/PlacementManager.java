package com.dumaru.pawprint.client.placement;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.studio.Studio;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Placements for the current server and dimension, and the ghost blocks derived from them.
 * Placements are saved per server and dimension so they come back after reconnecting.
 */
public final class PlacementManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Blocks compared with the world per tick, spread over the nearest sections first. */
    private static final int STATUS_BUDGET_PER_TICK = 40_000;
    /** Checked right after a change, so the nearby overlay updates without waiting. */
    private static final int STATUS_BUDGET_ON_CHANGE = 200_000;

    private static final List<Placement> placements = new ArrayList<>();
    private static int active = -1;
    /** Placement mode: placements are shown while clicks still go to the server, for building them for real. */
    private static boolean viewing;
    private static @Nullable String server;
    private static @Nullable String dimension;

    /** Ghosts of all placements, and separately of the draft, so editing the draft never rebuilds big placements. */
    private static final GhostStore placementGhosts = new GhostStore();
    private static final GhostStore draftGhosts = new GhostStore();
    /** Frees a section's GPU mesh; set by the renderer. */
    private static Consumer<GhostStore.Section> meshRelease = section -> {
    };

    private PlacementManager() {
    }

    public static void tick(Minecraft minecraft) {
        String newServer = ClientContext.server();
        String newDimension = ClientContext.dimension();
        if (!Objects.equals(newServer, server) || !Objects.equals(newDimension, dimension)) {
            save();
            placements.clear();
            active = -1;
            server = newServer;
            dimension = newDimension;
            load();
            Draft.switchContext(newServer, newDimension);
            rebuildPlacements();
            rebuildDraft();
            Studio.onWorldChanged(minecraft, newServer, newDimension);
        }
        if (minecraft.level != null && minecraft.player != null) {
            Vec3 center = minecraft.gameRenderer.getMainCamera().getPosition();
            double range = Pawprint.config().ghostRenderDistance;
            placementGhosts.updateStatus(minecraft.level, center, range, STATUS_BUDGET_PER_TICK);
            draftGhosts.updateStatus(minecraft.level, center, range, STATUS_BUDGET_PER_TICK);
        }
    }

    public static GhostStore placementGhosts() {
        return placementGhosts;
    }

    public static GhostStore draftGhosts() {
        return draftGhosts;
    }

    public static void setMeshRelease(Consumer<GhostStore.Section> release) {
        meshRelease = release;
    }

    /** The planned block at a position when it is still missing (draft first), for culling between ghosts. */
    public static @Nullable BlockState missingTarget(int x, int y, int z) {
        BlockState draft = draftGhosts.missingTarget(x, y, z);
        return draft != null ? draft : placementGhosts.missingTarget(x, y, z);
    }

    public static List<Placement> placements() {
        return Collections.unmodifiableList(placements);
    }

    public static @Nullable Placement active() {
        return active >= 0 && active < placements.size() ? placements.get(active) : null;
    }

    /** Ghosts are drawn only in placement mode or edit mode; otherwise the world looks untouched. */
    public static boolean isVisible() {
        return viewing || EditMode.isActive();
    }

    public static boolean isViewing() {
        return viewing;
    }

    public static void setViewing(boolean value) {
        viewing = value;
    }

    /** Chooses which placement the move, rotate and mirror keys act on. */
    public static void setActive(int index) {
        if (index >= 0 && index < placements.size()) {
            active = index;
            save();
        }
    }

    public static void add(Placement placement) {
        placements.add(placement);
        active = placements.size() - 1;
        changed();
    }

    public static void removeActive() {
        if (active() != null) {
            placements.remove(active);
            active = placements.size() - 1;
            changed();
        }
    }

    /** Call after adding, removing, moving, rotating or mirroring a placement. */
    public static void changed() {
        save();
        rebuildPlacements();
    }

    /** Call after the draft changed, or edit mode was toggled (the draft is only shown in edit mode). */
    public static void draftChanged() {
        rebuildDraft();
    }

    private static void rebuildPlacements() {
        int total = 0;
        for (Placement placement : placements) {
            total += placement.blueprint().blocks().size() + placement.blueprint().removals().size();
        }
        long[] positions = new long[total];
        BlockState[] targets = new BlockState[total];
        int n = 0;
        for (Placement placement : placements) {
            Blueprint blueprint = placement.blueprint();
            // Rotate and mirror each palette entry once, not once per block.
            BlockState[] palette = new BlockState[blueprint.palette().size()];
            for (int i = 0; i < palette.length; i++) {
                BlockState state = blueprint.state(i);
                palette[i] = state == null ? null : placement.toWorld(state);
            }
            for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
                positions[n] = placement.toWorldPacked(entry.getLongKey());
                targets[n++] = palette[entry.getIntValue()];
            }
            for (long removal : blueprint.removals()) {
                positions[n] = placement.toWorldPacked(removal);
                targets[n++] = Blocks.AIR.defaultBlockState();
            }
        }
        placementGhosts.build(positions, targets, n, meshRelease);
        checkNearby(placementGhosts);
    }

    private static void rebuildDraft() {
        if (!EditMode.isActive()) {
            draftGhosts.clear(meshRelease);
            return;
        }
        Long2ObjectMap<BlockState> cells = Draft.cells();
        long[] positions = new long[cells.size()];
        BlockState[] targets = new BlockState[cells.size()];
        int n = 0;
        for (Long2ObjectMap.Entry<BlockState> entry : cells.long2ObjectEntrySet()) {
            positions[n] = entry.getLongKey();
            targets[n++] = entry.getValue();
        }
        draftGhosts.build(positions, targets, n, meshRelease);
        checkNearby(draftGhosts);
    }

    private static void checkNearby(GhostStore store) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            store.updateStatus(minecraft.level, minecraft.gameRenderer.getMainCamera().getPosition(),
                    Pawprint.config().ghostRenderDistance, STATUS_BUDGET_ON_CHANGE);
        }
    }

    /**
     * Follows a blueprint file that was renamed, moved ({@code newPath} set) or deleted ({@code newPath} null),
     * both in the current placements and in the saved placements of every other server and dimension.
     */
    public static void pathChanged(String oldPath, @Nullable String newPath) {
        boolean changed = false;
        for (int i = placements.size() - 1; i >= 0; i--) {
            if (placements.get(i).file().equals(oldPath)) {
                if (newPath == null) {
                    placements.remove(i);
                } else {
                    placements.get(i).setFile(newPath);
                }
                changed = true;
            }
        }
        if (changed) {
            active = Math.min(active, placements.size() - 1);
            changed();
        }
        Path folder = Pawprint.dataDir().resolve("placements");
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (var files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
                rewriteSaved(file, oldPath, newPath);
            }
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not update saved placements", e);
        }
    }

    private static void rewriteSaved(Path file, String oldPath, @Nullable String newPath) {
        try {
            SavedPlacements saved;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                saved = GSON.fromJson(reader, SavedPlacements.class);
            }
            if (saved == null || saved.placements == null
                    || saved.placements.stream().noneMatch(entry -> oldPath.equals(entry.file))) {
                return;
            }
            if (newPath == null) {
                saved.placements.removeIf(entry -> oldPath.equals(entry.file));
                saved.active = Math.min(saved.active, saved.placements.size() - 1);
            } else {
                saved.placements.forEach(entry -> {
                    if (oldPath.equals(entry.file)) {
                        entry.file = newPath;
                    }
                });
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(saved, writer);
            }
        } catch (IOException | JsonParseException e) {
            Pawprint.LOG.warn("Could not update {}", file, e);
        }
    }

    // Persistence

    private static final class SavedPlacements {
        int active = -1;
        List<SavedPlacement> placements = new ArrayList<>();
    }

    private static final class SavedPlacement {
        String file = "";
        int[] origin = {0, 0, 0};
        String rotation = Rotation.NONE.name();
        String mirror = Mirror.NONE.name();
    }

    private static @Nullable Path file() {
        if (server == null || dimension == null) {
            return null;
        }
        return Pawprint.dataDir().resolve("placements")
                .resolve(ClientContext.fileSafe(server))
                .resolve(ClientContext.fileSafe(dimension) + ".json");
    }

    private static void load() {
        Path file = file();
        if (file == null || !Files.exists(file)) {
            return;
        }
        SavedPlacements saved;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            saved = GSON.fromJson(reader, SavedPlacements.class);
        } catch (IOException | JsonParseException e) {
            Pawprint.LOG.warn("Could not read placements from {}", file, e);
            return;
        }
        if (saved == null || saved.placements == null) {
            return;
        }
        for (SavedPlacement entry : saved.placements) {
            try {
                Blueprint blueprint = BlueprintLibrary.load(entry.file);
                placements.add(new Placement(entry.file, blueprint,
                        new BlockPos(entry.origin[0], entry.origin[1], entry.origin[2]),
                        Rotation.valueOf(entry.rotation), Mirror.valueOf(entry.mirror)));
            } catch (IOException | RuntimeException e) {
                Pawprint.LOG.warn("Dropping placement of {}: {}", entry.file, e.toString());
            }
        }
        active = Math.min(saved.active, placements.size() - 1);
    }

    private static void save() {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            if (placements.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            SavedPlacements saved = new SavedPlacements();
            saved.active = active;
            for (Placement placement : placements) {
                SavedPlacement entry = new SavedPlacement();
                entry.file = placement.file();
                entry.origin = new int[]{placement.origin().getX(), placement.origin().getY(), placement.origin().getZ()};
                entry.rotation = placement.rotation().name();
                entry.mirror = placement.mirror().name();
                saved.placements.add(entry);
            }
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(saved, writer);
            }
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save placements to {}", file, e);
        }
    }
}
