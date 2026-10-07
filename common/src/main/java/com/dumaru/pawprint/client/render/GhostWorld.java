package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.client.placement.BlockStatus;
import com.dumaru.pawprint.client.placement.GhostBlock;
import com.dumaru.pawprint.client.placement.PlacementManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * The real world with missing ghost blocks filled in. Rendering ghosts against this view gives them the same
 * face culling and smooth-lighting corner shadows as real blocks, while light levels and biome tints still come
 * from the real world.
 */
final class GhostWorld implements BlockAndTintGetter {
    private final ClientLevel level;

    GhostWorld(ClientLevel level) {
        this.level = level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        GhostBlock ghost = PlacementManager.ghostAt(pos.asLong());
        if (ghost != null && ghost.status() == BlockStatus.MISSING && ghost.target() != null) {
            return ghost.target();
        }
        return level.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return level.getBlockEntity(pos);
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return level.getBlockTint(pos, resolver);
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }
}
