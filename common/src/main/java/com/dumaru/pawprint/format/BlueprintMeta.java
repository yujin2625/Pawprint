package com.dumaru.pawprint.format;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
    public @Nullable Origin origin;

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
