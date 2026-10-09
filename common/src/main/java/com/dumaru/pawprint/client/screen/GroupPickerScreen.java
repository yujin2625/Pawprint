package com.dumaru.pawprint.client.screen;

import net.minecraft.client.input.MouseButtonEvent;

import com.dumaru.pawprint.library.BlueprintLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Picks a group: either a filter for the library (with "all", "favorites" and "recent") or a destination folder.
 * "New Group" asks for a name and picks the new group.
 */
final class GroupPickerScreen extends Screen {
    private final Screen parent;
    private final boolean filterMode;
    private final String current;
    private final Consumer<String> onPick;

    GroupPickerScreen(Screen parent, Component title, boolean filterMode, String current, Consumer<String> onPick) {
        super(title);
        this.parent = parent;
        this.filterMode = filterMode;
        this.current = current;
        this.onPick = onPick;
    }

    /** Display name for a group value, including the special filter values. */
    static Component label(String group) {
        return switch (group) {
            case LibraryQuery.ALL -> Component.translatable("pawprint.library.group.all");
            case LibraryQuery.FAVORITES -> Component.translatable("pawprint.library.group.favorites");
            case LibraryQuery.RECENT -> Component.translatable("pawprint.library.group.recent");
            case LibraryQuery.ROOT, "" -> Component.translatable("pawprint.library.group.root");
            default -> Component.literal(group);
        };
    }

    @Override
    protected void init() {
        List<String> options = new ArrayList<>();
        if (filterMode) {
            options.addAll(List.of(LibraryQuery.ALL, LibraryQuery.FAVORITES, LibraryQuery.RECENT, LibraryQuery.ROOT));
        } else {
            options.add("");
        }
        options.addAll(BlueprintLibrary.groups());

        GroupList list = addRenderableWidget(new GroupList(minecraft, width, height - 32 - 40, 32));
        for (String option : options) {
            GroupList.Entry entry = list.add(option);
            if (option.equals(current)) {
                list.setSelected(entry);
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("pawprint.library.group.new"), b ->
                minecraft.gui.setScreen(new TextInputScreen(this, Component.translatable("pawprint.library.group.new"), "",
                        Component.translatable("pawprint.library.group.new_hint"), false, name -> {
                    minecraft.gui.setScreen(parent);
                    onPick.accept(name);
                }))).bounds(width / 2 - 154, height - 30, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 4, height - 30, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private final class GroupList extends ObjectSelectionList<GroupList.Entry> {
        GroupList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 18);
        }

        Entry add(String group) {
            Entry entry = new Entry(group);
            addEntry(entry);
            return entry;
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String group;

            Entry(String group) {
                this.group = group;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
                int top = getContentY();
                int left = getContentX();
                int width = getContentWidth();
                int height = getContentHeight();
                boolean special = group.isEmpty() || group.startsWith("*");
                graphics.text(font, label(group), left + 4, top + 4, special ? 0xFFFFFF80 : 0xFFFFFFFF);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                double mouseX = event.x();
                double mouseY = event.y();
                int button = event.button();
                minecraft.gui.setScreen(parent);
                onPick.accept(group);
                return true;
            }

            @Override
            public Component getNarration() {
                return label(group);
            }
        }
    }
}
