package com.dumaru.pawprint.format;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contents of {@code meta.json} inside a {@code .pawprint} file. Read on its own when indexing the library,
 * so it must stay small. Field names are part of the file format.
 */
public final class BlueprintMeta {
    public int format = BlueprintIO.FORMAT_VERSION;
    public String id = "";
    public String name = "";
    public String description = "";
    public String author = "";
    public List<String> tags = new ArrayList<>();
    /** ISO-8601 instants; sorting the strings sorts by time. */
    public String created = "";
    public String modified = "";
    public String mcVersion = "";
    public int dataVersion;
    public int[] size = {0, 0, 0};
    public int blockCount;
    public int removalCount;
    public List<String> mods = new ArrayList<>();
    /** Distinct block IDs used (without states), so the library can search by block without loading the file. */
    public List<String> blocks = new ArrayList<>();
    public @Nullable Origin origin;
    /**
     * Format 2: layers and groups made in the web editor, and the block pack last used there. Kept as raw JSON:
     * the mod does not use them, it only carries them through saves. See docs/FORMAT_PAWPRINT.md.
     */
    public @Nullable JsonArray layers;
    public @Nullable JsonArray layerOrder;
    public @Nullable JsonObject packHint;

    /** IDs of the layers blocks may belong to (groups excluded); layer 0 always counts. */
    public static Set<Integer> layerIds(@Nullable JsonArray layers) {
        Set<Integer> ids = new HashSet<>();
        ids.add(0);
        if (layers == null) {
            return ids;
        }
        for (JsonElement element : layers) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject layer = element.getAsJsonObject();
            boolean group = layer.has("group") && layer.get("group").isJsonPrimitive() && layer.get("group").getAsBoolean();
            if (!group && layer.has("id") && layer.get("id").isJsonPrimitive() && layer.get("id").getAsJsonPrimitive().isNumber()) {
                ids.add(layer.get("id").getAsInt());
            }
        }
        return ids;
    }

    /** Where the blueprint was made, so it can be placed back at the same spot. */
    public static final class Origin {
        public String server = "";
        public String dimension = "";
        public int[] pos = {0, 0, 0};

        public Origin() {
        }

        public Origin(String server, String dimension, int x, int y, int z) {
            this.server = server;
            this.dimension = dimension;
            this.pos = new int[]{x, y, z};
        }
    }
}
