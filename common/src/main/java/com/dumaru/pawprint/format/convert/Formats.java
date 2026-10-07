package com.dumaru.pawprint.format.convert;

import com.dumaru.pawprint.format.Blueprint;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Other mods' blueprint formats. Reading upgrades block states from older game versions; air in other formats
 * usually just fills the box, so it is ignored on import.
 */
public enum Formats {
    LITEMATIC(".litematic", true),
    SPONGE_SCHEMATIC(".schem", true),
    STRUCTURE(".nbt", true),
    MCEDIT_SCHEMATIC(".schematic", false);

    static final long MAX_NBT_HEAP = 512L << 20;
    private static final long MAX_FILE_BYTES = 128L << 20;

    public final String extension;
    public final boolean canWrite;

    Formats(String extension, boolean canWrite) {
        this.extension = extension;
        this.canWrite = canWrite;
    }

    public static @Nullable Formats forFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (Formats format : values()) {
            if (name.endsWith(format.extension)) {
                return format;
            }
        }
        return null;
    }

    public Blueprint read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("File is larger than " + MAX_FILE_BYTES + " bytes");
        }
        CompoundTag root;
        try (InputStream in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtAccounter.create(MAX_NBT_HEAP));
        }
        String fallbackName = baseName(file);
        try {
            return switch (this) {
                case LITEMATIC -> LitematicFormat.read(root, fallbackName);
                case SPONGE_SCHEMATIC -> SpongeSchematicFormat.read(root, fallbackName);
                case STRUCTURE -> StructureFormat.read(root, fallbackName);
                case MCEDIT_SCHEMATIC -> McEditSchematicFormat.read(root, fallbackName);
            };
        } catch (RuntimeException e) {
            throw new IOException("Not a valid " + extension + " file: " + e.getMessage(), e);
        }
    }

    public void write(Blueprint blueprint, Path file) throws IOException {
        CompoundTag root = switch (this) {
            case LITEMATIC -> LitematicFormat.write(blueprint);
            case SPONGE_SCHEMATIC -> SpongeSchematicFormat.write(blueprint);
            case STRUCTURE -> StructureFormat.write(blueprint);
            case MCEDIT_SCHEMATIC -> throw new IOException("Writing legacy .schematic files is not supported");
        };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, bytes);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());
    }

    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Collects blocks at any coordinates, then shifts them so the blueprint starts at (0, 0, 0). */
    static final class Collector {
        private final Long2ObjectMap<String> blocks = new Long2ObjectOpenHashMap<>();

        void put(int x, int y, int z, String state) {
            blocks.put(BlockPos.asLong(x, y, z), state);
        }

        Blueprint build(String name, String author, String description) throws IOException {
            if (blocks.isEmpty()) {
                throw new IOException("The file contains no blocks");
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (long pos : blocks.keySet()) {
                minX = Math.min(minX, BlockPos.getX(pos));
                minY = Math.min(minY, BlockPos.getY(pos));
                minZ = Math.min(minZ, BlockPos.getZ(pos));
            }
            Blueprint.Builder builder = Blueprint.builder();
            for (Long2ObjectMap.Entry<String> entry : blocks.long2ObjectEntrySet()) {
                long pos = entry.getLongKey();
                builder.put(BlockPos.getX(pos) - minX, BlockPos.getY(pos) - minY, BlockPos.getZ(pos) - minZ, entry.getValue());
            }
            Blueprint blueprint = builder.build(name, author, null);
            blueprint.meta().description = description;
            return blueprint;
        }
    }

    /** For writers: the state at a position, or null for nothing; removals come back as air. */
    static @Nullable String stateAt(Blueprint blueprint, int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        int index = blueprint.blocks().getOrDefault(pos, -1);
        if (index >= 0) {
            return blueprint.palette().get(index);
        }
        return blueprint.removals().contains(pos) ? "minecraft:air" : null;
    }
}
