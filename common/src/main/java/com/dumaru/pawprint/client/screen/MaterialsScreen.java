package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.client.placement.MaterialList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Blocks a blueprint needs, with what the player already carries. Opened from the library, before placing.
 */
final class MaterialsScreen extends Screen {
    private static final int MARGIN = 20;
    private final Screen parent;
    private final MaterialList.Result result;
    private final String name;
    private int scroll;
    private long copiedAt;

    MaterialsScreen(Screen parent, String name, MaterialList.Result result) {
        super(Component.translatable("pawprint.materials.title", name));
        this.parent = parent;
        this.result = result;
        this.name = name;
    }

    @Override
    protected void init() {
        int buttonY = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("pawprint.placements.copy_materials"),
                b -> {
                    minecraft.keyboardHandler.setClipboard(MaterialList.toText(name, result, false));
                    copiedAt = net.minecraft.Util.getMillis();
                })
                .bounds(width / 2 - 152, buttonY, 150, 20).build()).active = !result.lines().isEmpty();
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(width / 2 + 2, buttonY, 150, 20).build());
    }

    private int left() {
        return Math.max(MARGIN, width / 2 - 170);
    }

    private int right() {
        return Math.min(width - MARGIN, width / 2 + 170);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int visible = MaterialTable.visibleRows(40, height - 44);
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, result.lines().size() - visible));
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int totalItems = result.lines().stream().mapToInt(MaterialList.Line::total).sum();
        graphics.drawCenteredString(font, Component.translatable("pawprint.materials.summary", result.lines().size(), totalItems),
                width / 2, 22, 0xA0A0A0);
        MaterialTable.render(graphics, font, result, left(), 40, right(), height - 44, scroll, false);
        if (net.minecraft.Util.getMillis() - copiedAt < 2500) {
            graphics.drawCenteredString(font, Component.translatable("pawprint.materials.copied"), width / 2, height - 40, 0x55FF55);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
