package com.dumaru.pawprint.mixin;

import com.dumaru.pawprint.client.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the camera to the freecam position after vanilla set it up. Marking it detached makes the game draw the
 * player's body (left behind) and hide the first-person hand.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void pawprint$freecam(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse,
                                  float partialTick, CallbackInfo ci) {
        if (Freecam.isActive()) {
            Vec3 position = Freecam.position(partialTick);
            setRotation(Freecam.yaw(), Freecam.pitch());
            setPosition(position.x, position.y, position.z);
            this.detached = true;
        }
    }
}
