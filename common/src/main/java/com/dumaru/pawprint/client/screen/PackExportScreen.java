package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.pack.PackExporter;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exports a block pack for the web editor. The player picks the pack name, which languages to include for block
 * names (English and the game's language are ticked by default) and whether resource packs apply.
 */
public class PackExportScreen extends Screen {
    private static final int ROW = 14;

    private final Screen parent;
    private final Set<String> chosen = new LinkedHashSet<>();
    private boolean resourcePacks = true;
    private @Nullable EditBox name;
    private @Nullable EditBox filter;
    private @Nullable LanguageList list;
    private @Nullable Button export;
    private @Nullable Component status;
    private int statusColor = 0xA0A0A0;
    private boolean working;
    private String nameValue;

    public PackExportScreen(Screen parent) {
        super(Component.translatable("pawprint.screen.pack.title"));
        this.parent = parent;
        Minecraft minecraft = Minecraft.getInstance();
        chosen.add("en_us");
        chosen.add(minecraft.getLanguageManager().getSelected());
        nameValue = PackExporter.defaultName(minecraft);
    }

    @Override
    protected void init() {
        int panel = Math.min(320, width - 20);
        int left = (width - panel) / 2;
        name = addRenderableWidget(new EditBox(font, left, 40, panel, 18, Component.translatable("pawprint.screen.pack.name")));
        name.setMaxLength(80);
        name.setValue(nameValue);
        name.setResponder(value -> {
            nameValue = value;
            updateButtons();
        });

        filter = addRenderableWidget(new EditBox(font, left, 74, panel, 16, Component.translatable("pawprint.screen.pack.filter")));
        filter.setHint(Component.translatable("pawprint.screen.pack.filter").withStyle(ChatFormatting.DARK_GRAY));
        filter.setResponder(value -> refill());

        int listTop = 94;
        int listBottom = height - 64;
        list = addRenderableWidget(new LanguageList(minecraft, panel, Math.max(ROW * 2, listBottom - listTop), listTop, left));
        refill();

        addRenderableWidget(CycleButton.booleanBuilder(
                        Component.translatable("pawprint.screen.pack.resource_packs.on"),
                        Component.translatable("pawprint.screen.pack.resource_packs.off"))
                .withInitialValue(resourcePacks)
                .displayOnlyValue()
                .create(left, height - 58, panel, 20, Component.empty(), (button, value) -> resourcePacks = value));
        export = addRenderableWidget(Button.builder(Component.translatable("pawprint.screen.pack.export"), b -> export())
                .bounds(left, height - 34, panel / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left + panel / 2 + 2, height - 34, panel / 2 - 2, 20).build());
        updateButtons();
    }

    private void refill() {
        if (list == null) {
            return;
        }
        String query = filter == null ? "" : filter.getValue().strip().toLowerCase(Locale.ROOT);
        List<LanguageList.Entry> entries = new ArrayList<>();
        Map<String, LanguageInfo> languages = minecraft.getLanguageManager().getLanguages();
        // Ticked languages first, then the rest by name.
        List<Map.Entry<String, LanguageInfo>> sorted = new ArrayList<>(languages.entrySet());
        sorted.sort((a, b) -> {
            int ticked = Boolean.compare(chosen.contains(b.getKey()), chosen.contains(a.getKey()));
            return ticked != 0 ? ticked : a.getValue().toComponent().getString().compareToIgnoreCase(b.getValue().toComponent().getString());
        });
        for (Map.Entry<String, LanguageInfo> language : sorted) {
            String label = language.getValue().toComponent().getString() + "  " + language.getKey();
            if (query.isEmpty() || label.toLowerCase(Locale.ROOT).contains(query)) {
                entries.add(list.new Entry(language.getKey(), label));
            }
        }
        list.replace(entries);
    }

    private void updateButtons() {
        if (export != null) {
            export.active = !working && !chosen.isEmpty() && !nameValue.isBlank();
        }
    }

    private void export() {
        working = true;
        updateButtons();
        status = Component.translatable("pawprint.screen.pack.working");
        statusColor = 0xFFFFA0;
        PackExporter.Options options = new PackExporter.Options(nameValue.strip(), List.copyOf(chosen), resourcePacks);
        PackExporter.export(minecraft, options).whenComplete((result, error) -> minecraft.execute(() -> {
            working = false;
            if (error != null) {
                Pawprint.LOG.warn("Block pack export failed", error);
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                status = Component.translatable("pawprint.screen.pack.failed", String.valueOf(cause.getMessage()));
                statusColor = 0xFF5555;
            } else {
                status = Component.translatable("pawprint.screen.pack.done", result.file().getFileName().toString(),
                        result.blocks(), result.textures());
                statusColor = 0x80FF80;
                Util.getPlatform().openPath(result.file().getParent());
            }
            updateButtons();
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int panel = Math.min(320, width - 20);
        int left = (width - panel) / 2;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("pawprint.screen.pack.name"), left, 30, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("pawprint.screen.pack.languages", chosen.size()), left, 64, 0xA0A0A0);
        Component line = status != null ? status : Component.translatable("pawprint.screen.pack.hint");
        graphics.drawCenteredString(font, line, width / 2, height - 10 - font.lineHeight + 2, status != null ? statusColor : 0x808080);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Languages installed in the game, each a checkbox row. */
    private class LanguageList extends ObjectSelectionList<LanguageList.Entry> {
        LanguageList(Minecraft minecraft, int width, int height, int top, int left) {
            super(minecraft, width, height, top, ROW);
            setX(left);
        }

        void replace(List<Entry> entries) {
            replaceEntries(entries);
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String code;
            private final String label;

            Entry(String code, String label) {
                this.code = code;
                this.label = label;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                boolean on = chosen.contains(code);
                graphics.fill(left + 2, top + 2, left + 11, top + 11, 0xFF000000);
                graphics.renderOutline(left + 2, top + 2, 9, 9, hovering ? 0xFFFFFFFF : 0xFFA0A0A0);
                if (on) {
                    graphics.fill(left + 4, top + 4, left + 9, top + 9, 0xFFEF9F27);
                }
                graphics.drawString(font, label, left + 16, top + 3, on ? 0xFFFFFF : 0xC0C0C0);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (!chosen.remove(code)) {
                    chosen.add(code);
                }
                updateButtons();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(label + (chosen.contains(code) ? " ✓" : ""));
            }
        }
    }
}
