package com.dumaru.pawprint;

import com.dumaru.pawprint.config.PawprintConfig;
import com.dumaru.pawprint.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent entry point. Each loader calls {@link #init()} once during client startup.
 */
public final class Pawprint {
    public static final String MOD_ID = "pawprint";
    public static final String MOD_NAME = "Pawprint";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private static PawprintConfig config;

    private Pawprint() {
    }

    public static void init() {
        config = PawprintConfig.load(Services.PLATFORM.getConfigDir().resolve(MOD_ID + ".json"));
        LOG.info("{} initialized on {}", MOD_NAME, Services.PLATFORM.getPlatformName());
    }

    public static PawprintConfig config() {
        return config;
    }
}
