package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.client.render.ThumbnailCache;
import com.dumaru.pawprint.client.render.ThumbnailRenderer;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.dumaru.pawprint.library.LibraryState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Scrollable list or grid of library entries with thumbnails. Double-clicking an entry activates it.
 */
final class BlueprintBrowser extends AbstractWidget {
    private static final int ROW_HEIGHT = 36;
    private static final int TILE_WIDTH = 72;
    private static final int TILE_HEIGHT = 82;
    private static final int THUMB_LIST = 32;
    private static final int THUMB_GRID = 64;
    private static final int SCROLLBAR = 6;
    private static final long DOUBLE_CLICK_MS = 300;

    private final Font font;
    private final Consumer<BlueprintLibrary.Entry> onSelect;
    private final Consumer<BlueprintLibrary.Entry> onActivate;
    private List<BlueprintLibrary.Entry> entries = List.of();
    private @Nullable BlueprintLibrary.Entry selected;
    private boolean grid;
    private double scroll;
    private long lastClick;

    BlueprintBrowser(Font font, int x, int y, int width, int height,
                     Consumer<BlueprintLibrary.Entry> onSelect, Consumer<BlueprintLibrary.Entry> onActivate) {
        super(x, y, width, height, Component.empty());
        this.font = font;
        this.onSelect = onSelect;
        this.onActivate = onActivate;
    }

    void setEntries(List<BlueprintLibrary.Entry> entries) {
        this.entries = entries;
        if (selected != null) {
            String path = selected.relativePath();
            selected = entries.stream().filter(e -> e.relativePath().equals(path)).findFirst().orElse(null);
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    void setGrid(boolean grid) {
        this.grid = grid;
        scroll = 0;
    }

    @Nullable BlueprintLibrary.Entry selected() {
        return selected;
    }

    void select(@Nullable String relativePath) {
        selected = relativePath == null ? null
                : entries.stream().filter(e -> e.relativePath().equals(relativePath)).findFirst().orElse(null);
        if (selected != null) {
            scrollTo(entries.indexOf(selected));
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    private int columns() {
        return grid ? Math.max(1, (width - SCROLLBAR) / TILE_WIDTH) : 1;
    }

    private int itemHeight() {
        return grid ? TILE_HEIGHT : ROW_HEIGHT;
    }

    private int contentHeight() {
        int rows = (entries.size() + columns() - 1) / columns();
        return rows * itemHeight();
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - height);
    }

    private void scrollTo(int index) {
        int top = index / columns() * itemHeight();
        if (top < scroll) {
            scroll = top;
        } else if (top + itemHeight() > scroll + height) {
            scroll = top + itemHeight() - height;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY) || mouseX >= getX() + width - SCROLLBAR) {
            return -1;
        }
        int column = grid ? (int) ((mouseX - getX()) / TILE_WIDTH) : 0;
        if (column >= columns()) {
            return -1;
        }
        int row = (int) ((mouseY - getY() + scroll) / itemHeight());
        int index = row * columns() + column;
        return index < entries.size() ? index : -1;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x60000000);
        graphics.enableScissor(getX(), getY(), getX() + width, getY() + height);
        int hovered = indexAt(mouseX, mouseY);
        int first = (int) (scroll / itemHeight()) * columns();
        int last = Math.min(entries.size(), first + (height / itemHeight() + 2) * columns());
        for (int i = first; i < last; i++) {
            int x = getX() + (grid ? i % columns() * TILE_WIDTH : 0);
            int y = getY() + i / columns() * itemHeight() - (int) scroll;
            BlueprintLibrary.Entry entry = entries.get(i);
            int itemWidth = grid ? TILE_WIDTH : width - SCROLLBAR;
            if (entry == selected) {
                graphics.fill(x, y, x + itemWidth, y + itemHeight(), 0x804080FF);
            } else if (i == hovered) {
                graphics.fill(x, y, x + itemWidth, y + itemHeight(), 0x30FFFFFF);
            }
            if (grid) {
                renderTile(graphics, entry, x, y);
            } else {
                renderRow(graphics, entry, x, y, itemWidth);
            }
        }
        graphics.disableScissor();

        if (maxScroll() > 0) {
            int barX = getX() + width - SCROLLBAR;
            int barHeight = Math.max(16, height * height / contentHeight());
            int barY = getY() + (int) ((height - barHeight) * scroll / maxScroll());
            graphics.fill(barX, getY(), barX + SCROLLBAR, getY() + height, 0x40000000);
            graphics.fill(barX + 1, barY, barX + SCROLLBAR - 1, barY + barHeight, 0xFFA0A0A0);
        }
    }

    private void renderRow(GuiGraphics graphics, BlueprintLibrary.Entry entry, int x, int y, int rowWidth) {
        BlueprintMeta meta = entry.meta();
        renderThumbnail(graphics, entry, x + 2, y + 2, THUMB_LIST);
        int textX = x + THUMB_LIST + 8;
        int textWidth = rowWidth - THUMB_LIST - 12;
        String star = LibraryState.isFavorite(entry.relativePath()) ? "★ " : "";
        graphics.drawString(font, font.plainSubstrByWidth(star + meta.name, textWidth), textX, y + 6, 0xFFFFFF);
        String details = Component.translatable("pawprint.screen.library.details",
                meta.size[0], meta.size[1], meta.size[2], meta.blockCount).getString();
        if (!entry.group().isEmpty()) {
            details += " · " + entry.group();
        }
        graphics.drawString(font, font.plainSubstrByWidth(details, textWidth), textX, y + 18, 0xA0A0A0);
    }

    private void renderTile(GuiGraphics graphics, BlueprintLibrary.Entry entry, int x, int y) {
        renderThumbnail(graphics, entry, x + (TILE_WIDTH - THUMB_GRID) / 2, y + 3, THUMB_GRID);
        String star = LibraryState.isFavorite(entry.relativePath()) ? "★" : "";
        String name = font.plainSubstrByWidth(star + entry.meta().name, TILE_WIDTH - 4);
        graphics.drawCenteredString(font, name, x + TILE_WIDTH / 2, y + THUMB_GRID + 7, 0xFFFFFF);
    }

    static void renderThumbnail(GuiGraphics graphics, BlueprintLibrary.Entry entry, int x, int y, int size) {
        graphics.fill(x, y, x + size, y + size, 0x40000000);
        ResourceLocation texture = ThumbnailCache.get(entry);
        if (texture == null) {
            return;
        }
        RenderSystem.enableBlend();
        graphics.blit(texture, x, y, size, size, 0f, 0f, ThumbnailRenderer.SIZE, ThumbnailRenderer.SIZE,
                ThumbnailRenderer.SIZE, ThumbnailRenderer.SIZE);
        RenderSystem.disableBlend();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = indexAt(mouseX, mouseY);
        if (index < 0 || button != 0) {
            return false;
        }
        BlueprintLibrary.Entry entry = entries.get(index);
        long now = Util.getMillis();
        boolean doubleClick = entry == selected && now - lastClick < DOUBLE_CLICK_MS;
        lastClick = now;
        selected = entry;
        onSelect.accept(entry);
        if (doubleClick) {
            onActivate.accept(entry);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scroll = Mth.clamp(scroll - scrollY * itemHeight() / 2, 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (selected != null) {
            output.add(NarratedElementType.TITLE, Component.literal(selected.meta().name));
        }
    }
}
