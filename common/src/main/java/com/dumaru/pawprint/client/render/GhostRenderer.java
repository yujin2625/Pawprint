package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.Selection;
import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.edit.EditTarget;
import com.dumaru.pawprint.client.placement.BlockStatus;
import com.dumaru.pawprint.client.placement.GhostBlock;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws placements and the capture selection. Called by each loader once per frame after translucent terrain,
 * while the model-view matrix already holds the camera rotation; positions are made camera-relative here.
 *
 * <p>Each pass uses its own render type and is flushed before the next, so the passes never share a buffer.
 */
public final class GhostRenderer {
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
    private static final Direction[] FACES_AND_GENERAL = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
    private static final RandomSource RANDOM = RandomSource.create();

    private static final TintingConsumer TINT = new TintingConsumer();

    private static final float WRONG_STATE_SCALE = 1.01f;
    /** The shape preview is drawn fainter than placed ghosts, relative to the configured opacity. */
    private static final float PREVIEW_OPACITY = 0.55f;
    /** Erasing only affects draft blocks, so only those are outlined, up to this many. */
    private static final int MAX_ERASE_OUTLINES = 4096;

    private GhostRenderer() {
    }

    public static void render(Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 cam = camera.getPosition();
        List<GhostBlock> visible = visibleGhosts(cam);
        PoseStack poseStack = new PoseStack();

        // Pass 1: block models for missing blocks, blocks in the wrong state, and the shape preview. They go
        // through the vanilla block renderer against a world view that includes the ghosts, so lighting, corner
        // shading and hidden faces match real blocks.
        float opacity = Pawprint.config().ghostOpacity;
        boolean fullBright = Pawprint.config().ghostFullBright;
        GhostWorld world = new GhostWorld(level);
        VertexConsumer buffer = BUFFERS.getBuffer(RenderType.translucent());
        for (GhostBlock ghost : visible) {
            BlockState target = ghost.target();
            if (target == null || target.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            if (ghost.status() == BlockStatus.MISSING) {
                drawBlock(minecraft, world, poseStack, TINT.set(buffer, 1f, 1f, 1f, opacity, fullBright),
                        cam, target, ghost.pos(), true, 1f);
            } else if (ghost.status() == BlockStatus.WRONG_STATE) {
                drawBlock(minecraft, world, poseStack, TINT.set(buffer, 1f, 0.85f, 0.2f, Math.min(1f, opacity + 0.1f), fullBright),
                        cam, target, ghost.pos(), false, WRONG_STATE_SCALE);
            }
        }
        Preview preview = Preview.current();
        if (preview != null && !preview.erase() && preview.state() != null
                && preview.state().getRenderShape() == RenderShape.MODEL) {
            TINT.set(buffer, 0.8f, 0.9f, 1f, opacity * PREVIEW_OPACITY, fullBright);
            for (long packed : preview.cells()) {
                drawBlock(minecraft, world, poseStack, TINT, cam, preview.state(), BlockPos.of(packed), false, 1f);
            }
        }
        BUFFERS.endBatch(RenderType.translucent());

        // Pass 2: tinted boxes over blocks that are in the way.
        VertexConsumer boxes = BUFFERS.getBuffer(RenderType.debugFilledBox());
        for (GhostBlock ghost : visible) {
            if (ghost.status() == BlockStatus.WRONG_BLOCK) {
                filledBox(poseStack, boxes, cam, ghost.pos(), 0.01, 1f, 0.15f, 0.15f, 0.35f);
            }
        }
        BUFFERS.endBatch(RenderType.debugFilledBox());

        // Pass 3: outlines for things a model cannot show, the active placement's bounds and the selection.
        VertexConsumer lines = BUFFERS.getBuffer(RenderType.lines());
        for (GhostBlock ghost : visible) {
            BlockState target = ghost.target();
            if (ghost.status() == BlockStatus.REMOVE) {
                lineBox(poseStack, lines, cam, ghost.pos(), 0.002, 1f, 0.55f, 0f, 1f);
            } else if (ghost.status() == BlockStatus.MISSING && target == null) {
                // Block from a mod that is not installed.
                lineBox(poseStack, lines, cam, ghost.pos(), -0.1, 1f, 0f, 1f, 1f);
            } else if (target != null && target.getRenderShape() != RenderShape.MODEL
                    && ghost.status() != BlockStatus.CORRECT && ghost.status() != BlockStatus.WRONG_BLOCK) {
                // Fluids and block-entity blocks such as chests have no plain block model.
                lineBox(poseStack, lines, cam, ghost.pos(), -0.05, 0.3f, 0.7f, 1f, 1f);
            }
        }
        Placement active = PlacementManager.active();
        if (active != null && PlacementManager.isVisible()) {
            boundsBox(poseStack, lines, cam, active.worldBounds(), 0.3f, 0.9f, 1f, 1f);
        }
        renderSelection(poseStack, lines, cam);
        renderEditTarget(poseStack, lines, cam, preview);
        BUFFERS.endBatch(RenderType.lines());
    }

    private static List<GhostBlock> visibleGhosts(Vec3 cam) {
        int distance = Pawprint.config().ghostRenderDistance;
        double maxDistanceSq = (double) distance * distance;
        int limit = Pawprint.config().maxGhostBlocks;
        List<GhostBlock> visible = new ArrayList<>();
        if (!PlacementManager.isVisible()) {
            return visible;
        }
        for (GhostBlock ghost : PlacementManager.ghosts()) {
            if (ghost.status() != BlockStatus.CORRECT && ghost.pos().distToCenterSqr(cam) <= maxDistanceSq) {
                visible.add(ghost);
                if (visible.size() >= limit) {
                    break;
                }
            }
        }
        return visible;
    }

    private static void drawBlock(Minecraft minecraft, GhostWorld world, PoseStack poseStack, VertexConsumer consumer,
                                  Vec3 cam, BlockState state, BlockPos pos, boolean cullHiddenFaces, float scale) {
        BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
        BakedModel model = blocks.getBlockModel(state);
        poseStack.pushPose();
        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        if (scale != 1f) {
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5, -0.5, -0.5);
        }
        try {
            blocks.getModelRenderer().tesselateBlock(world, model, state, pos, poseStack, consumer, cullHiddenFaces,
                    RANDOM, state.getSeed(pos), OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException e) {
            // A model that cannot handle being rendered outside a chunk: fall back to its plain quads.
            Pawprint.LOG.debug("Falling back to plain quads for {}", state, e);
            drawQuads(world, model, state, pos, poseStack.last(), consumer);
        }
        poseStack.popPose();
    }

    private static void drawQuads(GhostWorld world, BakedModel model, BlockState state, BlockPos pos,
                                  PoseStack.Pose pose, VertexConsumer consumer) {
        BlockColors colors = Minecraft.getInstance().getBlockColors();
        for (Direction face : FACES_AND_GENERAL) {
            RANDOM.setSeed(42L);
            for (BakedQuad quad : model.getQuads(state, face, RANDOM)) {
                float shade = world.getShade(quad.getDirection(), quad.isShade());
                int tint = quad.isTinted() ? colors.getColor(state, world, pos, quad.getTintIndex()) : -1;
                consumer.putBulkData(pose, quad, ((tint >> 16) & 0xFF) / 255f * shade, ((tint >> 8) & 0xFF) / 255f * shade,
                        (tint & 0xFF) / 255f * shade, 1f, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
        }
    }

    private static void renderEditTarget(PoseStack poseStack, VertexConsumer lines, Vec3 cam, @Nullable Preview preview) {
        if (!EditMode.isActive()) {
            return;
        }
        if (preview != null) {
            boundsBox(poseStack, lines, cam, preview.bounds(), 1f, 0.85f, 0.2f, 1f);
            if (preview.erase()) {
                int drawn = 0;
                for (long packed : preview.cells()) {
                    if (Draft.get(packed) != null && drawn++ < MAX_ERASE_OUTLINES) {
                        lineBox(poseStack, lines, cam, BlockPos.of(packed), -0.1, 1f, 0.3f, 0.3f, 1f);
                    }
                }
            }
        }
        EditTarget target = EditMode.target();
        if (target != null) {
            lineBox(poseStack, lines, cam, target.placePos(), 0.003, 1f, 1f, 1f, 0.8f);
            if (target.hovered() != null) {
                lineBox(poseStack, lines, cam, target.hovered(), 0.006, 1f, 0.4f, 0.4f, 0.6f);
            }
        }
    }

    private static void renderSelection(PoseStack poseStack, VertexConsumer lines, Vec3 cam) {
        BoundingBox box = Selection.box();
        if (box != null) {
            boundsBox(poseStack, lines, cam, box, 1f, 1f, 1f, 1f);
            return;
        }
        BlockPos first = Selection.first();
        if (first != null) {
            lineBox(poseStack, lines, cam, first, 0.004, 0.4f, 1f, 0.4f, 1f);
        }
    }

    private static void lineBox(PoseStack poseStack, VertexConsumer lines, Vec3 cam, BlockPos pos, double grow,
                                float r, float g, float b, float a) {
        LevelRenderer.renderLineBox(poseStack, lines, blockBox(cam, pos, grow), r, g, b, a);
    }

    private static void boundsBox(PoseStack poseStack, VertexConsumer lines, Vec3 cam, BoundingBox box,
                                  float r, float g, float b, float a) {
        AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)
                .inflate(0.01).move(-cam.x, -cam.y, -cam.z);
        LevelRenderer.renderLineBox(poseStack, lines, aabb, r, g, b, a);
    }

    private static void filledBox(PoseStack poseStack, VertexConsumer consumer, Vec3 cam, BlockPos pos, double grow,
                                  float r, float g, float b, float a) {
        AABB box = blockBox(cam, pos, grow);
        LevelRenderer.addChainedFilledBoxVertices(poseStack, consumer,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, a);
    }

    private static AABB blockBox(Vec3 cam, BlockPos pos, double grow) {
        return new AABB(pos).inflate(grow).move(-cam.x, -cam.y, -cam.z);
    }
}
