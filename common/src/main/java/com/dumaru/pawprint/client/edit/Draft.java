package com.dumaru.pawprint.client.edit;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.format.BlueprintMeta;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The blueprint being edited, in world coordinates. An air state means "this block must be removed".
 * One draft exists per server and dimension; it is saved automatically so nothing is lost on disconnect.
 */
public final class Draft {
    private static final int MAX_HISTORY = 100;
    private static final int AUTOSAVE_TICKS = 100;

    private static final Long2ObjectMap<BlockState> cells = new Long2ObjectOpenHashMap<>();
    private static final Deque<List<Change>> undo = new ArrayDeque<>();
    private static final Deque<List<Change>> redo = new ArrayDeque<>();
    private static @Nullable List<Change> recording;
    private static @Nullable String server;
    private static @Nullable String dimension;
    /** Library file the draft was loaded from, if any, so saving can overwrite it. */
    private static @Nullable String sourceFile;
    private static @Nullable BlueprintMeta sourceMeta;
    private static boolean dirty;
    private static int ticksSinceChange;
    private static int revision;

    private record Change(long pos, @Nullable BlockState before, @Nullable BlockState after) {
    }

    private Draft() {
    }

    // Reading

    public static @Nullable BlockState get(long pos) {
        return cells.get(pos);
    }

    public static boolean isEmpty() {
        return cells.isEmpty();
    }

    public static int size() {
        return cells.size();
    }

    public static Long2ObjectMap<BlockState> cells() {
        return cells;
    }

    /** Changes every time the draft changes, so caches can tell they are stale. */
    public static int revision() {
        return revision;
    }

    public static @Nullable String sourceFile() {
        return sourceFile;
    }

    /** Keeps "overwrite original" pointing at the right file when it is renamed or moved. */
    public static void pathChanged(String oldPath, @Nullable String newPath) {
        if (oldPath.equals(sourceFile)) {
            sourceFile = newPath;
            sourceMeta = null;
            dirty = true;
        }
    }

    public static @Nullable BlueprintMeta sourceMeta() {
        return sourceMeta;
    }

    public static boolean canUndo() {
        return !undo.isEmpty();
    }

    public static boolean canRedo() {
        return !redo.isEmpty();
    }

    // Editing. Group the changes of one user action between begin() and end() so they undo together.

    public static void begin() {
        recording = new ArrayList<>();
    }

    public static void set(long pos, @Nullable BlockState state) {
        BlockState before = state == null ? cells.remove(pos) : cells.put(pos, state);
        if (before == state) {
            return;
        }
        if (recording != null) {
            recording.add(new Change(pos, before, state));
        }
        touched();
    }

    public static void end() {
        if (recording != null && !recording.isEmpty()) {
            undo.push(recording);
            while (undo.size() > MAX_HISTORY) {
                undo.removeLast();
            }
            redo.clear();
        }
        recording = null;
    }

    public static boolean undo() {
        List<Change> changes = undo.poll();
        if (changes == null) {
            return false;
        }
        for (int i = changes.size() - 1; i >= 0; i--) {
            apply(changes.get(i).pos(), changes.get(i).before());
        }
        redo.push(changes);
        return true;
    }

    public static boolean redo() {
        List<Change> changes = redo.poll();
        if (changes == null) {
            return false;
        }
        for (Change change : changes) {
            apply(change.pos(), change.after());
        }
        undo.push(changes);
        return true;
    }

    private static void apply(long pos, @Nullable BlockState state) {
        if (state == null) {
            cells.remove(pos);
        } else {
            cells.put(pos, state);
        }
        touched();
    }

    public static void clear() {
        cells.clear();
        undo.clear();
        redo.clear();
        sourceFile = null;
        sourceMeta = null;
        touched();
    }

