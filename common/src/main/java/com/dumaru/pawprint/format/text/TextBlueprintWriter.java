package com.dumaru.pawprint.format.text;

import com.dumaru.pawprint.format.Blueprint;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;

/**
 * Writes a blueprint in the text format as layers only, so it can be handed to an AI to change.
 */
public final class TextBlueprintWriter {
    /** Readable characters first; more are taken from the Latin-1 and Greek ranges when a blueprint needs them. */
    private static final String CHARACTERS =
            "#ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!$%&*+-=?@^~";
    private static final char REMOVAL = '_';
    private static final char EMPTY = '.';

    private TextBlueprintWriter() {
    }

    public static String write(Blueprint blueprint) {
        JsonObject root = new JsonObject();
        root.addProperty("pawprint", TextBlueprintReader.VERSION);
        root.addProperty("name", blueprint.meta().name);
        if (!blueprint.meta().description.isEmpty()) {
            root.addProperty("description", blueprint.meta().description);
        }
        if (!blueprint.meta().tags.isEmpty()) {
            JsonArray tags = new JsonArray();
            blueprint.meta().tags.forEach(tags::add);
            root.add("tags", tags);
        }

        // Assign a character to each palette entry that is actually used.
        Int2IntMap characters = new Int2IntOpenHashMap();
        JsonObject palette = new JsonObject();
        int next = 0;
        for (int index : blueprint.blocks().values()) {
            if (!characters.containsKey(index)) {
                int c = character(next++);
                characters.put(index, c);
                palette.addProperty(new String(Character.toChars(c)), blueprint.palette().get(index));
            }
        }
        if (!blueprint.removals().isEmpty()) {
            palette.addProperty(String.valueOf(REMOVAL), TextBlueprintReader.AIR);
        }
        root.add("palette", palette);

        int sizeX = blueprint.sizeX();
        int sizeY = blueprint.sizeY();
        int sizeZ = blueprint.sizeZ();
        int[][][] grid = new int[sizeY][sizeZ][sizeX];
        for (int[][] layer : grid) {
            for (int[] row : layer) {
                java.util.Arrays.fill(row, EMPTY);
            }
        }
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            long pos = entry.getLongKey();
            grid[BlockPos.getY(pos)][BlockPos.getZ(pos)][BlockPos.getX(pos)] = characters.get(entry.getIntValue());
        }
        for (long pos : blueprint.removals()) {
            grid[BlockPos.getY(pos)][BlockPos.getZ(pos)][BlockPos.getX(pos)] = REMOVAL;
        }

        JsonArray layers = new JsonArray();
        for (int[][] layer : grid) {
            JsonArray rows = new JsonArray();
            for (int[] row : layer) {
                StringBuilder text = new StringBuilder();
                for (int c : row) {
                    text.appendCodePoint(c);
                }
                // Trailing "nothing" can be left out; the reader treats missing characters as ".".
                int end = text.length();
                while (end > 0 && text.charAt(end - 1) == EMPTY) {
                    end--;
                }
                rows.add(text.substring(0, end));
            }
            layers.add(rows);
        }
        JsonObject layersObject = new JsonObject();
        layersObject.add("grid", layers);
        root.add("layers", layersObject);

        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    private static int character(int index) {
        if (index < CHARACTERS.length()) {
            return CHARACTERS.charAt(index);
        }
        // Past the ASCII set: Latin-1 letters, then Greek, then CJK; all single characters.
        int extra = index - CHARACTERS.length();
        int[][] ranges = {{0xC0, 0xFF}, {0x391, 0x3C9}, {0x4E00, 0x9FFF}};
        for (int[] range : ranges) {
            int count = range[1] - range[0] + 1;
            if (extra < count) {
                return range[0] + extra;
            }
            extra -= count;
        }
        throw new IllegalStateException("Too many different blocks for the text format");
    }
}
