package com.dumaru.pawprint.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/**
 * Picks any registered block, searchable by Korean or English name, ID or Korean initials.
 * {@code onPick} decides which screen comes next.
 */
final class BlockPickerScreen extends Screen {
    private static final int MARGIN = 20;
    private final Screen parent;
    private final Consumer<Block> onPick;
    private BlockGrid grid;

    BlockPickerScreen(Screen parent, Component title, Consumer<Block> onPick) {
        super(title);
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        EditBox search = addRenderableWidget(new EditBox(font, MARGIN, 28, width - MARGIN * 2, 18,
                Component.translatable("pawprint.screen.edit.search")));
        search.setHint(Component.translatable("pawprint.screen.edit.search_hint").withStyle(ChatFormatting.DARK_GRAY));
        grid = addRenderableWidget(new BlockGrid(font, MARGIN, 52, width - MARGIN * 2, height - 52 - 34,
                block -> false, onPick));
        grid.search("");
        search.setResponder(grid::search);
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 - 75, height - 28, 150, 20).build());
        setInitialFocus(search);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        grid.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
