package com.dumaru.pawprint.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

import java.util.List;

/**
 * Key mappings shared by all loaders. Each loader registers {@link #ALL} with its own API.
 */
public final class PawprintKeys {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("pawprint", "keys"));

    /** The one key bound by default: opens the radial menu. */
    public static final KeyMapping MENU = new KeyMapping("key.pawprint.menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY);

    // Everything below is reachable from the menu or the mouse. The keys exist for players who want direct
    // shortcuts, but are unbound by default so Pawprint does not take keys other mods use.
    public static final KeyMapping OPEN_LIBRARY = key("open_library");
    public static final KeyMapping MARK_CORNER = key("mark_corner");
    public static final KeyMapping TOGGLE_EDIT = key("toggle_edit");
    public static final KeyMapping EDIT_MENU = key("edit_menu");
    public static final KeyMapping TOGGLE_PLACEMENT_VIEW = key("toggle_placement_view");
    public static final KeyMapping PLACEMENT_PANEL = key("placement_panel");
    public static final KeyMapping TOGGLE_FREECAM = key("toggle_freecam");
    public static final KeyMapping STUDIO = key("studio");
    public static final KeyMapping LAYER_UP = key("layer_up");
    public static final KeyMapping LAYER_DOWN = key("layer_down");

    // Moves are relative to the direction the player faces. The mouse equivalent is the menu's Adjust Placement.
    public static final KeyMapping MOVE_FORWARD = key("move_forward");
    public static final KeyMapping MOVE_BACK = key("move_back");
    public static final KeyMapping MOVE_LEFT = key("move_left");
    public static final KeyMapping MOVE_RIGHT = key("move_right");
    public static final KeyMapping MOVE_UP = key("move_up");
    public static final KeyMapping MOVE_DOWN = key("move_down");
    public static final KeyMapping ROTATE = key("rotate");
    public static final KeyMapping MIRROR = key("mirror");

    public static final List<KeyMapping> ALL = List.of(
            MENU, OPEN_LIBRARY, MARK_CORNER, TOGGLE_EDIT, EDIT_MENU, TOGGLE_PLACEMENT_VIEW, PLACEMENT_PANEL, TOGGLE_FREECAM, STUDIO, LAYER_UP, LAYER_DOWN,
            MOVE_FORWARD, MOVE_BACK, MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN, ROTATE, MIRROR);

    private PawprintKeys() {
    }

    private static KeyMapping key(String name) {
        return new KeyMapping("key.pawprint." + name, InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY);
    }
}
