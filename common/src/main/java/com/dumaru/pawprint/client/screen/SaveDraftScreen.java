package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.edit.Draft;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Saves the draft to the library, either as a new blueprint or over the one it was loaded from.
 * The saved blueprint stays visible as a placement where the draft was.
 */
public class SaveDraftScreen extends Screen {
    private final Screen parent;
    private EditBox name;
    private Button saveNew;
    private @Nullable Button overwrite;
    private @Nullable Component error;

    public SaveDraftScreen(Screen parent) {
        super(Component.translatable("pawprint.screen.save_draft.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        name = addRenderableWidget(new EditBox(font, left, height / 2 - 20, 200, 20,
                Component.translatable("pawprint.screen.save.name")));
        name.setMaxLength(64);
        String source = Draft.sourceFile();
        if (source != null) {
            name.setValue(sourceName(source));
            if (source.endsWith(BlueprintLibrary.TEXT_EXTENSION)) {
                source = null; // Text blueprints are not overwritten with the binary format.
            }
        }
        name.setResponder(value -> updateButtons());

        int y = height / 2 + 10;
        if (source != null) {
            overwrite = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.save_draft.overwrite"),
                    b -> save(true)).bounds(left, y, 200, 20).build());
            y += 24;
        }
        saveNew = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.save_draft.save_new"),
                b -> save(false)).bounds(left, y, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left + 102, y, 98, 20).build());
        updateButtons();
        setInitialFocus(name);
    }

    private static String sourceName(String source) {
        try {
            return BlueprintLibrary.load(source).meta().name;
        } catch (IOException e) {
            return "";
        }
    }

    private void updateButtons() {
        boolean valid = !name.getValue().isBlank();
        saveNew.active = valid;
        if (overwrite != null) {
            overwrite.active = valid;
        }
    }

    private void save(boolean replaceSource) {
        Blueprint blueprint = Draft.toBlueprint(name.getValue().strip(), minecraft.getUser().getName());
        if (blueprint == null) {
            onClose();
            return;
        }
        BlockPos min = Draft.minCorner();
        try {
            Path file;
            String source = Draft.sourceFile();
            if (replaceSource && source != null) {
                file = BlueprintLibrary.resolve(source);
                keepIdentity(blueprint.meta(), BlueprintIO.readMeta(file));
                BlueprintIO.write(blueprint, file);
            } else {
                file = BlueprintLibrary.saveNew(blueprint);
            }
            String relative = BlueprintLibrary.relativize(file);
            Draft.clear();
            PlacementManager.draftChanged();
            PlacementManager.add(new Placement(relative, blueprint, min, Rotation.NONE, Mirror.NONE));
            minecraft.setScreen(null);
            PawprintClient.notify(minecraft, Component.translatable("pawprint.capture.saved",
                    blueprint.meta().name, blueprint.meta().blockCount));
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save the draft", e);
            error = Component.translatable("pawprint.capture.write_failed", e.getMessage());
        }
    }

    /** Overwriting keeps the blueprint's ID, creation time, tags and description. */
    private static void keepIdentity(BlueprintMeta meta, BlueprintMeta old) {
        meta.id = old.id;
        meta.created = old.created;
        meta.tags = old.tags;
        meta.description = old.description;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("pawprint.hud.draft", Draft.size()),
                width / 2, height / 2 - 45, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("pawprint.screen.save.name"),
                width / 2 - 100, height / 2 - 32, 0xA0A0A0);
        if (error != null) {
            graphics.drawCenteredString(font, error, width / 2, height / 2 + 64, 0xFF5555);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
