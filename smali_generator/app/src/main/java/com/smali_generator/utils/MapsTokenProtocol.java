package com.smali_generator.utils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The wire format of Google's CreateAndroidApiToken call. No Android classes. */
public final class MapsTokenProtocol {
    public static final String BASE_URL = "https://mapsmobilesdks-pa.googleapis.com";
    public static final String PATH = "/$rpc/google.internal.maps.mapsmobilesdks.v1.MapsMobileSDKsService/CreateAndroidApiToken";
    public static final String PLAY_SERVICES_API_KEY = "AIzaSyDgmW4ZMvNblSXqMOgsbY8uRrTnfR3E7pY";
    public static final long VALIDITY_MILLIS = 432000000L;

    private MapsTokenProtocol() {}

    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    private static void bytes(ByteArrayOutputStream o, int field, byte[] b) {
        varint(o, ((long) field << 3) | 2);
        varint(o, b.length);
        o.write(b, 0, b.length);
    }

    public static byte[] request(String apiKey, String certSha1, String packageName, long expiryMillis) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        bytes(m, 1, apiKey.getBytes(StandardCharsets.UTF_8));
        bytes(m, 2, certSha1.getBytes(StandardCharsets.UTF_8));
        bytes(m, 3, packageName.getBytes(StandardCharsets.UTF_8));
        varint(m, 4 << 3);
        varint(m, expiryMillis);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bytes(o, 1, m.toByteArray());
        return o.toByteArray();
    }

    /** Field 1 of the response, or null when it is missing, empty or malformed. */
    public static String token(byte[] r) {
        if (r == null) return null;
        int[] pos = {0};
        while (pos[0] < r.length) {
            long tag = readVarint(r, pos);
            if (tag < 0) return null;
            int wire = (int) (tag & 7);
            long field = tag >>> 3;
            if (wire == 0) {
                if (readVarint(r, pos) < 0) return null;
            } else if (wire == 1) {
                pos[0] += 8;
            } else if (wire == 5) {
                pos[0] += 4;
            } else if (wire == 2) {
                long len = readVarint(r, pos);
                if (len < 0 || len > r.length - pos[0]) return null;
                if (field == 1) {
                    return len == 0 ? null : new String(r, pos[0], (int) len, StandardCharsets.UTF_8);
                }
                pos[0] += (int) len;
            } else {
                return null;
            }
            if (pos[0] > r.length) return null;
        }
        return null;
    }

    /** Returns -1 on a malformed or truncated varint. */
    private static long readVarint(byte[] b, int[] pos) {
        long v = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            if (pos[0] >= b.length) return -1;
            int x = b[pos[0]++];
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
        }
        return -1;
    }

    public static String clientHeader(int clientType) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, 34 << 3);
        varint(o, clientType);
        bytes(o, 46, new byte[] {0x0a, 0x00});
        return Base64.getEncoder().encodeToString(o.toByteArray());
    }

    /** -1 when the request may be fetched, 1 on a package mismatch, 3 when the API key is missing. */
    public static short check(String requestedPackage, String ownPackage, String apiKey) {
        if (requestedPackage == null || !requestedPackage.equals(ownPackage)) return 1;
        if (apiKey == null || apiKey.isEmpty()) return 3;
        return -1;
    }
}
