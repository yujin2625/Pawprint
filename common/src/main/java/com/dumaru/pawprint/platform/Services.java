package com.dumaru.pawprint.platform;

import com.dumaru.pawprint.Pawprint;

import java.util.ServiceLoader;

public final class Services {
    public static final Platform PLATFORM = load(Platform.class);

    private Services() {
    }

    public static <T> T load(Class<T> clazz) {
        T service = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to load service for " + clazz.getName()));
        Pawprint.LOG.debug("Loaded {} for service {}", service, clazz);
        return service;
    }
}
