package com.dumaru.pawprint.mixin;

import com.dumaru.pawprint.client.Freecam;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands movement keys to the freecam and leaves the player standing still. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void pawprint$freecam(boolean isSneaking, float sneakingSpeedMultiplier, CallbackInfo ci) {
        Freecam.captureInput((Input) (Object) this);
    }
}
