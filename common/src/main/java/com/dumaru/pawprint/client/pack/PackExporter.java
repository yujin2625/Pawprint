package com.dumaru.pawprint.client.pack;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import com.dumaru.pawprint.client.render.IconRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a block pack ({@code .pawpack}) for the Pawprint web editor: every registered block with its properties,
 * names in the chosen languages, and the blockstate, model and texture files the game uses for it.
 * Format: docs/FORMAT_PAWPACK.md. Registry and client state are read on the calling thread; files are read and
 * zipped in the background.
 */
public final class PackExporter {
    public static final String EXTENSION = ".pawpack";
    // Nulls are written: "item": null and "tint": null are part of the format.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    /** Blocks with more states than this are not searched for a color property (redstone wire has 1296). */
    private static final int MAX_STATES_FOR_TINT = 4096;

    public record Options(String name, List<String> languages, boolean resourcePacks) {
    }

    public record Result(Path file, int blocks, int models, int textures, int missingFiles, int icons) {
    }

    private PackExporter() {
    }

    public static Path folder() {
        return Pawprint.dataDir().resolve("packs");
    }

    /** Default pack name: the game folder (usually the modpack instance) and the game version. */
    public static String defaultName(Minecraft minecraft) {
        String folder = minecraft.gameDirectory.toPath().toAbsolutePath().normalize().getFileName().toString();
        if (folder.equals(".minecraft") || folder.equals("run")) {
            folder = Services.PLATFORM.getPlatformName();
        }
        return folder + " (" + SharedConstants.getCurrentVersion().getName() + ")";
    }

    public static CompletableFuture<Result> export(Minecraft minecraft, Options options) {
        Catalog catalog;
        try {
            catalog = Catalog.collect(minecraft, options);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        ResourceManager resources = minecraft.getResourceManager();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return write(catalog, resources, options);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.backgroundExecutor());
    }

