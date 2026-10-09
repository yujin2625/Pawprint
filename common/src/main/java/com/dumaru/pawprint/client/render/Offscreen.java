package com.dumaru.pawprint.client.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;

/**
 * Renders submitted geometry into an off-screen image the way the game draws GUI items and previews: an orthographic
 * projection in pixels (y up), then a feature render pass per step. The pixels come back asynchronously once the GPU
 * copy finishes. Call on the render thread, between frames (from a client tick or a screen).
 */
final class Offscreen {
    /** One render pass: lighting set up first, then geometry submitted with the pose stack at the image origin. */
    interface Step {
        void submit(PoseStack poseStack, SubmitNodeStorage storage);
    }

    private Offscreen() {
    }

    static CompletableFuture<NativeImage> render(String label, int width, int height, List<Step> steps) {
        Minecraft minecraft = Minecraft.getInstance();
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture color = device.createTexture(() -> "Pawprint " + label, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuTexture depth = device.createTexture(() -> "Pawprint " + label + " depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                GpuFormat.D32_FLOAT, width, height, 1, 1);
        GpuTextureView depthView = device.createTextureView(depth);
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.clearColorAndDepthTextures(color, new Vector4f(0f, 0f, 0f, 0f), depth, 0.0);

        Projection projection = new Projection();
        projection.setupOrtho(-1000f, 1000f, width, height, true);
        ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("Pawprint " + label);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        FeatureRenderDispatcher dispatcher = minecraft.gameRenderer.featureRenderDispatcher();
        try {
            for (Step step : steps) {
                SubmitNodeStorage storage = new SubmitNodeStorage();
                step.submit(new PoseStack(), storage);
                try (FeatureRenderDispatcher.PreparedFrame frame = dispatcher.prepareFrame(storage);
                     RenderPass pass = encoder.createRenderPass(() -> "Pawprint " + label, colorView, Optional.empty(), depthView,
                             OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    FeatureRenderDispatcher.renderAllFeatures(pass, frame);
                }
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }

        CompletableFuture<NativeImage> result = new CompletableFuture<>();
        GpuBuffer pixels = device.createBuffer(() -> "Pawprint " + label + " readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                (long) width * height * 4);
        encoder.copyTextureToBuffer(color, pixels, 0L, () -> {
            try (GpuBufferSlice.MappedView read = pixels.map(true, false)) {
                NativeImage image = new NativeImage(width, height, false);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        // Rows come bottom first.
                        image.setPixelABGR(x, height - y - 1, read.data().getInt((x + y * width) * 4));
                    }
                }
                result.complete(image);
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            } finally {
                pixels.close();
                colorView.close();
                color.close();
                depthView.close();
                depth.close();
                projectionBuffer.close();
            }
        }, 0);
        return result;
    }
}
