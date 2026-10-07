package com.dumaru.pawprint.client;

import com.dumaru.pawprint.client.screen.LibraryScreen;
import net.minecraft.client.Minecraft;

/**
 * Client callbacks that each loader forwards from its own event system.
 */
public final class PawprintClient {
    private PawprintClient() {
    }

    public static void onClientTick(Minecraft minecraft) {
        while (PawprintKeys.OPEN_LIBRARY.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new LibraryScreen(null));
            }
        }
    }
}
