package com.dumaru.pawprint.client.placement;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Set;

/**
 * How a position in the world compares with what the blueprint wants there.
 */
public enum BlockStatus {
    /** Nothing (or something replaceable such as grass) where a block should go. */
    MISSING,
    /** A different block is in the way. */
    WRONG_BLOCK,
    /** The right block, but facing, half, etc. differ. */
    WRONG_STATE,
    /** A block that the blueprint says must be cleared. */
    REMOVE,
    CORRECT;

    /**
     * Properties the world changes by itself: leaf decay counters, and connections that follow the neighbors
     * (fences, walls, panes, stair corners). Comparing them would mark finished blocks as wrong.
     */
    private static final Set<String> IGNORED_PROPERTIES = Set.of(
            "distance", "persistent", "shape", "north", "south", "east", "west", "up", "down");

    public static BlockStatus compare(BlockState target, BlockState actual) {
        if (actual == target) {
            return CORRECT;
        }
        if (actual.is(target.getBlock())) {
            return sameIgnoringVolatile(target, actual) ? CORRECT : WRONG_STATE;
        }
        if (actual.isAir() || actual.canBeReplaced()) {
            return MISSING;
        }
        return WRONG_BLOCK;
    }

    private static boolean sameIgnoringVolatile(BlockState target, BlockState actual) {
        for (Property<?> property : target.getProperties()) {
            if (!IGNORED_PROPERTIES.contains(property.getName())
                    && !target.getValue(property).equals(actual.getValue(property))) {
                return false;
            }
        }
        return true;
    }
}
