package com.dumaru.pawprint.client.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A placement context for a block that is only drawn, never placed. It lets blocks pick their own orientation
 * (stairs facing, log axis, slab half) exactly as if the player placed them, without touching the world.
 */
final class GhostPlaceContext extends BlockPlaceContext {
    private final BlockPos target;

    GhostPlaceContext(Player player, ItemStack stack, EditTarget hit) {
        super(player.level(), player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(hit.location(), hit.face(), hit.placePos().relative(hit.face().getOpposite()), false));
        this.target = hit.placePos();
    }

    @Override
    public BlockPos getClickedPos() {
        return target;
    }

    @Override
    public boolean canPlace() {
        return true;
    }

    @Override
    public boolean replacingClickedOnBlock() {
        return false;
    }
}
