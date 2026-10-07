package com.dumaru.pawprint.client.studio;

import com.dumaru.pawprint.Pawprint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a studio trip needs to remember across disconnecting and reconnecting: where the player came from, which
 * terrain was copied, and the blueprint to place back at the original spot. Saved as {@code pawprint/studio/session.json}.
 */
public final class StudioSession {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** "server" to reconnect to {@link #serverIp}, "local" to reopen the singleplayer world {@link #localFolder}. */
    public String returnType = "server";
    public String serverName = "";
    public String serverIp = "";
    public String localFolder = "";
    /** Context key and dimension of the origin world, as used by placements and blueprint origins. */
    public String server = "";
    public String dimension = "";
    /** World box covered by the snapshot: min x, y, z, max x, y, z. */
    public int[] bounds = new int[6];
    public boolean pasted;
    /** Blueprint saved in the studio, to be placed when the player is back in the origin world. */
    public @Nullable String pendingPlacement;
    public int[] pendingOrigin = new int[3];

    public BoundingBox box() {
        return new BoundingBox(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    public void setBox(BoundingBox box) {
        bounds = new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()};
    }

    public BlockPos min() {
        return new BlockPos(bounds[0], bounds[1], bounds[2]);
    }

    static Path folder() {
        return Pawprint.dataDir().resolve("studio");
    }

    static Path snapshotFile() {
        return folder().resolve("snapshot.pawprint");
    }

    private static Path file() {
        return folder().resolve("session.json");
    }

    public static @Nullable StudioSession load() {
        Path file = file();
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StudioSession session = GSON.fromJson(reader, StudioSession.class);
            return session != null && session.bounds != null && session.bounds.length == 6 ? session : null;
        } catch (IOException | JsonParseException e) {
            Pawprint.LOG.warn("Could not read the studio session", e);
            return null;
        }
    }

    public void save() {
        try {
            Files.createDirectories(folder());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save the studio session", e);
        }
    }
}
