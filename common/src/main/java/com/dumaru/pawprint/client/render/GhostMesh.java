package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.placement.BlockStatus;
import com.dumaru.pawprint.client.placement.GhostStore;
import com.dumaru.pawprint.client.placement.PlacementManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The geometry of one section of ghost blocks: block models, red boxes over blocks in the way, and outlines.
 * Vertices are relative to the section origin, so the mesh stays valid wherever the camera goes; it is rebuilt only
 * when statuses in the section change. Build on the render thread only.
 */
final class GhostMesh {
    private static final float WRONG_STATE_SCALE = 1.01f;

    final GhostGeometry.Quads models = new GhostGeometry.Quads();
    final GhostGeometry.Shapes shapes = new GhostGeometry.Shapes();

    private GhostMesh() {
    }

    static GhostMesh build(GhostStore.Section section, GhostWorld world) {
        Minecraft minecraft = Minecraft.getInstance();
        float opacity = Pawprint.config().ghostOpacity;
        boolean fullBright = Pawprint.config().ghostFullBright;
        boolean ao = minecraft.options.ambientOcclusion().get();
        // Missing blocks hide faces against neighbors like real blocks; wrong-state ones are drawn whole.
        ModelBlockRenderer culled = new ModelBlockRenderer(ao, true, minecraft.getBlockColors());
        ModelBlockRenderer whole = new ModelBlockRenderer(ao, false, minecraft.getBlockColors());
        BlockStateModelSet modelSet = minecraft.getModelManager().getBlockStateModelSet();
        GhostMesh mesh = new GhostMesh();
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
                        mesh.outline(lx, ly, lz, -0.1f, 1f, 0f, 1f); // Block from a mod that is not installed.
                    } else if (model) {
                        mesh.models.tint(1f, 1f, 1f, opacity, fullBright).scale(1f);
                        tesselate(culled, mesh.models, world, modelSet, target, pos, lx, ly, lz);
                    } else {
                        mesh.outline(lx, ly, lz, -0.05f, 0.3f, 0.7f, 1f); // Fluids and chests have no plain model.
                    }
                }
                case WRONG_STATE -> {
                    if (model) {
                        mesh.models.tint(1f, 0.85f, 0.2f, Math.min(1f, opacity + 0.1f), fullBright).scale(WRONG_STATE_SCALE);
                        tesselate(whole, mesh.models, world, modelSet, target, pos, lx, ly, lz);
                    } else {
                        mesh.outline(lx, ly, lz, -0.05f, 1f, 0.85f, 0.2f);
                    }
                }
                case WRONG_BLOCK -> mesh.shapes.box(lx - 0.01f, ly - 0.01f, lz - 0.01f, lx + 1.01f, ly + 1.01f, lz + 1.01f,
                        GhostGeometry.argb(1f, 0.15f, 0.15f, 0.35f));
                case REMOVE -> mesh.outline(lx, ly, lz, 0.002f, 1f, 0.55f, 0f);
                default -> {
                }
            }
        }
        return mesh;
    }

    static void tesselate(ModelBlockRenderer renderer, GhostGeometry.Quads out, GhostWorld world, BlockStateModelSet modelSet,
                          BlockState state, BlockPos pos, float x, float y, float z) {
        try {
            renderer.tesselateBlock(out.output(), x, y, z, world, pos, state, modelSet.get(state), state.getSeed(pos));
        } catch (RuntimeException e) {
            Pawprint.LOG.debug("Could not draw ghost {}", state, e);
        }
    }

    private void outline(float x, float y, float z, float grow, float r, float g, float b) {
        shapes.lineBox(x - grow, y - grow, z - grow, x + 1 + grow, y + 1 + grow, z + 1 + grow, GhostGeometry.argb(r, g, b, 1f), 2f);
    }
}
