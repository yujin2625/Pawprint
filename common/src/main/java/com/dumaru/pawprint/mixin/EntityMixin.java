package com.dumaru.pawprint.mixin;

import com.dumaru.pawprint.client.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends mouse look to the freecam instead of turning the player. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void pawprint$freecam(double yRot, double xRot, CallbackInfo ci) {
        if (Freecam.isActive() && (Object) this == Minecraft.getInstance().player) {
            Freecam.turn(yRot, xRot);
            ci.cancel();
        }
    }
}
