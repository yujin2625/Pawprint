package com.dumaru.pawprint.format.convert;

import com.dumaru.pawprint.format.Blueprint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Litematica schematics ({@code .litematic}). All regions are merged into one blueprint. Block states are packed
 * with Litematica's bit layout, where an entry may span two longs.
 */
final class LitematicFormat {
    private static final int VERSION = 6;

    private LitematicFormat() {
    }

    static Blueprint read(CompoundTag root, String fallbackName) throws IOException {
        int dataVersion = root.getInt("MinecraftDataVersion");
        CompoundTag metadata = root.getCompound("Metadata");
        CompoundTag regions = root.getCompound("Regions");
        Formats.Collector collector = new Formats.Collector();
        for (String regionName : regions.getAllKeys()) {
            CompoundTag region = regions.getCompound(regionName);
            CompoundTag position = region.getCompound("Position");
            CompoundTag size = region.getCompound("Size");
            int sx = size.getInt("x");
            int sy = size.getInt("y");
            int sz = size.getInt("z");
            // Negative sizes extend from the position toward smaller coordinates.
            int minX = position.getInt("x") + (sx < 0 ? sx + 1 : 0);
            int minY = position.getInt("y") + (sy < 0 ? sy + 1 : 0);
            int minZ = position.getInt("z") + (sz < 0 ? sz + 1 : 0);
            sx = Math.abs(sx);
            sy = Math.abs(sy);
            sz = Math.abs(sz);

            ListTag paletteTag = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            String[] palette = new String[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = StateTags.toString(paletteTag.getCompound(i), dataVersion);
            }
            long[] states = region.getLongArray("BlockStates");
            int bits = bitsFor(palette.length);
            long volume = (long) sx * sy * sz;
            if (states.length < (volume * bits + 63) / 64) {
                throw new IOException("Region " + regionName + " has too little block data");
            }
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        long index = ((long) y * sz + z) * sx + x;
                        int id = (int) get(states, index, bits);
                        if (id > 0 && id < palette.length && !StateTags.isAirLike(palette[id])) {
                            collector.put(minX + x, minY + y, minZ + z, palette[id]);
                        }
                    }
                }
            }
        }
        String name = metadata.getString("Name");
        return collector.build(name.isEmpty() ? fallbackName : name, metadata.getString("Author"),
                metadata.getString("Description"));
    }

    static CompoundTag write(Blueprint blueprint) {
        int sx = blueprint.sizeX();
        int sy = blueprint.sizeY();
        int sz = blueprint.sizeZ();
        ListTag palette = new ListTag();
        Map<String, Integer> ids = new HashMap<>();
        palette.add(StateTags.toTag("minecraft:air"));
        ids.put("minecraft:air", 0);
        for (String state : blueprint.palette()) {
            if (!ids.containsKey(state)) {
                ids.put(state, palette.size());
                palette.add(StateTags.toTag(state));
            }
        }
        int bits = bitsFor(palette.size());
        long volume = (long) sx * sy * sz;
        long[] states = new long[(int) ((volume * bits + 63) / 64)];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    String state = Formats.stateAt(blueprint, x, y, z);
                    if (state != null) {
                        set(states, ((long) y * sz + z) * sx + x, bits, ids.get(state));
                    }
                }
            }
        }

        CompoundTag region = new CompoundTag();
        region.put("Position", xyz(0, 0, 0));
        region.put("Size", xyz(sx, sy, sz));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", states);
        region.put("TileEntities", new ListTag());
        region.put("Entities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put(blueprint.meta().name.isEmpty() ? "Main" : blueprint.meta().name, region);

        long now = System.currentTimeMillis();
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", blueprint.meta().name);
        metadata.putString("Author", blueprint.meta().author);
        metadata.putString("Description", blueprint.meta().description);
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TotalVolume", volume);
        metadata.putLong("TotalBlocks", blueprint.blocks().size());
        metadata.putLong("TimeCreated", now);
        metadata.putLong("TimeModified", now);
        metadata.put("EnclosingSize", xyz(sx, sy, sz));

        CompoundTag root = new CompoundTag();
        root.putInt("Version", VERSION);
        root.putInt("MinecraftDataVersion", StateTags.currentDataVersion());
        root.put("Metadata", metadata);
        root.put("Regions", regions);
        return root;
    }

    private static int bitsFor(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }

    private static long get(long[] array, long index, int bits) {
        long mask = (1L << bits) - 1;
        long start = index * bits;
        int startLong = (int) (start >> 6);
        int endLong = (int) (((index + 1) * bits - 1) >> 6);
        int offset = (int) (start & 63);
        if (startLong == endLong) {
            return array[startLong] >>> offset & mask;
        }
        return (array[startLong] >>> offset | array[endLong] << (64 - offset)) & mask;
    }

    private static void set(long[] array, long index, int bits, long value) {
        long mask = (1L << bits) - 1;
        long start = index * bits;
        int startLong = (int) (start >> 6);
        int endLong = (int) (((index + 1) * bits - 1) >> 6);
        int offset = (int) (start & 63);
        array[startLong] = array[startLong] & ~(mask << offset) | (value & mask) << offset;
        if (startLong != endLong) {
            int shift = 64 - offset;
            int remaining = bits - shift;
            array[endLong] = array[endLong] >>> remaining << remaining | (value & mask) >> shift;
        }
    }

    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }
}
