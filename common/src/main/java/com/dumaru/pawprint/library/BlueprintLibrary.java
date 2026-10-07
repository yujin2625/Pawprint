package com.dumaru.pawprint.library;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.format.text.TextBlueprintReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Blueprints saved under {@code <game dir>/pawprint/blueprints}. Sub-folders are groups.
 * Files are addressed by their path relative to the library root, with forward slashes.
 */
public final class BlueprintLibrary {
    public static final String TEXT_EXTENSION = ".pawprint.json";
    private static final long MAX_TEXT_BYTES = 32L << 20;

    private BlueprintLibrary() {
    }

    public record Entry(Path file, String relativePath, String group, BlueprintMeta meta) {
    }

    public static Path root() {
        return Pawprint.dataDir().resolve("blueprints");
    }

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
                    String relative = relativize(file);
                    int slash = relative.lastIndexOf('/');
                    String group = slash < 0 ? "" : relative.substring(0, slash);
                    entries.add(new Entry(file, relative, group, readMeta(file)));
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

    /** Saves a new blueprint in the library root, named after the blueprint. Returns the written file. */
    public static Path saveNew(Blueprint blueprint) throws IOException {
        Path root = root();
        Files.createDirectories(root);
        String base = fileName(blueprint.meta().name);
        Path file = root.resolve(base + BlueprintIO.EXTENSION);
        for (int i = 2; Files.exists(file); i++) {
            file = root.resolve(base + " (" + i + ")" + BlueprintIO.EXTENSION);
        }
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
        if (result.toUpperCase(java.util.Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            return "_" + result;
        }
        return result;
    }
}
