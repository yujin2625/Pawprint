package com.dumaru.pawprint.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Key mappings shared by all loaders. Each loader registers {@link #ALL} with its own API.
 */
public final class PawprintKeys {
    public static final String CATEGORY = "key.categories.pawprint";

    public static final KeyMapping OPEN_LIBRARY = new KeyMapping(
            "key.pawprint.open_library", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);

    public static final List<KeyMapping> ALL = List.of(OPEN_LIBRARY);

    private PawprintKeys() {
    }
}
