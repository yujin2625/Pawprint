package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.client.palette.BlockSearchIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Scrollable grid of block icons from {@link BlockSearchIndex}. Clicking a block picks it. Screens draw the hover
 * tooltip with {@link #renderTooltip} after everything else, so it is not covered by other widgets.
 */
final class BlockGrid extends AbstractWidget {
    static final int CELL = 20;

    private final Font font;
    private final Consumer<Block> onPick;
    private final Predicate<Block> highlighted;
    private List<BlockSearchIndex.Entry> results = List.of();
    private int scrollRow;

    BlockGrid(Font font, int x, int y, int width, int height, Predicate<Block> highlighted, Consumer<Block> onPick) {
        super(x, y, width, height, Component.empty());
        this.font = font;
        this.onPick = onPick;
        this.highlighted = highlighted;
    }

    void search(String query) {
        results = BlockSearchIndex.search(query);
        scrollRow = 0;
    }

    private int columns() {
        return Math.max(1, width / CELL);
    }

    private int visibleRows() {
        return Math.max(1, height / CELL);
    }

    private int maxScroll() {
        int rows = (results.size() + columns() - 1) / columns();
        return Math.max(0, rows - visibleRows());
    }

    private @Nullable BlockSearchIndex.Entry entryAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return null;
        }
        int column = (int) ((mouseX - getX()) / CELL);
        int row = (int) ((mouseY - getY()) / CELL);
        if (column >= columns() || row >= visibleRows()) {
            return null;
        }
        int index = (scrollRow + row) * columns() + column;
        return index < results.size() ? results.get(index) : null;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int columns = columns();
        int first = scrollRow * columns;
        int last = Math.min(results.size(), first + visibleRows() * columns);
        for (int i = first; i < last; i++) {
            BlockSearchIndex.Entry entry = results.get(i);
            int x = getX() + (i - first) % columns * CELL;
            int y = getY() + (i - first) / columns * CELL;
            if (highlighted.test(entry.block())) {
                graphics.fill(x, y, x + CELL, y + CELL, 0x8040A0FF);
            }
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                graphics.fill(x, y, x + CELL, y + CELL, 0x40FFFFFF);
            }
            graphics.renderItem(entry.icon(), x + 2, y + 2);
        }
        if (results.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("pawprint.screen.edit.no_results"),
                    getX() + width / 2, getY() + 20, 0xA0A0A0);
        }
    }

    /** Names in all search languages and the ID of the hovered block. */
    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        BlockSearchIndex.Entry hovered = entryAt(mouseX, mouseY);
        if (hovered == null) {
            return;
        }
        List<Component> tooltip = new ArrayList<>();
        for (String name : hovered.names()) {
            tooltip.add(Component.literal(name));
        }
        tooltip.add(Component.literal(hovered.id().toString()).withStyle(ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        BlockSearchIndex.Entry entry = entryAt(mouseX, mouseY);
        if (entry != null && button == 0) {
            onPick.accept(entry.block());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
