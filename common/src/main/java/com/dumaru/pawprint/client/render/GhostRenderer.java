package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.Selection;
import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.edit.EditTarget;
import com.dumaru.pawprint.client.placement.GhostStore;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws ghost blocks, the shape preview, the edit target and the capture selection. Called by each loader once
 * per frame after translucent terrain, while the model-view matrix already holds the camera rotation.
 *
 * <p>Ghost blocks are cached as one GPU mesh per 16x16x16 section ({@link GhostMesh}) and rebuilt only when that
 * section's statuses change, a few sections per frame. Drawing a frame is then a handful of buffer draws,
 * which keeps very large blueprints smooth. Small, fast-changing things (preview, outlines) are drawn directly.
 */
public final class GhostRenderer {
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
    private static final TintingConsumer TINT = new TintingConsumer();
    private static final RandomSource RANDOM = RandomSource.create();

    /** Time per frame allowed for rebuilding section meshes. */
    private static final long REBUILD_BUDGET_NANOS = 4_000_000;
    /** Meshes this far beyond the render distance are freed. */
    private static final double FREE_MARGIN = 48;
    private static final float PREVIEW_OPACITY = 0.55f;
    /** Larger previews show only their bounds; drawing every block each frame would stutter. */
    private static final int MAX_PREVIEW_MODELS = 4096;
    /** Erasing only affects draft blocks, so only those are outlined, up to this many. */
    private static final int MAX_ERASE_OUTLINES = 4096;

    private static int frame;
    // Timing counters for the self-test and debugging.
    private static long statFrames;
    private static long statNanos;
    private static long statMaxNanos;
    private static long statMeshesBuilt;

    private GhostRenderer() {
    }

    /** Hooks mesh cleanup into the ghost stores; call once at startup. */
    public static void init() {
        PlacementManager.setMeshRelease(GhostRenderer::release);
    }

    private static void release(GhostStore.Section section) {
        if (section.mesh instanceof GhostMesh mesh) {
            mesh.close();
        }
        section.mesh = null;
        section.meshDirty = true;
    }

    public static void resetStats() {
        statFrames = statNanos = statMaxNanos = statMeshesBuilt = 0;
    }

    public static String stats() {
        return String.format("ghost render: %d frames, avg %.2f ms, max %.2f ms, %d section meshes built",
                statFrames, statFrames == 0 ? 0 : statNanos / 1e6 / statFrames, statMaxNanos / 1e6, statMeshesBuilt);
    }

    public static void render(Camera camera, @Nullable Frustum frustum) {
        long start = System.nanoTime();
        renderTimed(camera, frustum);
        long took = System.nanoTime() - start;
        statFrames++;
        statNanos += took;
        statMaxNanos = Math.max(statMaxNanos, took);
    }

