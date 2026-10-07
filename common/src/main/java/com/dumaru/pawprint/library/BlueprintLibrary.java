package com.dumaru.pawprint.library;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.format.text.TextBlueprintReader;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Blueprints saved under {@code <game dir>/pawprint/blueprints}. Sub-folders are groups.
 * Files are addressed by their path relative to the library root, with forward slashes.
 */
public final class BlueprintLibrary {
    public static final String TEXT_EXTENSION = ".pawprint.json";
    private static final long MAX_TEXT_BYTES = 32L << 20;

    /** Told about every move (old, new) and delete (old, null), so references elsewhere can follow. */
    private static final List<BiConsumer<String, @Nullable String>> pathListeners = new ArrayList<>();

    private BlueprintLibrary() {
    }

    /**
     * @param group        folder relative to the library root, "" for the root itself
     * @param lastModified file time, used to tell when cached thumbnails are stale
     */
    public record Entry(Path file, String relativePath, String group, BlueprintMeta meta, long lastModified) {
        public boolean isText() {
            return isTextFile(file);
        }
    }

    public static Path root() {
        return Pawprint.dataDir().resolve("blueprints");
    }

    public static void addPathListener(BiConsumer<String, @Nullable String> listener) {
        pathListeners.add(listener);
    }

    // Listing

