package com.dumaru.pawprint.client.screen;

import net.minecraft.client.input.MouseButtonEvent;

import net.minecraft.client.input.KeyEvent;

import com.dumaru.pawprint.client.AdjustMode;
import com.dumaru.pawprint.client.Freecam;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.placement.PlacementManager;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The one Pawprint key. Hold it, point toward an item and let go to choose; or tap it to keep the menu open and
 * click. Items are laid out clockwise from the top.
 */
public class RadialMenuScreen extends Screen {
    /** Pressing longer than this counts as holding: releasing then chooses the pointed item. */
    private static final long HOLD_MS = 250;
    private static final int RADIUS = 78;
    private static final int ITEM_WIDTH = 74;
    private static final int ITEM_HEIGHT = 34;
    private static final int DEAD_ZONE = 22;

    private record Option(String key, Item icon, BooleanSupplier on, Consumer<Minecraft> action) {
    }

    private static final List<Option> OPTIONS = List.of(
            new Option("edit", Items.BRUSH, EditMode::isActive, EditMode::toggle),
            new Option("tools", Items.PAINTING, () -> false, minecraft -> {
                if (!EditMode.isActive()) {
                    EditMode.toggle(minecraft);
                }
                minecraft.gui.setScreen(new EditMenuScreen());
            }),
            new Option("view", Items.SPYGLASS, PlacementManager::isViewing, PawprintClient::togglePlacementView),
            new Option("adjust", Items.PISTON, AdjustMode::isActive, AdjustMode::toggle),
            new Option("library", Items.BOOKSHELF, () -> false, minecraft -> minecraft.gui.setScreen(new LibraryScreen(null))),
            new Option("panel", Items.CHEST, () -> false, minecraft -> minecraft.gui.setScreen(new PlacementScreen())),
            new Option("studio", Items.GRASS_BLOCK, () -> false, minecraft -> minecraft.gui.setScreen(new StudioScreen())),
            new Option("freecam", Items.ENDER_EYE, Freecam::isActive, Freecam::toggle));

    private final long openedAt = Util.getMillis();
    /** After a quick tap the menu stays open and works by clicking. */
    private boolean clickMode;

    public RadialMenuScreen() {
        super(Component.translatable("pawprint.menu.title"));
    }

    private int centerX() {
        return width / 2;
    }

    private int centerY() {
        return height / 2;
    }

    private int itemX(int index) {
        double angle = Math.toRadians(-90 + index * 45);
        return centerX() + (int) Math.round(Math.cos(angle) * RADIUS * 1.35) - ITEM_WIDTH / 2;
    }

    private int itemY(int index) {
        double angle = Math.toRadians(-90 + index * 45);
        return centerY() + (int) Math.round(Math.sin(angle) * RADIUS) - ITEM_HEIGHT / 2;
    }

    /** The item in the direction of the mouse, or null near the center. */
    private @Nullable Integer pointed(double mouseX, double mouseY) {
        double dx = mouseX - centerX();
        double dy = mouseY - centerY();
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
            return null;
        }
        double degrees = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        return Math.floorMod(Math.round(degrees / 45), OPTIONS.size());
    }

    private void choose(@Nullable Integer index) {
        Minecraft client = minecraft;
        client.gui.setScreen(null);
        if (index != null) {
            OPTIONS.get(index).action().accept(client);
        }
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.keycode();
        int modifiers = event.modifiers();
        if (PawprintKeys.MENU.matches(event) && !clickMode) {
            releaseMenuKey();
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.keycode();
        int modifiers = event.modifiers();
        if (clickMode && PawprintKeys.MENU.matches(event)) {
            onClose(); // Pressing the key again closes the open menu.
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (PawprintKeys.MENU.matchesMouse(event) && !clickMode) {
            releaseMenuKey();
            return true;
        }
        return super.mouseReleased(event);
    }

    private void releaseMenuKey() {
        if (Util.getMillis() - openedAt < HOLD_MS) {
            clickMode = true;
        } else {
            choose(pointed(minecraft.mouseHandler.xpos() * width / minecraft.getWindow().getScreenWidth(),
                    minecraft.mouseHandler.ypos() * height / minecraft.getWindow().getScreenHeight()));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button == 0) {
            choose(pointed(mouseX, mouseY));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x50000000); // Light dimming; the world stays visible.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Integer pointed = pointed(mouseX, mouseY);
        for (int i = 0; i < OPTIONS.size(); i++) {
            Option option = OPTIONS.get(i);
            int x = itemX(i);
            int y = itemY(i);
            boolean on = option.on().getAsBoolean();
            int background = Integer.valueOf(i).equals(pointed) ? 0xD03070D0 : 0xB0101010;
            graphics.fill(x, y, x + ITEM_WIDTH, y + ITEM_HEIGHT, background);
            if (on) {
                graphics.outline(x, y, ITEM_WIDTH, ITEM_HEIGHT, 0xFF55FF55);
            }
            graphics.item(new ItemStack(option.icon()), x + (ITEM_WIDTH - 16) / 2, y + 3);
            Component label = Component.translatable("pawprint.menu." + option.key());
            graphics.centeredText(font, font.plainSubstrByWidth(label.getString(), ITEM_WIDTH - 4),
                    x + ITEM_WIDTH / 2, y + 22, on ? 0xFF55FF55 : 0xFFFFFFFF);
        }
        graphics.centeredText(font, title, centerX(), centerY() - 10, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable(clickMode ? "pawprint.menu.hint_click" : "pawprint.menu.hint_hold"),
                centerX(), centerY() + 2, 0xFFA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
