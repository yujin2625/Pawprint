package com.dumaru.pawprint.client.placement;

import com.dumaru.pawprint.format.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A blueprint shown in the world. The origin is where the blueprint's (0, 0, 0) lands; the blueprint is
 * mirrored, then rotated around it, the same way vanilla structure templates are.
 */
public final class Placement {
    private String file;
    private final Blueprint blueprint;
    private BlockPos origin;
    private Rotation rotation;
    private Mirror mirror;

    public Placement(String file, Blueprint blueprint, BlockPos origin, Rotation rotation, Mirror mirror) {
        this.file = file;
        this.blueprint = blueprint;
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
    }

    /** Library-relative path of the blueprint file. */
    public String file() {
        return file;
    }

    void setFile(String file) {
        this.file = file;
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    public BlockPos origin() {
        return origin;
    }

    public Rotation rotation() {
        return rotation;
    }

    public Mirror mirror() {
        return mirror;
    }

    public void move(int dx, int dy, int dz) {
        origin = origin.offset(dx, dy, dz);
    }

    public void moveTo(BlockPos pos) {
        origin = pos.immutable();
    }

    public void rotateClockwise() {
        rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
    }

    public void cycleMirror() {
        mirror = switch (mirror) {
            case NONE -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.NONE;
        };
    }

    /** Same as {@link #toWorld(long)} but packed and without allocating, for transforming whole blueprints. */
    public long toWorldPacked(long packedRelative) {
        int x = BlockPos.getX(packedRelative);
        int y = BlockPos.getY(packedRelative);
        int z = BlockPos.getZ(packedRelative);
        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> {
            }
        }
        int rx = switch (rotation) {
            case NONE -> x;
            case CLOCKWISE_90 -> -z;
            case CLOCKWISE_180 -> -x;
            case COUNTERCLOCKWISE_90 -> z;
        };
        int rz = switch (rotation) {
            case NONE -> z;
            case CLOCKWISE_90 -> x;
            case CLOCKWISE_180 -> -z;
            case COUNTERCLOCKWISE_90 -> -x;
        };
        return BlockPos.asLong(rx + origin.getX(), y + origin.getY(), rz + origin.getZ());
    }

    public BlockPos toWorld(long packedRelative) {
        return StructureTemplate.transform(BlockPos.of(packedRelative), mirror, rotation, BlockPos.ZERO).offset(origin);
    }

    public BlockState toWorld(BlockState state) {
        return state.mirror(mirror).rotate(rotation);
    }

    public BoundingBox worldBounds() {
        BlockPos a = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, BlockPos.ZERO);
        BlockPos b = StructureTemplate.transform(
                new BlockPos(blueprint.sizeX() - 1, blueprint.sizeY() - 1, blueprint.sizeZ() - 1), mirror, rotation, BlockPos.ZERO);
        return BoundingBox.fromCorners(a.offset(origin), b.offset(origin));
    }
}
