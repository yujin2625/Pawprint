package com.dumaru.pawprint.neoforge;

import com.dumaru.pawprint.platform.Platform;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;

public class NeoForgePlatform implements Platform {
    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Optional<ModInfo> modInfo(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> new ModInfo(container.getModInfo().getDisplayName(), container.getModInfo().getVersion().toString()));
    }

    @Override
    public String renderLayer(BlockState state) {
        return com.dumaru.pawprint.client.render.RenderLayers.of(state);
    }
}
