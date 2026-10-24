package com.smali_generator.utils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Asks Google for a Maps API token the way Play services does. Never throws. */
public final class MapsTokenClient {
    private static final int TIMEOUT_MILLIS = 10000;

    public static final class Result {
        public final String token;
        public final short errorCode;

        Result(String token, short errorCode) { this.token = token; this.errorCode = errorCode; }
    }

    private MapsTokenClient() {}

    private static Result fail(int code) { return new Result(null, (short) code); }

    public static Result fetch(String baseUrl, String apiKey, String packageName, String certSha1,
                               int clientType, long nowMillis) {
        HttpURLConnection c = null;
        try {
            byte[] body = MapsTokenProtocol.request(apiKey, certSha1, packageName,
                    nowMillis + MapsTokenProtocol.VALIDITY_MILLIS);
            c = (HttpURLConnection) new URL(baseUrl + MapsTokenProtocol.PATH).openConnection();
            c.setConnectTimeout(TIMEOUT_MILLIS);
            c.setReadTimeout(TIMEOUT_MILLIS);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-protobuf");
            c.setRequestProperty("X-Goog-Api-Key", MapsTokenProtocol.PLAY_SERVICES_API_KEY);
            c.setRequestProperty("X-Gmm-Client-bin", MapsTokenProtocol.clientHeader(clientType));
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int status = c.getResponseCode();
            if (status >= 500 || status == 408 || status == 429 || (status >= 300 && status < 400)) return fail(5);
            if (status != 200) return fail(4);
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-protobuf")) return fail(5);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            }
            String token = MapsTokenProtocol.token(buf.toByteArray());
            return token == null ? fail(4) : new Result(token, (short) -1);
        } catch (Exception e) {
            return fail(5);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
