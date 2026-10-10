package com.dumaru.pawprint.format.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Modded block IDs for the AI instructions, one line per namespace ("create: andesite_casing, brass_block"), so the
 * AI does not have to guess them. Same rules as the web's {@code listModdedBlocks} (docs/AI_BLUEPRINT_FORMAT.md).
 */
public final class ModdedBlockList {
    /** At most this many IDs are listed (about 15k tokens), so the instructions fit any AI chat. */
    public static final int MAX_LISTED = 3000;

    /** The lines, how many IDs they hold and how many modded blocks there are. */
    public record Listing(List<String> lines, int listed, int total) {
    }

    private ModdedBlockList() {
    }

    /**
     * Groups the IDs by namespace, leaving vanilla out. Over {@code max}, every namespace gets an equal share (small
     * ones keep all their blocks) and the earliest IDs of each are kept, so callers put the most useful first.
     */
    public static Listing of(List<String> ids, int max) {
        Map<String, List<String>> groups = new TreeMap<>();
        for (String id : new LinkedHashSet<>(ids)) {
            int colon = id.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
            if (!namespace.equals("minecraft")) {
                groups.computeIfAbsent(namespace, k -> new ArrayList<>()).add(id.substring(colon + 1));
            }
        }
        // Equal shares: namespaces smaller than the share give their leftover to the rest.
        Map<String, Integer> share = new TreeMap<>();
        List<Map.Entry<String, List<String>>> bySize = new ArrayList<>(groups.entrySet());
        bySize.sort(Comparator.comparingInt(e -> e.getValue().size()));
        int left = max;
        for (int i = 0; i < bySize.size(); i++) {
            int take = Math.min(bySize.get(i).getValue().size(), left / (bySize.size() - i));
            share.put(bySize.get(i).getKey(), take);
            left -= take;
        }
        List<String> lines = new ArrayList<>();
        int listed = 0;
        int total = 0;
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            List<String> names = group.getValue();
            List<String> kept = new ArrayList<>(names.subList(0, share.get(group.getKey())));
            kept.sort(null);
            total += names.size();
            listed += kept.size();
            if (kept.isEmpty()) {
                continue;
            }
            int more = names.size() - kept.size();
            lines.add(group.getKey() + ": " + String.join(", ", kept) + (more > 0 ? " (+" + more + " more not listed)" : ""));
        }
        return new Listing(lines, listed, total);
    }
}
