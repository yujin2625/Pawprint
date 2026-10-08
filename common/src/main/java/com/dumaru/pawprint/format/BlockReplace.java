package com.dumaru.pawprint.format;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Swaps one kind of block for another throughout a blueprint, e.g. every spruce plank for stone.
 * Properties both blocks share (facing, half, axis, ...) are carried over, so stairs keep their direction.
 */
public final class BlockReplace {
    private BlockReplace() {
    }

    /** Returns a new blueprint with the same metadata; positions, removals and layers are unchanged. */
    public static Blueprint replace(Blueprint blueprint, Predicate<BlockState> match, Block replacement) {
        String[] palette = new String[blueprint.palette().size()];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = blueprint.state(i);
            palette[i] = state != null && match.test(state)
                    ? BlockStateCodec.serialize(convert(state, replacement))
                    : blueprint.palette().get(i);
        }
        Blueprint.Builder builder = Blueprint.builder().layersFrom(blueprint.meta());
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            long pos = entry.getLongKey();
            builder.put(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), palette[entry.getIntValue()], blueprint.layer(pos));
        }
        for (long pos : blueprint.removals()) {
            builder.remove(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), blueprint.layer(pos));
        }
        return builder.build(blueprint.meta());
    }

    /** Counts blocks the predicate matches, to tell the player what a replacement will change. */
    public static int count(Blueprint blueprint, Predicate<BlockState> match) {
        int[] perEntry = new int[blueprint.palette().size()];
        for (int index : blueprint.blocks().values()) {
            perEntry[index]++;
        }
        int count = 0;
        for (int i = 0; i < perEntry.length; i++) {
            BlockState state = blueprint.state(i);
            if (state != null && match.test(state)) {
                count += perEntry[i];
            }
        }
        return count;
    }

    static BlockState convert(BlockState from, Block to) {
        BlockState result = to.defaultBlockState();
        for (Property<?> property : from.getProperties()) {
            Property<?> target = to.getStateDefinition().getProperty(property.getName());
            if (target != null) {
                result = copy(result, target, from.getValue(property).toString());
            }
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
    }
}