    /** Everything that must be read on the game thread. */
    private record Catalog(JsonObject pack, JsonArray blocks, Map<String, JsonObject> names, List<ResourceLocation> blockstates,
                           IconRenderer.@Nullable Sheet icons) {
        static Catalog collect(Minecraft minecraft, Options options) {
            Map<String, List<String>> tabsByBlock = new HashMap<>();
            Map<String, Integer> order = new HashMap<>();
            collectTabs(minecraft, tabsByBlock, order);

            ResourceManager resources = minecraft.getResourceManager();
            Map<String, ClientLanguage> languages = new LinkedHashMap<>();
            for (String code : options.languages()) {
                languages.put(code, ClientLanguage.loadFrom(resources, code.equals("en_us") ? List.of("en_us") : List.of("en_us", code), false));
            }
            Map<String, JsonObject> names = new TreeMap<>();
            languages.keySet().forEach(code -> names.put(code, new JsonObject()));

            BlockColors colors = minecraft.getBlockColors();
            JsonArray blocks = new JsonArray();
            List<ResourceLocation> blockstates = new ArrayList<>();
            Set<String> mods = new TreeSet<>();
            // Blocks the game draws in code (chests, signs…) or without a blockstate file: the web shows their icons.
            List<Map.Entry<String, ItemStack>> iconItems = new ArrayList<>();
            int index = 0;
            for (Block block : BuiltInRegistries.BLOCK) {
                index++;
                ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
                String id = key.toString();
                BlockState state = block.defaultBlockState();
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                JsonObject properties = new JsonObject();
                JsonObject defaults = new JsonObject();
                for (Property<?> property : block.getStateDefinition().getProperties()) {
                    JsonArray values = new JsonArray();
                    for (Comparable<?> value : property.getPossibleValues()) {
                        values.add(valueName(property, value));
                    }
                    properties.add(property.getName(), values);
                    defaults.addProperty(property.getName(), valueName(property, state.getValue(property)));
                }
                entry.add("properties", properties);
                entry.add("default", defaults);
                entry.addProperty("item", block.asItem() == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(block.asItem()).toString());
                entry.addProperty("renderLayer", renderLayer(state));
                entry.addProperty("renderShape", renderShape(state));
                entry.add("tint", tint(colors, block));
                JsonArray tabs = new JsonArray();
                tabsByBlock.getOrDefault(id, List.of()).forEach(tabs::add);
                entry.add("tabs", tabs);
                entry.addProperty("order", order.getOrDefault(id, 1_000_000 + index));
                if (block instanceof LiquidBlock) {
                    entry.addProperty("fluid", BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());
                }
                blocks.add(entry);
                ResourceLocation blockstate = ResourceLocation.tryParse(key.getNamespace() + ":blockstates/" + key.getPath() + ".json");
                blockstates.add(blockstate);
                if (block.asItem() != Items.AIR && (!renderShape(state).equals("model") || resources.getResource(blockstate).isEmpty())) {
                    iconItems.add(Map.entry(id, new ItemStack(block)));
                }
                if (!key.getNamespace().equals("minecraft")) {
                    mods.add(key.getNamespace());
                }
                for (Map.Entry<String, ClientLanguage> language : languages.entrySet()) {
                    String descriptionId = block.getDescriptionId();
                    String name = language.getValue().getOrDefault(descriptionId);
                    if (!name.equals(descriptionId)) {
                        names.get(language.getKey()).addProperty(id, name);
                    }
                }
            }

            JsonObject pack = new JsonObject();
            pack.addProperty("format", 1);
            pack.addProperty("id", UUID.nameUUIDFromBytes(("pawprint-pack:" + options.name()).getBytes(StandardCharsets.UTF_8)).toString());
            pack.addProperty("name", options.name());
            pack.addProperty("created", Instant.now().toString());
            pack.addProperty("generator", "pawprint-mod " + Services.PLATFORM.modInfo(Pawprint.MOD_ID).map(info -> info.version()).orElse("?"));
            pack.addProperty("source", "mod-export");
            pack.addProperty("mcVersion", SharedConstants.getCurrentVersion().getName());
            pack.addProperty("dataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            pack.addProperty("loader", Services.PLATFORM.getPlatformName().toLowerCase(java.util.Locale.ROOT));
            JsonArray modList = new JsonArray();
            for (String mod : mods) {
                JsonObject info = new JsonObject();
                info.addProperty("id", mod);
                var known = Services.PLATFORM.modInfo(mod);
                info.addProperty("name", known.map(m -> m.name()).orElse(mod));
                info.addProperty("version", known.map(m -> m.version()).orElse(""));
                modList.add(info);
            }
            pack.add("mods", modList);
            JsonArray packs = new JsonArray();
            if (options.resourcePacks()) {
                minecraft.getResourcePackRepository().getSelectedIds().forEach(packs::add);
            }
            pack.add("resourcePacks", packs);
            JsonArray languageCodes = new JsonArray();
            names.keySet().forEach(languageCodes::add);
            pack.add("languages", languageCodes);
            pack.addProperty("blockCount", blocks.size());
            pack.addProperty("propertiesComplete", true);
            return new Catalog(pack, blocks, names, blockstates, IconRenderer.render(iconItems));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Property<T> property, Comparable<?> value) {
        return property.getName((T) value);
    }

    private static String renderLayer(BlockState state) {
        try {
            return Services.PLATFORM.renderLayer(state);
        } catch (RuntimeException e) {
            return "solid";
        }
    }

    private static String renderShape(BlockState state) {
        RenderShape shape = state.getRenderShape();
        return shape == RenderShape.MODEL ? "model" : shape == RenderShape.INVISIBLE ? "invisible" : "entity";
    }

    /** Creative tabs each block's item is listed in, and a global order for the web palette. */
    private static void collectTabs(Minecraft minecraft, Map<String, List<String>> tabsByBlock, Map<String, Integer> order) {
        if (minecraft.level == null || minecraft.player == null) {
            return; // Tab contents need the world's registries and features.
        }
        try {
            CreativeModeTabs.tryRebuildTabContents(minecraft.level.enabledFeatures(), minecraft.player.canUseGameMasterBlocks(),
                    minecraft.level.registryAccess());
        } catch (RuntimeException e) {
            Pawprint.LOG.warn("Could not build creative tab contents for the block pack", e);
            return;
        }
        int next = 0;
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                continue;
            }
            ResourceLocation tabKey = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (tabKey == null) {
                continue;
            }
            for (ItemStack stack : tab.getDisplayItems()) {
                if (!(stack.getItem() instanceof BlockItem blockItem)) {
                    continue;
                }
                String id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
                List<String> tabs = tabsByBlock.computeIfAbsent(id, k -> new ArrayList<>());
                if (!tabs.contains(tabKey.toString())) {
                    tabs.add(tabKey.toString());
                }
                order.putIfAbsent(id, next++);
            }
        }
    }

