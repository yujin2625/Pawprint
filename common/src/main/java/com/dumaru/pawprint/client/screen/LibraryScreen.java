package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.Selection;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

/**
 * Lists saved blueprints and offers the phase-1 actions: place, capture the selection, remove the placement.
 */
public class LibraryScreen extends Screen {
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_GAP = 4;

    private final @Nullable Screen parent;
    private BlueprintList list;
    private Button placeHere;
    private Button placeAtOrigin;
    private Button removePlacement;
    private Button captureSelection;
    private @Nullable Component error;

    public LibraryScreen(@Nullable Screen parent) {
        super(Component.translatable("pawprint.screen.library.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = addRenderableWidget(new BlueprintList(minecraft, width, height - 32 - 64, 32));
        list.setEntries(BlueprintLibrary.list());

        int rowWidth = BUTTON_WIDTH * 3 + BUTTON_GAP * 2;
        int left = (width - rowWidth) / 2;
        int row1 = height - 56;
        int row2 = height - 30;
        placeHere = addRenderableWidget(button("pawprint.screen.library.place_here", this::placeHere, left, row1));
        placeAtOrigin = addRenderableWidget(button("pawprint.screen.library.place_at_origin", this::placeAtOrigin,
                left + BUTTON_WIDTH + BUTTON_GAP, row1));
        removePlacement = addRenderableWidget(button("pawprint.screen.library.remove_placement", this::removePlacement,
                left + (BUTTON_WIDTH + BUTTON_GAP) * 2, row1));
        captureSelection = addRenderableWidget(button("pawprint.screen.library.capture", this::captureSelection, left, row2));
        addRenderableWidget(button("pawprint.screen.library.open_folder", this::openFolder,
                left + BUTTON_WIDTH + BUTTON_GAP, row2));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + (BUTTON_WIDTH + BUTTON_GAP) * 2, row2, BUTTON_WIDTH, 20).build());
        updateButtons();
    }

    private Button button(String key, Runnable action, int x, int y) {
        return Button.builder(Component.translatable(key), b -> action.run()).bounds(x, y, BUTTON_WIDTH, 20).build();
    }

    private void updateButtons() {
        if (placeHere == null) {
            return; // The list can change selection before the buttons exist.
        }
        BlueprintLibrary.Entry selected = selected();
        placeHere.active = selected != null;
        placeAtOrigin.active = selected != null && isFromHere(selected.meta().origin);
        removePlacement.active = PlacementManager.active() != null;
        captureSelection.active = Selection.box() != null;
    }

    private @Nullable BlueprintLibrary.Entry selected() {
        BlueprintList.Entry entry = list.getSelected();
        return entry == null ? null : entry.entry;
    }

    private static boolean isFromHere(@Nullable BlueprintMeta.Origin origin) {
        return origin != null
                && Objects.equals(origin.server, ClientContext.server())
                && Objects.equals(origin.dimension, ClientContext.dimension());
    }

    private void placeHere() {
        BlockPos origin = PawprintClient.targetedBlock(minecraft) != null
                ? placeAgainst(minecraft.hitResult)
                : minecraft.player.blockPosition();
        place(origin);
    }

    /** The position next to the clicked face, like placing a block. */
    private static BlockPos placeAgainst(HitResult hit) {
        BlockHitResult blockHit = (BlockHitResult) hit;
        return blockHit.getBlockPos().relative(blockHit.getDirection());
    }

    private void placeAtOrigin() {
        BlueprintLibrary.Entry selected = selected();
        if (selected != null && selected.meta().origin != null) {
            int[] pos = selected.meta().origin.pos;
            place(new BlockPos(pos[0], pos[1], pos[2]));
        }
    }

    private void place(BlockPos origin) {
        BlueprintLibrary.Entry selected = selected();
        if (selected == null) {
            return;
        }
        try {
            Blueprint blueprint = BlueprintLibrary.load(selected.relativePath());
            PlacementManager.add(new Placement(selected.relativePath(), blueprint, origin, Rotation.NONE, Mirror.NONE));
            onClose();
            PawprintClient.notify(minecraft, Component.translatable("pawprint.placement.placed", selected.meta().name,
                    PawprintKeys.ROTATE.getTranslatedKeyMessage(), PawprintKeys.MIRROR.getTranslatedKeyMessage()));
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not load blueprint {}", selected.file(), e);
            error = Component.translatable("pawprint.screen.library.load_failed", e.getMessage());
        }
    }

    private void removePlacement() {
        PlacementManager.removeActive();
        updateButtons();
    }

    private void captureSelection() {
        BoundingBox box = Selection.box();
        if (box != null) {
            minecraft.setScreen(new SaveBlueprintScreen(this, box));
        }
    }

    private void openFolder() {
        try {
            Files.createDirectories(BlueprintLibrary.root());
            Util.getPlatform().openPath(BlueprintLibrary.root());
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not open the blueprint folder", e);
        }
    }

    /** Called when returning from the save screen so a new blueprint shows up. */
    void refresh() {
        list.setEntries(BlueprintLibrary.list());
        updateButtons();
    }

    @Override
    public void tick() {
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(font, selectionInfo(), width / 2, 20, 0xA0A0A0);
        if (list.children().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("pawprint.screen.library.empty"),
                    width / 2, height / 2 - 20, 0xA0A0A0);
        }
        if (error != null) {
            graphics.drawCenteredString(font, error, width / 2, height - 68, 0xFF5555);
        }
    }

    private Component selectionInfo() {
        BoundingBox box = Selection.box();
        if (box != null) {
            return Component.translatable("pawprint.screen.library.selection",
                    box.getXSpan(), box.getYSpan(), box.getZSpan());
        }
        return Component.translatable("pawprint.screen.library.no_selection",
                PawprintKeys.MARK_CORNER.getTranslatedKeyMessage());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private class BlueprintList extends ObjectSelectionList<BlueprintList.Entry> {
        BlueprintList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 24);
        }

        void setEntries(List<BlueprintLibrary.Entry> entries) {
            clearEntries();
            for (BlueprintLibrary.Entry entry : entries) {
                addEntry(new Entry(entry));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, width - 20);
        }

        @Override
        public void setSelected(@Nullable Entry entry) {
            super.setSelected(entry);
            error = null;
            updateButtons();
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final BlueprintLibrary.Entry entry;

            Entry(BlueprintLibrary.Entry entry) {
                this.entry = entry;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                BlueprintMeta meta = entry.meta();
                graphics.drawString(font, meta.name, left + 4, top + 2, 0xFFFFFF);
                Component details = Component.translatable("pawprint.screen.library.details",
                        meta.size[0], meta.size[1], meta.size[2], meta.blockCount);
                if (!entry.group().isEmpty()) {
                    details = details.copy().append(" · " + entry.group());
                }
                graphics.drawString(font, details.copy().withStyle(ChatFormatting.GRAY), left + 4, top + 12, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                BlueprintList.this.setSelected(this);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(entry.meta().name);
            }
        }
    }
}
