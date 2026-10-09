package com.dumaru.pawprint.fabric;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.edit.EditHud;
import com.dumaru.pawprint.client.render.GhostRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;

public class PawprintFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Pawprint.init();
        PawprintClient.init();
        GhostRenderer.init();
        PawprintKeys.ALL.forEach(KeyMappingHelper::registerKeyMapping);
        ClientTickEvents.END_CLIENT_TICK.register(PawprintClient::onClientTick);
        // Ghost blocks join the frame's geometry like the game's own (NeoForge: SubmitCustomGeometryEvent).
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> GhostRenderer.submit(
                context.submitNodeCollector(), context.poseStack(), context.levelState().cameraRenderState));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Pawprint.MOD_ID, "edit_hud"), (graphics, delta) -> EditHud.render(graphics));
    }
}
