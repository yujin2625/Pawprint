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
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        delegate.setColor((int) (r * red), (int) (g * green), (int) (b * blue), (int) (a * alpha));
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        if (fullBright) {
            delegate.setUv2(LightTexture.FULL_BRIGHT & 0xFFFF, LightTexture.FULL_BRIGHT >> 16 & 0xFFFF);
        } else {
            delegate.setUv2(u, v);
        }
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }
}
