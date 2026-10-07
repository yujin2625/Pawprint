package com.dumaru.pawprint.config;

import com.dumaru.pawprint.Pawprint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain JSON config so the mod needs no config library at runtime. Missing fields keep their defaults.
 */
public final class PawprintConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Feature switches, so players can follow a server's rules.
    public boolean enableFreecam = true;
    public boolean enableTerrainSnapshot = true;
    /** Freecam speed in blocks per tick; sprinting triples it. */
    public double freecamSpeed = 0.5;

    /** Ghost blocks farther than this many blocks from the camera are not drawn. */
    public int ghostRenderDistance = 64;
    /** Opacity of ghost blocks, from 0.2 (faint) to 1.0 (looks solid). */
    public float ghostOpacity = 0.65f;
    /** Draw ghost blocks at full brightness instead of the light level where they stand (easier to see at night). */
    public boolean ghostFullBright = false;

    /** How far the edit-mode crosshair reaches. Purely client-side, so it may exceed the normal reach. */
    public int editReach = 48;

    public int snapshotRadiusChunks = 8;
    /** Surface mode copies this many blocks below the surface. */
    public int snapshotSurfaceDepth = 4;

    /** Block search matches names in all of these languages, whatever the game language is. */
    public List<String> searchLanguages = new ArrayList<>(List.of("en_us", "ko_kr"));

    private transient Path path;

    public static PawprintConfig load(Path path) {
        PawprintConfig config = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, PawprintConfig.class);
            } catch (IOException | JsonParseException e) {
                Pawprint.LOG.warn("Could not read {}, using defaults", path, e);
                backup(path);
            }
        }
        if (config == null) {
            config = new PawprintConfig();
        }
        config.path = path;
        config.sanitize();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save {}", path, e);
        }
    }

    private void sanitize() {
        ghostRenderDistance = Math.clamp(ghostRenderDistance, 8, 512);
        ghostOpacity = Math.clamp(ghostOpacity, 0.2f, 1f);
        editReach = Math.clamp(editReach, 5, 256);
        freecamSpeed = Math.clamp(freecamSpeed, 0.05, 5.0);
        snapshotRadiusChunks = Math.clamp(snapshotRadiusChunks, 1, 32);
        snapshotSurfaceDepth = Math.clamp(snapshotSurfaceDepth, 0, 64);
        if (searchLanguages == null || searchLanguages.isEmpty()) {
            searchLanguages = new ArrayList<>(List.of("en_us", "ko_kr"));
        }
    }

    private static void backup(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not back up {}", path, e);
        }
    }
}
