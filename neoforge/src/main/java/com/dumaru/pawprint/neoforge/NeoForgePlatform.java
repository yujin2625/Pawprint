package com.dumaru.pawprint.neoforge;

import com.dumaru.pawprint.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
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
        return !FMLLoader.isProduction();
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
        // NeoForge models declare their own render types ("render_type" in the model JSON).
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        ChunkRenderTypeSet types = model.getRenderTypes(state, RandomSource.create(42), ModelData.EMPTY);
        if (types.contains(RenderType.translucent())) {
            return "translucent";
        }
        if (types.contains(RenderType.cutoutMipped())) {
            return "cutout_mipped";
        }
        if (types.contains(RenderType.cutout())) {
            return "cutout";
        }
        return "solid";
    }
}
