package com.dumaru.pawprint.client;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.palette.BlockSearchIndex;
import com.dumaru.pawprint.format.text.ModdedBlockList;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Helpers for making blueprints with an AI: the instructions to paste into the chat, and a list of every block
 * in this game so the AI can use modded blocks too. See docs/AI_BLUEPRINT_FORMAT.md.
 */
public final class AiTools {
    /** Clipboard imports larger than this are refused, matching the limit for text files in the library folder. */
    public static final int MAX_CLIPBOARD_CHARS = 32 << 20;

    private static final String PROMPT = """
            You are designing a Minecraft build for the Pawprint mod. Reply with ONE JSON object only, no prose.

            Format "Pawprint Text Blueprint v1":
            - Axes: x = east, y = up, z = south. Integer coordinates. Ranges are inclusive.
            - Top level: {"pawprint": 1, "name": str, "description": str?, "tags": [str]?,
              "palette": {char: block}?, "operations": [op]?, "layers": {"origin": [x,y,z]?, "grid": [[row]]}?}
            - Block strings use /setblock syntax: "minecraft:oak_stairs[facing=east,half=bottom]".
              "air" means the spot must be empty. Only use blocks that exist in %s%s.
            - Operations run in order, then layers; later writes overwrite earlier ones. Shapes:
              single{at}, line{from,to}, box{from,to}, hollow_box{from,to}, walls{from,to},
              sphere{center,radius}, cylinder{base,radius,height}; each has "block" (palette char or block string).
            - Layers: grid = list of layers bottom->top; each layer = list of rows north->south;
              each row = string west->east; one character per block; "." or space = nothing.
              Palette keys are single characters other than "." and space.
            - Use operations for large regular parts and layers for details (doors, windows, decoration).
            - Set facing/half/axis/hinge properties for stairs, doors, logs and slabs so the build looks right
              when its front faces south. Doors and beds need both halves.

            Build request: \
            """;

    private AiTools() {
    }

    /** The instructions, and how many of this game's modded blocks they list. */
    public record Prompt(String text, int listed, int total) {
    }

    /**
     * The instructions with this game's version and installed block mods filled in, listing the modded block IDs
     * (blocks with an item first when they do not all fit) so the AI does not have to guess them.
     */
    public static Prompt prompt() {
        List<String> withItem = new ArrayList<>();
        List<String> withoutItem = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof LiquidBlock) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            (block.asItem() != Items.AIR ? withItem : withoutItem).add(id);
        }
        withItem.addAll(withoutItem);
        ModdedBlockList.Listing blocks = ModdedBlockList.of(withItem, ModdedBlockList.MAX_LISTED);
        String game = "Minecraft " + SharedConstants.getCurrentVersion().name();
        TreeSet<String> mods = new TreeSet<>();
        for (String id : withItem) {
            int colon = id.indexOf(':');
            if (colon > 0 && !id.startsWith("minecraft:")) {
                mods.add(id.substring(0, colon));
            }
        }
        String modText;
        if (mods.isEmpty()) {
            modText = "";
        } else if (blocks.lines().isEmpty()) {
            modText = " and these mods (namespaces): " + String.join(", ", mods)
                    + ". If unsure whether a modded block ID exists, prefer vanilla blocks";
        } else {
            modText = " and these mods (namespaces): " + String.join(", ", mods)
                    + ". Modded blocks are listed at the end; use only those modded IDs"
                    + (blocks.listed() < blocks.total() ? ", and vanilla blocks where none of them fits" : "");
        }
        String text = PROMPT.formatted(game, modText);
        if (!blocks.lines().isEmpty()) {
            String list = "Modded blocks in this game (\"namespace: names\"; write them as \"namespace:name\"). Any vanilla block of "
                    + game + " may be used too:\n" + String.join("\n", blocks.lines()) + "\n\n";
            text = text.replace("Build request: ", list + "Build request: ");
        }
        return new Prompt(text, blocks.listed(), blocks.total());
    }

    /**
     * Writes every block ID with its names (one line per block, tab-separated) to
     * {@code pawprint/block-list.txt}, for attaching to an AI chat. Returns the written file.
     */
    public static Path exportBlockList() throws IOException {
        StringBuilder text = new StringBuilder("# Block ID\tNames (")
                .append(String.join(", ", Pawprint.config().searchLanguages)).append(")\n");
        for (BlockSearchIndex.Entry entry : BlockSearchIndex.entries()) {
            text.append(entry.id());
            for (String name : entry.names()) {
                text.append('\t').append(name);
            }
            text.append('\n');
        }
        Path file = Pawprint.dataDir().resolve("block-list.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
