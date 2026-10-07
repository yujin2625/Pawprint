package com.dumaru.pawprint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where the player is looking from and toward: the freecam when it is on, otherwise the player's eyes.
 * Pawprint uses this instead of the vanilla crosshair target, which always follows the player.
 */
public final class ViewRay {
    /** Reach for picking real blocks outside edit mode, e.g. marking selection corners. */
    public static final double PICK_REACH = 64;

    private ViewRay() {
    }

    public static Vec3 eye(Minecraft minecraft) {
        return Freecam.isActive() ? Freecam.position(1f) : minecraft.player.getEyePosition(1f);
    }

    public static Vec3 look(Minecraft minecraft) {
        return Freecam.isActive() ? Freecam.look() : minecraft.player.getViewVector(1f);
    }

    public static Direction facing(Minecraft minecraft) {
        return Freecam.isActive() ? Freecam.facing() : minecraft.player.getDirection();
    }

    /** The real block looked at, or null. */
    public static @Nullable BlockHitResult pick(Minecraft minecraft, double reach) {
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }
        Vec3 eye = eye(minecraft);
        Vec3 end = eye.add(look(minecraft).scale(reach));
        BlockHitResult hit = minecraft.level.clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** Where something placed "here" should go: against the looked-at face, or at the viewer's feet. */
    public static BlockPos placeHere(Minecraft minecraft) {
        BlockHitResult hit = pick(minecraft, PICK_REACH);
        if (hit != null) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        return BlockPos.containing(eye(minecraft)).below();
    }
}
