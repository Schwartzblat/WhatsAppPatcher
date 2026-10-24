package com.smali_generator.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Pure certificate SHA-1 rules; no android.* references so it runs on the JVM. */
public final class CertSha1 {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private CertSha1() {}

    public static String hex(byte[] der) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(der);
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                out[i * 2] = HEX[(d[i] >> 4) & 0xF];
                out[i * 2 + 1] = HEX[d[i] & 0xF];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-1 of a certificate given as hex DER (PACKAGE_SIGNATURE's form), or null when not hex. */
    public static String ofHexDer(String hexDer) {
        if (hexDer == null || hexDer.isEmpty() || hexDer.length() % 2 != 0) return null;
        byte[] der = new byte[hexDer.length() / 2];
        for (int i = 0; i < der.length; i++) {
            int hi = Character.digit(hexDer.charAt(i * 2), 16);
            int lo = Character.digit(hexDer.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            der[i] = (byte) ((hi << 4) | lo);
        }
        return hex(der);
    }

    public static boolean isSha1Hex(String s) {
        if (s == null || s.length() != 40) return false;
        for (int i = 0; i < 40; i++) {
            if (Character.digit(s.charAt(i), 16) < 0 || s.charAt(i) > 'f') return false;
        }
        return true;
    }

    public static boolean differs(String original, String installed) {
        return isSha1Hex(original) && isSha1Hex(installed) && !original.equalsIgnoreCase(installed);
    }
}
