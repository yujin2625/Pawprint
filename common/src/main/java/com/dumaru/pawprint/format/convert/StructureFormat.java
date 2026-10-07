package com.dumaru.pawprint.format.convert;

import com.dumaru.pawprint.format.Blueprint;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;

/**
 * Vanilla structure files ({@code .nbt}), written by structure blocks and used by Create's schematics.
 */
final class StructureFormat {
    private StructureFormat() {
    }

    static Blueprint read(CompoundTag root, String name) throws IOException {
        int dataVersion = root.getInt("DataVersion");
        ListTag paletteTag = root.contains("palettes", Tag.TAG_LIST)
                ? root.getList("palettes", Tag.TAG_LIST).getList(0)
                : root.getList("palette", Tag.TAG_COMPOUND);
        String[] palette = new String[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = StateTags.toString(paletteTag.getCompound(i), dataVersion);
        }
        Formats.Collector collector = new Formats.Collector();
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            int state = block.getInt("state");
            if (pos.size() != 3 || state < 0 || state >= palette.length || StateTags.isAirLike(palette[state])) {
                continue;
            }
            collector.put(pos.getInt(0), pos.getInt(1), pos.getInt(2), palette[state]);
        }
        return collector.build(name, "", "");
    }

    static CompoundTag write(Blueprint blueprint) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", StateTags.currentDataVersion());
        root.put("size", ints(blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ()));

        ListTag palette = new ListTag();
        for (String state : blueprint.palette()) {
            palette.add(StateTags.toTag(state));
        }
        int airIndex = -1;
        if (!blueprint.removals().isEmpty()) {
            airIndex = palette.size();
            palette.add(StateTags.toTag("minecraft:air"));
        }
        root.put("palette", palette);

        ListTag blocks = new ListTag();
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            blocks.add(block(entry.getLongKey(), entry.getIntValue()));
        }
        for (long removal : blueprint.removals()) {
            blocks.add(block(removal, airIndex));
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private static CompoundTag block(long pos, int state) {
        CompoundTag tag = new CompoundTag();
        tag.put("pos", ints(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos)));
        tag.putInt("state", state);
        return tag;
    }

    private static ListTag ints(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }
}
