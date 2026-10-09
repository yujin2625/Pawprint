package com.dumaru.pawprint.format.convert;

import com.dumaru.pawprint.format.Blueprint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Sponge schematics ({@code .schem}) as used by WorldEdit and FastAsyncWorldEdit. Reads versions 2 and 3,
 * writes version 2, which both old and new WorldEdit read.
 */
final class SpongeSchematicFormat {
    private SpongeSchematicFormat() {
    }

    static Blueprint read(CompoundTag root, String name) throws IOException {
        CompoundTag schematic = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        int version = schematic.getIntOr("Version", 0);
        int dataVersion = schematic.getIntOr("DataVersion", 0);
        int width = schematic.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = schematic.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = schematic.getShortOr("Length", (short) 0) & 0xFFFF;

        CompoundTag paletteTag;
        byte[] data;
        if (version >= 3) {
            CompoundTag blocks = schematic.getCompoundOrEmpty("Blocks");
            paletteTag = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(new byte[0]);
        } else {
            paletteTag = schematic.getCompoundOrEmpty("Palette");
            data = schematic.getByteArray("BlockData").orElse(new byte[0]);
        }
        Map<Integer, String> palette = new HashMap<>();
        for (String key : paletteTag.keySet()) {
            palette.put(paletteTag.getIntOr(key, 0), StateTags.upgrade(key, dataVersion));
        }

        Formats.Collector collector = new Formats.Collector();
        int index = 0;
        int i = 0;
        long volume = (long) width * height * length;
        while (i < data.length && index < volume) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (i >= data.length) {
                    throw new IOException("Truncated block data");
                }
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            String state = palette.get(value);
            if (state != null && !StateTags.isAirLike(state)) {
                int y = index / (width * length);
                int z = index % (width * length) / width;
                int x = index % width;
                collector.put(x, y, z, state);
            }
            index++;
        }
        CompoundTag metadata = schematic.getCompoundOrEmpty("Metadata");
        String title = metadata.contains("Name") ? metadata.getStringOr("Name", "") : name;
        return collector.build(title, metadata.getStringOr("Author", ""), "");
    }

    static CompoundTag write(Blueprint blueprint) throws IOException {
        int width = blueprint.sizeX();
        int height = blueprint.sizeY();
        int length = blueprint.sizeZ();
        if (width > 0xFFFF || height > 0xFFFF || length > 0xFFFF) {
            throw new IOException("Too large for a .schem file");
        }
        CompoundTag palette = new CompoundTag();
        Map<String, Integer> ids = new HashMap<>();
        ids.put("minecraft:air", 0);
        palette.putInt("minecraft:air", 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    String state = Formats.stateAt(blueprint, x, y, z);
                    String key = state == null ? "minecraft:air" : StateTags.toString(StateTags.toTag(state), 0);
                    Integer id = ids.get(key);
                    if (id == null) {
                        id = ids.size();
                        ids.put(key, id);
                        palette.putInt(key, id);
                    }
                    int value = id;
                    while ((value & ~0x7F) != 0) {
                        data.write(value & 0x7F | 0x80);
                        value >>>= 7;
                    }
                    data.write(value);
                }
            }
        }
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", StateTags.currentDataVersion());
        root.putShort("Width", (short) width);
        root.putShort("Height", (short) height);
        root.putShort("Length", (short) length);
        root.putInt("PaletteMax", ids.size());
        root.put("Palette", palette);
        root.putByteArray("BlockData", data.toByteArray());
        root.putIntArray("Offset", new int[]{0, 0, 0});
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", blueprint.meta().name);
        metadata.putString("Author", blueprint.meta().author);
        root.put("Metadata", metadata);
        return root;
    }
}
