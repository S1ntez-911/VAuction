package com.valorcraft.vauction.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Небольшая обёртка над штатной Forge/Minecraft NBT-сериализацией.
 * Формат байт: несжатый детерминированный NBT из {@code NbtIo.write(CompoundTag, DataOutput)}
 * (т.е. бинарный формат NBT из самого Minecraft). Gzip — только для компактного хранения
 * в БД; fingerprint всегда считается по НЕсжатым байтам.
 */
public final class NbtBytes {

    private NbtBytes() {}

    /** Несжатая детерминированная NBT-сериализация CompoundTag. */
    public static byte[] serialize(CompoundTag tag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            NbtIo.write(tag, out);
        }
        return buffer.toByteArray();
    }

    /** Восстановление CompoundTag из несжатых NBT-байт. */
    public static CompoundTag deserialize(byte[] data) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)));
    }

    /** Gzip-сжатие (для blob в БД). */
    public static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(raw);
        }
        return buffer.toByteArray();
    }

    /**
     * Gzip-распаковка с жёстким ограничением объёма результата
     * ({@code maxUncompressedBytes}) — защита от аномально больших blob'ов.
     * Бросает {@link IOException}, если лимит превышен или поток повреждён.
     */
    public static byte[] gunzip(byte[] compressed, int maxUncompressedBytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.min(64 * 1024, Math.max(64, maxUncompressedBytes)));
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                if (read == 0) {
                    continue;
                }
                int seen = out.size();
                if (seen + read > maxUncompressedBytes) {
                    throw new IOException("uncompressed size exceeds limit " + maxUncompressedBytes
                            + " bytes (got at least " + (seen + read) + ")");
                }
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }
    }
}