package com.dumaru.pawprint.client;

import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintMeta;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Copies blocks the client already knows about into a blueprint. Sends nothing to the server.
 */
public final class Capture {
    public static final long MAX_VOLUME = 256L * 256 * 256;

    private Capture() {
    }

    /** Thrown with a translatable message the UI can show as is. */
    public static final class CaptureException extends Exception {
        private final Component message;

        public CaptureException(Component message) {
            super(message.getString());
            this.message = message;
        }

        public Component message() {
            return message;
        }
    }

    public static Blueprint capture(Level level, BoundingBox box, String name, String author,
                                    @Nullable BlueprintMeta.Origin origin) throws CaptureException {
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > MAX_VOLUME) {
            throw new CaptureException(Component.translatable("pawprint.capture.too_large", volume, MAX_VOLUME));
        }
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    throw new CaptureException(Component.translatable("pawprint.capture.unloaded"));
                }
            }
        }

        Blueprint.Builder builder = Blueprint.builder();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (!state.isAir()) {
                        builder.put(x - box.minX(), y - box.minY(), z - box.minZ(), state);
                    }
                }
            }
        }
        if (builder.isEmpty()) {
            throw new CaptureException(Component.translatable("pawprint.capture.empty"));
        }
        return builder.build(name, author, origin);
    }
}
