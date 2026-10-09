package com.dumaru.pawprint.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.util.ARGB;
import org.joml.Vector3fc;

import java.util.Arrays;

/**
 * Vertices kept on the CPU and written again into a vertex consumer every frame. Since 26.x the game collects
 * geometry each frame through {@code submitCustomGeometry}; keeping our own copy means ghost blocks are tessellated
 * once per change, not once per frame.
 */
final class GhostGeometry {
    /** Block quads: x y z color u v overlay light nx ny nz. */
    static final class Quads {
        private static final int STRIDE = 11;
        private float[] data = new float[STRIDE * 64];
        private int size;
        private float tintR = 1, tintG = 1, tintB = 1, alpha = 1;
        private boolean fullBright;
        private float scale = 1;

        /** Color and alpha multiplied into the next quads; full brightness ignores the world light. */
        Quads tint(float r, float g, float b, float a, boolean fullBright) {
            this.tintR = r;
            this.tintG = g;
            this.tintB = b;
            this.alpha = a;
            this.fullBright = fullBright;
            return this;
        }

        /** Grows the next blocks around their center (wrong-state blocks are drawn slightly larger). */
        Quads scale(float scale) {
            this.scale = scale;
            return this;
        }

        boolean isEmpty() {
            return size == 0;
        }

        /** Takes block quads from {@code ModelBlockRenderer.tesselateBlock}; x/y/z is the block's local origin. */
        BlockQuadOutput output() {
            return (x, y, z, quad, instance) -> put(x, y, z, quad, instance);
        }

        private void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            Vector3fc normal = quad.direction().getUnitVec3f();
            int emission = quad.materialInfo().lightEmission();
            for (int v = 0; v < 4; v++) {
                Vector3fc p = quad.position(v);
                float px = p.x(), py = p.y(), pz = p.z();
                if (scale != 1) {
                    px = 0.5f + (px - 0.5f) * scale;
                    py = 0.5f + (py - 0.5f) * scale;
                    pz = 0.5f + (pz - 0.5f) * scale;
                }
                int c = instance.getColor(v);
                int color = ARGB.color(Math.round(ARGB.alpha(c) * alpha), Math.round(ARGB.red(c) * tintR),
                        Math.round(ARGB.green(c) * tintG), Math.round(ARGB.blue(c) * tintB));
                long uv = quad.packedUV(v);
                int light = fullBright ? 0xF000F0 : instance.getLightCoordsWithEmission(v, emission);
                grow();
                int i = size * STRIDE;
                data[i] = px + x;
                data[i + 1] = py + y;
                data[i + 2] = pz + z;
                data[i + 3] = Float.intBitsToFloat(color);
                data[i + 4] = UVPair.unpackU(uv);
                data[i + 5] = UVPair.unpackV(uv);
                data[i + 6] = Float.intBitsToFloat(instance.overlayCoords());
                data[i + 7] = Float.intBitsToFloat(light);
                data[i + 8] = normal.x();
                data[i + 9] = normal.y();
                data[i + 10] = normal.z();
                size++;
            }
        }

        private void grow() {
            if ((size + 1) * STRIDE > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
        }

        void replay(PoseStack.Pose pose, VertexConsumer out) {
            for (int v = 0; v < size; v++) {
                int i = v * STRIDE;
                out.addVertex(pose, data[i], data[i + 1], data[i + 2])
                        .setColor(Float.floatToRawIntBits(data[i + 3]))
                        .setUv(data[i + 4], data[i + 5])
                        .setOverlay(Float.floatToRawIntBits(data[i + 6]))
                        .setLight(Float.floatToRawIntBits(data[i + 7]))
                        .setNormal(pose, data[i + 8], data[i + 9], data[i + 10]);
            }
        }
    }

    /** Colored quads and lines without textures: x y z color (quads) or x y z color nx ny nz width (lines). */
    static final class Shapes {
        private float[] quads = new float[4 * 64];
        private int quadVertices;
        private float[] lines = new float[8 * 64];
        private int lineVertices;

        boolean hasQuads() {
            return quadVertices > 0;
        }

        boolean hasLines() {
            return lineVertices > 0;
        }

        /** A filled box (all six faces). */
        void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            float[][] corners = {
                    {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
                    {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0}};
            for (int[] face : faces) {
                for (int corner : face) {
                    if ((quadVertices + 1) * 4 > quads.length) {
                        quads = Arrays.copyOf(quads, quads.length * 2);
                    }
                    int i = quadVertices++ * 4;
                    quads[i] = corners[corner][0];
                    quads[i + 1] = corners[corner][1];
                    quads[i + 2] = corners[corner][2];
                    quads[i + 3] = Float.intBitsToFloat(color);
                }
            }
        }

        /** The twelve edges of a box. */
        void lineBox(float x0, float y0, float z0, float x1, float y1, float z1, int color, float width) {
            line(x0, y0, z0, x1, y0, z0, color, width);
            line(x0, y1, z0, x1, y1, z0, color, width);
            line(x0, y0, z1, x1, y0, z1, color, width);
            line(x0, y1, z1, x1, y1, z1, color, width);
            line(x0, y0, z0, x0, y1, z0, color, width);
            line(x1, y0, z0, x1, y1, z0, color, width);
            line(x0, y0, z1, x0, y1, z1, color, width);
            line(x1, y0, z1, x1, y1, z1, color, width);
            line(x0, y0, z0, x0, y0, z1, color, width);
            line(x1, y0, z0, x1, y0, z1, color, width);
            line(x0, y1, z0, x0, y1, z1, color, width);
            line(x1, y1, z0, x1, y1, z1, color, width);
        }

        private void line(float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
            float nx = bx - ax, ny = by - ay, nz = bz - az;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0) {
                nx /= length;
                ny /= length;
                nz /= length;
            }
            vertex(ax, ay, az, color, nx, ny, nz, width);
            vertex(bx, by, bz, color, nx, ny, nz, width);
        }

        private void vertex(float x, float y, float z, int color, float nx, float ny, float nz, float width) {
            if ((lineVertices + 1) * 8 > lines.length) {
                lines = Arrays.copyOf(lines, lines.length * 2);
            }
            int i = lineVertices++ * 8;
            lines[i] = x;
            lines[i + 1] = y;
            lines[i + 2] = z;
            lines[i + 3] = Float.intBitsToFloat(color);
            lines[i + 4] = nx;
            lines[i + 5] = ny;
            lines[i + 6] = nz;
            lines[i + 7] = width;
        }

        void replayQuads(PoseStack.Pose pose, VertexConsumer out) {
            for (int v = 0; v < quadVertices; v++) {
                int i = v * 4;
                out.addVertex(pose, quads[i], quads[i + 1], quads[i + 2]).setColor(Float.floatToRawIntBits(quads[i + 3]));
            }
        }

        void replayLines(PoseStack.Pose pose, VertexConsumer out) {
            for (int v = 0; v < lineVertices; v++) {
                int i = v * 8;
                out.addVertex(pose, lines[i], lines[i + 1], lines[i + 2])
                        .setColor(Float.floatToRawIntBits(lines[i + 3]))
                        .setNormal(pose, lines[i + 4], lines[i + 5], lines[i + 6])
                        .setLineWidth(lines[i + 7]);
            }
        }
    }

    private GhostGeometry() {
    }

    static int argb(float r, float g, float b, float a) {
        return ARGB.colorFromFloat(a, r, g, b);
    }
}
