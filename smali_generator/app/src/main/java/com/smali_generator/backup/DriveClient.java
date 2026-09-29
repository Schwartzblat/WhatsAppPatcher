package com.smali_generator.backup;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Drive v3 over HttpsURLConnection. No client library, by the rule in the plan's
 * global constraints.
 *
 * Every method is total: it logs and returns null or false rather than throwing,
 * because the only caller is a background job whose job is to try again
 * tomorrow.
 */
public final class DriveClient {

    private static final String TAG = "PATCH";
    private static final String FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final int TIMEOUT_MS = 60_000;

    private final Context context;
    private final String token;
    /** Set when a call came back 401, so the caller knows to invalidate and retry once. */
    public boolean unauthorized;

    public DriveClient(Context context, String token) {
        this.context = context;
        this.token = token;
    }

    public static class Entry {
        public final String id;
        public final String name;
        public final long size;

        Entry(String id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }
    }

    /**
     * Drive's q= is a string language, so a name carrying a quote or a backslash
     * would change the query rather than fail it. Backslashes first: doing
     * quotes first would leave a backslash escaping our own escape.
     */
    public static String escapeQueryLiteral(String raw) {
        return raw.replace("\\", "\\\\").replace("'", "\\'");
    }

    public static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            Log.e(TAG, "DriveClient: cannot hash " + file, e);
            return null;
        }
    }

    /**
     * Find a folder by name, or create it. Always resolved rather than cached
     * blindly by the caller: a user who deletes the folder in Drive would
     * otherwise leave a stale id that 404s on every run from then on.
     */
    public String folderId(String name, String parentId) {
        String q = "mimeType='" + FOLDER_MIME + "' and trashed=false and name='"
                + escapeQueryLiteral(name) + "'"
                + (parentId == null ? "" : " and '" + escapeQueryLiteral(parentId) + "' in parents");
        try {
            String body = get(FILES + "?q=" + URLEncoder.encode(q, "UTF-8")
                    + "&fields=" + URLEncoder.encode("files(id,name)", "UTF-8"));
            String existing = firstJsonValue(body, "id");
            if (existing != null) {
                return existing;
            }
            String metadata = "{\"name\":\"" + name + "\",\"mimeType\":\"" + FOLDER_MIME + "\""
                    + (parentId == null ? "" : ",\"parents\":[\"" + parentId + "\"]") + "}";
            return firstJsonValue(postJson(FILES, metadata), "id");
        } catch (Exception e) {
            Log.e(TAG, "DriveClient: folderId(" + name + ") failed", e);
            return null;
        }
    }

    public List<Entry> children(String parentId) {
        List<Entry> out = new ArrayList<>();
        try {
            String q = "'" + escapeQueryLiteral(parentId) + "' in parents and trashed=false";
            String body = get(FILES + "?q=" + URLEncoder.encode(q, "UTF-8")
                    + "&pageSize=1000&fields=" + URLEncoder.encode("files(id,name,size)", "UTF-8"));
            if (body == null) {
                return out;
            }
            for (String chunk : body.split("\\{")) {
                String id = firstJsonValue(chunk, "id");
                String name = firstJsonValue(chunk, "name");
                if (id != null && name != null) {
                    String size = firstJsonValue(chunk, "size");
                    out.add(new Entry(id, name, size == null ? 0L : Long.parseLong(size)));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "DriveClient: children(" + parentId + ") failed", e);
        }
        return out;
    }

    /** Resumable upload. Returns the new file's id, or null. */
    public String upload(String parentId, String name, String mime, File file) {
        try {
            String session = startSession(parentId, name, mime, file.length());
            if (session == null) {
                return null;
            }
            long total = file.length();
            long start = 0L;
            String lastBody = null;
            while (start < total) {
                long length = ChunkPlan.chunkLength(start, total);
                HttpURLConnection c = open(session, "PUT");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(length);
                c.setRequestProperty("Content-Range", ChunkPlan.contentRange(start, length, total));
                try (InputStream in = new FileInputStream(file); OutputStream out = c.getOutputStream()) {
                    skipFully(in, start);
                    copy(in, out, length);
                }
                int status = c.getResponseCode();
                if (ChunkPlan.sessionIsDead(status)) {
                    // Drive forgets a session after about a week. Retrying it
                    // never succeeds, so start the whole upload again.
                    Log.w(TAG, "DriveClient: upload session expired, restarting");
                    c.disconnect();
                    return upload(parentId, name, mime, file);
                }
                if (status == 308) {
                    start = ChunkPlan.nextStart(c.getHeaderField("Range"));
                    c.disconnect();
                    continue;
                }
                if (status == 200 || status == 201) {
                    lastBody = read(c.getInputStream());
                    c.disconnect();
                    break;
                }
                Log.e(TAG, "DriveClient: upload chunk failed, HTTP " + status);
                noteUnauthorized(status);
                c.disconnect();
                return null;
            }
            return firstJsonValue(lastBody, "id");
        } catch (Exception e) {
            Log.e(TAG, "DriveClient: upload(" + name + ") failed", e);
            return null;
        }
    }

    public boolean downloadTo(String fileId, File target) {
        try {
            HttpURLConnection c = open(FILES + "/" + fileId + "?alt=media", "GET");
            int status = c.getResponseCode();
            if (status != 200) {
                Log.e(TAG, "DriveClient: download failed, HTTP " + status);
                noteUnauthorized(status);
                return false;
            }
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                copy(in, out, Long.MAX_VALUE);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "DriveClient: downloadTo failed", e);
            return false;
        }
    }

    public byte[] download(String fileId) {
        try {
            HttpURLConnection c = open(FILES + "/" + fileId + "?alt=media", "GET");
            if (c.getResponseCode() != 200) {
                noteUnauthorized(c.getResponseCode());
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                copy(in, out, Long.MAX_VALUE);
            }
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "DriveClient: download failed", e);
            return null;
        }
    }

    public boolean delete(String fileId) {
        try {
            HttpURLConnection c = open(FILES + "/" + fileId, "DELETE");
            int status = c.getResponseCode();
            noteUnauthorized(status);
            return status == 200 || status == 204;
        } catch (Exception e) {
            Log.e(TAG, "DriveClient: delete failed", e);
            return false;
        }
    }

    private String startSession(String parentId, String name, String mime, long size)
            throws IOException {
        HttpURLConnection c = open(UPLOAD + "?uploadType=resumable", "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("X-Upload-Content-Type", mime);
        c.setRequestProperty("X-Upload-Content-Length", String.valueOf(size));
        String metadata = "{\"name\":\"" + name + "\",\"parents\":[\"" + parentId + "\"]}";
        try (OutputStream out = c.getOutputStream()) {
            out.write(metadata.getBytes("UTF-8"));
        }
        int status = c.getResponseCode();
        if (status != 200) {
            Log.e(TAG, "DriveClient: cannot start upload session, HTTP " + status);
            noteUnauthorized(status);
            return null;
        }
        return c.getHeaderField("Location");
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private String get(String url) throws IOException {
        HttpURLConnection c = open(url, "GET");
        int status = c.getResponseCode();
        if (status != 200) {
            Log.e(TAG, "DriveClient: GET " + status);
            noteUnauthorized(status);
            return null;
        }
        return read(c.getInputStream());
    }

    private String postJson(String url, String json) throws IOException {
        HttpURLConnection c = open(url, "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream out = c.getOutputStream()) {
            out.write(json.getBytes("UTF-8"));
        }
        int status = c.getResponseCode();
        if (status != 200 && status != 201) {
            Log.e(TAG, "DriveClient: POST " + status);
            noteUnauthorized(status);
            return null;
        }
        return read(c.getInputStream());
    }

    private void noteUnauthorized(int status) {
        if (status == 401) {
            unauthorized = true;
        }
    }

    /**
     * Enough JSON for the four fields this uses. Android ships org.json, but the
     * module's unit tests have no Android framework, and a reader small enough
     * to be obviously correct beats a dependency that cannot be tested here.
     */
    private static String firstJsonValue(String body, String key) {
        if (body == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int at = body.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = body.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length()) {
            return null;
        }
        if (body.charAt(i) == '"') {
            int end = body.indexOf('"', i + 1);
            return end < 0 ? null : body.substring(i + 1, end);
        }
        int end = i;
        while (end < body.length() && "0123456789".indexOf(body.charAt(end)) >= 0) {
            end++;
        }
        return end == i ? null : body.substring(i, end);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out, Long.MAX_VALUE);
        return out.toString("UTF-8");
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("file ended while seeking to " + count);
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = limit;
        while (left > 0) {
            int want = (int) Math.min(buffer.length, left);
            int n = in.read(buffer, 0, want);
            if (n < 0) {
                return;
            }
            out.write(buffer, 0, n);
            left -= n;
        }
    }
}
