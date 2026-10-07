package com.dumaru.pawprint.client.render;

import com.dumaru.pawprint.client.edit.EditMode;
import com.dumaru.pawprint.client.edit.EditTarget;
import com.dumaru.pawprint.shape.Shape;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The shape being drawn: from the first point to wherever the crosshair is now. Cells are cached until the
 * points or the tool change, since the shape is drawn every frame.
 *
 * @param cells empty when the shape is too large to preview block by block; the bounds are still shown
 */
record Preview(Shape shape, BlockPos first, BlockPos second, boolean erase, @Nullable BlockState state,
               LongList cells, BoundingBox bounds) {
    private static final long MAX_PREVIEW_CELLS = 20_000;
    private static @Nullable Preview cached;

    static @Nullable Preview current() {
        BlockPos first = EditMode.firstPoint();
        EditTarget target = EditMode.target();
        if (first == null || target == null) {
            return null;
        }
        boolean erase = EditMode.pendingErase();
        BlockPos second = erase && target.hovered() != null ? target.hovered() : target.placePos();
        Shape shape = EditMode.tool();
        Preview previous = cached;
        if (previous != null && previous.shape == shape && previous.erase == erase
                && previous.first.equals(first) && previous.second.equals(second)
                && Objects.equals(previous.state, EditMode.pendingState())) {
            return previous;
        }
        LongList cells = new LongArrayList();
        if (shape.estimate(first, second) <= MAX_PREVIEW_CELLS) {
            shape.forEach(first, second, cells::add);
        }
        cached = new Preview(shape, first, second, erase, EditMode.pendingState(), cells, bounds(shape, first, second));
        return cached;
    }

    private static BoundingBox bounds(Shape shape, BlockPos first, BlockPos second) {
        if (shape == Shape.SPHERE || shape == Shape.CYLINDER) {
            int radius = (int) Math.round(Math.sqrt(shape == Shape.SPHERE
                    ? first.distSqr(second)
                    : Math.pow(second.getX() - first.getX(), 2) + Math.pow(second.getZ() - first.getZ(), 2)));
            int minY = shape == Shape.SPHERE ? first.getY() - radius : Math.min(first.getY(), second.getY());
            int maxY = shape == Shape.SPHERE ? first.getY() + radius : Math.max(first.getY(), second.getY());
            return new BoundingBox(first.getX() - radius, minY, first.getZ() - radius,
                    first.getX() + radius, maxY, first.getZ() + radius);
        }
        return BoundingBox.fromCorners(first, second);
    }
}
