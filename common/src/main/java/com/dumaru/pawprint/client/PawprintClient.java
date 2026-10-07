package com.dumaru.pawprint.client;

import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.client.screen.EditMenuScreen;
import com.dumaru.pawprint.client.screen.LibraryScreen;
import com.dumaru.pawprint.client.screen.PlacementScreen;
import com.dumaru.pawprint.client.screen.StudioScreen;
import com.dumaru.pawprint.client.studio.Studio;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Client callbacks that each loader forwards from its own event system.
 */
public final class PawprintClient {
    /** Holding Shift while moving a placement moves it this many blocks. */
    private static final int FAST_MOVE = 5;

    private PawprintClient() {
    }

    /** Called once by each loader after the common init. */
    public static void init() {
        BlueprintLibrary.addPathListener(PlacementManager::pathChanged);
        BlueprintLibrary.addPathListener(Draft::pathChanged);
    }

    public static void onClientTick(Minecraft minecraft) {
        SelfTest.tick(minecraft);
        Freecam.tick(minecraft);
        PlacementManager.tick(minecraft);
        Studio.tick(minecraft);
        EditMode.tick(minecraft);
        if (minecraft.player == null) {
            return;
        }
        while (PawprintKeys.OPEN_LIBRARY.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new LibraryScreen(null));
            }
        }
        while (PawprintKeys.TOGGLE_EDIT.consumeClick()) {
            EditMode.toggle(minecraft);
        }
        while (PawprintKeys.TOGGLE_FREECAM.consumeClick()) {
            Freecam.toggle(minecraft);
        }
        while (PawprintKeys.LAYER_UP.consumeClick()) {
            stepLayer(minecraft, 1);
        }
        while (PawprintKeys.LAYER_DOWN.consumeClick()) {
            stepLayer(minecraft, -1);
        }
        while (PawprintKeys.STUDIO.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new StudioScreen());
            }
        }
        while (PawprintKeys.PLACEMENT_PANEL.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new PlacementScreen());
            }
        }
        while (PawprintKeys.TOGGLE_PLACEMENT_VIEW.consumeClick()) {
            togglePlacementView(minecraft);
        }
        while (PawprintKeys.EDIT_MENU.consumeClick()) {
            if (minecraft.screen == null) {
                if (!EditMode.isActive()) {
                    EditMode.toggle(minecraft);
                }
                minecraft.setScreen(new EditMenuScreen());
            }
        }
        while (PawprintKeys.MARK_CORNER.consumeClick()) {
            markCorner(minecraft);
        }
        handlePlacementKeys(minecraft);
    }

    /** The first press starts the layer view at the viewer's feet; later presses move it up or down. */
    private static void stepLayer(Minecraft minecraft, int delta) {
        Integer layer = PlacementManager.layer();
        int next = layer == null ? BlockPos.containing(ViewRay.eye(minecraft)).getY() - 1 : layer + delta;
        PlacementManager.setLayer(next);
        notify(minecraft, Component.translatable("pawprint.layer.shown", next,
                PawprintKeys.PLACEMENT_PANEL.getTranslatedKeyMessage()));
    }

    private static void togglePlacementView(Minecraft minecraft) {
        if (EditMode.isActive()) {
            EditMode.toggle(minecraft); // Edit mode already shows everything; switch over to placement mode.
        }
        boolean viewing = !PlacementManager.isViewing();
        PlacementManager.setViewing(viewing);
        notify(minecraft, Component.translatable(viewing ? "pawprint.placement.view_on" : "pawprint.placement.view_off",
                PlacementManager.placements().size()));
    }

    private static void markCorner(Minecraft minecraft) {
        BlockPos pos = targetedBlock(minecraft);
        if (pos == null) {
            pos = BlockPos.containing(ViewRay.eye(minecraft)).below();
        }
        int corner = Selection.mark(pos);
        BoundingBox box = Selection.box();
        if (corner == 2 && box != null) {
            notify(minecraft, Component.translatable("pawprint.selection.complete",
                    box.getXSpan(), box.getYSpan(), box.getZSpan(), PawprintKeys.OPEN_LIBRARY.getTranslatedKeyMessage()));
        } else {
            notify(minecraft, Component.translatable("pawprint.selection.first", pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    private static void handlePlacementKeys(Minecraft minecraft) {
        Placement placement = PlacementManager.isVisible() ? PlacementManager.active() : null;
        Direction facing = ViewRay.facing(minecraft);
        int step = Screen.hasShiftDown() ? FAST_MOVE : 1;
        boolean changed = false;

        changed |= move(PawprintKeys.MOVE_FORWARD, placement, facing, step);
        changed |= move(PawprintKeys.MOVE_BACK, placement, facing.getOpposite(), step);
        changed |= move(PawprintKeys.MOVE_LEFT, placement, facing.getCounterClockWise(), step);
        changed |= move(PawprintKeys.MOVE_RIGHT, placement, facing.getClockWise(), step);
        changed |= move(PawprintKeys.MOVE_UP, placement, Direction.UP, step);
        changed |= move(PawprintKeys.MOVE_DOWN, placement, Direction.DOWN, step);
        while (PawprintKeys.ROTATE.consumeClick()) {
            if (placement != null) {
                placement.rotateClockwise();
                changed = true;
            }
        }
        while (PawprintKeys.MIRROR.consumeClick()) {
            if (placement != null) {
                placement.cycleMirror();
                changed = true;
            }
        }
        if (changed) {
            PlacementManager.changed();
        }
    }

    private static boolean move(KeyMapping key, @Nullable Placement placement, Direction direction, int step) {
        boolean moved = false;
        while (key.consumeClick()) {
            if (placement != null) {
                placement.move(direction.getStepX() * step, direction.getStepY() * step, direction.getStepZ() * step);
                moved = true;
            }
        }
        return moved;
    }

    /** The block looked at (from the freecam when it is on), or null. */
    public static @Nullable BlockPos targetedBlock(Minecraft minecraft) {
        BlockHitResult hit = ViewRay.pick(minecraft, ViewRay.PICK_REACH);
        return hit != null ? hit.getBlockPos() : null;
    }

    public static void notify(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, true);
        }
    }
}
