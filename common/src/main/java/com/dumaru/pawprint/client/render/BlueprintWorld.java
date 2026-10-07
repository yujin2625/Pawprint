package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.format.Blueprint;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * A blueprint seen as a tiny world on its own, lit by daylight with plains colors. Used to render thumbnails
 * without a level, so blocks still get corner shading and hidden faces between neighbors.
 */
final class BlueprintWorld implements BlockAndTintGetter {
    private static final int GRASS = 0x91BD59;
    private static final int FOLIAGE = 0x77AB2F;
    private static final int WATER = 0x3F76E4;

    private final Blueprint blueprint;

    BlueprintWorld(Blueprint blueprint) {
        this.blueprint = blueprint;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int index = blueprint.blocks().getOrDefault(pos.asLong(), -1);
        BlockState state = index < 0 ? null : blueprint.state(index);
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    /** Same face shading as the overworld. */
    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    /** Full daylight everywhere; with this override the light engine is never consulted. */
    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return layer == LightLayer.SKY ? 15 : 0;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15 - amount;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public LevelLightEngine getLightEngine() {
        return null;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
            return GRASS;
        }
        if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
            return FOLIAGE;
        }
        return resolver == BiomeColors.WATER_COLOR_RESOLVER ? WATER : 0xFFFFFF;
    }

    @Override
    public int getHeight() {
        return 4096;
    }

    @Override
    public int getMinBuildHeight() {
        return -2048;
    }
}
