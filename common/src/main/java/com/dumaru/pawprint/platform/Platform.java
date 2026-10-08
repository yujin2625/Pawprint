package com.dumaru.pawprint.platform;

import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Loader-specific functionality used by common code. Implementations are found through {@link java.util.ServiceLoader}.
 */
public interface Platform {
    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    Path getGameDir();

    Path getConfigDir();

    /** Display name and version of a loaded mod. */
    Optional<ModInfo> modInfo(String modId);

    /**
     * How the block is drawn in the world: {@code solid}, {@code cutout}, {@code cutout_mipped} or {@code translucent}.
     * Loaders differ here (NeoForge and Forge read it from the block model).
     */
    String renderLayer(BlockState state);

    record ModInfo(String name, String version) {
    }
}
