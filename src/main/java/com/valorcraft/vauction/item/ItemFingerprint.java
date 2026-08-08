package com.valorcraft.vauction.item;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 fingerprint предмета. Хешируется КАНОНИЧЕСКОЕ сериализованное содержимое
 * (несжатые NBT-байты, детерминированные для одного и того же ItemStack), а не
 * {@code toString()} и не сжатый gzip (сжатие в принципе детерминировано, но
 * каноническим источником истины считаем сами NBT-байты).
 */
public final class ItemFingerprint {

    public static final int HEX_LENGTH = 64;

    private ItemFingerprint() {}

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен в любой JVM 17
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean matches(String expected, byte[] data) {
        return expected != null && expected.equals(sha256Hex(data));
    }
}