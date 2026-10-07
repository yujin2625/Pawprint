package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.placement.MaterialList;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Blocks a blueprint needs, with what the player already carries. Opened from the library, before placing.
 * A row can be selected and its block replaced throughout the blueprint.
 */
final class MaterialsScreen extends Screen {
    private static final int MARGIN = 20;
    private static final int TOP = 40;
    private final @Nullable Screen parent;
    /** The library file shown; null when showing a blueprint that is not in the library (self-test). */
    private @Nullable BlueprintLibrary.Entry entry;
    private Blueprint blueprint;
    private MaterialList.Result result;
    private int scroll;
    private int selected = -1;
    private long copiedAt;
    private @Nullable Component message;
    private Button replace;

    MaterialsScreen(@Nullable Screen parent, @Nullable BlueprintLibrary.Entry entry, Blueprint blueprint) {
        super(Component.translatable("pawprint.materials.title", blueprint.meta().name));
        this.parent = parent;
        this.entry = entry;
        this.blueprint = blueprint;
        this.result = MaterialList.forBlueprint(blueprint, null);
    }

    @Override
    protected void init() {
        result = MaterialList.forBlueprint(blueprint, minecraft.player);
        int buttonY = height - 28;
        int buttonWidth = 120;
        int left = width / 2 - (buttonWidth * 3 + 8) / 2;
        addRenderableWidget(Button.builder(Component.translatable("pawprint.placements.copy_materials"), b -> {
            minecraft.keyboardHandler.setClipboard(MaterialList.toText(blueprint.meta().name, result, false));
            copiedAt = Util.getMillis();
        }).bounds(left, buttonY, buttonWidth, 20).build()).active = !result.lines().isEmpty();
        replace = addRenderableWidget(Button.builder(Component.translatable("pawprint.replace.button"), b -> startReplace())
                .bounds(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        replace.active = entry != null && selected >= 0 && selected < result.lines().size();
    }

    private void startReplace() {
        if (entry == null || selected < 0 || selected >= result.lines().size()) {
            return;
        }
        ReplaceBlockScreen.start(this, entry, result.lines().get(selected).item(), (changed, asCopy) -> {
            entry = changed;
            try {
                blueprint = BlueprintLibrary.read(changed.file());
            } catch (IOException e) {
                Pawprint.LOG.warn("Could not reload {}", changed.file(), e);
            }
            selected = -1;
            message = Component.translatable(asCopy ? "pawprint.replace.done_copy" : "pawprint.replace.done", changed.meta().name);
            rebuildWidgets();
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int row = MaterialTable.rowAt(mouseX, mouseY, MARGIN, TOP, width - MARGIN, height - 44, scroll, result.lines().size());
        if (row >= 0 && button == 0) {
            selected = row;
            updateButtons();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int visible = MaterialTable.visibleRows(TOP, height - 44);
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, result.lines().size() - visible));
        return true;
    }

    private int left() {
        return Math.max(MARGIN, width / 2 - 170);
    }

    private int right() {
        return Math.min(width - MARGIN, width / 2 + 170);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Component.translatable("pawprint.materials.title", blueprint.meta().name), width / 2, 10, 0xFFFFFF);
        int totalItems = result.lines().stream().mapToInt(MaterialList.Line::total).sum();
        graphics.drawCenteredString(font, Component.translatable("pawprint.materials.summary", result.lines().size(), totalItems),
                width / 2, 22, 0xA0A0A0);
        MaterialTable.render(graphics, font, result, left(), TOP, right(), height - 44, scroll, false, selected);
        if (Util.getMillis() - copiedAt < 2500) {
            graphics.drawCenteredString(font, Component.translatable("pawprint.materials.copied"), width / 2, height - 40, 0x55FF55);
        } else if (message != null) {
            graphics.drawCenteredString(font, message, width / 2, height - 40, 0x55FF55);
        } else if (entry != null) {
            graphics.drawCenteredString(font, Component.translatable("pawprint.replace.hint"), width / 2, height - 40, 0x909090);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