    /** Replaces the draft with a placement's blocks, so an existing blueprint can be edited. */
    public static void loadFrom(Placement placement) {
        clear();
        Blueprint blueprint = placement.blueprint();
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            BlockState state = blueprint.state(entry.getIntValue());
            if (state != null) {
                cells.put(placement.toWorld(entry.getLongKey()).asLong(), placement.toWorld(state));
            }
        }
        for (long removal : blueprint.removals()) {
            cells.put(placement.toWorld(removal).asLong(), Blocks.AIR.defaultBlockState());
        }
        sourceFile = placement.file();
        sourceMeta = blueprint.meta();
        touched();
    }

    /** Converts to a blueprint whose (0, 0, 0) is the draft's minimum corner. Returns null for an empty draft. */
    public static @Nullable Blueprint toBlueprint(String name, String author) {
        if (cells.isEmpty()) {
            return null;
        }
        BlockPos min = minCorner();
        Blueprint.Builder builder = Blueprint.builder();
        for (Long2ObjectMap.Entry<BlockState> entry : cells.long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            int x = pos.getX() - min.getX();
            int y = pos.getY() - min.getY();
            int z = pos.getZ() - min.getZ();
            if (entry.getValue().isAir()) {
                builder.remove(x, y, z);
            } else {
                builder.put(x, y, z, entry.getValue());
            }
        }
        BlueprintMeta.Origin origin = server != null && dimension != null
                ? new BlueprintMeta.Origin(server, dimension, min.getX(), min.getY(), min.getZ())
                : null;
        return builder.build(name, author, origin);
    }

    public static BlockPos minCorner() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (long pos : cells.keySet()) {
            minX = Math.min(minX, BlockPos.getX(pos));
            minY = Math.min(minY, BlockPos.getY(pos));
            minZ = Math.min(minZ, BlockPos.getZ(pos));
        }
        return new BlockPos(minX, minY, minZ);
    }

    private static void touched() {
        dirty = true;
        ticksSinceChange = 0;
        revision++;
    }

    // Persistence

    /** Saves the old world's draft and loads the new one. Called when the server or dimension changes. */
    public static void switchContext(@Nullable String newServer, @Nullable String newDimension) {
        save();
        cells.clear();
        undo.clear();
        redo.clear();
        sourceFile = null;
        sourceMeta = null;
        server = newServer;
        dimension = newDimension;
        load();
        dirty = false;
        revision++;
    }

    public static void tick() {
        if (dirty && ++ticksSinceChange >= AUTOSAVE_TICKS) {
            save();
        }
    }

    public static void save() {
        Path file = file();
        if (file == null || !dirty) {
            return;
        }
        dirty = false;
        try {
            Blueprint blueprint = toBlueprint("Draft", "");
            if (blueprint == null) {
                Files.deleteIfExists(file);
                return;
            }
            if (sourceFile != null) {
                blueprint.meta().description = sourceFile;
            }
            BlueprintIO.write(blueprint, file);
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save the draft to {}", file, e);
        }
    }

    private static void load() {
        Path file = file();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            Blueprint blueprint = BlueprintIO.read(file);
            BlueprintMeta.Origin origin = blueprint.meta().origin;
            if (origin == null) {
                return;
            }
            BlockPos min = new BlockPos(origin.pos[0], origin.pos[1], origin.pos[2]);
            for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
                BlockState state = blueprint.state(entry.getIntValue());
                if (state != null) {
                    cells.put(BlockPos.of(entry.getLongKey()).offset(min).asLong(), state);
                }
            }
            for (long removal : blueprint.removals()) {
                cells.put(BlockPos.of(removal).offset(min).asLong(), Blocks.AIR.defaultBlockState());
            }
            // The library file being edited is remembered in the description field of the draft file.
            String description = blueprint.meta().description;
            sourceFile = description == null || description.isEmpty() ? null : description;
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not load the draft from {}", file, e);
        }
    }

    private static @Nullable Path file() {
        if (server == null || dimension == null) {
            return null;
        }
        return Pawprint.dataDir().resolve("drafts")
                .resolve(ClientContext.fileSafe(server))
                .resolve(ClientContext.fileSafe(dimension) + BlueprintIO.EXTENSION);
    }
}
