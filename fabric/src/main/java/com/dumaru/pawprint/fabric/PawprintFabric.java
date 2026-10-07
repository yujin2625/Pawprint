package com.dumaru.pawprint.fabric;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.dumaru.pawprint.client.render.GhostRenderer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class PawprintFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Pawprint.init();
        PawprintKeys.ALL.forEach(KeyBindingHelper::registerKeyBinding);
        ClientTickEvents.END_CLIENT_TICK.register(PawprintClient::onClientTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> GhostRenderer.render(context.camera()));
    }
}
