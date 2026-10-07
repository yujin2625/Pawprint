package com.dumaru.pawprint.neoforge;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.render.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Pawprint.MOD_ID, dist = Dist.CLIENT)
public class PawprintNeoForge {
    public PawprintNeoForge(IEventBus modBus) {
        Pawprint.init();
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> PawprintKeys.ALL.forEach(event::register));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> PawprintClient.onClientTick(Minecraft.getInstance()));
        // Same point in the frame as Fabric's AFTER_TRANSLUCENT: after translucent terrain and particles.
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                GhostRenderer.render(event.getCamera());
            }
        });
    }
}
