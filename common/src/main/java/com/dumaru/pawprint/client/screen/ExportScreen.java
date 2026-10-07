package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.convert.Formats;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Exports a blueprint for other mods: Litematica, WorldEdit (Sponge schematic) or a vanilla structure file.
 * Files go to {@code pawprint/exports}, which is opened afterwards.
 */
final class ExportScreen extends Screen {
    private final Screen back;
    private final LibraryScreen library;
    private final BlueprintLibrary.Entry entry;

    ExportScreen(Screen back, LibraryScreen library, BlueprintLibrary.Entry entry) {
        super(Component.translatable("pawprint.library.export.title", entry.meta().name));
        this.back = back;
        this.library = library;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int left = width / 2 - 110;
        int y = height / 2 - 40;
        for (Formats format : Formats.values()) {
            if (!format.canWrite) {
                continue;
            }
            addRenderableWidget(Button.builder(Component.translatable("pawprint.library.export." + format.name().toLowerCase(java.util.Locale.ROOT)),
                    b -> export(format)).bounds(left, y, 220, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(left, y + 6, 220, 20).build());
    }

    private void export(Formats format) {
        try {
            Path file = BlueprintLibrary.export(entry, format);
            Util.getPlatform().openPath(file.getParent());
            library.refresh(entry.relativePath(), Component.translatable("pawprint.library.exported", file.getFileName().toString()), false);
        } catch (IOException | RuntimeException e) {
            Pawprint.LOG.warn("Export of {} failed", entry.file(), e);
            library.refresh(entry.relativePath(), Component.translatable("pawprint.library.action_failed", e.getMessage()), true);
        }
        minecraft.setScreen(library);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(back);
    }
}
