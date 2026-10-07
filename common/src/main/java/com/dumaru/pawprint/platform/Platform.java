package com.dumaru.pawprint.platform;

import java.nio.file.Path;

/**
 * Loader-specific functionality used by common code. Implementations are found through {@link java.util.ServiceLoader}.
 */
public interface Platform {
    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    Path getGameDir();

    Path getConfigDir();
}
