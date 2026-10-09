package com.dumaru.pawprint.neoforge;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.edit.EditHud;
import com.dumaru.pawprint.client.render.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Pawprint.MOD_ID, dist = Dist.CLIENT)
public class PawprintNeoForge {
    public PawprintNeoForge(IEventBus modBus) {
        Pawprint.init();
        PawprintClient.init();
        GhostRenderer.init();
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> PawprintKeys.ALL.forEach(event::register));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> PawprintClient.onClientTick(Minecraft.getInstance()));
        // Ghost blocks join the frame's geometry like the game's own (Fabric: COLLECT_SUBMITS).
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, event -> GhostRenderer.submit(
                event.getSubmitNodeCollector(), event.getPoseStack(), event.getLevelRenderState().cameraRenderState));
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, event -> EditHud.render(event.getGuiGraphics()));
    }
}
