package com.dumaru.pawprint.library;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.format.BlueprintMeta;

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
                    entries.add(new Entry(file, relative, group, BlueprintIO.readMeta(file)));
                } catch (IOException e) {
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
        return BlueprintIO.read(resolve(relativePath));
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
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(BlueprintIO.EXTENSION);
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
