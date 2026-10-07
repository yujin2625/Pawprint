package com.dumaru.pawprint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Identifies the world the player is in, so placements and origins can be tied to it.
 */
public final class ClientContext {
    private ClientContext() {
    }

    /** Server address for multiplayer, {@code local/<world folder>} for singleplayer, or null when not in a world. */
    public static @Nullable String server() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        IntegratedServer local = minecraft.getSingleplayerServer();
        if (local != null) {
            return "local/" + local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        }
        ServerData server = minecraft.getCurrentServer();
        return server != null ? server.ip : "unknown";
    }

    public static @Nullable String dimension() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : minecraft.level.dimension().location().toString();
    }

    /** Turns any string into a single safe file or folder name. */
    public static String fileSafe(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return out.toString();
    }
}
