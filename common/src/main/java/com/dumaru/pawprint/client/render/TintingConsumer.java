package com.dumaru.pawprint.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;

/**
 * Passes vertices through, multiplying their color by a tint and alpha, and optionally forcing full brightness.
 * Lets the vanilla block renderer draw see-through, recolored ghost blocks.
 */
final class TintingConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    private float red = 1f;
    private float green = 1f;
    private float blue = 1f;
    private float alpha = 1f;
    private boolean fullBright;

    TintingConsumer set(VertexConsumer delegate, float red, float green, float blue, float alpha, boolean fullBright) {
        this.delegate = delegate;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        this.fullBright = fullBright;
        return this;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        delegate.color((int) (r * red), (int) (g * green), (int) (b * blue), (int) (a * alpha));
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        if (fullBright) {
            delegate.uv2(LightTexture.FULL_BRIGHT & 0xFFFF, LightTexture.FULL_BRIGHT >> 16 & 0xFFFF);
        } else {
            delegate.uv2(u, v);
        }
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        delegate.endVertex();
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
        delegate.defaultColor(r, g, b, a);
    }

    @Override
    public void unsetDefaultColor() {
        delegate.unsetDefaultColor();
    }
}
