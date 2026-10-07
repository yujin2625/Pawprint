package com.dumaru.pawprint.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Two-corner box selection used for capturing blueprints. Marking alternates between the corners.
 */
public final class Selection {
    private static @Nullable BlockPos first;
    private static @Nullable BlockPos second;

    private Selection() {
    }

    /** Marks the next corner. Returns 1 or 2 for the corner that was set. */
    public static int mark(BlockPos pos) {
        if (first == null || second != null) {
            first = pos.immutable();
            second = null;
            return 1;
        }
        second = pos.immutable();
        return 2;
    }

    public static void clear() {
        first = null;
        second = null;
    }

    public static @Nullable BlockPos first() {
        return first;
    }

    public static @Nullable BoundingBox box() {
        if (first == null || second == null) {
            return null;
        }
        return BoundingBox.fromCorners(first, second);
    }
}
