package com.dumaru.pawprint.fabric;

import com.dumaru.pawprint.platform.Platform;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Optional;

public class FabricPlatform implements Platform {
    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Optional<ModInfo> modInfo(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> new ModInfo(container.getMetadata().getName(), container.getMetadata().getVersion().getFriendlyString()));
    }

    @Override
    public String renderLayer(BlockState state) {
        return com.dumaru.pawprint.client.render.RenderLayers.of(state);
    }
}
