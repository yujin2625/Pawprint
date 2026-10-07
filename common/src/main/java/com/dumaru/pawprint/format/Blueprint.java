package com.dumaru.pawprint.format;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A building plan: block states to place and positions that must be cleared, relative to the blueprint's
 * minimum corner (0, 0, 0).
 */
public final class Blueprint {
    private final BlueprintMeta meta;
    /** Serialized states. Kept even when a state cannot be resolved, so unknown blocks survive a save. */
    private final List<String> palette;
    /** Resolved states, parallel to {@link #palette}; null where the block is unknown in this game. */
    private final List<@Nullable BlockState> states;
    private final Long2IntMap blocks;
    private final LongSet removals;

    Blueprint(BlueprintMeta meta, List<String> palette, Long2IntMap blocks, LongSet removals) {
        this.meta = meta;
        this.palette = List.copyOf(palette);
        List<@Nullable BlockState> resolved = new ArrayList<>(palette.size());
        for (String entry : palette) {
            resolved.add(BlockStateCodec.parse(entry));
        }
        this.states = Collections.unmodifiableList(resolved);
        this.blocks = blocks;
        this.removals = removals;
    }

    public BlueprintMeta meta() {
        return meta;
    }

    public List<String> palette() {
        return palette;
    }

    public @Nullable BlockState state(int paletteIndex) {
        return states.get(paletteIndex);
    }

    /** Packed relative position ({@link BlockPos#asLong}) to palette index. */
    public Long2IntMap blocks() {
        return blocks;
    }

    /** Packed relative positions that must be empty. */
    public LongSet removals() {
        return removals;
    }

    public int sizeX() {
        return meta.size[0];
    }

    public int sizeY() {
        return meta.size[1];
    }

    public int sizeZ() {
        return meta.size[2];
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<String> palette = new ArrayList<>();
        private final Object2IntMap<String> paletteIndex = new Object2IntOpenHashMap<>();
        private final Long2IntMap blocks = new Long2IntOpenHashMap();
        private final LongSet removals = new LongOpenHashSet();
        private int maxX = -1;
        private int maxY = -1;
        private int maxZ = -1;

        private Builder() {
            paletteIndex.defaultReturnValue(-1);
        }

        public Builder put(int x, int y, int z, BlockState state) {
            return put(x, y, z, BlockStateCodec.serialize(state));
        }

        public Builder put(int x, int y, int z, String serializedState) {
            int index = paletteIndex.getInt(serializedState);
            if (index < 0) {
                index = palette.size();
                palette.add(serializedState);
                paletteIndex.put(serializedState, index);
            }
            long key = BlockPos.asLong(x, y, z);
            blocks.put(key, index);
            removals.remove(key);
            grow(x, y, z);
            return this;
        }

        public Builder remove(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            blocks.remove(key);
            removals.add(key);
            grow(x, y, z);
            return this;
        }

        public boolean isEmpty() {
            return blocks.isEmpty() && removals.isEmpty();
        }

        private void grow(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0) {
                throw new IllegalArgumentException("Blueprint positions are relative to (0, 0, 0) and must not be negative");
            }
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        /** Builds a new blueprint with fresh metadata. */
        public Blueprint build(String name, String author, @Nullable BlueprintMeta.Origin origin) {
            BlueprintMeta meta = new BlueprintMeta();
            meta.id = UUID.randomUUID().toString();
            meta.name = name;
            meta.author = author;
            meta.created = meta.modified = Instant.now().toString();
            meta.origin = origin;
            return build(meta);
        }

        /** Builds with the given metadata; derived fields (size, counts, versions, mods) are recomputed. */
        public Blueprint build(BlueprintMeta meta) {
            meta.format = BlueprintIO.FORMAT_VERSION;
            meta.mcVersion = SharedConstants.getCurrentVersion().getName();
            meta.dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            meta.size = new int[]{maxX + 1, maxY + 1, maxZ + 1};
            meta.blockCount = blocks.size();
            meta.removalCount = removals.size();
            TreeSet<String> mods = new TreeSet<>();
            for (String entry : palette) {
                mods.add(BlockStateCodec.namespace(entry));
            }
            meta.mods = new ArrayList<>(mods);
            return new Blueprint(meta, palette, blocks, removals);
        }
    }
}
