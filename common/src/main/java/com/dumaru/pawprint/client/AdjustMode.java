package com.dumaru.pawprint.client;

import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * Moving the active placement with the mouse instead of keys: the wheel moves it (forward/back, with Shift up/down,
 * with Ctrl left/right, relative to where the player looks), right click rotates, middle click mirrors, and left
 * click or Escape finishes. Clicks are not sent to the server while adjusting.
 */
public final class AdjustMode {
    private static boolean active;
    private static boolean pauseWasOpen;

    private AdjustMode() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void toggle(Minecraft minecraft) {
        if (active) {
            finish(minecraft);
            return;
        }
        if (PlacementManager.active() == null) {
            PawprintClient.notify(minecraft, Component.translatable("pawprint.adjust.none"));
            return;
        }
        active = true;
        if (!EditMode.isActive()) {
            PlacementManager.setViewing(true);
        }
        PawprintClient.notify(minecraft, Component.translatable("pawprint.adjust.on", PlacementManager.active().blueprint().meta().name));
    }

    public static void finish(Minecraft minecraft) {
        active = false;
        PawprintClient.notify(minecraft, Component.translatable("pawprint.adjust.off"));
    }

    public static void tick(Minecraft minecraft) {
        boolean pauseOpen = minecraft.gui.screen() instanceof PauseScreen;
        if (active) {
            if (PlacementManager.active() == null || minecraft.player == null) {
                active = false;
            } else if (pauseOpen && !pauseWasOpen) {
                // Escape ends adjusting instead of pausing.
                minecraft.gui.setScreen(null);
                finish(minecraft);
                pauseOpen = false;
            }
        }
        pauseWasOpen = pauseOpen;
    }

    /** Moves the placement one block per wheel notch. Returns false when not adjusting, so the wheel works as usual. */
    public static boolean onScroll(Minecraft minecraft, double amount) {
        Placement placement = PlacementManager.active();
        if (!active || placement == null || amount == 0) {
            return false;
        }
        int step = amount > 0 ? 1 : -1;
        Direction facing = ViewRay.facing(minecraft);
        Direction direction;
        if (net.minecraft.client.Minecraft.getInstance().hasShiftDown()) {
            direction = Direction.UP;
        } else if (net.minecraft.client.Minecraft.getInstance().hasControlDown()) {
            direction = facing.getClockWise();
        } else {
            direction = facing;
        }
        placement.move(direction.getStepX() * step, direction.getStepY() * step, direction.getStepZ() * step);
        PlacementManager.changed();
        return true;
    }

    public static void rotate() {
        Placement placement = PlacementManager.active();
        if (placement != null) {
            placement.rotateClockwise();
            PlacementManager.changed();
        }
    }

    public static void mirror() {
        Placement placement = PlacementManager.active();
        if (placement != null) {
            placement.cycleMirror();
            PlacementManager.changed();
        }
    }
}
