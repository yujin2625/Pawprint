package com.dumaru.pawprint.client.palette;

import com.dumaru.pawprint.Pawprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every placeable block with its names in all search languages, so a search finds blocks by their Korean name,
 * English name or ID no matter which language the game is set to.
 */
public final class BlockSearchIndex {
    private static @Nullable List<Entry> entries;
    private static @Nullable Map<String, Entry> byId;

    private BlockSearchIndex() {
    }

    /**
     * @param searchText lower-cased ID and names joined, for substring search
     * @param initials   the same text with Hangul syllables reduced to initial consonants
     */
    public record Entry(Block block, ItemStack icon, Identifier id, List<String> names, String searchText,
                        String initials) {
    }

    public static List<Entry> entries() {
        if (entries == null) {
            entries = build();
        }
        return entries;
    }

    /** The searchable text (ID and names, lower-cased) for a block ID, or the ID itself when unknown. */
    public static String searchTextFor(String blockId) {
        if (byId == null) {
            Map<String, Entry> map = new HashMap<>();
            for (Entry entry : entries()) {
                map.put(entry.id().toString(), entry);
            }
            byId = map;
        }
        Entry entry = byId.get(blockId);
        return entry != null ? entry.searchText() : blockId;
    }

    /** Drops the index so it is rebuilt with the current resource packs and language files. */
    public static void invalidate() {
        entries = null;
        byId = null;
    }

    public static List<Entry> search(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (q.isEmpty()) {
            return entries();
        }
        boolean initialsQuery = Hangul.isInitialsQuery(q);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries()) {
            if (entry.searchText().contains(q) || (initialsQuery && entry.initials().contains(q))) {
                result.add(entry);
            }
        }
        return result;
    }

    private static List<Entry> build() {
        long start = System.nanoTime();
        Minecraft minecraft = Minecraft.getInstance();
        List<ClientLanguage> languages = new ArrayList<>();
        for (String code : Pawprint.config().searchLanguages) {
            // Load English first so keys missing in the other language fall back to the English name.
            List<String> files = code.equals("en_us") ? List.of("en_us") : List.of("en_us", code);
            languages.add(ClientLanguage.loadFrom(minecraft.getResourceManager(), files, false));
        }

        List<Entry> result = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.asItem() == Items.AIR) {
                continue; // Technical blocks such as piston heads have no item.
            }
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            List<String> names = new ArrayList<>();
            for (ClientLanguage language : languages) {
                String name = language.getOrDefault(block.getDescriptionId(), id.getPath());
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
            StringBuilder text = new StringBuilder(id.toString());
            for (String name : names) {
                text.append('|').append(name.toLowerCase(Locale.ROOT).replace(" ", ""));
            }
            String searchText = text.toString();
            result.add(new Entry(block, new ItemStack(block), id, List.copyOf(names), searchText,
                    Hangul.initials(searchText)));
        }
        Pawprint.LOG.debug("Indexed {} blocks in {} ms", result.size(), (System.nanoTime() - start) / 1_000_000);
        return result;
    }
}
