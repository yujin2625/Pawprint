package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.text.TextBlueprintWriter;
import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Less frequent actions for one blueprint: rename, tags, description, group, duplicate, copy as AI text, delete.
 */
final class ManageBlueprintScreen extends Screen {
    private final LibraryScreen parent;
    private final BlueprintLibrary.Entry entry;

    ManageBlueprintScreen(LibraryScreen parent, BlueprintLibrary.Entry entry) {
        super(Component.translatable("pawprint.library.manage.title", entry.meta().name));
        this.parent = parent;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        int y = Math.max(30, height / 2 - 100);
        y = add("pawprint.library.manage.rename", left, y, () -> minecraft.setScreen(new TextInputScreen(this,
                Component.translatable("pawprint.library.manage.rename"), entry.meta().name, null, false,
                name -> apply(() -> BlueprintLibrary.updateMeta(entry, meta -> meta.name = name), "pawprint.library.renamed"))));
        y = add("pawprint.library.manage.tags", left, y, () -> minecraft.setScreen(new TextInputScreen(this,
                Component.translatable("pawprint.library.manage.tags"), String.join(", ", entry.meta().tags),
                Component.translatable("pawprint.library.manage.tags_hint"), true,
                text -> apply(() -> BlueprintLibrary.updateMeta(entry, meta -> meta.tags = parseTags(text)),
                        "pawprint.library.tags_saved"))));
        y = add("pawprint.library.manage.description", left, y, () -> minecraft.setScreen(new TextInputScreen(this,
                Component.translatable("pawprint.library.manage.description"), entry.meta().description, null, true,
                text -> apply(() -> BlueprintLibrary.updateMeta(entry, meta -> meta.description = text),
                        "pawprint.library.description_saved"))));
        y = add("pawprint.library.manage.move", left, y, () -> minecraft.setScreen(new GroupPickerScreen(this,
                Component.translatable("pawprint.library.manage.move"), false, entry.group(),
                group -> apply(() -> BlueprintLibrary.moveToGroup(entry, group), "pawprint.library.moved"))));
        y = add("pawprint.library.manage.duplicate", left, y, () -> apply(() -> BlueprintLibrary.duplicate(entry,
                Component.translatable("pawprint.library.copy_suffix").getString()), "pawprint.library.duplicated"));
        y = add("pawprint.screen.library.copy_text", left, y, this::copyAsText);
        y = add("pawprint.library.manage.share", left, y, this::copyShareString);
        y = add("pawprint.library.manage.export", left, y, () -> minecraft.setScreen(new ExportScreen(this, parent, entry)));
        y = add("pawprint.library.manage.delete", left, y, () -> minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                try {
                    BlueprintLibrary.delete(entry);
                    parent.refresh(null, Component.translatable("pawprint.library.deleted", entry.meta().name), false);
                } catch (IOException e) {
                    parent.refresh(entry.relativePath(), error(e), true);
                }
                minecraft.setScreen(parent);
            } else {
                minecraft.setScreen(this);
            }
        }, Component.translatable("pawprint.library.manage.delete_confirm", entry.meta().name),
                Component.translatable("pawprint.library.manage.delete_detail"))));
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(left, y + 6, 200, 20).build());
    }

    private int add(String key, int x, int y, Runnable action) {
        addRenderableWidget(Button.builder(Component.translatable(key), b -> action.run()).bounds(x, y, 200, 20).build());
        return y + 22;
    }

    private interface Change {
        BlueprintLibrary.Entry run() throws IOException;
    }

    /** Runs a change, then returns to the library with the changed entry selected. */
    private void apply(Change change, String messageKey) {
        try {
            BlueprintLibrary.Entry changed = change.run();
            parent.refresh(changed.relativePath(), Component.translatable(messageKey, changed.meta().name), false);
        } catch (IOException e) {
            Pawprint.LOG.warn("Library action failed for {}", entry.file(), e);
            parent.refresh(entry.relativePath(), error(e), true);
        }
        minecraft.setScreen(parent);
    }

    private void copyAsText() {
        try {
            Blueprint blueprint = BlueprintLibrary.read(entry.file());
            minecraft.keyboardHandler.setClipboard(TextBlueprintWriter.write(blueprint));
            parent.refresh(entry.relativePath(), Component.translatable("pawprint.ai.text_copied",
                    blueprint.meta().blockCount), false);
        } catch (IOException | IllegalStateException e) {
            parent.refresh(entry.relativePath(), error(e), true);
        }
        minecraft.setScreen(parent);
    }

    private void copyShareString() {
        try {
            String text = com.dumaru.pawprint.format.BlueprintIO.toShareString(BlueprintLibrary.read(entry.file()));
            minecraft.keyboardHandler.setClipboard(text);
            parent.refresh(entry.relativePath(), Component.translatable("pawprint.library.share_copied", text.length()), false);
        } catch (IOException e) {
            parent.refresh(entry.relativePath(), error(e), true);
        }
        minecraft.setScreen(parent);
    }

    private static Component error(Exception e) {
        return Component.translatable("pawprint.library.action_failed", e.getMessage());
    }

    /** Tags are comma separated; blanks and duplicates are dropped. */
    static List<String> parseTags(String text) {
        List<String> tags = new ArrayList<>();
        Arrays.stream(text.split(",")).map(String::strip).filter(tag -> !tag.isEmpty())
                .filter(tag -> !tags.contains(tag)).forEach(tags::add);
        return tags;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, Math.max(30, height / 2 - 100) - 16, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
