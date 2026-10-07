package com.dumaru.pawprint.client.placement;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
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

/**
 * Placements for the current server and dimension, and the ghost blocks derived from them.
 * Placements are saved per server and dimension so they come back after reconnecting.
 */
public final class PlacementManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int REFRESH_INTERVAL_TICKS = 10;

    private static final List<Placement> placements = new ArrayList<>();
    private static int active = -1;
    /** Placement mode: placements are shown while clicks still go to the server, for building them for real. */
    private static boolean viewing;
    private static @Nullable String server;
    private static @Nullable String dimension;

    private static List<GhostBlock> ghosts = List.of();
    private static Long2ObjectMap<GhostBlock> ghostsByPos = new Long2ObjectOpenHashMap<>();
    private static int ticksUntilRefresh;

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
            refreshNow();
        }
        if (minecraft.level != null && --ticksUntilRefresh <= 0) {
            refresh(minecraft.level);
        }
    }

    public static List<GhostBlock> ghosts() {
        return ghosts;
    }

    public static @Nullable GhostBlock ghostAt(long packedPos) {
        return ghostsByPos.get(packedPos);
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

    /** Call after changing a placement so the overlay and the saved file update right away. */
    public static void changed() {
        save();
        refreshNow();
    }

    private static void refreshNow() {
        ticksUntilRefresh = 0;
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            refresh(level);
        } else {
            ghosts = List.of();
            ghostsByPos = new Long2ObjectOpenHashMap<>();
        }
    }

    private static void refresh(Level level) {
        ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
        Long2ObjectMap<GhostBlock> newByPos = new Long2ObjectOpenHashMap<>();
        for (Placement placement : placements) {
            Blueprint blueprint = placement.blueprint();
            for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
                BlockPos pos = placement.toWorld(entry.getLongKey());
                BlockState state = blueprint.state(entry.getIntValue());
                GhostBlock ghost;
                if (state == null) {
                    ghost = new GhostBlock(pos, null, BlockStatus.MISSING);
                } else {
                    BlockState target = placement.toWorld(state);
                    ghost = new GhostBlock(pos, target, BlockStatus.compare(target, level.getBlockState(pos)));
                }
                newByPos.put(pos.asLong(), ghost);
            }
            for (long removal : blueprint.removals()) {
                BlockPos pos = placement.toWorld(removal);
                if (!level.getBlockState(pos).isAir()) {
                    GhostBlock ghost = new GhostBlock(pos, null, BlockStatus.REMOVE);
                    newByPos.put(pos.asLong(), ghost);
                }
            }
        }
        // The draft being edited is drawn like a placement, but only in edit mode; it wins where both overlap.
        Iterable<Long2ObjectMap.Entry<BlockState>> draftCells = EditMode.isActive()
                ? Draft.cells().long2ObjectEntrySet()
                : List.of();
        for (Long2ObjectMap.Entry<BlockState> entry : draftCells) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            BlockState target = entry.getValue();
            BlockState actual = level.getBlockState(pos);
            GhostBlock ghost;
            if (target.isAir()) {
                if (actual.isAir()) {
                    continue;
                }
                ghost = new GhostBlock(pos, null, BlockStatus.REMOVE);
            } else {
                ghost = new GhostBlock(pos, target, BlockStatus.compare(target, actual));
            }
            newByPos.put(pos.asLong(), ghost);
        }
        ghosts = new ArrayList<>(newByPos.values());
        ghostsByPos = newByPos;
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
