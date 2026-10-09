package com.dumaru.pawprint.mixin;

import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.SelfTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Pawprint use the mouse wheel in the world (moving placements, changing the layer) before the hotbar does.
 * During the development self-test the game never captures the mouse, so the person at the computer keeps using it.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void pawprint$grab(CallbackInfo ci) {
        if (SelfTest.enabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void pawprint$scroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (window == minecraft.getWindow().getWindow() && minecraft.screen == null && minecraft.player != null
                && PawprintClient.onScroll(minecraft, yOffset)) {
            ci.cancel();
        }
    }
}
