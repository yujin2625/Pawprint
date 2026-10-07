package com.dumaru.pawprint.neoforge;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Pawprint.MOD_ID, dist = Dist.CLIENT)
public class PawprintNeoForge {
    public PawprintNeoForge(IEventBus modBus) {
        Pawprint.init();
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> PawprintKeys.ALL.forEach(event::register));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> PawprintClient.onClientTick(Minecraft.getInstance()));
    }
}
