package com.dumaru.pawprint.format.text;

import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlockStateCodec;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.shape.Shape;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

/**
 * Reads the "Pawprint Text Blueprint" JSON format (docs/AI_BLUEPRINT_FORMAT.md).
 * Error messages are in English and name the exact spot, so they can be pasted back to the AI that wrote the JSON.
 */
public final class TextBlueprintReader {
    public static final int VERSION = 1;
    public static final int MAX_POSITIONS = 1_000_000;
    private static final int MAX_COORDINATE = 100_000;
    private static final int MAX_Y = 1_000;
    /** Marks a position that must be empty. */
    static final String AIR = "air";

    private final Map<Integer, String> palette = new HashMap<>();
    private final Long2ObjectMap<String> cells = new Long2ObjectOpenHashMap<>();
    private final Set<String> unknownBlocks = new LinkedHashSet<>();
    private final Map<String, String> resolved = new HashMap<>();

    /** A successfully read blueprint, with warnings such as blocks from mods that are not installed. */
    public record Result(Blueprint blueprint, List<String> warnings) {
    }

    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    private TextBlueprintReader() {
    }

    /**
     * Parses text that may be wrapped in a Markdown code fence or surrounded by chatter,
     * as AI replies often are: everything from the first '{' to the last '}' is used.
     */
    public static Result read(String text, String author) throws FormatException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new FormatException("No JSON object found. The reply must contain one JSON object.");
        }
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(text.substring(start, end + 1));
            if (!element.isJsonObject()) {
                throw new FormatException("The top level must be a JSON object.");
            }
            root = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new FormatException("Invalid JSON: " + rootCause(e));
        }
        return new TextBlueprintReader().readRoot(root, author);
    }

    private Result readRoot(JsonObject root, String author) throws FormatException {
        int version = intField(root, "pawprint", "top level");
        if (version != VERSION) {
            throw new FormatException("\"pawprint\" must be " + VERSION + ", got " + version + ".");
        }
        String name = stringField(root, "name", "top level").strip();
        if (name.isEmpty()) {
            throw new FormatException("\"name\" must not be empty.");
        }
        if (root.has("palette")) {
            readPalette(object(root.get("palette"), "palette"));
        }
        boolean hasContent = false;
        if (root.has("operations")) {
            JsonArray operations = array(root.get("operations"), "operations");
            for (int i = 0; i < operations.size(); i++) {
                readOperation(object(operations.get(i), "operations[" + i + "]"), "operations[" + i + "]");
            }
            hasContent = true;
        }
        if (root.has("layers")) {
            readLayers(object(root.get("layers"), "layers"));
            hasContent = true;
        }
        if (!hasContent) {
            throw new FormatException("Either \"operations\" or \"layers\" is required.");
        }
        if (cells.isEmpty()) {
            throw new FormatException("The blueprint contains no blocks.");
        }

        BlueprintMeta meta = new BlueprintMeta();
        meta.id = UUID.randomUUID().toString();
        meta.name = name;
        meta.author = author;
        meta.description = root.has("description") ? stringField(root, "description", "top level") : "";
        meta.created = meta.modified = Instant.now().toString();
        if (root.has("tags")) {
            JsonArray tags = array(root.get("tags"), "tags");
            for (int i = 0; i < tags.size(); i++) {
                meta.tags.add(string(tags.get(i), "tags[" + i + "]"));
            }
        }
        return new Result(build(meta), warnings());
    }

    private void readPalette(JsonObject object) throws FormatException {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            String where = "palette \"" + key + "\"";
            if (key.codePointCount(0, key.length()) != 1) {
                throw new FormatException(where + ": palette keys must be exactly one character.");
            }
            int codePoint = key.codePointAt(0);
            if (codePoint == '.' || codePoint == ' ') {
                throw new FormatException(where + ": \".\" and space are reserved for \"nothing\" and cannot be keys.");
            }
            palette.put(codePoint, block(string(entry.getValue(), where), where));
        }
    }

    private void readOperation(JsonObject op, String where) throws FormatException {
        String shapeName = stringField(op, "shape", where).toLowerCase(Locale.ROOT);
        String block = blockField(op, where);
        BlockPos a;
        BlockPos b;
        Shape shape;
        switch (shapeName) {
            case "single" -> {
                shape = Shape.SINGLE;
                a = b = pos(op, "at", where);
            }
            case "line", "box", "hollow_box", "walls" -> {
                shape = Shape.valueOf(shapeName.toUpperCase(Locale.ROOT));
                a = pos(op, "from", where);
                b = pos(op, "to", where);
            }
            case "sphere" -> {
                shape = Shape.SPHERE;
                a = pos(op, "center", where);
                b = a.offset(nonNegative(op, "radius", where), 0, 0);
            }
            case "cylinder" -> {
                shape = Shape.CYLINDER;
                a = pos(op, "base", where);
                int height = intField(op, "height", where);
                if (height < 1) {
                    throw new FormatException(where + ": \"height\" must be at least 1.");
                }
                b = a.offset(nonNegative(op, "radius", where), height - 1, 0);
            }
            default -> throw new FormatException(where + ": unknown shape \"" + shapeName
                    + "\". Use single, line, box, hollow_box, walls, sphere or cylinder.");
        }
        long size = shape.estimate(a, b);
        if (size > Shape.MAX_CELLS) {
            throw new FormatException(where + ": shape covers about " + size + " blocks; the limit is " + Shape.MAX_CELLS + ".");
        }
        shape.forEach(a, b, pos -> cells.put(pos, block));
        checkTotal(where);
    }

    private void readLayers(JsonObject layers) throws FormatException {
        BlockPos origin = layers.has("origin") ? pos(layers, "origin", "layers") : BlockPos.ZERO;
        JsonArray grid = array(layers.get("grid"), "layers.grid");
        for (int layer = 0; layer < grid.size(); layer++) {
            String layerWhere = "layers.grid[" + layer + "]";
            JsonArray rows = array(grid.get(layer), layerWhere);
            for (int row = 0; row < rows.size(); row++) {
                String rowWhere = layerWhere + "[" + row + "]";
                String text = string(rows.get(row), rowWhere);
                int column = 0;
                for (int i = 0; i < text.length(); column++) {
                    int c = text.codePointAt(i);
                    i += Character.charCount(c);
                    if (c == '.' || c == ' ') {
                        continue;
                    }
                    String block = palette.get(c);
                    if (block == null) {
                        throw new FormatException(rowWhere + " column " + column + ": character \""
                                + new String(Character.toChars(c)) + "\" is not in the palette.");
                    }
                    cells.put(BlockPos.asLong(origin.getX() + column, origin.getY() + layer, origin.getZ() + row), block);
                }
            }
            checkTotal(layerWhere);
        }
    }

    private void checkTotal(String where) throws FormatException {
        if (cells.size() > MAX_POSITIONS) {
            throw new FormatException("After " + where + " the blueprint has more than " + MAX_POSITIONS + " blocks.");
        }
    }

    private Blueprint build(BlueprintMeta meta) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (long pos : cells.keySet()) {
            minX = Math.min(minX, BlockPos.getX(pos));
            minY = Math.min(minY, BlockPos.getY(pos));
            minZ = Math.min(minZ, BlockPos.getZ(pos));
        }
        Blueprint.Builder builder = Blueprint.builder();
        for (Long2ObjectMap.Entry<String> entry : cells.long2ObjectEntrySet()) {
            long pos = entry.getLongKey();
            int x = BlockPos.getX(pos) - minX;
            int y = BlockPos.getY(pos) - minY;
            int z = BlockPos.getZ(pos) - minZ;
            if (entry.getValue().equals(AIR)) {
                builder.remove(x, y, z);
            } else {
                builder.put(x, y, z, entry.getValue());
            }
        }
        return builder.build(meta);
    }

    private List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        for (String block : unknownBlocks) {
            warnings.add("Unknown block kept as is (mod not installed?): " + block);
        }
        return warnings;
    }

    // Blocks

    /** A "block" field holds a palette key or a block string. */
    private String blockField(JsonObject object, String where) throws FormatException {
        String value = stringField(object, "block", where);
        if (value.equals(AIR) || value.contains(":")) {
            return block(value, where);
        }
        if (value.codePointCount(0, value.length()) == 1) {
            String block = palette.get(value.codePointAt(0));
            if (block != null) {
                return block;
            }
        }
        throw new FormatException(where + ": \"block\" is \"" + value
                + "\", which is neither a palette key nor a block ID like \"minecraft:stone\".");
    }

    /** Validates a block string and returns it in canonical form, or as written when the block is unknown. */
    private String block(String value, String where) throws FormatException {
        String trimmed = value.strip();
        if (trimmed.equalsIgnoreCase(AIR) || trimmed.equals("minecraft:air")) {
            return AIR;
        }
        String cached = resolved.get(trimmed);
        if (cached != null) {
            return cached;
        }
        String result;
        try {
            result = BlockStateCodec.serialize(
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), trimmed, false).blockState());
        } catch (CommandSyntaxException e) {
            int bracket = trimmed.indexOf('[');
            ResourceLocation id = ResourceLocation.tryParse(bracket < 0 ? trimmed : trimmed.substring(0, bracket));
            if (id == null) {
                throw new FormatException(where + ": \"" + trimmed + "\" is not a valid block ID.");
            }
            if (BuiltInRegistries.BLOCK.containsKey(id)) {
                throw new FormatException(where + ": invalid state for " + id + " in \"" + trimmed + "\": " + e.getMessage());
            }
            unknownBlocks.add(trimmed);
            result = trimmed;
        }
        resolved.put(trimmed, result);
        return result;
    }

    // JSON helpers with readable errors

    private static JsonObject object(@Nullable JsonElement element, String where) throws FormatException {
        if (element == null || !element.isJsonObject()) {
            throw new FormatException(where + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(@Nullable JsonElement element, String where) throws FormatException {
        if (element == null || !element.isJsonArray()) {
            throw new FormatException(where + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    private static String string(@Nullable JsonElement element, String where) throws FormatException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new FormatException(where + " must be a string.");
        }
        return element.getAsString();
    }

    private static String stringField(JsonObject object, String field, String where) throws FormatException {
        return string(object.get(field), where + ": \"" + field + "\"");
    }

    private static int intField(JsonObject object, String field, String where) throws FormatException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new FormatException(where + ": \"" + field + "\" must be an integer.");
        }
        double value = element.getAsDouble();
        if (value != Math.rint(value) || Math.abs(value) > 30_000_000) {
            throw new FormatException(where + ": \"" + field + "\" must be an integer, got " + element + ".");
        }
        return (int) value;
    }

    private static int nonNegative(JsonObject object, String field, String where) throws FormatException {
        int value = intField(object, field, where);
        if (value < 0) {
            throw new FormatException(where + ": \"" + field + "\" must not be negative.");
        }
        return value;
    }

    private static BlockPos pos(JsonObject object, String field, String where) throws FormatException {
        JsonElement element = object.get(field);
        String label = where + ": \"" + field + "\"";
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new FormatException(label + " must be an array of three integers [x, y, z].");
        }
        JsonArray array = element.getAsJsonArray();
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement value = array.get(i);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || value.getAsDouble() != Math.rint(value.getAsDouble())) {
                throw new FormatException(label + " must be an array of three integers [x, y, z].");
            }
            values[i] = value.getAsInt();
        }
        // Positions are packed into longs internally, which limits Y far more than X and Z.
        if (Math.abs(values[0]) > MAX_COORDINATE || Math.abs(values[1]) > MAX_Y || Math.abs(values[2]) > MAX_COORDINATE) {
            throw new FormatException(label + " is out of range (|x|, |z| <= " + MAX_COORDINATE + ", |y| <= " + MAX_Y + ").");
        }
        return new BlockPos(values[0], values[1], values[2]);
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
