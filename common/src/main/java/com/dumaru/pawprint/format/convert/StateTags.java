package com.dumaru.pawprint.format.convert;

import com.dumaru.pawprint.format.BlockStateCodec;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.TreeMap;

/**
 * Converts between block-state NBT ({@code {Name, Properties}}) and Pawprint's state strings, upgrading states
 * from older game versions with the game's own data fixer (renamed blocks, changed properties).
 */
public final class StateTags {
    /** Data version right after the 1.13 "flattening", the format of legacy numeric IDs once mapped. */
    static final int FLATTENING = 1451;

    private StateTags() {
    }

    public static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /** {@code {Name:"minecraft:oak_stairs", Properties:{facing:"east"}}} to {@code minecraft:oak_stairs[facing=east]}. */
    public static String toString(CompoundTag tag, int dataVersion) {
        CompoundTag fixed = upgrade(tag, dataVersion);
        String name = fixed.getString("Name");
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        CompoundTag properties = fixed.getCompound("Properties");
        if (properties.isEmpty()) {
            return name;
        }
        StringBuilder text = new StringBuilder(name).append('[');
        boolean first = true;
        for (String key : new TreeMap<>(asStrings(properties)).keySet()) {
            text.append(first ? "" : ",").append(key).append('=').append(properties.getString(key));
            first = false;
        }
        return text.append(']').toString();
    }

    /** A state string to NBT, for writing other formats. Unknown blocks are written as they are. */
    public static CompoundTag toTag(String state) {
        BlockState resolved = BlockStateCodec.parse(state);
        if (resolved != null) {
            return NbtUtils.writeBlockState(resolved);
        }
        CompoundTag tag = new CompoundTag();
        int bracket = state.indexOf('[');
        tag.putString("Name", bracket < 0 ? state : state.substring(0, bracket));
        if (bracket >= 0 && state.endsWith("]")) {
            CompoundTag properties = new CompoundTag();
            for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    properties.putString(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
                }
            }
            tag.put("Properties", properties);
        }
        return tag;
    }

    /** Upgrades a state string written by an older game version. */
    public static String upgrade(String state, int dataVersion) {
        return dataVersion >= currentDataVersion() ? state : toString(toTag(state), dataVersion);
    }

    private static CompoundTag upgrade(CompoundTag tag, int dataVersion) {
        int current = currentDataVersion();
        if (dataVersion <= 0 || dataVersion >= current) {
            return tag;
        }
        Tag fixed = DataFixers.getDataFixer()
                .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), dataVersion, current).getValue();
        return fixed instanceof CompoundTag compound ? compound : tag;
    }

    private static Map<String, String> asStrings(CompoundTag tag) {
        Map<String, String> map = new TreeMap<>();
        for (String key : tag.getAllKeys()) {
            map.put(key, tag.getString(key));
        }
        return map;
    }

    static boolean isAirLike(String state) {
        String id = BlockStateCodec.blockId(state);
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")
                || id.equals("minecraft:structure_void");
    }
}
