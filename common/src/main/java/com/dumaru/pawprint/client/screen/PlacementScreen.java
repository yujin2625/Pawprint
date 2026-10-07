package com.dumaru.pawprint.client.screen;

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
import net.minecraft.world.item.ItemStack;
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
    private static final int MATERIAL_ROW = 18;

    private @Nullable MaterialList.Result materials;
    private int materialScroll;
    private Button remove;
    private Button moveHere;
    private Button copy;

    public PlacementScreen() {
        super(Component.translatable("pawprint.placements.title"));
    }

    @Override
    protected void init() {
        int bottom = height - 28;
        remove = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.library.remove_placement"), b -> {
            PlacementManager.removeActive();
            recompute();
        }).bounds(MARGIN, bottom, 82, 20).build());
        moveHere = addRenderableWidget(Button.builder(Component.translatable("pawprint.placements.move_here"), b -> {
            Placement active = PlacementManager.active();
            if (active != null) {
                active.moveTo(ViewRay.placeHere(minecraft));
                PlacementManager.changed();
                recompute();
            }
        }).bounds(MARGIN + 86, bottom, 84, 20).build());
        copy = addRenderableWidget(Button.builder(Component.translatable("pawprint.placements.copy_materials"), b -> {
            if (materials != null) {
                minecraft.keyboardHandler.setClipboard(MaterialList.toText(materials));
            }
        }).bounds(materialLeft(), bottom, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("pawprint.layer.all"), b -> PlacementManager.setLayer(null))
                .bounds(materialLeft() + 124, bottom, 90, 20).build()).active = PlacementManager.layer() != null;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width - MARGIN - 80, bottom, 80, 20).build());
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
        return 30;
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
            int visible = (height - 40 - listTop() - 24) / MATERIAL_ROW;
            materialScroll = Mth.clamp(materialScroll - (int) Math.signum(scrollY), 0,
                    Math.max(0, materials.lines().size() - visible));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        renderPlacements(graphics, mouseX, mouseY);
        renderMaterials(graphics);
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
        int columnRemaining = right - 120;
        int columnHave = right - 80;
        int columnTotal = right - 40;
        graphics.drawString(font, Component.translatable("pawprint.placements.col.block"), left + 20, top, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("pawprint.placements.col.remaining"), columnRemaining, top, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("pawprint.placements.col.have"), columnHave, top, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("pawprint.placements.col.total"), columnTotal, top, 0xA0A0A0);

        int y = top + 12;
        List<MaterialList.Line> lines = materials.lines();
        for (int i = materialScroll; i < lines.size() && y + MATERIAL_ROW < height - 34; i++) {
            MaterialList.Line line = lines.get(i);
            graphics.renderItem(new ItemStack(line.item()), left, y);
            String name = font.plainSubstrByWidth(line.item().getDescription().getString(), columnRemaining - left - 26);
            graphics.drawString(font, name, left + 20, y + 4, 0xFFFFFF);
            int remainingColor = line.remaining() == 0 ? 0x55FF55 : line.have() >= line.remaining() ? 0xFFFF55 : 0xFF5555;
            graphics.drawString(font, String.valueOf(line.remaining()), columnRemaining, y + 4, remainingColor);
            graphics.drawString(font, String.valueOf(line.have()), columnHave, y + 4, 0xC0C0C0);
            graphics.drawString(font, String.valueOf(line.total()), columnTotal, y + 4, 0xC0C0C0);
            y += MATERIAL_ROW;
        }
        if (materials.withoutItem() > 0) {
            graphics.drawString(font, Component.translatable("pawprint.placements.without_item", materials.withoutItem()),
                    left, height - 44, 0x909090);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
