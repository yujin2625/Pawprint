package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.client.palette.BlockSearchIndex;
import com.dumaru.pawprint.client.palette.Hangul;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.dumaru.pawprint.library.LibraryState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Filtering and sorting for the library list.
 *
 * <p>Search words must all match. A plain word matches the name, description, tags, group or the names of blocks
 * used (Korean, English, ID or Korean initials); {@code #word} matches tags only and {@code @word} mods only.
 */
final class LibraryQuery {
    /** Group filter values that are not folders. Folder names cannot start with '*' (it is not file-safe). */
    static final String ALL = "*all";
    static final String FAVORITES = "*favorites";
    static final String RECENT = "*recent";
    static final String ROOT = "*root";

    enum Sort {
        MODIFIED, NAME, CREATED, BLOCKS, SIZE, RECENT;

        String translationKey() {
            return "pawprint.library.sort." + name().toLowerCase(Locale.ROOT);
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private LibraryQuery() {
    }

    static List<BlueprintLibrary.Entry> apply(List<BlueprintLibrary.Entry> all, String search, String group,
                                              Sort sort, boolean ascending) {
        String[] words = search.strip().toLowerCase(Locale.ROOT).split("\\s+");
        List<BlueprintLibrary.Entry> result = new ArrayList<>();
        for (BlueprintLibrary.Entry entry : all) {
            if (inGroup(entry, group) && matchesAll(entry, words)) {
                result.add(entry);
            }
        }
        Comparator<BlueprintLibrary.Entry> order = switch (sort) {
            case MODIFIED -> Comparator.comparing(entry -> entry.meta().modified);
            case CREATED -> Comparator.comparing(entry -> entry.meta().created);
            case NAME -> Comparator.comparing(entry -> entry.meta().name.toLowerCase(Locale.ROOT));
            case BLOCKS -> Comparator.comparingInt(entry -> entry.meta().blockCount);
            case SIZE -> Comparator.comparingLong(entry -> volume(entry.meta()));
            case RECENT -> Comparator.comparingLong(entry -> LibraryState.lastUsed(entry.relativePath()));
        };
        result.sort(ascending ? order : order.reversed());
        return result;
    }

    private static boolean inGroup(BlueprintLibrary.Entry entry, String group) {
        return switch (group) {
            case ALL -> true;
            case FAVORITES -> LibraryState.isFavorite(entry.relativePath());
            case RECENT -> LibraryState.lastUsed(entry.relativePath()) > 0;
            case ROOT -> entry.group().isEmpty();
            // A group includes its sub-groups.
            default -> entry.group().equals(group) || entry.group().startsWith(group + "/");
        };
    }

    private static boolean matchesAll(BlueprintLibrary.Entry entry, String[] words) {
        for (String word : words) {
            if (!word.isEmpty() && !matches(entry, word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(BlueprintLibrary.Entry entry, String word) {
        BlueprintMeta meta = entry.meta();
        if (word.startsWith("#") && word.length() > 1) {
            String tag = word.substring(1);
            return meta.tags.stream().anyMatch(t -> contains(t, tag));
        }
        if (word.startsWith("@") && word.length() > 1) {
            String mod = word.substring(1);
            return meta.mods.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).startsWith(mod));
        }
        if (contains(meta.name, word) || contains(meta.description, word) || contains(entry.group(), word)) {
            return true;
        }
        if (meta.tags.stream().anyMatch(tag -> contains(tag, word))) {
            return true;
        }
        for (String block : meta.blocks) {
            if (contains(BlockSearchIndex.searchTextFor(block), word)) {
                return true;
            }
        }
        return false;
    }

    /** Case-insensitive, ignores spaces, and lets "ㅊㄴㅁ" match "참나무". */
    private static boolean contains(String text, String word) {
        String haystack = text.toLowerCase(Locale.ROOT).replace(" ", "");
        return haystack.contains(word) || (Hangul.isInitialsQuery(word) && Hangul.initials(haystack).contains(word));
    }

    private static long volume(BlueprintMeta meta) {
        return (long) meta.size[0] * meta.size[1] * meta.size[2];
    }
}
