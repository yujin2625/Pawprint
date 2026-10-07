package com.dumaru.pawprint.client.edit;

import com.dumaru.pawprint.client.AdjustMode;
import com.dumaru.pawprint.client.Freecam;
import com.dumaru.pawprint.client.PawprintKeys;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.client.studio.Studio;
import com.dumaru.pawprint.shape.Shape;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Status lines shown in the top-left corner while edit mode is on.
 */
public final class EditHud {
    private EditHud() {
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        if (Studio.pasteProgress() >= 0) {
            lines.add(Component.translatable("pawprint.studio.pasting", Studio.pasteProgress()).withStyle(ChatFormatting.GOLD));
        }
        if (PlacementManager.layer() != null && PlacementManager.isVisible()) {
            lines.add(Component.translatable("pawprint.hud.layer", PlacementManager.layer()).withStyle(ChatFormatting.GREEN));
        }
        if (AdjustMode.isActive()) {
            lines.add(Component.translatable("pawprint.hud.adjust").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            lines.add(Component.translatable("pawprint.hud.adjust_hint").withStyle(ChatFormatting.GRAY));
        }
        if (Freecam.isActive()) {
            lines.add(Component.translatable("pawprint.hud.freecam", PawprintKeys.MENU.getTranslatedKeyMessage())
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (!EditMode.isActive()) {
            draw(graphics, minecraft, lines);
            return;
        }
        lines.add(Component.translatable("pawprint.hud.title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lines.add(Component.translatable("pawprint.hud.tool", Component.translatable(EditMode.tool().translationKey())));
        lines.add(Component.translatable("pawprint.hud.block", brushName(minecraft)));
        lines.add(Component.translatable("pawprint.hud.draft", Draft.size()));

        BlockPos first = EditMode.firstPoint();
        EditTarget target = EditMode.target();
        if (first != null && target != null) {
            BlockPos second = EditMode.pendingErase() && target.hovered() != null ? target.hovered() : target.placePos();
            lines.add(Component.translatable(EditMode.pendingErase() ? "pawprint.hud.erasing" : "pawprint.hud.filling",
                    Math.abs(second.getX() - first.getX()) + 1,
                    Math.abs(second.getY() - first.getY()) + 1,
                    Math.abs(second.getZ() - first.getZ()) + 1).withStyle(ChatFormatting.YELLOW));
        } else if (EditMode.tool() == Shape.SELECT) {
            lines.add(Component.translatable("pawprint.hud.hint_select").withStyle(ChatFormatting.GRAY));
        } else if (EditMode.tool() == Shape.SINGLE) {
            lines.add(Component.translatable("pawprint.hud.hint_single").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("pawprint.hud.hint_shape").withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("pawprint.hud.hint_keys", PawprintKeys.MENU.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.GRAY));

        draw(graphics, minecraft, lines);
    }

    private static void draw(GuiGraphics graphics, Minecraft minecraft, List<Component> lines) {
        Font font = minecraft.font;
        int y = 4;
        for (Component line : lines) {
            graphics.drawString(font, line, 4, y, 0xFFFFFF, true);
            y += font.lineHeight + 1;
        }
    }

    private static Component brushName(Minecraft minecraft) {
        BlockState brush = EditMode.brush();
        if (brush != null) {
            return brush.getBlock().getName();
        }
        if (minecraft.player.getMainHandItem().getItem() instanceof BlockItem item) {
            return Component.translatable("pawprint.hud.block_in_hand", item.getBlock().getName());
        }
        return Component.translatable("pawprint.hud.no_block").withStyle(ChatFormatting.RED);
    }
}
