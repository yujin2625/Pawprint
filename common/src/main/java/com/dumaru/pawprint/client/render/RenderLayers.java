package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The block-pack render layer of a block state, from its model's quads: since 26.x each quad carries its layer, on
 * every loader alike. Translucent wins over cutout, cutout over solid.
 */
public final class RenderLayers {
    private static final Direction[] FACES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private RenderLayers() {
    }

    /**
     * Whether the state's model has any quads. Blocks the game draws in code (chests, signs, beds…) keep a model with
     * only a particle texture since render shapes lost ENTITYBLOCK_ANIMATED.
     */
    public static boolean hasQuads(BlockState state) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state).collectParts(RandomSource.create(42), parts);
        } catch (RuntimeException e) {
            return false;
        }
        for (BlockStateModelPart part : parts) {
            for (Direction face : FACES) {
                if (!part.getQuads(face).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String of(BlockState state) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state).collectParts(RandomSource.create(42), parts);
        } catch (RuntimeException e) {
            Pawprint.LOG.debug("No model parts for {}", state, e);
            return "solid";
        }
        boolean cutout = false;
        for (BlockStateModelPart part : parts) {
            for (Direction face : FACES) {
                for (BakedQuad quad : part.getQuads(face)) {
                    ChunkSectionLayer layer = quad.materialInfo().layer();
                    if (layer == ChunkSectionLayer.TRANSLUCENT) {
                        return "translucent";
                    }
                    cutout |= layer == ChunkSectionLayer.CUTOUT;
                }
            }
        }
        return cutout ? "cutout" : "solid";
    }
}
