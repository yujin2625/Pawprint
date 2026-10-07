package com.dumaru.pawprint.forge;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.edit.EditHud;
import com.dumaru.pawprint.client.render.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkConstants;

@Mod(Pawprint.MOD_ID)
public class PawprintForge {
    public PawprintForge() {
        // Client-only: servers do not need this mod and clients may join servers without it.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        Pawprint.init();
        PawprintClient.init();
        GhostRenderer.init();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((RegisterKeyMappingsEvent event) -> PawprintKeys.ALL.forEach(event::register));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                PawprintClient.onClientTick(Minecraft.getInstance());
            }
        });
        // Same point in the frame as Fabric's AFTER_TRANSLUCENT: after translucent terrain and particles.
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                GhostRenderer.render(event.getCamera(), event.getFrustum());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> EditHud.render(event.getGuiGraphics()));
    }
}
