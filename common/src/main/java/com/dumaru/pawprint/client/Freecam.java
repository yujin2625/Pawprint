package com.dumaru.pawprint.client;

import com.dumaru.pawprint.Pawprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Detached camera for planning from above or far away. Only the camera moves: the player stands still, so the
 * server sees nothing unusual. Movement keys and mouse look are taken from the player while it is on
 * (see the camera, input and turn mixins).
 */
public final class Freecam {
    private static boolean active;
    private static double x, y, z;
    private static double prevX, prevY, prevZ;
    private static float yaw, pitch;
    private static float forward, strafe;
    private static boolean up, down;

    private Freecam() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void toggle(Minecraft minecraft) {
        if (active) {
            active = false;
            PawprintClient.notify(minecraft, Component.translatable("pawprint.freecam.off"));
            return;
        }
        if (!Pawprint.config().enableFreecam) {
            PawprintClient.notify(minecraft, Component.translatable("pawprint.freecam.disabled"));
            return;
        }
        if (minecraft.player == null) {
            return;
        }
        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        x = prevX = eye.x;
        y = prevY = eye.y;
        z = prevZ = eye.z;
        yaw = minecraft.player.getYRot();
        pitch = minecraft.player.getXRot();
        active = true;
        PawprintClient.notify(minecraft, Component.translatable("pawprint.freecam.on",
                PawprintKeys.TOGGLE_FREECAM.getTranslatedKeyMessage()));
    }

    public static void tick(Minecraft minecraft) {
        if (!active) {
            return;
        }
        if (minecraft.player == null || !minecraft.player.isAlive() || !Pawprint.config().enableFreecam) {
            active = false;
            return;
        }
        prevX = x;
        prevY = y;
        prevZ = z;
        double speed = Pawprint.config().freecamSpeed * (minecraft.options.keySprint.isDown() ? 3 : 1);
        double sin = Mth.sin(yaw * Mth.DEG_TO_RAD);
        double cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
        // Same as Entity.moveRelative: strafe is positive to the left.
        x += (strafe * cos - forward * sin) * speed;
        z += (forward * cos + strafe * sin) * speed;
        y += ((up ? 1 : 0) - (down ? 1 : 0)) * speed;
    }

    /** Called after the keyboard input is read: keeps the movement for the camera and stops the player. */
    public static void captureInput(Input input) {
        if (!active) {
            return;
        }
        forward = input.forwardImpulse;
        strafe = input.leftImpulse;
        up = input.jumping;
        down = input.shiftKeyDown;
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    /** Mouse look, with the same sensitivity factor as {@code Entity.turn}. */
    public static void turn(double yawDelta, double pitchDelta) {
        yaw += (float) yawDelta * 0.15f;
        pitch = Mth.clamp(pitch + (float) pitchDelta * 0.15f, -90f, 90f);
    }

    public static Vec3 position(float partialTick) {
        return new Vec3(Mth.lerp(partialTick, prevX, x), Mth.lerp(partialTick, prevY, y), Mth.lerp(partialTick, prevZ, z));
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static Vec3 look() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    public static Direction facing() {
        return Direction.fromYRot(yaw);
    }
}
