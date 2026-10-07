package com.dumaru.pawprint.format;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Block states are stored as strings such as {@code minecraft:oak_stairs[facing=east,half=bottom]},
 * so files stay readable across mod sets and game versions.
 */
public final class BlockStateCodec {
    private BlockStateCodec() {
    }

    public static String serialize(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** Returns null when the block is not registered in this game, e.g. a block from a mod that is not installed. */
    public static @Nullable BlockState parse(String value) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), value, false).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
    }

    /** The block ID part of a serialized state, e.g. {@code minecraft:oak_stairs}. */
    public static String blockId(String value) {
        int bracket = value.indexOf('[');
        String id = bracket < 0 ? value : value.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    public static String namespace(String value) {
        int colon = value.indexOf(':');
        int bracket = value.indexOf('[');
        if (colon < 0 || (bracket >= 0 && bracket < colon)) {
            return "minecraft";
        }
        return value.substring(0, colon);
    }
}