    /**
     * The block's color as the game tints it in a plains biome. When states differ in color (redstone power), finds
     * the one property that decides it and lists a color per value.
     */
    private static @Nullable JsonObject tint(BlockColors colors, Block block) {
        int base = color(colors, block.defaultBlockState());
        if (base == -1) {
            return null;
        }
        JsonObject tint = new JsonObject();
        tint.addProperty("kind", base == GrassColor.getDefaultColor() ? "grass"
                : base == FoliageColor.getDefaultColor() ? "foliage"
                : base == 0x3F76E4 ? "water" : "constant");
        tint.addProperty("color", hex(base));
        List<BlockState> states = block.getStateDefinition().getPossibleStates();
        if (states.size() > 1 && states.size() <= MAX_STATES_FOR_TINT) {
            Map<BlockState, Integer> byState = new HashMap<>();
            for (BlockState state : states) {
                byState.put(state, color(colors, state));
            }
            if (new HashSet<>(byState.values()).size() > 1) {
                tint.addProperty("kind", "other");
                for (Property<?> property : block.getStateDefinition().getProperties()) {
                    Map<String, Integer> perValue = new LinkedHashMap<>();
                    boolean decides = true;
                    for (Map.Entry<BlockState, Integer> entry : byState.entrySet()) {
                        String value = valueName(property, entry.getKey().getValue(property));
                        Integer seen = perValue.putIfAbsent(value, entry.getValue());
                        if (seen != null && !seen.equals(entry.getValue())) {
                            decides = false;
                            break;
                        }
                    }
                    if (decides) {
                        JsonObject map = new JsonObject();
                        perValue.forEach((value, color) -> map.addProperty(property.getName() + "=" + value, hex(color)));
                        tint.add("byState", map);
                        break;
                    }
                }
            }
        }
        return tint;
    }

