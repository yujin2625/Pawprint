package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.shape.Shape;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool selection, the block palette (every registered block, searchable in Korean and English), and draft actions.
 */
public class EditMenuScreen extends Screen {
    private static final int SIDE_WIDTH = 110;
    private static final int MARGIN = 10;

    private static String lastQuery = "";

    private final List<Button> toolButtons = new ArrayList<>();
    private EditBox search;
    private BlockGrid grid;
    private Button undo;
    private Button redo;
    private Button editPlacement;
    private Button saveDraft;
    private Button clearDraft;

    public EditMenuScreen() {
        super(Component.translatable("pawprint.screen.edit.title"));
    }

    @Override
    protected void init() {
        toolButtons.clear();
        int y = 30;
        for (Shape shape : Shape.values()) {
            Button button = addRenderableWidget(Button.builder(Component.translatable(shape.translationKey()), b -> {
                EditMode.setTool(shape);
                updateButtons();
            }).bounds(MARGIN, y, SIDE_WIDTH, 20).build());
            toolButtons.add(button);
            y += 22;
        }
        y += 6;
        undo = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.edit.undo"), b -> {
            EditMode.undo(minecraft);
            updateButtons();
        }).bounds(MARGIN, y, SIDE_WIDTH / 2 - 1, 20).build());
        redo = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.edit.redo"), b -> {
            EditMode.redo(minecraft);
            updateButtons();
        }).bounds(MARGIN + SIDE_WIDTH / 2 + 1, y, SIDE_WIDTH / 2 - 1, 20).build());

        int gridLeft = MARGIN * 2 + SIDE_WIDTH;
        int gridWidth = width - gridLeft - MARGIN;
        grid = addRenderableWidget(new BlockGrid(font, gridLeft, 54, gridWidth, height - 40 - 54,
                block -> EditMode.brush() != null && EditMode.brush().getBlock() == block, block -> {
                    EditMode.setBrush(block.defaultBlockState(), false);
                    onClose();
                }));
        search = addRenderableWidget(new EditBox(font, gridLeft, 30, gridWidth, 18,
                Component.translatable("pawprint.screen.edit.search")));
        search.setHint(Component.translatable("pawprint.screen.edit.search_hint").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(lastQuery);
        search.setResponder(this::runSearch);
        runSearch(lastQuery);

        int buttonWidth = 110;
        int row = height - 28;
        int left = (width - (buttonWidth * 4 + 12)) / 2;
        saveDraft = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.edit.save_draft"),
                b -> minecraft.setScreen(new SaveDraftScreen(this))).bounds(left, row, buttonWidth, 20).build());
        editPlacement = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.edit.edit_placement"),
                b -> editActivePlacement()).bounds(left + buttonWidth + 4, row, buttonWidth, 20).build());
        clearDraft = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.edit.clear_draft"),
                b -> clearDraft()).bounds(left + (buttonWidth + 4) * 2, row, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + (buttonWidth + 4) * 3, row, buttonWidth, 20).build());
        updateButtons();
        setInitialFocus(search);
    }

    private void updateButtons() {
        Shape[] shapes = Shape.values();
        for (int i = 0; i < shapes.length; i++) {
            toolButtons.get(i).active = shapes[i] != EditMode.tool();
        }
        undo.active = Draft.canUndo();
        redo.active = Draft.canRedo();
        saveDraft.active = !Draft.isEmpty();
        clearDraft.active = !Draft.isEmpty();
        // Loading a placement replaces the draft, so only allow it when there is nothing to lose.
        editPlacement.active = Draft.isEmpty() && PlacementManager.active() != null;
    }

    private void runSearch(String query) {
        lastQuery = query;
        grid.search(query);
    }

    private void editActivePlacement() {
        Placement placement = PlacementManager.active();
        if (placement != null) {
            Draft.loadFrom(placement);
            PlacementManager.removeActive();
            PlacementManager.draftChanged();
            onClose();
        }
    }

    /** Clearing is one undoable edit, so a misclick can be taken back. */
    private void clearDraft() {
        LongArrayList positions = new LongArrayList(Draft.cells().keySet());
        Draft.begin();
        positions.forEach(pos -> Draft.set(pos, null));
        Draft.end();
        EditMode.cancelShape();
        PlacementManager.draftChanged();
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("pawprint.screen.edit.tools"), MARGIN, 20, 0xA0A0A0);
        grid.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
