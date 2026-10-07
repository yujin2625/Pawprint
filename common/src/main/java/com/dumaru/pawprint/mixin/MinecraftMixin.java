package com.dumaru.pawprint.mixin;

import com.dumaru.pawprint.client.Freecam;
import com.dumaru.pawprint.client.edit.EditMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes mouse buttons to the blueprint editor while edit mode is on. Injecting at the head of these methods
 * runs before any hand swing or packet, so the server sees nothing.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void pawprint$startUseItem(CallbackInfo ci) {
        if (EditMode.isActive()) {
            rightClickDelay = 4; // Same repeat rate as vanilla while the button is held.
            EditMode.onUse((Minecraft) (Object) this);
            ci.cancel();
        } else if (Freecam.isActive()) {
            ci.cancel(); // The player cannot see what it would use while the camera is elsewhere.
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void pawprint$startAttack(CallbackInfoReturnable<Boolean> cir) {
        if (EditMode.isActive()) {
            EditMode.onAttack((Minecraft) (Object) this);
            cir.setReturnValue(false);
        } else if (Freecam.isActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void pawprint$continueAttack(boolean leftClick, CallbackInfo ci) {
        if (EditMode.isActive() || Freecam.isActive()) {
            ci.cancel(); // Holding the attack button must not start breaking real blocks.
        }
    }

    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void pawprint$pickBlock(CallbackInfo ci) {
        if (EditMode.isActive()) {
            EditMode.onPick((Minecraft) (Object) this);
            ci.cancel();
        }
    }
}
