package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Draws item icons the way the inventory shows them into one image (a grid of {@link #CELL}-pixel cells), for block
 * packs: the web editor shows these for blocks the game draws in code (chests, signs, beds…). Call on the render
 * thread; the sheet arrives a frame or so later. Each item is its own pass, lit like the GUI does (flat or 3D).
 */
public final class IconRenderer {
    public static final int CELL = 32;
    public static final int COLUMNS = 32;

    /** The sheet as PNG and each block ID's cell number. */
    public record Sheet(byte[] png, Map<String, Integer> icons) {
    }

    private IconRenderer() {
    }

    public static CompletableFuture<@Nullable Sheet> render(List<Map.Entry<String, ItemStack>> items) {
        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Minecraft minecraft = Minecraft.getInstance();
        int rows = (items.size() + COLUMNS - 1) / COLUMNS;
        int width = COLUMNS * CELL;
        int height = rows * CELL;
        Map<String, Integer> icons = new LinkedHashMap<>();
        List<Offscreen.Step> steps = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStackRenderState state = new ItemStackRenderState();
            try {
                minecraft.getItemModelResolver().updateForTopItem(state, items.get(i).getValue(), ItemDisplayContext.GUI, null, null, 0);
            } catch (RuntimeException e) {
                Pawprint.LOG.debug("No icon for {}", items.get(i).getKey(), e);
                continue;
            }
            if (state.isEmpty()) {
                continue;
            }
            icons.put(items.get(i).getKey(), i);
            float left = (i % COLUMNS) * CELL;
            // y points up in the image: the first row is at the top.
            float bottom = height - (i / COLUMNS + 1) * CELL;
            steps.add((poseStack, storage) -> {
                minecraft.gameRenderer.lighting().setupFor(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
                poseStack.translate(left + CELL / 2f, bottom + CELL / 2f, 0f);
                poseStack.scale(CELL, CELL, CELL);
                state.submit(poseStack, storage, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            });
        }
        if (steps.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return Offscreen.render("icons", width, height, steps).thenApply(image -> {
            try (image) {
                java.nio.file.Path temp = java.nio.file.Files.createTempFile("pawprint-icons", ".png");
                try {
                    image.writeToFile(temp);
                    return new Sheet(java.nio.file.Files.readAllBytes(temp), icons);
                } finally {
                    java.nio.file.Files.deleteIfExists(temp);
                }
            } catch (IOException e) {
                Pawprint.LOG.warn("Could not encode the icon sheet", e);
                return null;
            }
        });
    }
}
