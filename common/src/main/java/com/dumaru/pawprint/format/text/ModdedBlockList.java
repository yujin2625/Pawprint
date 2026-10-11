package com.dumaru.pawprint.format.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** A block ID and whether the block has an item of its own. */
    public record Block(String id, boolean item) {
    }

    /**
     * Groups the IDs by namespace, leaving vanilla out. Over {@code max}, every namespace gets an equal share (small
     * ones keep all their blocks), filled with blocks that have an item first; within each kind the kept IDs are
     * spread over the whole list, so a list sorted by name does not lose everything after "d".
     */
    public static Listing of(List<Block> blocks, int max) {
        Map<String, List<String>> withItem = new TreeMap<>();
        Map<String, List<String>> without = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (Block block : blocks) {
            if (!seen.add(block.id())) {
                continue;
            }
            int colon = block.id().indexOf(':');
            String namespace = colon < 0 ? "minecraft" : block.id().substring(0, colon);
            if (namespace.equals("minecraft")) {
                continue;
            }
            withItem.computeIfAbsent(namespace, k -> new ArrayList<>());
            without.computeIfAbsent(namespace, k -> new ArrayList<>());
            (block.item() ? withItem : without).get(namespace).add(block.id().substring(colon + 1));
        }
        // Equal shares: namespaces smaller than the share give their leftover to the rest.
        Map<String, Integer> share = new TreeMap<>();
        List<String> bySize = new ArrayList<>(withItem.keySet());
        bySize.sort(Comparator.comparingInt(ns -> withItem.get(ns).size() + without.get(ns).size()));
        int left = max;
        for (int i = 0; i < bySize.size(); i++) {
            String ns = bySize.get(i);
            int take = Math.min(withItem.get(ns).size() + without.get(ns).size(), left / (bySize.size() - i));
            share.put(ns, take);
            left -= take;
        }
        List<String> lines = new ArrayList<>();
        int listed = 0;
        int total = 0;
        for (String ns : withItem.keySet()) {
            int size = withItem.get(ns).size() + without.get(ns).size();
            List<String> kept = spread(withItem.get(ns), share.get(ns));
            kept.addAll(spread(without.get(ns), share.get(ns) - kept.size()));
            kept.sort(null);
            total += size;
            listed += kept.size();
            if (kept.isEmpty()) {
                continue;
            }
            int more = size - kept.size();
            lines.add(ns + ": " + String.join(", ", kept) + (more > 0 ? " (+" + more + " more not listed)" : ""));
        }
        return new Listing(lines, listed, total);
    }

    /** {@code count} entries spread evenly over the list, in its order (all of them when it is short enough). */
    private static List<String> spread(List<String> list, int count) {
        if (count >= list.size()) {
            return new ArrayList<>(list);
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(list.get((int) ((long) i * list.size() / count)));
        }
        return out;
    }
}
