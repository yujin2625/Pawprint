package com.dumaru.pawprint.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes {@code .pawprint} files: a zip with {@code meta.json} and {@code blueprint.nbt}.
 * Every read is bounded so a crafted file cannot exhaust memory.
 */
public final class BlueprintIO {
    /** Newest format this version reads and writes (2 adds layers; files without extra layers are still written as 1). */
    public static final int FORMAT_VERSION = 2;
    public static final String EXTENSION = ".pawprint";

    private static final String META_ENTRY = "meta.json";
    private static final String DATA_ENTRY = "blueprint.nbt";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final int MAX_META_BYTES = 1 << 20;
    private static final int MAX_DATA_BYTES = 64 << 20;
    private static final long MAX_NBT_HEAP = 512L << 20;
    private static final int MAX_BLOCKS = 16_000_000;
    private static final int MAX_PALETTE = 1 << 20;

    private BlueprintIO() {
    }

    public static void write(Blueprint blueprint, Path file) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        // NbtIo closes the stream it writes to, so compress into memory first instead of into the zip.
        NbtIo.writeCompressed(toNbt(blueprint), data);

        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            zip.putNextEntry(new ZipEntry(META_ENTRY));
            zip.write(GSON.toJson(blueprint.meta()).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(DATA_ENTRY));
            data.writeTo(zip);
            zip.closeEntry();
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Replaces only {@code meta.json}, copying the block data unchanged. */
    public static void writeMeta(Path file, BlueprintMeta meta) throws IOException {
        byte[] data;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            data = readEntry(zip, DATA_ENTRY, MAX_DATA_BYTES);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            zip.putNextEntry(new ZipEntry(META_ENTRY));
            zip.write(GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(DATA_ENTRY));
            zip.write(data);
            zip.closeEntry();
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static BlueprintMeta readMeta(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return parseMeta(readEntry(zip, META_ENTRY, MAX_META_BYTES));
        }
    }

