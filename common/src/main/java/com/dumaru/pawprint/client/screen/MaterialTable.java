package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.client.placement.MaterialList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Draws a material list as a table: item, still needed, in inventory, total. Shared by the placement panel and
 * the library's material screen.
 */
final class MaterialTable {
    /** Two lines per row: the name in the game language, and the ID below it. */
    static final int ROW = 22;

    private MaterialTable() {
    }

    /** Index into the result's lines of the row under the mouse, or -1. */
    static int rowAt(double mouseX, double mouseY, int left, int top, int right, int bottom, int scroll, int size) {
        if (mouseX < left || mouseX >= right || mouseY < top + 12 || mouseY >= bottom) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - top - 12) / ROW);
        return index < size ? index : -1;
    }

    static int visibleRows(int top, int bottom) {
        return Math.max(1, (bottom - top - 12) / ROW);
    }

    /**
     * @param showRemaining false for a blueprint that is not placed, where "left" would just repeat the total
     */
    static void render(GuiGraphicsExtractor graphics, Font font, MaterialList.Result result, int left, int top, int right,
                       int bottom, int scroll, boolean showRemaining, int selected) {
        int columnTotal = right - 40;
        int columnHave = right - 80;
        int columnRemaining = right - 120;
        int nameRight = showRemaining ? columnRemaining : columnHave;
        graphics.text(font, Component.translatable("pawprint.placements.col.block"), left + 20, top, 0xFFA0A0A0);
        if (showRemaining) {
            graphics.text(font, Component.translatable("pawprint.placements.col.remaining"), columnRemaining, top, 0xFFA0A0A0);
        }
        graphics.text(font, Component.translatable("pawprint.placements.col.have"), columnHave, top, 0xFFA0A0A0);
        graphics.text(font, Component.translatable("pawprint.placements.col.total"), columnTotal, top, 0xFFA0A0A0);

        List<MaterialList.Line> lines = result.lines();
        if (lines.isEmpty()) {
            graphics.text(font, Component.translatable("pawprint.materials.none"), left, top + 16, 0xFF808080);
        }
        int y = top + 12;
        for (int i = scroll; i < lines.size() && y + ROW <= bottom; i++) {
            MaterialList.Line line = lines.get(i);
            if (i == selected) {
                graphics.fill(left - 2, y, right, y + ROW, 0x804080FF);
            }
            graphics.item(new ItemStack(line.item()), left, y + 2);
            String name = font.plainSubstrByWidth(line.item().getName(new net.minecraft.world.item.ItemStack(line.item())).getString(), nameRight - left - 26);
            graphics.text(font, name, left + 20, y + 1, 0xFFFFFFFF);
            graphics.text(font, font.plainSubstrByWidth(MaterialList.id(line), nameRight - left - 26),
                    left + 20, y + 11, 0xFF808080);
            int needed = showRemaining ? line.remaining() : line.total();
            int haveColor = line.have() >= needed ? 0xFF55FF55 : 0xFFFF5555;
            if (showRemaining) {
                int remainingColor = line.remaining() == 0 ? 0xFF55FF55 : line.have() >= line.remaining() ? 0xFFFFFF55 : 0xFFFF5555;
                graphics.text(font, String.valueOf(line.remaining()), columnRemaining, y + 6, remainingColor);
                haveColor = 0xFFC0C0C0;
            }
            graphics.text(font, String.valueOf(line.have()), columnHave, y + 6, haveColor);
            graphics.text(font, String.valueOf(line.total()), columnTotal, y + 6, 0xFFC0C0C0);
            y += ROW;
        }
        if (result.withoutItem() > 0) {
            graphics.text(font, Component.translatable("pawprint.placements.without_item", result.withoutItem()),
                    left, bottom + 2, 0xFF909090);
        }
    }
}
