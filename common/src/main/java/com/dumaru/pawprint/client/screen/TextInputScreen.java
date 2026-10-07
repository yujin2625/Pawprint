package com.dumaru.pawprint.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Asks for one line of text. {@code onConfirm} runs on OK or Enter; it is responsible for changing the screen.
 */
final class TextInputScreen extends Screen {
    private final Screen parent;
    private final String initial;
    private final @Nullable Component hint;
    private final boolean allowEmpty;
    private final Consumer<String> onConfirm;
    private EditBox input;
    private Button ok;

    TextInputScreen(Screen parent, Component title, String initial, @Nullable Component hint, boolean allowEmpty,
                    Consumer<String> onConfirm) {
        super(title);
        this.parent = parent;
        this.initial = initial;
        this.hint = hint;
        this.allowEmpty = allowEmpty;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int left = width / 2 - 120;
        input = addRenderableWidget(new EditBox(font, left, height / 2 - 10, 240, 20, title));
        input.setMaxLength(200);
        input.setValue(initial);
        input.setResponder(value -> ok.active = allowEmpty || !value.isBlank());
        ok = addRenderableWidget(Button.builder(CommonComponents.GUI_OK, b -> confirm())
                .bounds(left, height / 2 + 16, 118, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left + 122, height / 2 + 16, 118, 20).build());
        ok.active = allowEmpty || !initial.isBlank();
        setInitialFocus(input);
    }

    private void confirm() {
        if (ok.active) {
            onConfirm.accept(input.getValue().strip());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter, keypad Enter
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xFFFFFF);
        if (hint != null) {
            graphics.drawCenteredString(font, hint, width / 2, height / 2 - 26, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
