package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.google.common.hash.Hashing;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Thumbnails for library entries. Images are rendered on demand, one per frame, and kept as PNG files under
 * {@code pawprint/cache/thumbnails} keyed by file path and modification time, so editing a blueprint makes a new one.
 */
public final class ThumbnailCache {
    private static final Map<String, ResourceLocation> textures = new HashMap<>();
    /** Entries that have no thumbnail (too large, empty or unreadable), so they are not retried every frame. */
    private static final Set<String> failed = new HashSet<>();
    private static final Deque<BlueprintLibrary.Entry> queue = new ArrayDeque<>();
    private static final Set<String> queued = new HashSet<>();

    private ThumbnailCache() {
    }

    /** The texture for an entry, or null while it is being prepared or when it cannot have one. */
    public static @Nullable ResourceLocation get(BlueprintLibrary.Entry entry) {
        String key = key(entry);
        ResourceLocation texture = textures.get(key);
        if (texture != null || failed.contains(key)) {
            return texture;
        }
        Path file = cacheFile(key);
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                return register(key, NativeImage.read(in));
            } catch (IOException e) {
                Pawprint.LOG.debug("Unreadable cached thumbnail {}", file, e);
            }
        }
        if (queued.add(key)) {
            queue.add(entry);
        }
        return null;
    }

    /** Renders the next queued thumbnail. Call once per frame from a screen. */
    public static void tick() {
        BlueprintLibrary.Entry entry = queue.poll();
        if (entry == null) {
            return;
        }
        String key = key(entry);
        queued.remove(key);
        try {
            NativeImage image = ThumbnailRenderer.render(BlueprintLibrary.read(entry.file()));
            if (image == null) {
                failed.add(key);
                return;
            }
            Path file = cacheFile(key);
            Files.createDirectories(file.getParent());
            image.writeToFile(file);
            register(key, image);
        } catch (IOException | RuntimeException e) {
            Pawprint.LOG.warn("Could not make a thumbnail for {}", entry.file(), e);
            failed.add(key);
        }
    }

    /** Frees all textures; they are reloaded from the disk cache when the library opens again. */
    public static void releaseAll() {
        Minecraft minecraft = Minecraft.getInstance();
        textures.values().forEach(minecraft.getTextureManager()::release);
        textures.clear();
        failed.clear();
        queue.clear();
        queued.clear();
    }

    private static ResourceLocation register(String key, NativeImage image) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Pawprint.MOD_ID, "thumbnail/" + hash(key));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        textures.put(key, id);
        return id;
    }

    private static String key(BlueprintLibrary.Entry entry) {
        return entry.relativePath() + "|" + entry.lastModified();
    }

    private static Path cacheFile(String key) {
        return Pawprint.dataDir().resolve("cache").resolve("thumbnails").resolve(hash(key) + ".png");
    }

    @SuppressWarnings("deprecation") // SHA-1 is only a cache key here.
    private static String hash(String key) {
        return Hashing.sha1().hashString(key, StandardCharsets.UTF_8).toString();
    }
}
