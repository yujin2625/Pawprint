package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.Selection;
import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.edit.EditTarget;
import com.dumaru.pawprint.client.placement.GhostStore;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws ghost blocks, the shape preview, the edit target and the capture selection. Each loader calls
 * {@link #submit} once per frame while the game collects world geometry.
 *
 * <p>Ghost blocks are kept as one mesh per 16x16x16 section ({@link GhostMesh}) and rebuilt only when that section's
 * statuses change, a few sections per frame; a frame only hands the cached vertices to the game. Small,
 * fast-changing things (preview, outlines) are built each frame.
 */
public final class GhostRenderer {
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

    /** Hands this frame's ghost geometry to the game. {@code poseStack} is at the camera, without its offset. */
    public static void submit(SubmitNodeCollector collector, PoseStack poseStack, CameraRenderState camera) {
        long start = System.nanoTime();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != null) {
            Vec3 cam = camera.pos;
            if (PlacementManager.isVisible()) {
                submitSections(collector, poseStack, level, cam, camera.cullFrustum);
            }
            if (++frame % 40 == 0) {
                freeFarMeshes(cam);
            }
            submitImmediate(collector, poseStack, minecraft, level, cam);
        }
        long took = System.nanoTime() - start;
        statFrames++;
        statNanos += took;
        statMaxNanos = Math.max(statMaxNanos, took);
    }

    // Cached sections

    private static void submitSections(SubmitNodeCollector collector, PoseStack poseStack, ClientLevel level, Vec3 cam,
                                       @Nullable Frustum frustum) {
        double range = Pawprint.config().ghostRenderDistance + 8;
        double rangeSq = range * range;
        List<GhostStore.Section> visible = new ArrayList<>();
        Integer layer = PlacementManager.layer();
        for (GhostStore store : List.of(PlacementManager.placementGhosts(), PlacementManager.draftGhosts())) {
            for (GhostStore.Section section : store.sections()) {
                if (layer != null && (layer < section.originY || layer >= section.originY + 16)) {
                    continue;
                }
                if (section.isChecked() && GhostStore.distanceSq(section, cam) <= rangeSq
                        && (frustum == null || frustum.isVisible(bounds(section)))) {
                    visible.add(section);
                }
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        // Far to near, so see-through ghosts in front blend over those behind.
        visible.sort(Comparator.comparingDouble((GhostStore.Section section) -> GhostStore.distanceSq(section, cam)).reversed());

        GhostWorld world = new GhostWorld(level);
        long deadline = System.nanoTime() + REBUILD_BUDGET_NANOS;
        for (int i = visible.size() - 1; i >= 0; i--) { // Nearest first while time lasts.
            GhostStore.Section section = visible.get(i);
            if ((section.meshDirty || section.mesh == null) && System.nanoTime() < deadline) {
                section.mesh = GhostMesh.build(section, world);
                section.meshDirty = false;
                statMeshesBuilt++;
            }
        }
        for (GhostStore.Section section : visible) {
            if (!(section.mesh instanceof GhostMesh mesh)) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(section.originX - cam.x, section.originY - cam.y, section.originZ - cam.z);
            submit(collector, poseStack, mesh.models, mesh.shapes);
            poseStack.popPose();
        }
    }

    private static void submit(SubmitNodeCollector collector, PoseStack poseStack, GhostGeometry.Quads models,
                               GhostGeometry.Shapes shapes) {
        if (!models.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), models::replay);
        }
        if (shapes.hasQuads()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), shapes::replayQuads);
        }
        if (shapes.hasLines()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.lines(), shapes::replayLines);
        }
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

    // Built each frame: preview, outlines, selection. Coordinates are relative to the camera.

    private static void submitImmediate(SubmitNodeCollector collector, PoseStack poseStack, Minecraft minecraft,
                                        ClientLevel level, Vec3 cam) {
        GhostGeometry.Quads models = new GhostGeometry.Quads();
        GhostGeometry.Shapes lines = new GhostGeometry.Shapes();
        Preview preview = Preview.current();
        if (preview != null && !preview.erase() && preview.state() != null
                && preview.state().getRenderShape() == RenderShape.MODEL && preview.cells().size() <= MAX_PREVIEW_MODELS) {
            GhostWorld world = new GhostWorld(level);
            ModelBlockRenderer renderer = new ModelBlockRenderer(minecraft.options.ambientOcclusion().get(), false, minecraft.getBlockColors());
            models.tint(0.8f, 0.9f, 1f, Pawprint.config().ghostOpacity * PREVIEW_OPACITY, Pawprint.config().ghostFullBright);
            for (long packed : preview.cells()) {
                BlockPos pos = BlockPos.of(packed);
                GhostMesh.tesselate(renderer, models, world, minecraft.getModelManager().getBlockStateModelSet(), preview.state(), pos,
                        (float) (pos.getX() - cam.x), (float) (pos.getY() - cam.y), (float) (pos.getZ() - cam.z));
            }
        }

        Placement active = PlacementManager.active();
        if (active != null && PlacementManager.isVisible()) {
            boundsBox(lines, cam, active.worldBounds(), 0.3f, 0.9f, 1f, 1f);
        }
        addSelection(lines, cam);
        addEditTarget(lines, cam, preview);
        submit(collector, poseStack, models, lines);
    }

    private static void addEditTarget(GhostGeometry.Shapes lines, Vec3 cam, @Nullable Preview preview) {
        if (!EditMode.isActive()) {
            return;
        }
        if (preview != null) {
            boundsBox(lines, cam, preview.bounds(), 1f, 0.85f, 0.2f, 1f);
            if (preview.erase()) {
                int drawn = 0;
                for (long packed : preview.cells()) {
                    if (Draft.get(packed) != null && drawn++ < MAX_ERASE_OUTLINES) {
                        lineBox(lines, cam, BlockPos.of(packed), -0.1, 1f, 0.3f, 0.3f, 1f);
                    }
                }
            }
        }
        EditTarget target = EditMode.target();
        if (target != null) {
            lineBox(lines, cam, target.placePos(), 0.003, 1f, 1f, 1f, 0.8f);
            if (target.hovered() != null) {
                lineBox(lines, cam, target.hovered(), 0.006, 1f, 0.4f, 0.4f, 0.6f);
            }
        }
    }

    private static void addSelection(GhostGeometry.Shapes lines, Vec3 cam) {
        BoundingBox box = Selection.box();
        if (box != null) {
            boundsBox(lines, cam, box, 1f, 1f, 1f, 1f);
            return;
        }
        BlockPos first = Selection.first();
        if (first != null) {
            lineBox(lines, cam, first, 0.004, 0.4f, 1f, 0.4f, 1f);
        }
    }

    private static void lineBox(GhostGeometry.Shapes lines, Vec3 cam, BlockPos pos, double grow, float r, float g, float b, float a) {
        AABB box = new AABB(pos).inflate(grow).move(-cam.x, -cam.y, -cam.z);
        lines.lineBox((float) box.minX, (float) box.minY, (float) box.minZ, (float) box.maxX, (float) box.maxY, (float) box.maxZ,
                GhostGeometry.argb(r, g, b, a), 2f);
    }

    private static void boundsBox(GhostGeometry.Shapes lines, Vec3 cam, BoundingBox box, float r, float g, float b, float a) {
        AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)
                .inflate(0.01).move(-cam.x, -cam.y, -cam.z);
        lines.lineBox((float) aabb.minX, (float) aabb.minY, (float) aabb.minZ, (float) aabb.maxX, (float) aabb.maxY, (float) aabb.maxZ,
                GhostGeometry.argb(r, g, b, a), 2f);
    }
}
