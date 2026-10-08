package com.dumaru.pawprint.forge;

import com.dumaru.pawprint.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;

public class ForgePlatform implements Platform {
    @Override
    public String getPlatformName() {
        return "Forge";
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
        // Forge models declare their own render types ("render_type" in the model JSON).
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
