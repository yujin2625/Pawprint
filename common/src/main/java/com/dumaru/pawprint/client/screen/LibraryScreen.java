package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.AiTools;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.Selection;
import com.dumaru.pawprint.client.ViewRay;
import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.placement.MaterialList;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.client.render.ThumbnailCache;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.format.text.TextBlueprintReader;
import com.dumaru.pawprint.client.web.WebLink;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.dumaru.pawprint.library.LibraryState;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The blueprint library: search, group filter, sorting, list or grid view with thumbnails, a detail panel for the
 * selected blueprint, and the global actions (capture, AI import, block list, folder).
 */
public class LibraryScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int GAP = 4;
    private static final int DETAIL_WIDTH = 150;
    private static final int TOP = 32;
    private static final int BOTTOM = 66;
    private static final int DETAIL_THUMB = 96;
    private static final int SUCCESS_COLOR = 0x55FF55;
    private static final int WARNING_COLOR = 0xFFFF55;
    private static final int ERROR_COLOR = 0xFF5555;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static String lastSearch = "";

    private final @Nullable Screen parent;
    private List<BlueprintLibrary.Entry> all = List.of();
    private BlueprintBrowser browser;
    private EditBox search;
    private Button groupButton;
    private Button sortButton;
    private Button directionButton;
    private Button viewButton;
    private Button placeHere;
    private Button placeAtOrigin;
    private Button favorite;
    private Button manage;
    private Button openWeb;
    private Button materials;
    private Button removePlacement;
    private Button captureSelection;
    private @Nullable Component status;
    private int statusColor;
    private @Nullable String pendingSelection;
    /** Files from other mods are converted once per opening of the library. */
    private boolean importedOnOpen;

    public LibraryScreen(@Nullable Screen parent) {
        super(Component.translatable("pawprint.screen.library.title"));
        this.parent = parent;
    }

    // Layout

    @Override
    protected void init() {
        LibraryState.Data state = LibraryState.get();
        String keepSelection = pendingSelection != null ? pendingSelection
                : browser != null && browser.selected() != null ? browser.selected().relativePath() : null;
        pendingSelection = null;

        int right = width - MARGIN;
        int detailX = right - DETAIL_WIDTH;
        int listRight = detailX - GAP;

        // Top bar: search, group, sort, direction, view.
        int x = right;
        viewButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleView())
                .bounds(x -= 20, 12, 20, 18).build());
        directionButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleDirection())
                .bounds(x -= 20 + 2, 12, 20, 18).build());
        sortButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleSort())
                .bounds(x -= 90 + 2, 12, 90, 18).build());
        groupButton = addRenderableWidget(Button.builder(Component.empty(), b -> pickGroup())
                .bounds(x -= 100 + 2, 12, 100, 18).build());
        search = addRenderableWidget(new EditBox(font, MARGIN, 12, Math.max(60, x - 4 - MARGIN), 18,
                Component.translatable("pawprint.library.search")));
        search.setHint(Component.translatable("pawprint.library.search_hint").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(lastSearch);
        search.setResponder(value -> {
            lastSearch = value;
            applyFilter();
        });

        browser = addRenderableWidget(new BlueprintBrowser(font, MARGIN, TOP + 4, listRight - MARGIN,
                height - BOTTOM - TOP - 4, entry -> onSelected(), this::placeHereFor));
        browser.setGrid(state.grid);

        // Detail panel buttons.
        int buttonY = TOP + 4 + DETAIL_THUMB + 4 + 6 * 10 + 4;
        int starWidth = 22;
        int third = (DETAIL_WIDTH - starWidth - GAP * 2) / 2;
        placeHere = addRenderableWidget(button("pawprint.screen.library.place_here", this::placeHere, detailX, buttonY, DETAIL_WIDTH));
        placeAtOrigin = addRenderableWidget(button("pawprint.screen.library.place_at_origin", this::placeAtOrigin,
                detailX, buttonY + 22, DETAIL_WIDTH));
        favorite = addRenderableWidget(Button.builder(Component.empty(), b -> toggleFavorite())
                .bounds(detailX, buttonY + 44, starWidth, 20).build());
        materials = addRenderableWidget(button("pawprint.library.materials", this::openMaterials,
                detailX + starWidth + GAP, buttonY + 44, third));
        manage = addRenderableWidget(button("pawprint.library.manage", this::openManage,
                detailX + starWidth + GAP * 2 + third, buttonY + 44, third));
        openWeb = addRenderableWidget(button("pawprint.library.open_web", this::openInWeb, detailX, buttonY + 66, DETAIL_WIDTH));

        // Bottom bar: global actions in two rows of five.
        int buttonWidth = Math.min(104, (width - MARGIN * 2 - GAP * 4) / 5);
        int rowLeft = (width - (buttonWidth * 5 + GAP * 4)) / 2;
        int row1 = height - 50;
        int row2 = height - 26;
        captureSelection = addRenderableWidget(button("pawprint.screen.library.capture", this::captureSelection, rowLeft, row1, buttonWidth));
        addRenderableWidget(button("pawprint.screen.library.import_clipboard", this::importClipboard,
                rowLeft + (buttonWidth + GAP), row1, buttonWidth));
        addRenderableWidget(button("pawprint.screen.library.copy_prompt", this::copyPrompt,
                rowLeft + (buttonWidth + GAP) * 2, row1, buttonWidth));
        addRenderableWidget(button("pawprint.screen.library.export_blocks", this::exportBlockList,
                rowLeft + (buttonWidth + GAP) * 3, row1, buttonWidth));
        addRenderableWidget(button("pawprint.screen.library.export_pack", () -> minecraft.setScreen(new PackExportScreen(this)),
                rowLeft + (buttonWidth + GAP) * 4, row1, buttonWidth));
        removePlacement = addRenderableWidget(button("pawprint.screen.library.remove_placement", this::removePlacement,
                rowLeft, row2, buttonWidth));
        addRenderableWidget(button("pawprint.library.group.new", this::newGroup, rowLeft + (buttonWidth + GAP), row2, buttonWidth));
        addRenderableWidget(button("pawprint.screen.library.open_folder", this::openFolder,
                rowLeft + (buttonWidth + GAP) * 2, row2, buttonWidth));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(rowLeft + (buttonWidth + GAP) * 4, row2, buttonWidth, 20).build());

        if (!importedOnOpen) {
            importedOnOpen = true;
            showImportResult(BlueprintLibrary.importForeignFiles(), false);
        }
        all = BlueprintLibrary.list();
        applyFilter();
        browser.select(keepSelection);
        updateButtons();
    }

    /** Dropping files on the game window adds them to the shown group, converting other formats. */
    @Override
    public void onFilesDrop(List<Path> files) {
        String group = groupFilter().startsWith("*") ? "" : groupFilter();
        showImportResult(BlueprintLibrary.importDropped(files, group), true);
        refresh();
    }

    private void showImportResult(BlueprintLibrary.ImportResult result, boolean always) {
        if (!result.failures().isEmpty()) {
            setStatus(Component.translatable("pawprint.library.import_failed", result.failures().size(),
                    result.failures().get(0)), ERROR_COLOR);
        } else if (result.imported() > 0 || always) {
            setStatus(Component.translatable("pawprint.library.imported_files", result.imported()), SUCCESS_COLOR);
        }
    }

    private Button button(String key, Runnable action, int x, int y, int buttonWidth) {
        return Button.builder(Component.translatable(key), b -> action.run()).bounds(x, y, buttonWidth, 20).build();
    }

    private void applyFilter() {
        LibraryState.Data state = LibraryState.get();
        browser.setEntries(LibraryQuery.apply(all, search.getValue(), groupFilter(),
                LibraryQuery.Sort.valueOf(state.sort), state.ascending));
        updateButtons();
    }

    private static String groupFilter() {
        String group = LibraryState.get().group;
        return group == null || group.isEmpty() ? LibraryQuery.ALL : group;
    }

    private void updateButtons() {
        if (manage == null) {
            return;
        }
        LibraryState.Data state = LibraryState.get();
        groupButton.setMessage(Component.translatable("pawprint.library.group", GroupPickerScreen.label(groupFilter())));
        sortButton.setMessage(Component.translatable(LibraryQuery.Sort.valueOf(state.sort).translationKey()));
        directionButton.setMessage(Component.literal(state.ascending ? "↑" : "↓"));
        viewButton.setMessage(Component.literal(state.grid ? "≡" : "▦"));

        BlueprintLibrary.Entry selected = browser.selected();
        placeHere.active = selected != null;
        placeAtOrigin.active = selected != null && isFromHere(selected.meta().origin);
        favorite.active = selected != null;
        favorite.setMessage(Component.literal(selected != null && LibraryState.isFavorite(selected.relativePath()) ? "★" : "☆"));
        favorite.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("pawprint.library.favorite_tooltip")));
        materials.active = selected != null;
        manage.active = selected != null;
        openWeb.active = selected != null && WebLink.port() >= 0;
        openWeb.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                WebLink.port() >= 0 ? "pawprint.library.open_web_tooltip" : "pawprint.library.open_web_off")));
        removePlacement.active = PlacementManager.active() != null;
        captureSelection.active = Selection.box() != null;
    }

    private void onSelected() {
        status = null;
        updateButtons();
    }

    // Top bar actions

    private void toggleView() {
        LibraryState.Data state = LibraryState.get();
        state.grid = !state.grid;
        LibraryState.save();
        browser.setGrid(state.grid);
        updateButtons();
    }

    private void toggleDirection() {
        LibraryState.get().ascending = !LibraryState.get().ascending;
        LibraryState.save();
        applyFilter();
    }

    private void cycleSort() {
        LibraryState.Data state = LibraryState.get();
        state.sort = LibraryQuery.Sort.valueOf(state.sort).next().name();
        // Names read best A to Z; everything else newest or largest first.
        state.ascending = state.sort.equals(LibraryQuery.Sort.NAME.name());
        LibraryState.save();
        applyFilter();
    }

    private void pickGroup() {
        minecraft.setScreen(new GroupPickerScreen(this, Component.translatable("pawprint.library.group.pick"), true,
                groupFilter(), group -> {
            if (!group.startsWith("*")) {
                createGroup(group);
            }
            LibraryState.get().group = group;
            LibraryState.save();
        }));
    }

    private void newGroup() {
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("pawprint.library.group.new"), "",
                Component.translatable("pawprint.library.group.new_hint"), false, name -> {
            createGroup(name);
            minecraft.setScreen(this);
        }));
    }

    private void createGroup(String group) {
        try {
            BlueprintLibrary.createGroup(group);
        } catch (IOException e) {
            setStatus(Component.translatable("pawprint.library.action_failed", e.getMessage()), ERROR_COLOR);
        }
    }

    // Detail panel actions

    private void placeHere() {
        BlueprintLibrary.Entry selected = browser.selected();
        if (selected != null) {
            placeHereFor(selected);
        }
    }

    private void placeHereFor(BlueprintLibrary.Entry entry) {
        place(entry, ViewRay.placeHere(minecraft));
    }

    private void placeAtOrigin() {
        BlueprintLibrary.Entry selected = browser.selected();
        if (selected != null && selected.meta().origin != null) {
            int[] pos = selected.meta().origin.pos;
            place(selected, new BlockPos(pos[0], pos[1], pos[2]));
        }
    }

    private void place(BlueprintLibrary.Entry entry, BlockPos origin) {
        try {
            Blueprint blueprint = BlueprintLibrary.load(entry.relativePath());
            PlacementManager.add(new Placement(entry.relativePath(), blueprint, origin, Rotation.NONE, Mirror.NONE));
            if (!EditMode.isActive()) {
                PlacementManager.setViewing(true); // Otherwise the new placement would be invisible.
            }
            LibraryState.markUsed(entry.relativePath());
            minecraft.setScreen(null);
            PawprintClient.notify(minecraft, Component.translatable("pawprint.placement.placed", entry.meta().name,
                    PawprintKeys.MENU.getTranslatedKeyMessage()));
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not load blueprint {}", entry.file(), e);
            setStatus(Component.translatable("pawprint.screen.library.load_failed", e.getMessage()), ERROR_COLOR);
        }
    }

    private void toggleFavorite() {
        BlueprintLibrary.Entry selected = browser.selected();
        if (selected != null) {
            LibraryState.toggleFavorite(selected.relativePath());
            applyFilter();
        }
    }

    /** Opens the selected blueprint in the web editor, which fetches it from this game over the local web link. */
    private void openInWeb() {
        BlueprintLibrary.Entry selected = browser.selected();
        if (selected == null) {
            return;
        }
        try {
            Path file = selected.file();
            if (BlueprintLibrary.isTextFile(file)) {
                // The editor reads .pawprint files: convert text blueprints first.
                Path cache = com.dumaru.pawprint.Pawprint.dataDir().resolve("cache").resolve("web");
                java.nio.file.Files.createDirectories(cache);
                Path converted = cache.resolve("open" + com.dumaru.pawprint.format.BlueprintIO.EXTENSION);
                com.dumaru.pawprint.format.BlueprintIO.write(BlueprintLibrary.read(file), converted);
                file = converted;
            }
            if (WebLink.openInWeb(file, selected.meta().name)) {
                setStatus(Component.translatable("pawprint.library.open_web_done"), SUCCESS_COLOR);
            }
        } catch (IOException e) {
            setStatus(Component.translatable("pawprint.library.action_failed", e.getMessage()), ERROR_COLOR);
        }
    }

    private void openMaterials() {
        BlueprintLibrary.Entry selected = browser.selected();
        if (selected == null) {
            return;
        }
        try {
            minecraft.setScreen(new MaterialsScreen(this, selected, BlueprintLibrary.load(selected.relativePath())));
        } catch (IOException e) {
            setStatus(Component.translatable("pawprint.screen.library.load_failed", e.getMessage()), ERROR_COLOR);
        }
    }

    /** The materials screen for any blueprint; used by the self-test. */
    public static Screen materialsFor(Blueprint blueprint) {
        Minecraft minecraft = Minecraft.getInstance();
        return new MaterialsScreen(null, null, blueprint);
    }

    private void openManage() {
        BlueprintLibrary.Entry selected = browser.selected();
        if (selected != null) {
            minecraft.setScreen(new ManageBlueprintScreen(this, selected));
        }
    }

    private static boolean isFromHere(@Nullable BlueprintMeta.Origin origin) {
        return origin != null
                && Objects.equals(origin.server, ClientContext.server())
                && Objects.equals(origin.dimension, ClientContext.dimension());
    }

    // Global actions

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
            Util.getPlatform().openFile((BlueprintLibrary.root()).toFile());
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not open the blueprint folder", e);
        }
    }

    private void copyPrompt() {
        AiTools.Prompt prompt = AiTools.prompt();
        minecraft.keyboardHandler.setClipboard(prompt.text());
        setStatus(prompt.total() == 0
                ? Component.translatable("pawprint.ai.prompt_copied")
                : Component.translatable("pawprint.ai.prompt_copied_blocks", prompt.listed(), prompt.total()), SUCCESS_COLOR);
    }

    private void importClipboard() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (text.length() > AiTools.MAX_CLIPBOARD_CHARS) {
            setStatus(Component.translatable("pawprint.ai.clipboard_too_large", AiTools.MAX_CLIPBOARD_CHARS), ERROR_COLOR);
            return;
        }
        if (text.strip().startsWith(BlueprintIO.SHARE_PREFIX)) {
            try {
                Blueprint shared = BlueprintIO.fromShareString(text, minecraft.getUser().getName());
                String group = groupFilter().startsWith("*") ? "" : groupFilter();
                Path file = BlueprintLibrary.saveNew(shared, group);
                refresh(BlueprintLibrary.relativize(file), Component.translatable("pawprint.ai.imported",
                        shared.meta().name, shared.meta().blockCount), false);
            } catch (IOException e) {
                setStatus(Component.translatable("pawprint.library.action_failed", e.getMessage()), ERROR_COLOR);
            }
            return;
        }
        try {
            TextBlueprintReader.Result result = TextBlueprintReader.read(text, minecraft.getUser().getName());
            String group = groupFilter().startsWith("*") ? "" : groupFilter();
            Path file = BlueprintLibrary.saveNew(result.blueprint(), group);
            Component message;
            int color;
            if (result.warnings().isEmpty()) {
                message = Component.translatable("pawprint.ai.imported", result.blueprint().meta().name,
                        result.blueprint().meta().blockCount);
                color = SUCCESS_COLOR;
            } else {
                result.warnings().forEach(warning -> Pawprint.LOG.info("Import warning: {}", warning));
                message = Component.translatable("pawprint.ai.imported_with_warnings", result.blueprint().meta().name,
                        result.warnings().size(), result.warnings().get(0));
                color = WARNING_COLOR;
            }
            refresh(BlueprintLibrary.relativize(file), message, false);
            statusColor = color;
        } catch (TextBlueprintReader.FormatException e) {
            // Put the error on the clipboard so it can be pasted straight back to the AI.
            minecraft.keyboardHandler.setClipboard("The Pawprint importer rejected the JSON: " + e.getMessage()
                    + "\nPlease fix it and reply with the corrected JSON only.");
            setStatus(Component.translatable("pawprint.ai.import_failed", e.getMessage()), ERROR_COLOR);
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save the imported blueprint", e);
            setStatus(Component.translatable("pawprint.capture.write_failed", e.getMessage()), ERROR_COLOR);
        }
    }

    private void exportBlockList() {
        try {
            Path file = AiTools.exportBlockList();
            Util.getPlatform().openFile((file).toFile());
            setStatus(Component.translatable("pawprint.ai.blocks_exported", file.toString()), SUCCESS_COLOR);
        } catch (IOException e) {
            setStatus(Component.translatable("pawprint.capture.write_failed", e.getMessage()), ERROR_COLOR);
        }
    }

    private void setStatus(Component message, int color) {
        status = message;
        statusColor = color;
    }

    /** Reloads the list; called when returning from the save screen so a new blueprint shows up. */
    void refresh() {
        all = BlueprintLibrary.list();
        applyFilter();
    }

    /** Reloads the list after a change made by a sub-screen, selecting {@code select} and showing a message. */
    void refresh(@Nullable String select, Component message, boolean error) {
        pendingSelection = select;
        setStatus(message, error ? ERROR_COLOR : SUCCESS_COLOR);
        if (browser != null) {
            all = BlueprintLibrary.list();
            applyFilter();
            browser.select(select);
            updateButtons();
        }
    }

    // Rendering

    @Override
    public void tick() {
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ThumbnailCache.tick();
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, MARGIN, 2, 0xFFFFFF);
        graphics.drawString(font, selectionInfo(), MARGIN + font.width(title) + 8, 2, 0xA0A0A0);
        if (browser.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(all.isEmpty()
                            ? "pawprint.screen.library.empty" : "pawprint.library.no_match"),
                    browser.getX() + browser.getWidth() / 2, browser.getY() + 20, 0xA0A0A0);
        }
        renderDetails(graphics);
        if (status != null) {
            List<FormattedCharSequence> lines = font.split(status, width - MARGIN * 2);
            graphics.drawCenteredString(font, lines.get(0), width / 2, height - BOTTOM + 4, statusColor);
        }
    }

    private void renderDetails(GuiGraphics graphics) {
        BlueprintLibrary.Entry entry = browser.selected();
        int x = width - MARGIN - DETAIL_WIDTH;
        int y = TOP + 4;
        if (entry == null) {
            graphics.drawCenteredString(font, Component.translatable("pawprint.library.select_hint"),
                    x + DETAIL_WIDTH / 2, y + 40, 0x808080);
            return;
        }
        BlueprintBrowser.renderThumbnail(graphics, entry, x + (DETAIL_WIDTH - DETAIL_THUMB) / 2, y, DETAIL_THUMB);
        y += DETAIL_THUMB + 4;
        BlueprintMeta meta = entry.meta();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(meta.name).withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("pawprint.screen.library.details", meta.size[0], meta.size[1], meta.size[2],
                meta.blockCount));
        lines.add(Component.translatable("pawprint.library.detail.group", GroupPickerScreen.label(entry.group())));
        lines.add(Component.translatable("pawprint.library.detail.tags",
                meta.tags.isEmpty() ? "-" : String.join(", ", meta.tags)));
        lines.add(Component.translatable("pawprint.library.detail.modified", formatDate(meta.modified)));
        lines.add(Component.translatable("pawprint.library.detail.author", meta.author.isEmpty() ? "-" : meta.author));
        for (Component line : lines) {
            graphics.drawString(font, font.plainSubstrByWidth(line.getString(), DETAIL_WIDTH), x, y, 0xC0C0C0);
            y += 10;
        }
        if (!meta.description.isEmpty()) {
            int descriptionY = placeHere.getY() + 70;
            for (FormattedCharSequence line : font.split(Component.literal(meta.description), DETAIL_WIDTH)) {
                if (descriptionY + 10 > height - BOTTOM) {
                    break;
                }
                graphics.drawString(font, line, x, descriptionY, 0x909090);
                descriptionY += 10;
            }
        }
    }

    private static String formatDate(String instant) {
        try {
            return DATE.format(Instant.parse(instant));
        } catch (DateTimeParseException e) {
            return instant;
        }
    }

    private Component selectionInfo() {
        BoundingBox box = Selection.box();
        if (box != null) {
            return Component.translatable("pawprint.screen.library.selection", box.getXSpan(), box.getYSpan(), box.getZSpan());
        }
        return Component.translatable("pawprint.screen.library.no_selection");
    }

    @Override
    public void removed() {
        ThumbnailCache.releaseAll();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
