package com.dumaru.pawprint.library;

import com.dumaru.pawprint.Pawprint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-player library state kept beside the blueprints: favorites, recent use and view preferences.
 * Blueprints are referenced by library-relative path.
 */
public final class LibraryState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_RECENT = 50;
    private static @Nullable Data data;

    private LibraryState() {
    }

    /** Saved form. Field names are part of the file format. */
    public static final class Data {
        public Set<String> favorites = new LinkedHashSet<>();
        /** Path to last-use time in epoch milliseconds, most recent last. */
        public Map<String, Long> recent = new LinkedHashMap<>();
        public String sort = "MODIFIED";
        public boolean ascending = false;
        public boolean grid = false;
        /** Selected group filter; see the library screen for the special values. */
        public String group = "";
    }

    public static Data get() {
        if (data == null) {
            data = load();
        }
        return data;
    }

    public static boolean isFavorite(String path) {
        return get().favorites.contains(path);
    }

    public static void toggleFavorite(String path) {
        if (!get().favorites.remove(path)) {
            get().favorites.add(path);
        }
        save();
    }

    public static long lastUsed(String path) {
        return get().recent.getOrDefault(path, 0L);
    }

    public static void markUsed(String path) {
        Map<String, Long> recent = get().recent;
        recent.remove(path);
        recent.put(path, System.currentTimeMillis());
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.keySet().iterator().next());
        }
        save();
    }

    static void pathChanged(String oldPath, @Nullable String newPath) {
        Data state = get();
        boolean favorite = state.favorites.remove(oldPath);
        Long used = state.recent.remove(oldPath);
        if (newPath != null) {
            if (favorite) {
                state.favorites.add(newPath);
            }
            if (used != null) {
                state.recent.put(newPath, used);
            }
        }
        save();
    }

    private static Path file() {
        return Pawprint.dataDir().resolve("library.json");
    }

    private static Data load() {
        Path file = file();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Data loaded = GSON.fromJson(reader, Data.class);
                if (loaded != null) {
                    if (loaded.favorites == null) {
                        loaded.favorites = new LinkedHashSet<>();
                    }
                    if (loaded.recent == null) {
                        loaded.recent = new LinkedHashMap<>();
                    }
                    return loaded;
                }
            } catch (IOException | JsonParseException e) {
                Pawprint.LOG.warn("Could not read {}", file, e);
            }
        }
        return new Data();
    }

    public static void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(get(), writer);
            }
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save {}", file, e);
        }
    }
}
