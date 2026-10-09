package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.Pawprint;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws item icons the way the inventory shows them into one image (a grid of {@link #CELL}-pixel cells), for block
 * packs: the web editor shows these for blocks the game draws in code (chests, signs, beds…). Must run on the
 * render thread. Icons are drawn a page at a time into an off-screen target and copied into the sheet.
 */
public final class IconRenderer {
    public static final int CELL = 32;
    public static final int COLUMNS = 32;
    private static final int PAGE = COLUMNS * CELL;
    private static final int PER_PAGE = COLUMNS * COLUMNS;
    private static final float FAR = 21000f;

    /** The sheet as PNG and each block ID's cell number. */
    public record Sheet(byte[] png, Map<String, Integer> icons) {
    }

    private IconRenderer() {
    }

    public static @Nullable Sheet render(List<Map.Entry<String, ItemStack>> items) {
        if (items.isEmpty()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int rows = (items.size() + COLUMNS - 1) / COLUMNS;
        Map<String, Integer> icons = new LinkedHashMap<>();
        TextureTarget target = new TextureTarget(PAGE, PAGE, true, Minecraft.ON_OSX);
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try (NativeImage sheet = new NativeImage(PAGE, rows * CELL, true)) {
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, PAGE, PAGE, 0f, 1000f, FAR), VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - FAR);
            RenderSystem.applyModelViewMatrix();
            for (int start = 0; start < items.size(); start += PER_PAGE) {
                int end = Math.min(items.size(), start + PER_PAGE);
                target.setClearColor(0f, 0f, 0f, 0f);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                Lighting.setupFor3DItems();
                GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
                graphics.pose().scale(CELL / 16f, CELL / 16f, 1f);
                for (int i = start; i < end; i++) {
                    int cell = i - start;
                    try {
                        graphics.renderItem(items.get(i).getValue(), (cell % COLUMNS) * 16, (cell / COLUMNS) * 16);
                        icons.put(items.get(i).getKey(), i);
                    } catch (RuntimeException e) {
                        Pawprint.LOG.debug("No icon for {}", items.get(i).getKey(), e);
                    }
                }
                graphics.flush();
                copyPage(target, sheet, start / COLUMNS * CELL, ((end - start + COLUMNS - 1) / COLUMNS) * CELL);
            }
            return new Sheet(sheet.asByteArray(), icons);
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not encode the icon sheet", e);
            return null;
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            minecraft.getMainRenderTarget().bindWrite(true);
            target.destroyBuffers();
        }
    }

    private static void copyPage(TextureTarget target, NativeImage sheet, int top, int height) {
        try (NativeImage page = new NativeImage(PAGE, PAGE, false)) {
            RenderSystem.bindTexture(target.getColorTextureId());
            page.downloadTexture(0, false);
            page.flipY();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < PAGE; x++) {
                    sheet.setPixelRGBA(x, top + y, page.getPixelRGBA(x, y));
                }
            }
        }
    }
}
