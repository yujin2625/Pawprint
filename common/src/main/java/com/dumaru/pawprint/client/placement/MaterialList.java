package com.dumaru.pawprint.client.placement;

import com.dumaru.pawprint.format.Blueprint;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
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

    /** Tab-separated text for spreadsheets or chat: item ID, name, remaining, have, total. */
    public static String toText(Result result) {
        StringBuilder text = new StringBuilder("item\tname\tremaining\thave\ttotal\n");
        for (Line line : result.lines()) {
            text.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(line.item())).append('\t')
                    .append(line.item().getDescription().getString()).append('\t')
                    .append(line.remaining()).append('\t').append(line.have()).append('\t').append(line.total()).append('\n');
        }
        return text.toString();
    }
}
