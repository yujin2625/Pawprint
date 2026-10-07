package com.dumaru.pawprint.shape;

import net.minecraft.core.BlockPos;

import java.util.function.LongConsumer;

/**
 * Shapes defined by two points. Used by the edit tools and meant to be reused by text blueprint imports.
 * Positions are passed to the consumer packed with {@link BlockPos#asLong}.
 *
 * <ul>
 *   <li>Box shapes use the two points as opposite corners.</li>
 *   <li>Sphere and cylinder use the first point as the center and the second to set the radius
 *       (the cylinder's height runs from the center's Y to the second point's Y).</li>
 * </ul>
 */
public enum Shape {
    SINGLE,
    LINE,
    BOX,
    HOLLOW_BOX,
    WALLS,
    SPHERE,
    CYLINDER,
    /** Edit tool only: marks a box to save as a blueprint; places nothing. Not part of the text format. */
    SELECT;

    /** Upper bound on cells one shape may touch, to keep edits and undo history bounded. */
    public static final long MAX_CELLS = 262_144;

    public String translationKey() {
        return "pawprint.shape." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean needsTwoPoints() {
        return this != SINGLE;
    }

    /** Cells the shape would touch, computed without generating them. */
    public long estimate(BlockPos a, BlockPos b) {
        return switch (this) {
            case SINGLE -> 1;
            case LINE -> Math.max(Math.abs(b.getX() - a.getX()), Math.max(Math.abs(b.getY() - a.getY()), Math.abs(b.getZ() - a.getZ()))) + 1L;
            case BOX, HOLLOW_BOX, WALLS, SELECT -> (long) span(a.getX(), b.getX()) * span(a.getY(), b.getY()) * span(a.getZ(), b.getZ());
            case SPHERE -> {
                long d = 2L * sphereRadius(a, b) + 1;
                yield d * d * d;
            }
            case CYLINDER -> {
                long d = 2L * horizontalRadius(a, b) + 1;
                yield d * d * span(a.getY(), b.getY());
            }
        };
    }

    public void forEach(BlockPos a, BlockPos b, LongConsumer out) {
        switch (this) {
            case SINGLE -> out.accept(a.asLong());
            case LINE -> line(a, b, out);
            case BOX, HOLLOW_BOX, WALLS -> box(a, b, out);
            case SPHERE -> sphere(a, sphereRadius(a, b), out);
            case CYLINDER -> cylinder(a, horizontalRadius(a, b), b.getY(), out);
            case SELECT -> {
            }
        }
    }

    private void box(BlockPos a, BlockPos b, LongConsumer out) {
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    boolean sideX = x == minX || x == maxX;
                    boolean sideZ = z == minZ || z == maxZ;
                    boolean sideY = y == minY || y == maxY;
                    boolean include = switch (this) {
                        case HOLLOW_BOX -> sideX || sideZ || sideY;
                        case WALLS -> sideX || sideZ;
                        default -> true;
                    };
                    if (include) {
                        out.accept(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    /** Steps along the longest axis so the line has no gaps. */
    private static void line(BlockPos a, BlockPos b, LongConsumer out) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps == 0) {
            out.accept(a.asLong());
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            out.accept(BlockPos.asLong(
                    (int) Math.round(a.getX() + dx * t),
                    (int) Math.round(a.getY() + dy * t),
                    (int) Math.round(a.getZ() + dz * t)));
        }
    }

    private static void sphere(BlockPos center, int radius, LongConsumer out) {
        double limit = (radius + 0.5) * (radius + 0.5);
        for (int y = -radius; y <= radius; y++) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    if (x * x + y * y + z * z <= limit) {
                        out.accept(BlockPos.asLong(center.getX() + x, center.getY() + y, center.getZ() + z));
                    }
                }
            }
        }
    }

    private static void cylinder(BlockPos center, int radius, int otherY, LongConsumer out) {
        double limit = (radius + 0.5) * (radius + 0.5);
        int minY = Math.min(center.getY(), otherY);
        int maxY = Math.max(center.getY(), otherY);
        for (int y = minY; y <= maxY; y++) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    if (x * x + z * z <= limit) {
                        out.accept(BlockPos.asLong(center.getX() + x, y, center.getZ() + z));
                    }
                }
            }
        }
    }

    private static int span(int a, int b) {
        return Math.abs(b - a) + 1;
    }

    private static int sphereRadius(BlockPos a, BlockPos b) {
        return (int) Math.round(Math.sqrt(a.distSqr(b)));
    }

    private static int horizontalRadius(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }
}
