package com.dumaru.pawprint.client.edit;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.shape.Shape;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.function.LongConsumer;

/**
 * In-world blueprint editing. While active, use/attack/pick-block clicks are intercepted before the game handles
 * them (see {@code MinecraftMixin}), so nothing is sent to the server: they edit the {@link Draft} instead.
 *
 * <ul>
 *   <li>Right click: place (single) or set the shape's points and fill.</li>
 *   <li>Left click: delete a draft block, or mark a real block for removal (single);
 *       with a shape tool, set the points and erase draft blocks inside the shape.</li>
 *   <li>Middle click: pick the hovered block's exact state as the brush.</li>
 * </ul>
 */
public final class EditMode {
    private static boolean active;
    private static Shape tool = Shape.SINGLE;
    /** Block chosen in the palette or picked; null means "use the block in the main hand". */
    private static @Nullable BlockState brush;
    /** Picked states are used as is; palette and held blocks orient themselves like a normal placement. */
    private static boolean brushExact;

    private static @Nullable BlockPos firstPoint;
    private static boolean pendingErase;
    private static @Nullable BlockState pendingState;

    private static @Nullable EditTarget target;
    /** Edge detection: holding the use button repeats clicks, which must not complete a shape by accident. */
    private static boolean useHandled;
    private static boolean undoKeyDown;
    private static boolean redoKeyDown;

    private EditMode() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void toggle(Minecraft minecraft) {
        active = !active;
        cancelShape();
        if (!active) {
            Draft.save();
        }
        PawprintClient.notify(minecraft, Component.translatable(active ? "pawprint.edit.on" : "pawprint.edit.off"));
    }

    public static Shape tool() {
        return tool;
    }

    public static void setTool(Shape newTool) {
        tool = newTool;
        cancelShape();
    }

    public static @Nullable BlockState brush() {
        return brush;
    }

    public static void setBrush(@Nullable BlockState state, boolean exact) {
        brush = state;
        brushExact = exact;
    }

    public static @Nullable EditTarget target() {
        return target;
    }

    public static @Nullable BlockPos firstPoint() {
        return firstPoint;
    }

    public static boolean pendingErase() {
        return pendingErase;
    }

    public static @Nullable BlockState pendingState() {
        return pendingState;
    }

    public static void tick(Minecraft minecraft) {
        Draft.tick();
        if (!active || minecraft.player == null) {
            target = null;
            return;
        }
        target = EditTarget.compute(minecraft, Pawprint.config().editReach);
        if (!minecraft.options.keyUse.isDown()) {
            useHandled = false;
        }
        if (minecraft.screen == null) {
            handleUndoRedo(minecraft);
        }
    }

