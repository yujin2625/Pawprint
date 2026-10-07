package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.ViewRay;
import com.dumaru.pawprint.client.placement.MaterialList;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Placements in this world: pick the one the move/rotate keys act on, see build progress, and the materials
 * still needed compared with the inventory.
 */
public class PlacementScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int LIST_WIDTH = 170;
    private static final int ROW = 22;

    private @Nullable MaterialList.Result materials;
    private int materialScroll;
    private long copiedAt;
    private Button remove;
    private Button moveHere;
    private Button copy;

    public PlacementScreen() {
        super(Component.translatable("pawprint.placements.title"));
    }

    @Override
    protected void init() {
        int bottom = height - 28;
        // Five buttons spread evenly over the width, so nothing overlaps on small screens.
        int gap = 4;
        int buttonWidth = (width - MARGIN * 2 - gap * 4) / 5;
        int x = MARGIN;
        remove = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.library.remove_placement"), b -> {
            PlacementManager.removeActive();
            recompute();
        }).bounds(x, bottom, buttonWidth, 20).build());
        x += buttonWidth + gap;
        moveHere = addRenderableWidget(Button.builder(Component.translatable("pawprint.placements.move_here"), b -> {
            Placement active = PlacementManager.active();
            if (active != null) {
                active.moveTo(ViewRay.placeHere(minecraft));
                PlacementManager.changed();
                recompute();
            }
        }).bounds(x, bottom, buttonWidth, 20).build());
        x += buttonWidth + gap;
        copy = addRenderableWidget(Button.builder(Component.translatable("pawprint.placements.copy_materials"), b -> {
            if (materials != null) {
                Placement active = PlacementManager.active();
                minecraft.keyboardHandler.setClipboard(MaterialList.toText(
                        active == null ? "" : active.blueprint().meta().name, materials, true));
                copiedAt = net.minecraft.Util.getMillis();
            }
        }).bounds(x, bottom, buttonWidth, 20).build());
        x += buttonWidth + gap;
        addRenderableWidget(Button.builder(Component.translatable(PlacementManager.layer() != null ? "pawprint.layer.all" : "pawprint.layer.on"),
                b -> {
                    PawprintClient.toggleLayer(minecraft);
                    b.setMessage(Component.translatable(PlacementManager.layer() != null ? "pawprint.layer.all" : "pawprint.layer.on"));
                }).bounds(x, bottom, buttonWidth, 20).build());
        x += buttonWidth + gap;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x, bottom, buttonWidth, 20).build());
        recompute();
    }

    private void recompute() {
        Placement active = PlacementManager.active();
        materials = active != null && minecraft.level != null
                ? MaterialList.compute(active, minecraft.level, minecraft.player)
                : null;
        materialScroll = 0;
        remove.active = active != null;
        moveHere.active = active != null;
        copy.active = materials != null && !materials.lines().isEmpty();
    }

    private int materialLeft() {
        return MARGIN + LIST_WIDTH + 12;
    }

    private int listTop() {
        return 40;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<Placement> placements = PlacementManager.placements();
        if (mouseX >= MARGIN && mouseX < MARGIN + LIST_WIDTH && mouseY >= listTop()) {
            int index = (int) ((mouseY - listTop()) / ROW);
            if (index >= 0 && index < placements.size()) {
                PlacementManager.setActive(index);
                recompute();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (materials != null && mouseX >= materialLeft()) {
            int visible = MaterialTable.visibleRows(listTop(), height - 46);
            materialScroll = Mth.clamp(materialScroll - (int) Math.signum(scrollY), 0,
                    Math.max(0, materials.lines().size() - visible));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        renderPlacements(graphics, mouseX, mouseY);
        renderMaterials(graphics);
        if (net.minecraft.Util.getMillis() - copiedAt < 2500) {
            graphics.drawCenteredString(font, Component.translatable("pawprint.materials.copied"), width / 2, height - 40, 0x55FF55);
        }
    }

    private void renderPlacements(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Placement> placements = PlacementManager.placements();
        graphics.drawString(font, Component.translatable("pawprint.placements.list"), MARGIN, listTop() - 11, 0xA0A0A0);
        if (placements.isEmpty()) {
            graphics.drawString(font, Component.translatable("pawprint.placements.none"), MARGIN, listTop() + 4, 0x808080);
            return;
        }
        Placement active = PlacementManager.active();
        for (int i = 0; i < placements.size(); i++) {
            Placement placement = placements.get(i);
            int y = listTop() + i * ROW;
            if (y + ROW > height - 34) {
                break;
            }
            boolean hovered = mouseX >= MARGIN && mouseX < MARGIN + LIST_WIDTH && mouseY >= y && mouseY < y + ROW;
            if (placement == active) {
                graphics.fill(MARGIN, y, MARGIN + LIST_WIDTH, y + ROW - 2, 0x804080FF);
            } else if (hovered) {
                graphics.fill(MARGIN, y, MARGIN + LIST_WIDTH, y + ROW - 2, 0x30FFFFFF);
            }
            graphics.drawString(font, font.plainSubstrByWidth(placement.blueprint().meta().name, LIST_WIDTH - 6),
                    MARGIN + 3, y + 1, 0xFFFFFF);
            graphics.drawString(font, placement.origin().toShortString(), MARGIN + 3, y + 11, 0x909090);
        }
    }

    private void renderMaterials(GuiGraphics graphics) {
        int left = materialLeft();
        int right = width - MARGIN;
        int top = listTop();
        if (materials == null) {
            graphics.drawString(font, Component.translatable("pawprint.placements.select"), left, top + 4, 0x808080);
            return;
        }
        graphics.drawString(font, Component.translatable("pawprint.placements.progress", materials.percent(),
                materials.correct(), materials.blocks()), left, top - 11, 0xFFFF80);
        MaterialTable.render(graphics, font, materials, left, top, right, height - 46, materialScroll, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
