package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.placement.BlockStatus;
import com.dumaru.pawprint.client.placement.GhostStore;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * GPU buffers for one section of ghost blocks: block models, red boxes over blocks in the way, and outlines.
 * Vertices are relative to the section origin, so the mesh stays valid wherever the camera goes; it is rebuilt
 * only when statuses in the section change. Build and draw on the render thread only.
 */
final class GhostMesh implements AutoCloseable {
    static final int MODELS = 0;
    static final int BOXES = 1;
    static final int LINES = 2;

    private static final BufferBuilder MODEL_BUILDER = new BufferBuilder(1 << 20);
    private static final BufferBuilder BOX_BUILDER = new BufferBuilder(1 << 16);
    private static final BufferBuilder LINE_BUILDER = new BufferBuilder(1 << 16);
    private static final TintingConsumer TINT = new TintingConsumer();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final float WRONG_STATE_SCALE = 1.01f;

    private final @Nullable VertexBuffer[] buffers = new VertexBuffer[3];

    private GhostMesh() {
    }

    @Nullable VertexBuffer get(int layer) {
        return buffers[layer];
    }

    static GhostMesh build(GhostStore.Section section, GhostWorld world, Vec3 camera) {
        float opacity = Pawprint.config().ghostOpacity;
        boolean fullBright = Pawprint.config().ghostFullBright;
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        BufferBuilder models = MODEL_BUILDER;
        BufferBuilder boxes = BOX_BUILDER;
        BufferBuilder lines = LINE_BUILDER;
        models.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        boxes.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        lines.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        PoseStack poseStack = new PoseStack();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        Integer layer = PlacementManager.layer();
        for (int i = 0; i < section.size(); i++) {
            BlockStatus status = section.status(i);
            if (status == BlockStatus.CORRECT || (layer != null && section.y(i) != layer)) {
                continue;
            }
            pos.set(section.x(i), section.y(i), section.z(i));
            float lx = pos.getX() - section.originX;
            float ly = pos.getY() - section.originY;
            float lz = pos.getZ() - section.originZ;
            BlockState target = section.target(i);
            boolean model = target != null && target.getRenderShape() == RenderShape.MODEL;
            switch (status) {
                case MISSING -> {
                    if (target == null) {
                        box(lines, lx, ly, lz, -0.1, 1f, 0f, 1f); // Block from a mod that is not installed.
                    } else if (model) {
                        tesselate(blocks, world, poseStack, TINT.set(models, 1f, 1f, 1f, opacity, fullBright),
                                target, pos, lx, ly, lz, true, 1f);
                    } else {
                        box(lines, lx, ly, lz, -0.05, 0.3f, 0.7f, 1f); // Fluids and chests have no plain model.
                    }
                }
                case WRONG_STATE -> {
                    if (model) {
                        tesselate(blocks, world, poseStack, TINT.set(models, 1f, 0.85f, 0.2f, Math.min(1f, opacity + 0.1f),
                                fullBright), target, pos, lx, ly, lz, false, WRONG_STATE_SCALE);
                    } else {
                        box(lines, lx, ly, lz, -0.05, 1f, 0.85f, 0.2f);
                    }
                }
                case WRONG_BLOCK -> LevelRenderer.addChainedFilledBoxVertices(poseStack, boxes,
                        lx - 0.01, ly - 0.01, lz - 0.01, lx + 1.01, ly + 1.01, lz + 1.01, 1f, 0.15f, 0.15f, 0.35f);
                case REMOVE -> box(lines, lx, ly, lz, 0.002, 1f, 0.55f, 0f);
                default -> {
                }
            }
        }

        GhostMesh mesh = new GhostMesh();
        // Sorted once for the camera at build time; close enough for see-through ghosts.
        models.setQuadSorting(VertexSorting.byDistance(
                (float) (camera.x - section.originX), (float) (camera.y - section.originY), (float) (camera.z - section.originZ)));
        mesh.buffers[MODELS] = upload(models.end());
        mesh.buffers[BOXES] = upload(boxes.end());
        mesh.buffers[LINES] = upload(lines.end());
        return mesh;
    }

    private static void tesselate(BlockRenderDispatcher blocks, GhostWorld world, PoseStack poseStack, TintingConsumer consumer,
                                  BlockState state, BlockPos pos, float lx, float ly, float lz, boolean cullHiddenFaces,
                                  float scale) {
        poseStack.pushPose();
        poseStack.translate(lx, ly, lz);
        if (scale != 1f) {
            poseStack.translate(0.5f, 0.5f, 0.5f);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5f, -0.5f, -0.5f);
        }
        try {
            blocks.getModelRenderer().tesselateBlock(world, blocks.getBlockModel(state), state, pos, poseStack, consumer,
                    cullHiddenFaces, RANDOM, state.getSeed(pos), OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException e) {
            Pawprint.LOG.debug("Could not draw ghost {}", state, e);
        }
        poseStack.popPose();
    }

    private static void box(BufferBuilder lines, float x, float y, float z, double grow, float r, float g, float b) {
        LevelRenderer.renderLineBox(new PoseStack(), lines, new AABB(x, y, z, x + 1, y + 1, z + 1).inflate(grow), r, g, b, 1f);
    }

    private static @Nullable VertexBuffer upload(BufferBuilder.RenderedBuffer data) {
        if (data.isEmpty()) {
            data.release();
            return null;
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(data);
        VertexBuffer.unbind();
        return buffer;
    }

    @Override
    public void close() {
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) {
                buffers[i].close();
                buffers[i] = null;
            }
        }
    }
}