    private static void handleUndoRedo(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        boolean ctrl = Screen.hasControlDown();
        boolean undoDown = ctrl && !Screen.hasShiftDown() && InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Z);
        boolean redoDown = ctrl && (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Y)
                || (Screen.hasShiftDown() && InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Z)));
        if (undoDown && !undoKeyDown) {
            undo(minecraft);
        }
        if (redoDown && !redoKeyDown) {
            redo(minecraft);
        }
        undoKeyDown = undoDown;
        redoKeyDown = redoDown;
    }

    public static void undo(Minecraft minecraft) {
        cancelShape();
        PawprintClient.notify(minecraft, Component.translatable(Draft.undo() ? "pawprint.edit.undone" : "pawprint.edit.nothing_to_undo"));
        PlacementManager.changed();
    }

    public static void redo(Minecraft minecraft) {
        cancelShape();
        PawprintClient.notify(minecraft, Component.translatable(Draft.redo() ? "pawprint.edit.redone" : "pawprint.edit.nothing_to_redo"));
        PlacementManager.changed();
    }

    public static void cancelShape() {
        firstPoint = null;
        pendingState = null;
        pendingErase = false;
    }

    // Click handlers, called from the mixin instead of the vanilla behavior.

    public static void onUse(Minecraft minecraft) {
        boolean repeat = useHandled;
        useHandled = true;
        EditTarget hit = target;
        if (hit == null) {
            return;
        }
        if (tool == Shape.SINGLE) {
            BlockState state = brushState(minecraft, hit);
            if (state != null) {
                edit(() -> Draft.set(hit.placePos().asLong(), state));
            }
            return;
        }
        if (repeat) {
            return;
        }
        if (firstPoint == null) {
            BlockState state = brushState(minecraft, hit);
            if (state != null) {
                firstPoint = hit.placePos();
                pendingState = state;
                pendingErase = false;
            }
        } else if (pendingErase) {
            cancelShape();
        } else {
            BlockState state = pendingState;
            applyShape(minecraft, firstPoint, hit.placePos(), pos -> Draft.set(pos, state));
        }
    }

    public static void onAttack(Minecraft minecraft) {
        EditTarget hit = target;
        if (hit == null) {
            return;
        }
        if (tool == Shape.SINGLE) {
            if (hit.hovered() == null) {
                return;
            }
            long pos = hit.hovered().asLong();
            if (hit.ghost()) {
                edit(() -> Draft.set(pos, null));
            } else {
                BlockState current = Draft.get(pos);
                // Toggle "remove this real block".
                edit(() -> Draft.set(pos, current != null && current.isAir() ? null : Blocks.AIR.defaultBlockState()));
            }
            return;
        }
        BlockPos point = hit.hovered() != null ? hit.hovered() : hit.placePos();
        if (firstPoint == null) {
            firstPoint = point;
            pendingErase = true;
            pendingState = null;
        } else if (!pendingErase) {
            cancelShape();
        } else {
            applyShape(minecraft, firstPoint, point, pos -> Draft.set(pos, null));
        }
    }

    public static void onPick(Minecraft minecraft) {
        EditTarget hit = target;
        if (hit == null || hit.hovered() == null) {
            return;
        }
        BlockState state = hit.ghost() ? Draft.get(hit.hovered().asLong()) : minecraft.level.getBlockState(hit.hovered());
        if (state != null && !state.isAir()) {
            setBrush(state, true);
            PawprintClient.notify(minecraft, Component.translatable("pawprint.edit.picked", state.getBlock().getName()));
        }
    }

    private static void applyShape(Minecraft minecraft, BlockPos a, BlockPos b, LongConsumer action) {
        cancelShape();
        long cells = tool.estimate(a, b);
        if (cells > Shape.MAX_CELLS) {
            PawprintClient.notify(minecraft, Component.translatable("pawprint.edit.too_large", cells, Shape.MAX_CELLS));
            return;
        }
        LongList positions = new LongArrayList();
        tool.forEach(a, b, positions::add);
        edit(() -> positions.forEach(action));
    }

    private static void edit(Runnable change) {
        Draft.begin();
        try {
            change.run();
        } finally {
            Draft.end();
        }
        PlacementManager.changed();
    }

    /** The state to place at the target: the brush (oriented unless exact), or the block in the main hand. */
    private static @Nullable BlockState brushState(Minecraft minecraft, EditTarget hit) {
        Block block;
        if (brush != null) {
            if (brushExact) {
                return brush;
            }
            block = brush.getBlock();
        } else if (minecraft.player.getMainHandItem().getItem() instanceof BlockItem blockItem) {
            block = blockItem.getBlock();
        } else {
            PawprintClient.notify(minecraft, Component.translatable("pawprint.edit.no_brush",
                    PawprintKeys.EDIT_MENU.getTranslatedKeyMessage()));
            return null;
        }
        try {
            BlockState state = block.getStateForPlacement(new GhostPlaceContext(minecraft.player, new ItemStack(block), hit));
            return state != null ? state : block.defaultBlockState();
        } catch (RuntimeException e) {
            // Some modded blocks assume a real placement; fall back to the plain state.
            Pawprint.LOG.debug("getStateForPlacement failed for {}", block, e);
            return block.defaultBlockState();
        }
    }
}
