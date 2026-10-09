package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.BlockReplace;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Confirms replacing one block type in a blueprint, and asks whether to overwrite the file or save a copy.
 * Start it with {@link #start}, which first lets the player pick the new block.
 */
final class ReplaceBlockScreen extends Screen {
    private final Screen back;
    private final BlueprintLibrary.Entry entry;
    private final Item from;
    private final Block to;
    private final int count;
    /** Called with the changed (or copied) entry and whether it is a copy; then returns to {@link #back}. */
    private final BiConsumer<BlueprintLibrary.Entry, Boolean> done;
    private Component error;

    private ReplaceBlockScreen(Screen back, BlueprintLibrary.Entry entry, Item from, Block to, int count,
                               BiConsumer<BlueprintLibrary.Entry, Boolean> done) {
        super(Component.translatable("pawprint.replace.title"));
        this.back = back;
        this.entry = entry;
        this.from = from;
        this.to = to;
        this.count = count;
        this.done = done;
    }

    /** Every block that drops as this item, e.g. both torch and wall torch for the torch item. */
    static Predicate<BlockState> matching(Item item) {
        return state -> state.getBlock().asItem() == item;
    }

    static void start(Screen back, BlueprintLibrary.Entry entry, Item from, BiConsumer<BlueprintLibrary.Entry, Boolean> done) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new BlockPickerScreen(back, Component.translatable("pawprint.replace.pick", from.getName(new net.minecraft.world.item.ItemStack(from))),
                block -> {
                    int count;
                    try {
                        count = BlockReplace.count(BlueprintLibrary.read(entry.file()), matching(from));
                    } catch (IOException e) {
                        count = 0;
                    }
                    minecraft.gui.setScreen(new ReplaceBlockScreen(back, entry, from, block, count, done));
                }));
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        int y = height / 2;
        addRenderableWidget(Button.builder(Component.translatable("pawprint.replace.overwrite"), b -> apply(false))
                .bounds(left, y, 200, 20).build()).active = !entry.isText();
        addRenderableWidget(Button.builder(Component.translatable("pawprint.replace.copy"), b -> apply(true))
                .bounds(left, y + 24, 200, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left, y + 48, 200, 20).build());
    }

    private void apply(boolean asCopy) {
        try {
            BlueprintLibrary.Entry changed = BlueprintLibrary.replaceBlocks(entry, matching(from), to, asCopy,
                    Component.translatable("pawprint.library.copy_suffix").getString());
            minecraft.gui.setScreen(back);
            done.accept(changed, asCopy);
        } catch (IOException e) {
            Pawprint.LOG.warn("Replacing blocks in {} failed", entry.file(), e);
            error = Component.translatable("pawprint.library.action_failed", e.getMessage());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 60, 0xFFFFFFFF);
        Component summary = Component.translatable("pawprint.replace.summary", from.getName(new net.minecraft.world.item.ItemStack(from)), count,
                to.getName(), entry.meta().name);
        int y = height / 2 - 40;
        for (FormattedCharSequence line : font.split(summary, Math.min(width - 40, 320))) {
            graphics.centeredText(font, line, width / 2, y, 0xFFC0C0C0);
            y += 10;
        }
        if (entry.isText()) {
            graphics.centeredText(font, Component.translatable("pawprint.replace.text_file"), width / 2, y + 2, 0xFF909090);
        }
        if (error != null) {
            graphics.centeredText(font, error, width / 2, height / 2 + 74, 0xFFFF5555);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(back);
    }
}
