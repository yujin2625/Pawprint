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

    public static final KeyMapping OPEN_LIBRARY = key("open_library", GLFW.GLFW_KEY_B);
    public static final KeyMapping MARK_CORNER = key("mark_corner", GLFW.GLFW_KEY_N);
    public static final KeyMapping TOGGLE_EDIT = key("toggle_edit", GLFW.GLFW_KEY_G);
    public static final KeyMapping EDIT_MENU = key("edit_menu", GLFW.GLFW_KEY_H);

    // Moves are relative to the direction the player faces.
    public static final KeyMapping MOVE_FORWARD = key("move_forward", GLFW.GLFW_KEY_UP);
    public static final KeyMapping MOVE_BACK = key("move_back", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping MOVE_LEFT = key("move_left", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping MOVE_RIGHT = key("move_right", GLFW.GLFW_KEY_RIGHT);
    public static final KeyMapping MOVE_UP = key("move_up", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping MOVE_DOWN = key("move_down", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping ROTATE = key("rotate", GLFW.GLFW_KEY_RIGHT_BRACKET);
    public static final KeyMapping MIRROR = key("mirror", GLFW.GLFW_KEY_LEFT_BRACKET);

    public static final List<KeyMapping> ALL = List.of(
            OPEN_LIBRARY, MARK_CORNER, TOGGLE_EDIT, EDIT_MENU,
            MOVE_FORWARD, MOVE_BACK, MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN, ROTATE, MIRROR);

    private PawprintKeys() {
    }

    private static KeyMapping key(String name, int defaultKey) {
        return new KeyMapping("key.pawprint." + name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY);
    }
}
