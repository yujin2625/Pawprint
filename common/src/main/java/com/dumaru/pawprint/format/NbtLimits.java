package com.dumaru.pawprint.format;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Reads gzip-compressed NBT with a memory limit, so a crafted file cannot exhaust memory. */
public final class NbtLimits {
    private NbtLimits() {
    }

    public static CompoundTag readCompressed(InputStream in, long maxBytes) throws IOException {
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(new GZIPInputStream(in)))) {
            return NbtIo.read(data, new NbtAccounter(maxBytes));
        }
    }
}
