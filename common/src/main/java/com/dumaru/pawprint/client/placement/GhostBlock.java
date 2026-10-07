package com.dumaru.pawprint.client.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * One position the overlay draws. {@code target} is null for removals and for blocks unknown in this game.
 */
public record GhostBlock(BlockPos pos, @Nullable BlockState target, BlockStatus status) {
}
