package com.dumaru.pawprint.client.screen;

import net.minecraft.client.input.KeyEvent;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.Capture;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.Selection;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Asks for a name, then captures the selection into a new blueprint.
 */
public class SaveBlueprintScreen extends Screen {
    private final LibraryScreen parent;
    private final BoundingBox box;
    private EditBox name;
    private Button save;
    private @Nullable Component error;

    public SaveBlueprintScreen(LibraryScreen parent, BoundingBox box) {
        super(Component.translatable("pawprint.screen.save.title"));
        this.parent = parent;
        this.box = box;
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        name = addRenderableWidget(new EditBox(font, left, height / 2 - 20, 200, 20,
                Component.translatable("pawprint.screen.save.name")));
        name.setMaxLength(64);
        name.setResponder(value -> save.active = !value.isBlank());
        save = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.save.save"), b -> save())
                .bounds(left, height / 2 + 10, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left + 102, height / 2 + 10, 98, 20).build());
        save.active = false;
        setInitialFocus(name);
    }

    private void save() {
        String server = ClientContext.server();
        String dimension = ClientContext.dimension();
        BlueprintMeta.Origin origin = server != null && dimension != null
                ? new BlueprintMeta.Origin(server, dimension, box.minX(), box.minY(), box.minZ())
                : null;
        try {
            Blueprint blueprint = Capture.capture(minecraft.level, box, name.getValue().strip(),
                    minecraft.getUser().getName(), origin);
            BlueprintLibrary.saveNew(blueprint);
            Selection.clear();
            parent.refresh();
            minecraft.gui.setScreen(parent);
            PawprintClient.notify(minecraft, Component.translatable("pawprint.capture.saved",
                    blueprint.meta().name, blueprint.meta().blockCount));
        } catch (Capture.CaptureException e) {
            error = e.message();
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save blueprint", e);
            error = Component.translatable("pawprint.capture.write_failed", e.getMessage());
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.keycode();
        int modifiers = event.modifiers();
        if ((keyCode == 257 || keyCode == 335) && save.active) { // Enter, keypad Enter
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 60, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("pawprint.screen.library.selection",
                box.getXSpan(), box.getYSpan(), box.getZSpan()), width / 2, height / 2 - 45, 0xFFA0A0A0);
        graphics.text(font, Component.translatable("pawprint.screen.save.name"),
                width / 2 - 100, height / 2 - 32, 0xFFA0A0A0);
        if (error != null) {
            graphics.centeredText(font, error, width / 2, height / 2 + 40, 0xFFFF5555);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
