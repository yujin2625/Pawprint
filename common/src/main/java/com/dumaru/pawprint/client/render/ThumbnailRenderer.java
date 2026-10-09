package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.Blueprint;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Draws a blueprint into an off-screen image from above and to the south-east, the way the front (south side) of a
 * build is usually seen. Call on the render thread; the image arrives a frame or so later.
 */
public final class ThumbnailRenderer {
    public static final int SIZE = 128;
    /** Above this many blocks only the outer shell is drawn; inner blocks cannot be seen anyway. */
    private static final int SHELL_THRESHOLD = 20_000;
    /** Shells larger than this are not rendered; drawing them would stall the frame. */
    public static final int MAX_BLOCKS = 300_000;

    private ThumbnailRenderer() {
    }

    /** A new image the caller must close, or null when the blueprint is too large or empty. */
    public static CompletableFuture<@Nullable NativeImage> render(Blueprint blueprint) {
        if (blueprint.blocks().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LongSet only = blueprint.blocks().size() > SHELL_THRESHOLD ? shell(blueprint) : null;
        if ((only != null ? only.size() : blueprint.blocks().size()) > MAX_BLOCKS) {
            return CompletableFuture.completedFuture(null);
        }
        GhostGeometry.Quads opaque = new GhostGeometry.Quads().tint(1f, 1f, 1f, 1f, true);
        GhostGeometry.Quads translucent = new GhostGeometry.Quads().tint(1f, 1f, 1f, 1f, true);
        tesselate(blueprint, only, opaque, translucent);

        float sx = blueprint.sizeX();
        float sy = blueprint.sizeY();
        float sz = blueprint.sizeZ();
        float radius = (float) Math.sqrt(sx * sx + sy * sy + sz * sz) / 2f;
        float scale = SIZE / 2f / radius;
        Offscreen.Step step = (poseStack, storage) -> {
            poseStack.translate(SIZE / 2f, SIZE / 2f, 0f);
            poseStack.scale(scale, -scale, scale); // y points down in this projection, as for GUI items.
            poseStack.rotate(Axis.XP.rotationDegrees(30f));
            poseStack.rotate(Axis.YP.rotationDegrees(-45f));
            poseStack.translate(-sx / 2f, -sy / 2f, -sz / 2f);
            if (!opaque.isEmpty()) {
                storage.submitCustomGeometry(poseStack, RenderTypes.cutoutMovingBlock(), opaque::replay);
            }
            if (!translucent.isEmpty()) {
                storage.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), translucent::replay);
            }
        };
        return Offscreen.render("thumbnail", SIZE, SIZE, List.of(step)).thenApply(image -> image);
    }

    private static void tesselate(Blueprint blueprint, @Nullable LongSet only, GhostGeometry.Quads opaque, GhostGeometry.Quads translucent) {
        Minecraft minecraft = Minecraft.getInstance();
        ModelBlockRenderer renderer = new ModelBlockRenderer(true, true, minecraft.getBlockColors());
        BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        BlueprintWorld world = new BlueprintWorld(blueprint);
        var toOpaque = opaque.output();
        var toTranslucent = translucent.output();
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            if (only != null && !only.contains(entry.getLongKey())) {
                continue;
            }
            BlockState state = blueprint.state(entry.getIntValue());
            if (state == null || state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            BlockPos pos = BlockPos.of(entry.getLongKey());
            try {
                renderer.tesselateBlock((x, y, z, quad, instance) -> (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT
                                ? toTranslucent : toOpaque).put(x, y, z, quad, instance),
                        pos.getX(), pos.getY(), pos.getZ(), world, pos, state, models.get(state), state.getSeed(pos));
            } catch (RuntimeException e) {
                Pawprint.LOG.debug("Skipping {} in thumbnail", state, e);
            }
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
}