    public static Blueprint read(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            BlueprintMeta meta = parseMeta(readEntry(zip, META_ENTRY, MAX_META_BYTES));
            byte[] data = readEntry(zip, DATA_ENTRY, MAX_DATA_BYTES);
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(MAX_NBT_HEAP));
            return fromNbt(meta, tag);
        }
    }

    /** Prefix of share strings; the number is the share format version. */
    public static final String SHARE_PREFIX = "PAW1:";
    private static final int MAX_SHARE_CHARS = 8 << 20;

    /**
     * The blueprint as one line of text for chat: {@code PAW1:} and Base64 (URL-safe) of the compressed NBT with the
     * name and tags included. Thumbnails and other metadata are left out.
     */
    public static String toShareString(Blueprint blueprint) throws IOException {
        CompoundTag tag = toNbt(blueprint);
        tag.putString("Name", blueprint.meta().name);
        tag.putString("Description", blueprint.meta().description);
        ListTag tags = new ListTag();
        blueprint.meta().tags.forEach(text -> tags.add(StringTag.valueOf(text)));
        tag.put("Tags", tags);
        if (blueprint.meta().layers != null) {
            JsonObject layers = new JsonObject();
            layers.add("layers", blueprint.meta().layers);
            if (blueprint.meta().layerOrder != null) {
                layers.add("layerOrder", blueprint.meta().layerOrder);
            }
            tag.putString("Layers", GSON.toJson(layers));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return SHARE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    public static Blueprint fromShareString(String text, String author) throws IOException {
        String trimmed = text.strip();
        int start = trimmed.indexOf(SHARE_PREFIX);
        if (start < 0) {
            throw new IOException("Not a Pawprint share string");
        }
        String payload = trimmed.substring(start + SHARE_PREFIX.length()).split("\\s")[0];
        if (payload.length() > MAX_SHARE_CHARS) {
            throw new IOException("Share string too long");
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IOException("Share string is damaged (incomplete copy?)", e);
        }
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_NBT_HEAP));
        BlueprintMeta meta = new BlueprintMeta();
        meta.id = java.util.UUID.randomUUID().toString();
        meta.name = tag.getStringOr("Name", "").isBlank() ? "Shared Blueprint" : tag.getStringOr("Name", "");
        meta.description = tag.getStringOr("Description", "");
        ListTag tags = tag.getListOrEmpty("Tags");
        for (int i = 0; i < tags.size(); i++) {
            meta.tags.add(tags.getStringOr(i, ""));
        }
        meta.author = author;
        meta.created = meta.modified = java.time.Instant.now().toString();
        meta.dataVersion = tag.getIntOr("DataVersion", 0);
        if (tag.contains("Layers")) {
            try {
                JsonObject layers = GSON.fromJson(tag.getStringOr("Layers", ""), JsonObject.class);
                if (layers != null && layers.has("layers") && layers.get("layers").isJsonArray()) {
                    meta.layers = layers.getAsJsonArray("layers");
                    meta.layerOrder = layers.has("layerOrder") && layers.get("layerOrder").isJsonArray() ? layers.getAsJsonArray("layerOrder") : null;
                    meta.format = 2;
                }
            } catch (JsonParseException e) {
                // A damaged layer list only loses the layers, not the blocks.
                meta.layers = null;
            }
        }
        // Recompute derived fields (mods, block list, versions) like any new blueprint.
        return fromNbt(meta, tag).toBuilder().build(meta);
    }

    private static CompoundTag toNbt(Blueprint blueprint) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", blueprint.meta().dataVersion);
        tag.putIntArray("Size", blueprint.meta().size.clone());

        ListTag palette = new ListTag();
        for (String entry : blueprint.palette()) {
            palette.add(StringTag.valueOf(entry));
        }
        tag.put("Palette", palette);

        long[] positions = new long[blueprint.blocks().size()];
        int[] states = new int[positions.length];
        int i = 0;
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            positions[i] = entry.getLongKey();
            states[i] = entry.getIntValue();
            i++;
        }
        tag.put("Positions", new LongArrayTag(positions));
        tag.put("States", new IntArrayTag(states));
        long[] removals = blueprint.removals().toLongArray();
        tag.put("Removals", new LongArrayTag(removals));
        if (blueprint.meta().format >= 2) {
            // Parallel to Positions and Removals, in the same order.
            int[] blockLayers = new int[positions.length];
            for (int j = 0; j < positions.length; j++) {
                blockLayers[j] = blueprint.layer(positions[j]);
            }
            int[] removalLayers = new int[removals.length];
            for (int j = 0; j < removals.length; j++) {
                removalLayers[j] = blueprint.layer(removals[j]);
            }
            tag.put("BlockLayers", new IntArrayTag(blockLayers));
            tag.put("RemovalLayers", new IntArrayTag(removalLayers));
        }
        return tag;
    }

    private static Blueprint fromNbt(BlueprintMeta meta, CompoundTag tag) throws IOException {
        ListTag paletteTag = tag.getListOrEmpty("Palette");
        if (paletteTag.size() > MAX_PALETTE) {
            throw new IOException("Palette too large: " + paletteTag.size());
        }
        List<String> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(paletteTag.getStringOr(i, ""));
        }

        long[] positions = tag.getLongArray("Positions").orElse(new long[0]);
        int[] states = tag.getIntArray("States").orElse(new int[0]);
        long[] removalArray = tag.getLongArray("Removals").orElse(new long[0]);
        if (positions.length != states.length) {
            throw new IOException("Positions and states differ in length");
        }
        if (positions.length + removalArray.length > MAX_BLOCKS) {
            throw new IOException("Too many blocks: " + (positions.length + removalArray.length));
        }

        Long2IntMap blocks = new Long2IntOpenHashMap(positions.length);
        for (int i = 0; i < positions.length; i++) {
            if (states[i] < 0 || states[i] >= palette.size()) {
                throw new IOException("Palette index out of range: " + states[i]);
            }
            blocks.put(positions[i], states[i]);
        }
        LongSet removals = new LongOpenHashSet(removalArray);
        Long2IntMap blockLayers = new Long2IntOpenHashMap();
        Long2IntMap removalLayers = new Long2IntOpenHashMap();
        if (meta.format >= 2) {
            java.util.Set<Integer> known = BlueprintMeta.layerIds(meta.layers);
            readLayers(tag.getIntArray("BlockLayers").orElse(new int[0]), positions, known, blockLayers);
            readLayers(tag.getIntArray("RemovalLayers").orElse(new int[0]), removalArray, known, removalLayers);
        }

        int[] size = tag.getIntArray("Size").orElse(new int[0]);
        if (size.length == 3) {
            meta.size = size;
        }
        meta.blockCount = blocks.size();
        meta.removalCount = removals.size();
        return new Blueprint(meta, palette, blocks, removals, blockLayers, removalLayers);
    }

    /** A layer array that does not match its positions is ignored; unknown layer IDs mean the default layer. */
    private static void readLayers(int[] layers, long[] positions, java.util.Set<Integer> known, Long2IntMap out) {
        if (layers.length != positions.length) {
            return;
        }
        for (int i = 0; i < layers.length; i++) {
            if (layers[i] != 0 && known.contains(layers[i])) {
                out.put(positions[i], layers[i]);
            }
        }
    }

    private static BlueprintMeta parseMeta(byte[] bytes) throws IOException {
        try {
            BlueprintMeta meta = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), BlueprintMeta.class);
            if (meta == null) {
                throw new IOException("Empty meta.json");
            }
            if (meta.format > FORMAT_VERSION) {
                throw new IOException("Blueprint format " + meta.format + " is newer than this version of Pawprint supports");
            }
            if (meta.size == null || meta.size.length != 3) {
                meta.size = new int[]{0, 0, 0};
            }
            if (meta.tags == null) {
                meta.tags = new ArrayList<>();
            }
            if (meta.mods == null) {
                meta.mods = new ArrayList<>();
            }
            if (meta.blocks == null) {
                meta.blocks = new ArrayList<>();
            }
            return meta;
        } catch (JsonParseException e) {
            throw new IOException("Invalid meta.json", e);
        }
    }

    private static byte[] readEntry(ZipFile zip, String name, int maxBytes) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException("Missing " + name);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException(name + " is larger than " + maxBytes + " bytes");
            }
            return bytes;
        }
    }
}
