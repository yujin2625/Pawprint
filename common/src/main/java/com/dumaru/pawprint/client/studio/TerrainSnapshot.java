package com.dumaru.pawprint.client.studio;

import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintMeta;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Copies terrain the client already has loaded. Nothing is requested from the server.
 *
 * <p>Surface mode takes, per column, the top block and a few blocks below it (plus anything above, like trees).
 * Staying near the surface also avoids the fake ores that anti-xray plugins send for underground blocks.
 */
public final class TerrainSnapshot {
    /** Too many blocks for a comfortable paste; ask for a smaller area. */
    public static final int MAX_BLOCKS = 4_000_000;

    private TerrainSnapshot() {
    }

    /** A captured snapshot: blocks relative to {@code min}, and the world box it covers. */
    public record Result(Blueprint blueprint, BlockPos min, BoundingBox bounds) {
    }

    public static final class TooLargeException extends Exception {
        public TooLargeException() {
            super("Snapshot too large");
        }
    }

    public static Result surface(ClientLevel level, BlockPos center, int radiusChunks, int depth, @Nullable BlueprintMeta.Origin origin)
            throws TooLargeException {
        Long2ObjectMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        int centerX = center.getX() >> 4;
        int centerZ = center.getZ() >> 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = centerX - radiusChunks; cx <= centerX + radiusChunks; cx++) {
            for (int cz = centerZ - radiusChunks; cz <= centerZ + radiusChunks; cz++) {
                LevelChunk chunk = (LevelChunk) level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue; // Not loaded on this client.
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        int bottom = Math.max(level.getMinY(), top - depth);
                        for (int y = bottom; y <= top; y++) {
                            BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (!state.isAir()) {
                                blocks.put(BlockPos.asLong((cx << 4) + x, y, (cz << 4) + z), state);
                            }
                        }
                    }
                }
                if (blocks.size() > MAX_BLOCKS) {
                    throw new TooLargeException();
                }
            }
        }
        return toResult(blocks, origin);
    }

    public static Result box(ClientLevel level, BoundingBox box, @Nullable BlueprintMeta.Origin origin) throws TooLargeException {
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > MAX_BLOCKS * 4L) {
            throw new TooLargeException();
        }
        Long2ObjectMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (!state.isAir()) {
                        blocks.put(pos.asLong(), state);
                    }
                }
            }
            if (blocks.size() > MAX_BLOCKS) {
                throw new TooLargeException();
            }
        }
        return toResult(blocks, origin);
    }

    private static Result toResult(Long2ObjectMap<BlockState> blocks, @Nullable BlueprintMeta.Origin origin) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long pos : blocks.keySet()) {
            minX = Math.min(minX, BlockPos.getX(pos));
            minY = Math.min(minY, BlockPos.getY(pos));
            minZ = Math.min(minZ, BlockPos.getZ(pos));
            maxX = Math.max(maxX, BlockPos.getX(pos));
            maxY = Math.max(maxY, BlockPos.getY(pos));
            maxZ = Math.max(maxZ, BlockPos.getZ(pos));
        }
        if (blocks.isEmpty()) {
            minX = minY = minZ = maxX = maxY = maxZ = 0;
        }
        Blueprint.Builder builder = Blueprint.builder();
        for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
            long pos = entry.getLongKey();
            builder.put(BlockPos.getX(pos) - minX, BlockPos.getY(pos) - minY, BlockPos.getZ(pos) - minZ, entry.getValue());
        }
        BlockPos min = new BlockPos(minX, minY, minZ);
        if (origin != null) {
            origin.pos = new int[]{minX, minY, minZ};
        }
        return new Result(builder.build("Terrain Snapshot", "", origin), min,
                new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
    }
}
