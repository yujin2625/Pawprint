package com.dumaru.pawprint.fabric;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.edit.EditHud;
import com.dumaru.pawprint.client.render.GhostRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class PawprintFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Pawprint.init();
        PawprintClient.init();
        GhostRenderer.init();
        PawprintKeys.ALL.forEach(KeyBindingHelper::registerKeyBinding);
        ClientTickEvents.END_CLIENT_TICK.register(PawprintClient::onClientTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> GhostRenderer.render(context.camera(), context.frustum(), context.matrixStack()));
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> EditHud.render(graphics));
    }
}
