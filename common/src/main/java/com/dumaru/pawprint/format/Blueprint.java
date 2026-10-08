package com.dumaru.pawprint.format;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    /** Layer of each block and removal (format 2). Only layers other than 0 are stored; the mod keeps them, it does not use them. */
    private final Long2IntMap blockLayers;
    private final Long2IntMap removalLayers;

    Blueprint(BlueprintMeta meta, List<String> palette, Long2IntMap blocks, LongSet removals) {
        this(meta, palette, blocks, removals, new Long2IntOpenHashMap(), new Long2IntOpenHashMap());
    }

    Blueprint(BlueprintMeta meta, List<String> palette, Long2IntMap blocks, LongSet removals,
              Long2IntMap blockLayers, Long2IntMap removalLayers) {
        this.meta = meta;
        this.palette = List.copyOf(palette);
        List<@Nullable BlockState> resolved = new ArrayList<>(palette.size());
        for (String entry : palette) {
            resolved.add(BlockStateCodec.parse(entry));
        }
        this.states = Collections.unmodifiableList(resolved);
        this.blocks = blocks;
        this.removals = removals;
        this.blockLayers = blockLayers;
        this.removalLayers = removalLayers;
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

    /** Layer of the block or removal at a packed relative position; 0 when none was set. */
    public int layer(long pos) {
        return blockLayers.getOrDefault(pos, removalLayers.getOrDefault(pos, 0));
    }

    public boolean hasLayers() {
        return !blockLayers.isEmpty() || !removalLayers.isEmpty() || (meta.layers != null && meta.layers.size() > 1);
    }

    /** Copies this blueprint's blocks into a builder, with their layers, for edits that keep everything else. */
    public Builder toBuilder() {
        Builder builder = builder().layersFrom(meta);
        for (Long2IntMap.Entry entry : blocks.long2IntEntrySet()) {
            long pos = entry.getLongKey();
            builder.put(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), palette.get(entry.getIntValue()), layer(pos));
        }
        for (long pos : removals) {
            builder.remove(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), layer(pos));
        }
        return builder;
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
        private final Long2IntMap blockLayers = new Long2IntOpenHashMap();
        private final Long2IntMap removalLayers = new Long2IntOpenHashMap();
        private @Nullable JsonArray layerList;
        private @Nullable JsonArray layerOrder;
        private @Nullable JsonObject packHint;
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
            return put(x, y, z, serializedState, 0);
        }

        public Builder put(int x, int y, int z, BlockState state, int layer) {
            return put(x, y, z, BlockStateCodec.serialize(state), layer);
        }

        public Builder put(int x, int y, int z, String serializedState, int layer) {
            int index = paletteIndex.getInt(serializedState);
            if (index < 0) {
                index = palette.size();
                palette.add(serializedState);
                paletteIndex.put(serializedState, index);
            }
            long key = BlockPos.asLong(x, y, z);
            blocks.put(key, index);
            removals.remove(key);
            removalLayers.remove(key);
            setLayer(blockLayers, key, layer);
            grow(x, y, z);
            return this;
        }

        public Builder remove(int x, int y, int z) {
            return remove(x, y, z, 0);
        }

        public Builder remove(int x, int y, int z, int layer) {
            long key = BlockPos.asLong(x, y, z);
            blocks.remove(key);
            blockLayers.remove(key);
            removals.add(key);
            setLayer(removalLayers, key, layer);
            grow(x, y, z);
            return this;
        }

        private static void setLayer(Long2IntMap map, long key, int layer) {
            if (layer == 0) {
                map.remove(key);
            } else {
                map.put(key, layer);
            }
        }

        /** Keeps the layer list of an existing blueprint (format 2) in the one being built. */
        public Builder layersFrom(BlueprintMeta meta) {
            return layers(meta.layers, meta.layerOrder, meta.packHint);
        }

        public Builder layers(@Nullable JsonArray list, @Nullable JsonArray order, @Nullable JsonObject hint) {
            layerList = list == null ? null : list.deepCopy();
            layerOrder = order == null ? null : order.deepCopy();
            packHint = hint == null ? null : hint.deepCopy();
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

        /** Format 2 only when there is more than the default layer; otherwise format 1, which older versions read. */
        private void applyLayers(BlueprintMeta meta) {
            if (layerList != null) {
                meta.layers = layerList;
                meta.layerOrder = layerOrder;
            }
            if (packHint != null) {
                meta.packHint = packHint;
            }
            boolean layered = !blockLayers.isEmpty() || !removalLayers.isEmpty() || (meta.layers != null && meta.layers.size() > 1);
            if (!layered) {
                meta.layers = null;
                meta.layerOrder = null;
                return;
            }
            // Layers a block points at must exist; unknown ones fall back to the default layer 0.
            java.util.Set<Integer> known = BlueprintMeta.layerIds(meta.layers);
            blockLayers.values().removeIf(id -> !known.contains(id));
            removalLayers.values().removeIf(id -> !known.contains(id));
        }

        /** Builds with the given metadata; derived fields (size, counts, versions, mods) are recomputed. */
        public Blueprint build(BlueprintMeta meta) {
            applyLayers(meta);
            meta.format = meta.layers != null ? BlueprintIO.FORMAT_VERSION : 1;
            meta.mcVersion = SharedConstants.getCurrentVersion().getName();
            meta.dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            meta.size = new int[]{maxX + 1, maxY + 1, maxZ + 1};
            meta.blockCount = blocks.size();
            meta.removalCount = removals.size();
            TreeSet<String> mods = new TreeSet<>();
            TreeSet<String> blockIds = new TreeSet<>();
            for (String entry : palette) {
                mods.add(BlockStateCodec.namespace(entry));
                blockIds.add(BlockStateCodec.blockId(entry));
            }
            meta.mods = new ArrayList<>(mods);
            meta.blocks = new ArrayList<>(blockIds);
            return new Blueprint(meta, palette, blocks, removals, blockLayers, removalLayers);
        }
    }
}