    private static void renderTimed(Camera camera, @Nullable Frustum frustum) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 cam = camera.getPosition();
        if (PlacementManager.isVisible()) {
            renderSections(level, cam, frustum);
        }
        if (++frame % 40 == 0) {
            freeFarMeshes(cam);
        }
        renderImmediate(minecraft, level, cam);
    }

    // Cached sections

    private static void renderSections(ClientLevel level, Vec3 cam, @Nullable Frustum frustum) {
        double range = Pawprint.config().ghostRenderDistance + 8;
        double rangeSq = range * range;
        List<GhostStore.Section> visible = new ArrayList<>();
        for (GhostStore store : List.of(PlacementManager.placementGhosts(), PlacementManager.draftGhosts())) {
            for (GhostStore.Section section : store.sections()) {
                if (section.isChecked() && GhostStore.distanceSq(section, cam) <= rangeSq
                        && (frustum == null || frustum.isVisible(bounds(section)))) {
                    visible.add(section);
                }
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        visible.sort(Comparator.comparingDouble(section -> GhostStore.distanceSq(section, cam)));

        GhostWorld world = new GhostWorld(level);
        long deadline = System.nanoTime() + REBUILD_BUDGET_NANOS;
        for (GhostStore.Section section : visible) {
            if ((section.meshDirty || section.mesh == null) && System.nanoTime() < deadline) {
                release(section);
                section.mesh = GhostMesh.build(section, world, cam);
                section.meshDirty = false;
                statMeshesBuilt++;
            }
        }
        draw(RenderType.translucent(), visible, cam, GhostMesh.MODELS);
        draw(RenderType.debugFilledBox(), visible, cam, GhostMesh.BOXES);
        draw(RenderType.lines(), visible, cam, GhostMesh.LINES);
    }

    private static void draw(RenderType type, List<GhostStore.Section> sections, Vec3 cam, int layer) {
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            if (shader.CHUNK_OFFSET != null) {
                shader.CHUNK_OFFSET.set(0f, 0f, 0f); // Offsets go into the model-view matrix instead.
            }
            Matrix4f modelView = RenderSystem.getModelViewMatrix();
            Matrix4f projection = RenderSystem.getProjectionMatrix();
            for (GhostStore.Section section : sections) {
                if (!(section.mesh instanceof GhostMesh mesh)) {
                    continue;
                }
                VertexBuffer buffer = mesh.get(layer);
                if (buffer == null) {
                    continue;
                }
                Matrix4f matrix = new Matrix4f(modelView).translate(
                        (float) (section.originX - cam.x), (float) (section.originY - cam.y), (float) (section.originZ - cam.z));
                buffer.bind();
                buffer.drawWithShader(matrix, projection, shader);
            }
            VertexBuffer.unbind();
        }
        type.clearRenderState();
    }

    private static void freeFarMeshes(Vec3 cam) {
        double limit = Pawprint.config().ghostRenderDistance + FREE_MARGIN;
        double limitSq = limit * limit;
        for (GhostStore store : List.of(PlacementManager.placementGhosts(), PlacementManager.draftGhosts())) {
            for (GhostStore.Section section : store.sections()) {
                if (section.mesh != null && GhostStore.distanceSq(section, cam) > limitSq) {
                    release(section);
                }
            }
        }
    }

    private static AABB bounds(GhostStore.Section section) {
        return new AABB(section.originX, section.originY, section.originZ,
                section.originX + 16, section.originY + 16, section.originZ + 16);
    }

    // Immediate: preview, outlines, selection

    private static void renderImmediate(Minecraft minecraft, ClientLevel level, Vec3 cam) {
        PoseStack poseStack = new PoseStack();
        Preview preview = Preview.current();
        if (preview != null && !preview.erase() && preview.state() != null
                && preview.state().getRenderShape() == RenderShape.MODEL && preview.cells().size() <= MAX_PREVIEW_MODELS) {
            GhostWorld world = new GhostWorld(level);
            TINT.set(BUFFERS.getBuffer(RenderType.translucent()), 0.8f, 0.9f, 1f,
                    Pawprint.config().ghostOpacity * PREVIEW_OPACITY, Pawprint.config().ghostFullBright);
            for (long packed : preview.cells()) {
                drawBlock(minecraft, world, poseStack, TINT, cam, preview.state(), BlockPos.of(packed));
            }
            BUFFERS.endBatch(RenderType.translucent());
        }

        VertexConsumer lines = BUFFERS.getBuffer(RenderType.lines());
        Placement active = PlacementManager.active();
        if (active != null && PlacementManager.isVisible()) {
            boundsBox(poseStack, lines, cam, active.worldBounds(), 0.3f, 0.9f, 1f, 1f);
        }
        renderSelection(poseStack, lines, cam);
        renderEditTarget(poseStack, lines, cam, preview);
        BUFFERS.endBatch(RenderType.lines());
    }

    private static void drawBlock(Minecraft minecraft, GhostWorld world, PoseStack poseStack, VertexConsumer consumer,
                                  Vec3 cam, BlockState state, BlockPos pos) {
        poseStack.pushPose();
        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        try {
            minecraft.getBlockRenderer().getModelRenderer().tesselateBlock(world, minecraft.getBlockRenderer().getBlockModel(state),
                    state, pos, poseStack, consumer, false, RANDOM, state.getSeed(pos), OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException e) {
            Pawprint.LOG.debug("Could not draw preview {}", state, e);
        }
        poseStack.popPose();
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
        LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).inflate(grow).move(-cam.x, -cam.y, -cam.z), r, g, b, a);
    }

    private static void boundsBox(PoseStack poseStack, VertexConsumer lines, Vec3 cam, BoundingBox box,
                                  float r, float g, float b, float a) {
        AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)
                .inflate(0.01).move(-cam.x, -cam.y, -cam.z);
        LevelRenderer.renderLineBox(poseStack, lines, aabb, r, g, b, a);
    }
}
