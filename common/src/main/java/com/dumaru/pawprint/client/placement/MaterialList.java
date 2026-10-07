package com.dumaru.pawprint.client.placement;

import com.dumaru.pawprint.format.Blueprint;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Items needed to build a placement, compared with what is already built and what the player carries.
 * Blocks without an item (fluids, fire) are counted separately.
 */
public final class MaterialList {
    /**
     * @param total     items for the whole blueprint
     * @param remaining items still to place (missing blocks and blocks where something else stands)
     * @param have      items of this kind in the player's inventory
     */
    public record Line(Item item, int total, int remaining, int have) {
    }

    /** @param correct blocks already built as planned, for the progress percentage */
    public record Result(List<Line> lines, int blocks, int correct, int withoutItem) {
        public int percent() {
            return blocks == 0 ? 100 : (int) Math.floor(100.0 * correct / blocks);
        }
    }

    private MaterialList() {
    }

    public static Result compute(Placement placement, Level level, @Nullable Player player) {
        Blueprint blueprint = placement.blueprint();
        Map<Item, int[]> counts = new LinkedHashMap<>();
        int blocks = 0;
        int correct = 0;
        int withoutItem = 0;
        for (Long2IntMap.Entry entry : blueprint.blocks().long2IntEntrySet()) {
            BlockState state = blueprint.state(entry.getIntValue());
            if (state == null) {
                continue;
            }
            BlockState target = placement.toWorld(state);
            BlockPos pos = placement.toWorld(entry.getLongKey());
            BlockStatus status = BlockStatus.compare(target, level.getBlockState(pos));
            blocks++;
            if (status == BlockStatus.CORRECT || status == BlockStatus.WRONG_STATE) {
                correct++;
            }
            int amount = itemsFor(target);
            if (amount == 0) {
                continue; // Second half of a door or bed: the first half's item covers it.
            }
            Item item = target.getBlock().asItem();
            if (item == Items.AIR) {
                withoutItem++;
                continue;
            }
            int[] count = counts.computeIfAbsent(item, key -> new int[2]);
            count[0] += amount;
            if (status == BlockStatus.MISSING || status == BlockStatus.WRONG_BLOCK) {
                count[1] += amount;
            }
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<Item, int[]> entry : counts.entrySet()) {
            int have = player == null ? 0 : player.getInventory().countItem(entry.getKey());
            lines.add(new Line(entry.getKey(), entry.getValue()[0], entry.getValue()[1], have));
        }
        lines.sort(Comparator.comparingInt(Line::remaining).reversed().thenComparing(line -> -line.total()));
        return new Result(lines, blocks, correct, withoutItem);
    }

    /** Items for a whole blueprint that is not placed anywhere: everything counts as still to place. */
    public static Result forBlueprint(Blueprint blueprint, @Nullable Player player) {
        int[] perPaletteEntry = new int[blueprint.palette().size()];
        for (int index : blueprint.blocks().values()) {
            perPaletteEntry[index]++;
        }
        Map<Item, int[]> counts = new LinkedHashMap<>();
        int blocks = 0;
        int withoutItem = 0;
        for (int i = 0; i < perPaletteEntry.length; i++) {
            BlockState state = blueprint.state(i);
            if (state == null || perPaletteEntry[i] == 0) {
                continue;
            }
            blocks += perPaletteEntry[i];
            int amount = itemsFor(state) * perPaletteEntry[i];
            if (amount == 0) {
                continue;
            }
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) {
                withoutItem += perPaletteEntry[i];
                continue;
            }
            counts.computeIfAbsent(item, key -> new int[1])[0] += amount;
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<Item, int[]> entry : counts.entrySet()) {
            int have = player == null ? 0 : player.getInventory().countItem(entry.getKey());
            lines.add(new Line(entry.getKey(), entry.getValue()[0], entry.getValue()[0], have));
        }
        lines.sort(Comparator.comparingInt(Line::total).reversed());
        return new Result(lines, blocks, 0, withoutItem);
    }

    /** How many items one block state takes: 0 for the upper half of doors and the head of beds, 2 for double slabs. */
    static int itemsFor(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return 0;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return 0;
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        return 1;
    }

    /**
     * A shopping list in the game's language, one block per line, for reading in a text editor:
     * how many are needed (with full stacks spelled out), how many the player has and how many are missing.
     *
     * @param placed true for a placement in the world, where only what is still missing counts
     */
    public static String toText(String name, Result result, boolean placed) {
        List<Line> needed = result.lines().stream().filter(line -> (placed ? line.remaining() : line.total()) > 0).toList();
        int totalItems = needed.stream().mapToInt(line -> placed ? line.remaining() : line.total()).sum();
        StringBuilder text = new StringBuilder(Component.translatable(
                placed ? "pawprint.materials.text.header_placed" : "pawprint.materials.text.header",
                name, needed.size(), totalItems).getString()).append('\n');
        if (placed) {
            text.append(Component.translatable("pawprint.placements.progress", result.percent(), result.correct(),
                    result.blocks()).getString()).append('\n');
        }
        text.append('\n');
        for (Line line : needed) {
            int count = placed ? line.remaining() : line.total();
            int missing = Math.max(0, count - line.have());
            Component status = missing == 0
                    ? Component.translatable("pawprint.materials.text.enough", line.have())
                    : Component.translatable("pawprint.materials.text.missing", line.have(), missing);
            text.append(Component.translatable("pawprint.materials.text.line", line.item().getDescription(), id(line),
                    amount(count, line.item().getDefaultMaxStackSize()), status).getString()).append('\n');
        }
        if (needed.isEmpty()) {
            text.append(Component.translatable(placed ? "pawprint.materials.text.done" : "pawprint.materials.none").getString())
                    .append('\n');
        }
        if (result.withoutItem() > 0) {
            text.append('\n').append(Component.translatable("pawprint.placements.without_item", result.withoutItem()).getString())
                    .append('\n');
        }
        return text.toString();
    }

    /** The item's ID, as used in commands such as {@code /give}. */
    public static String id(Line line) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(line.item()).toString();
    }

    /** "150" becomes "150 (2 stacks + 22)" for items that stack. */
    private static String amount(int count, int stackSize) {
        if (stackSize <= 1 || count < stackSize) {
            return Component.translatable("pawprint.materials.text.count", count).getString();
        }
        return Component.translatable("pawprint.materials.text.count_stacks", count, count / stackSize, count % stackSize).getString();
    }
}