    private static int color(BlockColors colors, BlockState state) {
        try {
            return colors.getColor(state, null, null, 0);
        } catch (RuntimeException e) {
            return -1; // Some mods' color handlers need a world.
        }
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    // Background part: files.

    private static Result write(Catalog catalog, ResourceManager resources, Options options) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<ResourceLocation> models = new LinkedHashSet<>();
        Set<ResourceLocation> textures = new LinkedHashSet<>();
        int missing = 0;

        for (ResourceLocation blockstate : catalog.blockstates()) {
            Optional<byte[]> data = read(resources, blockstate, options.resourcePacks());
            if (data.isEmpty()) {
                continue; // Drawn in code by its mod; the web shows a stand-in.
            }
            files.put(assetPath(blockstate), data.get());
            try {
                collectModels(JsonParser.parseString(new String(data.get(), StandardCharsets.UTF_8)), models);
            } catch (JsonParseException | IllegalStateException e) {
                Pawprint.LOG.debug("Unreadable blockstate {}", blockstate, e);
            }
        }

        Deque<ResourceLocation> queue = new ArrayDeque<>(models);
        Set<ResourceLocation> seen = new HashSet<>(models);
        int modelCount = 0;
        while (!queue.isEmpty()) {
            ResourceLocation model = queue.poll();
            if (model.getPath().startsWith("builtin/")) {
                continue;
            }
            ResourceLocation file = ResourceLocation.tryParse(model.getNamespace() + ":models/" + model.getPath() + ".json");
            Optional<byte[]> data = file == null ? Optional.empty() : read(resources, file, options.resourcePacks());
            if (data.isEmpty()) {
                missing++;
                continue;
            }
            files.put(assetPath(file), data.get());
            modelCount++;
            try {
                JsonObject json = JsonParser.parseString(new String(data.get(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("parent") && json.get("parent").isJsonPrimitive()) {
                    ResourceLocation parent = ResourceLocation.tryParse(json.get("parent").getAsString());
                    if (parent != null && seen.add(parent)) {
                        queue.add(parent);
                    }
                }
                if (json.has("textures") && json.get("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> texture : json.getAsJsonObject("textures").entrySet()) {
                        String ref = texture.getValue().isJsonPrimitive() ? texture.getValue().getAsString()
                                : texture.getValue().isJsonObject() && texture.getValue().getAsJsonObject().has("sprite")
                                ? texture.getValue().getAsJsonObject().get("sprite").getAsString() : null;
                        if (ref != null && !ref.startsWith("#")) {
                            ResourceLocation location = ResourceLocation.tryParse(ref);
                            if (location != null) {
                                textures.add(location);
                            }
                        }
                    }
                }
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                Pawprint.LOG.debug("Unreadable model {}", file, e);
            }
        }

        int textureCount = 0;
        for (ResourceLocation texture : textures) {
            ResourceLocation png = ResourceLocation.tryParse(texture.getNamespace() + ":textures/" + texture.getPath() + ".png");
            Optional<byte[]> data = png == null ? Optional.empty() : read(resources, png, options.resourcePacks());
            if (data.isEmpty()) {
                missing++;
                continue;
            }
            files.put(assetPath(png), data.get());
            textureCount++;
            ResourceLocation meta = ResourceLocation.tryParse(png + ".mcmeta");
            if (meta != null) {
                read(resources, meta, options.resourcePacks()).ifPresent(bytes -> files.put(assetPath(meta), bytes));
            }
        }

        Path folder = folder();
        Files.createDirectories(folder);
        Path file = folder.resolve(ClientContext.fileSafe(options.name()) + EXTENSION);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "pack.json", GSON.toJson(catalog.pack()).getBytes(StandardCharsets.UTF_8));
            put(zip, "blocks.json", GSON.toJson(catalog.blocks()).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, JsonObject> names : catalog.names().entrySet()) {
                put(zip, "lang/" + names.getKey() + ".json", GSON.toJson(names.getValue()).getBytes(StandardCharsets.UTF_8));
            }
            JsonObject colors = new JsonObject();
            colors.addProperty("grass", hex(GrassColor.getDefaultColor()));
            colors.addProperty("foliage", hex(FoliageColor.getDefaultColor()));
            colors.addProperty("water", "#3F76E4");
            put(zip, "colors.json", GSON.toJson(colors).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
            if (catalog.icons() != null) {
                JsonObject icons = new JsonObject();
                icons.addProperty("cell", IconRenderer.CELL);
                icons.addProperty("columns", IconRenderer.COLUMNS);
                JsonObject cells = new JsonObject();
                catalog.icons().icons().forEach(cells::addProperty);
                icons.add("icons", cells);
                put(zip, "icons.json", GSON.toJson(icons).getBytes(StandardCharsets.UTF_8));
                put(zip, "icons.png", catalog.icons().png());
            }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        return new Result(file, catalog.blocks().size(), modelCount, textureCount, missing,
                catalog.icons() == null ? 0 : catalog.icons().icons().size());
    }

    private static void collectModels(JsonElement blockstate, Set<ResourceLocation> out) {
        if (!blockstate.isJsonObject()) {
            return;
        }
        JsonObject root = blockstate.getAsJsonObject();
        if (root.has("variants") && root.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> variant : root.getAsJsonObject("variants").entrySet()) {
                addModels(variant.getValue(), out);
            }
        }
        if (root.has("multipart") && root.get("multipart").isJsonArray()) {
            for (JsonElement part : root.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    addModels(part.getAsJsonObject().get("apply"), out);
                }
            }
        }
    }

    private static void addModels(JsonElement apply, Set<ResourceLocation> out) {
        if (apply.isJsonArray()) {
            apply.getAsJsonArray().forEach(element -> addModels(element, out));
        } else if (apply.isJsonObject() && apply.getAsJsonObject().has("model")) {
            ResourceLocation model = ResourceLocation.tryParse(apply.getAsJsonObject().get("model").getAsString());
            if (model != null) {
                out.add(model);
            }
        }
    }

    /**
     * Reads a resource. With resource packs off, takes the topmost copy that does not come from a user resource pack,
     * so the pack shows blocks the way the game and its mods ship them.
     */
    private static Optional<byte[]> read(ResourceManager resources, ResourceLocation location, boolean resourcePacks) {
        try {
            Optional<Resource> resource;
            if (resourcePacks) {
                resource = resources.getResource(location);
            } else {
                List<Resource> stack = resources.getResourceStack(location);
                resource = Optional.empty();
                for (int i = stack.size() - 1; i >= 0; i--) {
                    String pack = stack.get(i).sourcePackId();
                    if (!pack.startsWith("file/") && !pack.equals("server")) {
                        resource = Optional.of(stack.get(i));
                        break;
                    }
                }
            }
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = resource.get().open()) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String assetPath(ResourceLocation location) {
        return "assets/" + location.getNamespace() + "/" + location.getPath();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