    /** Lists every readable blueprint, newest first. Unreadable files are logged and skipped. */
    public static List<Entry> list() {
        Path root = root();
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return entries;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(BlueprintLibrary::isBlueprintFile)::iterator) {
                try {
                    entries.add(entry(file));
                } catch (IOException | TextBlueprintReader.FormatException e) {
                    Pawprint.LOG.warn("Skipping unreadable blueprint {}", file, e);
                }
            }
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not list blueprints in {}", root, e);
        }
        entries.sort(Comparator.comparing((Entry entry) -> entry.meta().modified).reversed());
        return entries;
    }

    private static Entry entry(Path file) throws IOException, TextBlueprintReader.FormatException {
        String relative = relativize(file);
        return new Entry(file, relative, groupOf(relative), readMeta(file), Files.getLastModifiedTime(file).toMillis());
    }

    /** Every group folder, including empty ones, sorted by path. */
    public static List<String> groups() {
        Path root = root();
        List<String> groups = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return groups;
        }
        try (Stream<Path> dirs = Files.walk(root)) {
            dirs.filter(Files::isDirectory).filter(dir -> !dir.equals(root))
                    .map(BlueprintLibrary::relativize).sorted().forEach(groups::add);
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not list groups in {}", root, e);
        }
        return groups;
    }

    public static String groupOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    // Reading and writing

    /** Saves a new blueprint in the library root, named after the blueprint. Returns the written file. */
    public static Path saveNew(Blueprint blueprint) throws IOException {
        return saveNew(blueprint, "");
    }

    public static Path saveNew(Blueprint blueprint, String group) throws IOException {
        Path folder = folder(group);
        Files.createDirectories(folder);
        Path file = uniqueFile(folder, fileName(blueprint.meta().name), BlueprintIO.EXTENSION);
        BlueprintIO.write(blueprint, file);
        return file;
    }

    public static Blueprint load(String relativePath) throws IOException {
        return read(resolve(relativePath));
    }

    /** Reads either format: binary {@code .pawprint} or text {@code .pawprint.json}. */
    public static Blueprint read(Path file) throws IOException {
        if (!isTextFile(file)) {
            return BlueprintIO.read(file);
        }
        try {
            return TextBlueprintReader.read(Files.readString(file), "").blueprint();
        } catch (TextBlueprintReader.FormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static BlueprintMeta readMeta(Path file) throws IOException, TextBlueprintReader.FormatException {
        if (!isTextFile(file)) {
            return BlueprintIO.readMeta(file);
        }
        // Text files have no separate metadata; read them fully. They are small by nature.
        if (Files.size(file) > MAX_TEXT_BYTES) {
            throw new IOException("Text blueprint larger than " + MAX_TEXT_BYTES + " bytes");
        }
        return TextBlueprintReader.read(Files.readString(file), "").blueprint().meta();
    }

    // Management

    /**
     * Changes the name, tags or description. Binary files get a new {@code meta.json}; text files keep their
     * formatting except for the changed fields. Renaming also renames the file.
     */
    public static Entry updateMeta(Entry entry, Consumer<BlueprintMeta> change) throws IOException {
        BlueprintMeta meta = entry.meta();
        String oldName = meta.name;
        change.accept(meta);
        meta.modified = Instant.now().toString();
        if (entry.isText()) {
            writeTextMeta(entry.file(), meta);
        } else {
            BlueprintIO.writeMeta(entry.file(), meta);
        }
        Path file = entry.file();
        if (!meta.name.equals(oldName)) {
            file = moveFile(entry, entry.file().getParent(), fileName(meta.name));
        }
        return refreshed(file);
    }

    /** Moves a blueprint into another group, creating the folder if needed. */
    public static Entry moveToGroup(Entry entry, String group) throws IOException {
        Path folder = folder(group);
        Files.createDirectories(folder);
        return refreshed(moveFile(entry, folder, baseName(entry.file())));
    }

    /** Copies a blueprint next to the original, with a new ID and " (copy)" added to the name. */
    public static Entry duplicate(Entry entry, String copySuffix) throws IOException {
        Blueprint blueprint = read(entry.file());
        BlueprintMeta meta = blueprint.meta();
        meta.id = UUID.randomUUID().toString();
        meta.name = meta.name + copySuffix;
        meta.created = meta.modified = Instant.now().toString();
        Path file = uniqueFile(entry.file().getParent(), fileName(meta.name), BlueprintIO.EXTENSION);
        BlueprintIO.write(blueprint, file);
        return refreshed(file);
    }

    /** Moves the file to {@code pawprint/trash} instead of deleting it, so a mistake can be undone by hand. */
    public static void delete(Entry entry) throws IOException {
        Path trash = Pawprint.dataDir().resolve("trash");
        Files.createDirectories(trash);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.move(entry.file(), trash.resolve(stamp + " " + entry.file().getFileName()));
        notifyPath(entry.relativePath(), null);
    }

    public static void createGroup(String group) throws IOException {
        Files.createDirectories(folder(group));
    }

    private static Entry refreshed(Path file) throws IOException {
        try {
            return entry(file);
        } catch (TextBlueprintReader.FormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Path moveFile(Entry entry, Path folder, String baseName) throws IOException {
        String extension = entry.isText() ? TEXT_EXTENSION : BlueprintIO.EXTENSION;
        Path target = folder.resolve(baseName + extension);
        if (target.equals(entry.file())) {
            return target;
        }
        target = uniqueFile(folder, baseName, extension);
        Files.move(entry.file(), target);
        notifyPath(entry.relativePath(), relativize(target));
        return target;
    }

    private static void notifyPath(String oldPath, @Nullable String newPath) {
        for (BiConsumer<String, @Nullable String> listener : pathListeners) {
            listener.accept(oldPath, newPath);
        }
        LibraryState.pathChanged(oldPath, newPath);
    }

    private static void writeTextMeta(Path file, BlueprintMeta meta) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Could not update " + file.getFileName(), e);
        }
        root.addProperty("name", meta.name);
        root.addProperty("description", meta.description);
        JsonArray tags = new JsonArray();
        meta.tags.forEach(tags::add);
        root.add("tags", tags);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),
                StandardCharsets.UTF_8);
    }

    // Paths

    /** Text blueprints can only be replaced by saving as a new binary file. */
    public static boolean isTextFile(Path file) {
        return file.getFileName().toString().endsWith(TEXT_EXTENSION);
    }

    public static Path resolve(String relativePath) throws IOException {
        Path root = root().toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Path escapes the blueprint library: " + relativePath);
        }
        return file;
    }

    public static String relativize(Path file) {
        return root().toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /** A group folder; group names are split on '/' and each part is made file-safe. */
    private static Path folder(String group) throws IOException {
        StringBuilder safe = new StringBuilder();
        for (String part : group.split("/")) {
            if (!part.isBlank()) {
                safe.append(safe.isEmpty() ? "" : "/").append(fileName(part));
            }
        }
        return safe.isEmpty() ? root() : resolve(safe.toString());
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        String extension = isTextFile(file) ? TEXT_EXTENSION : BlueprintIO.EXTENSION;
        return name.substring(0, name.length() - extension.length());
    }

    private static Path uniqueFile(Path folder, String base, String extension) {
        Path file = folder.resolve(base + extension);
        for (int i = 2; Files.exists(file); i++) {
            file = folder.resolve(base + " (" + i + ")" + extension);
        }
        return file;
    }

    private static boolean isBlueprintFile(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(BlueprintIO.EXTENSION) || name.endsWith(TEXT_EXTENSION));
    }

    /** Keeps letters (any script), digits, spaces, '-' and '_' so names like "참나무 오두막" stay readable. */
    static String fileName(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            out.appendCodePoint(Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' ? c : '_');
            i += Character.charCount(c);
        }
        String result = out.toString().strip();
        if (result.length() > 64) {
            result = result.substring(0, 64).strip();
        }
        if (result.isEmpty()) {
            return "blueprint";
        }
        // Windows refuses these as file names, with or without an extension.
        if (result.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            return "_" + result;
        }
        return result;
    }
}
