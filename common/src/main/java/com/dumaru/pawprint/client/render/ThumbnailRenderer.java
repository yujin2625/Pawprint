package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.Blueprint;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.List;

/**
 * Draws a blueprint into an off-screen image from above and to the south-east, the way the front (south side)
 * of a build is usually seen. Must run on the render thread.
 */
public final class ThumbnailRenderer {
    public static final int SIZE = 128;
    /** Above this many blocks only the outer shell is drawn; inner blocks cannot be seen anyway. */
    private static final int SHELL_THRESHOLD = 20_000;
    /** Shells larger than this are not rendered; drawing them would stall the frame. */
    public static final int MAX_BLOCKS = 300_000;

    private static final List<RenderType> LAYERS = List.of(
            RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent());
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
    private static final TintingConsumer TINT = new TintingConsumer();
    private static final RandomSource RANDOM = RandomSource.create();
    private static @Nullable TextureTarget target;

    private ThumbnailRenderer() {
    }

    /** Returns a new image the caller must close, or null when the blueprint is too large or empty. */
    public static @Nullable NativeImage render(Blueprint blueprint) {
        if (blueprint.blocks().isEmpty()) {
            return null;
        }
        LongSet only = blueprint.blocks().size() > SHELL_THRESHOLD ? shell(blueprint) : null;
        if ((only != null ? only.size() : blueprint.blocks().size()) > MAX_BLOCKS) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (target == null) {
            target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        }
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        float sx = blueprint.sizeX();
        float sy = blueprint.sizeY();
        float sz = blueprint.sizeZ();
        float radius = (float) Math.sqrt(sx * sx + sy * sy + sz * sz) / 2f;
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-radius, radius, -radius, radius, -1000f, 1000f),
                VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        float fogStart = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);

        try {
            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(Axis.XP.rotationDegrees(30f));
            poseStack.mulPose(Axis.YP.rotationDegrees(-45f));
            poseStack.translate(-sx / 2f, -sy / 2f, -sz / 2f);
            drawBlocks(minecraft, blueprint, poseStack, only);
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
        return download(target);
    }

    private static void drawBlocks(Minecraft minecraft, Blueprint blueprint, PoseStack poseStack, @Nullable LongSet only) {
        BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
        BlueprintWorld world = new BlueprintWorld(blueprint);
        for (RenderType layer : LAYERS) {
            TINT.set(BUFFERS.getBuffer(layer), 1f, 1f, 1f, 1f, true);
            for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
                BlockState state = blueprint.state(entry.getIntValue());
                if (only != null && !only.contains(entry.getLongKey())) {
                    continue;
                }
                if (state == null || state.getRenderShape() != RenderShape.MODEL
                        || ItemBlockRenderTypes.getChunkRenderType(state) != layer) {
                    continue;
                }
                BlockPos pos = BlockPos.of(entry.getLongKey());
                poseStack.pushPose();
                poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                try {
                    blocks.getModelRenderer().tesselateBlock(world, blocks.getBlockModel(state), state, pos, poseStack,
                            TINT, true, RANDOM, state.getSeed(pos), OverlayTexture.NO_OVERLAY);
                } catch (RuntimeException e) {
                    Pawprint.LOG.debug("Skipping {} in thumbnail", state, e);
                }
                poseStack.popPose();
            }
            BUFFERS.endBatch(layer);
        }
    }

    /** Blocks with at least one neighbor that does not fully hide them. */
    private static LongSet shell(Blueprint blueprint) {
        LongSet shell = new LongOpenHashSet();
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            long pos = entry.getLongKey();
            for (Direction direction : Direction.values()) {
                int neighbor = blueprint.blocks().getOrDefault(BlockPos.offset(pos, direction), -1);
                BlockState state = neighbor < 0 ? null : blueprint.state(neighbor);
                if (state == null || !state.canOcclude()) {
                    shell.add(pos);
                    break;
                }
            }
        }
        return shell;
    }

    private static NativeImage download(RenderTarget source) {
        NativeImage image = new NativeImage(SIZE, SIZE, false);
        RenderSystem.bindTexture(source.getColorTextureId());
        image.downloadTexture(0, false);
        image.flipY();
        return image;
    }
}
