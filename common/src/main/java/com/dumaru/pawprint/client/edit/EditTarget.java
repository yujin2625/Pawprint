package com.dumaru.pawprint.client.edit;

import com.dumaru.pawprint.client.ViewRay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What the crosshair points at in edit mode. Draft blocks count as solid, so blocks can be stacked in mid-air.
 *
 * @param hovered   the block under the crosshair (real or draft), or null when pointing at nothing
 * @param ghost     whether {@code hovered} is a draft block
 * @param face      the face of {@code hovered} that was hit; for mid-air targets, the face toward the player
 * @param placePos  where a new block would go
 * @param location  the exact point that was hit, used for orientation (e.g. top or bottom slab)
 */
public record EditTarget(@Nullable BlockPos hovered, boolean ghost, Direction face, BlockPos placePos, Vec3 location) {
    /** Distance at which blocks are placed when the crosshair points at nothing. */
    private static final double AIR_DISTANCE = 4.5;

    public static @Nullable EditTarget compute(Minecraft minecraft, double reach) {
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        Vec3 eye = ViewRay.eye(minecraft);
        Vec3 look = ViewRay.look(minecraft);
        Vec3 end = eye.add(look.scale(reach));

        BlockHitResult real = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double realDistance = real.getType() == HitResult.Type.MISS ? reach : real.getLocation().distanceTo(eye);

        EditTarget ghost = traceDraft(eye, look, realDistance);
        if (ghost != null) {
            return ghost;
        }
        if (real.getType() == HitResult.Type.BLOCK) {
            BlockPos hit = real.getBlockPos();
            return new EditTarget(hit, false, real.getDirection(), hit.relative(real.getDirection()), real.getLocation());
        }
        Vec3 point = eye.add(look.scale(AIR_DISTANCE));
        Direction toward = Direction.getApproximateNearest(-look.x, -look.y, -look.z);
        return new EditTarget(null, false, toward, BlockPos.containing(point), point);
    }

    /** Walks the grid cells along the ray (Amanatides–Woo) and stops at the first draft block. */
    private static @Nullable EditTarget traceDraft(Vec3 eye, Vec3 dir, double maxDistance) {
        if (Draft.isEmpty()) {
            return null;
        }
        int x = (int) Math.floor(eye.x);
        int y = (int) Math.floor(eye.y);
        int z = (int) Math.floor(eye.z);
        int stepX = dir.x > 0 ? 1 : -1;
        int stepY = dir.y > 0 ? 1 : -1;
        int stepZ = dir.z > 0 ? 1 : -1;
        double deltaX = dir.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.x);
        double deltaY = dir.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.y);
        double deltaZ = dir.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.z);
        double maxX = dir.x == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? x + 1 - eye.x : eye.x - x) * deltaX;
        double maxY = dir.y == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? y + 1 - eye.y : eye.y - y) * deltaY;
        double maxZ = dir.z == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? z + 1 - eye.z : eye.z - z) * deltaZ;

        while (true) {
            double t;
            Direction face;
            if (maxX < maxY && maxX < maxZ) {
                t = maxX;
                x += stepX;
                maxX += deltaX;
                face = stepX > 0 ? Direction.WEST : Direction.EAST;
            } else if (maxY < maxZ) {
                t = maxY;
                y += stepY;
                maxY += deltaY;
                face = stepY > 0 ? Direction.DOWN : Direction.UP;
            } else {
                t = maxZ;
                z += stepZ;
                maxZ += deltaZ;
                face = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
            }
            if (t > maxDistance) {
                return null;
            }
            BlockPos pos = new BlockPos(x, y, z);
            var state = Draft.get(pos.asLong());
            if (state != null && !state.isAir()) {
                return new EditTarget(pos, true, face, pos.relative(face), eye.add(dir.scale(t)));
            }
        }
    }
}
