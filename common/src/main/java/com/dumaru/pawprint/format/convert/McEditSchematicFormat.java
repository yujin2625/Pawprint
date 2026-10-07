package com.dumaru.pawprint.format.convert;

import com.dumaru.pawprint.format.Blueprint;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.BlockStateData;

import java.io.IOException;

/**
 * Legacy MCEdit/WorldEdit schematics ({@code .schematic}, Minecraft 1.12 and older) with numeric block IDs.
 * IDs are mapped with the game's own flattening table, then upgraded to current block names.
 * Modded numeric IDs cannot be mapped and become unknown blocks.
 */
final class McEditSchematicFormat {
    private McEditSchematicFormat() {
    }

    static Blueprint read(CompoundTag root, String name) throws IOException {
        if (!root.contains("Blocks", Tag.TAG_BYTE_ARRAY)) {
            throw new IOException("Not a legacy schematic (no numeric block data); newer .schematic files are not supported");
        }
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        byte[] blocks = root.getByteArray("Blocks");
        byte[] data = root.getByteArray("Data");
        byte[] add = root.getByteArray("AddBlocks");
        long volume = (long) width * height * length;
        if (blocks.length < volume || data.length < volume) {
            throw new IOException("Block data is shorter than the schematic size");
        }

        String[] cache = new String[4096 * 16];
        Formats.Collector collector = new Formats.Collector();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int index = (y * length + z) * width + x;
                    int id = blocks[index] & 0xFF;
                    if (add.length > index >> 1) {
                        int high = (index & 1) == 0 ? add[index >> 1] >> 4 & 15 : add[index >> 1] & 15;
                        id |= high << 8;
                    }
                    if (id == 0) {
                        continue;
                    }
                    int key = id << 4 | data[index] & 15;
                    String state = key < cache.length ? cache[key] : null;
                    if (state == null) {
                        state = legacyState(id, data[index] & 15);
                        if (key < cache.length) {
                            cache[key] = state;
                        }
                    }
                    if (!StateTags.isAirLike(state)) {
                        collector.put(x, y, z, state);
                    }
                }
            }
        }
        return collector.build(name, "", "");
    }

    private static String legacyState(int id, int meta) {
        if (id >= 256) {
            return "legacy:block_" + id + "[meta=" + meta + "]"; // Modded ID; nothing to map it to.
        }
        Dynamic<?> flattened = BlockStateData.getTag(id << 4 | meta);
        Tag tag = flattened.convert(NbtOps.INSTANCE).getValue();
        return tag instanceof CompoundTag compound ? StateTags.toString(compound, StateTags.FLATTENING) : "minecraft:air";
    }
}
