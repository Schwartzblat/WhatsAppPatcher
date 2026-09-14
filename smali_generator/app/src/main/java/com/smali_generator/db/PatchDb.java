package com.smali_generator.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-message facts that outlive the process.
 *
 * Writes happen once per revoke, on the decrypt thread. Reads happen once per
 * row bind, on the UI thread, and hit only the in-memory set -- SQLite is the
 * durable backing and is never on the hot path.
 *
 * Every method is total: on any failure it logs and degrades to "nothing is
 * marked" rather than throwing into a list bind or the message pipeline.
 */
public final class PatchDb {
    private static final String TAG = "PATCH";
    private static final String DB_NAME = "patch_metadata.db";
    private static final int SCHEMA_VERSION = 3;

    /** Message ids known to have been deleted. Read on every row bind. */
    private static final Set<String> deletedIds = ConcurrentHashMap.newKeySet();

    /**
     * The settings table, in full. It holds one row per hook, so reading it
     * whole at init costs nothing and keeps {@link #getFlag} off SQLite -- it
     * is called once per hook during startup and once per row when the
     * settings screen is built.
     */
    private static final Map<String, String> settings = new ConcurrentHashMap<>();

    /**
     * Chats a feature has been pointed at, per feature.
     *
     * Read on the receipt path, so it is held in memory for the same reason
     * the settings map is. A feature with no chats selected has no entry here
     * rather than an empty set; {@link #selectedChats} is what papers over it.
     */
    private static final Map<String, Set<String>> chatSelection = new ConcurrentHashMap<>();

    private static volatile SQLiteDatabase db;

    private PatchDb() {
    }

    public static synchronized void init(Context context) {
        if (db != null) {
            return;
        }
        if (context == null) {
            Log.e(TAG, "PatchDb: no application context, storage disabled");
            return;
        }
        try {
            // Not the databases/ directory: WhatsApp enumerates and backs that up.
            File file = new File(context.getNoBackupFilesDir(), DB_NAME);
            SQLiteDatabase opened = SQLiteDatabase.openOrCreateDatabase(file, null);
            try {
                migrate(opened);
                warmCache(opened);
                warmSettings(opened);
                warmChatSelection(opened);
                db = opened;
                Log.i(TAG, "PatchDb: ready at " + file + ", " + deletedIds.size() + " deleted message(s)");
            } catch (Throwable t) {
                try {
                    opened.close();
                } catch (Throwable closeT) {
                    Log.e(TAG, "PatchDb: close failed after init failure", closeT);
                }
                throw t;
            }
        } catch (Throwable t) {
            Log.e(TAG, "PatchDb: init failed, storage disabled", t);
        }
    }

    /**
     * getVersion()/setVersion() are PRAGMA user_version. Add an if-block per future version.
     *
     * A file newer than this build is left strictly alone: stamping the version
     * back down while the schema on disk is still the newer one would make a
     * later upgrade skip its migration. Throwing here leaves storage disabled,
     * which is the designed degradation.
     */
    private static void migrate(SQLiteDatabase database) {
        int version = database.getVersion();
        if (version > SCHEMA_VERSION) {
            Log.e(TAG, "PatchDb: on-disk schema v" + version + " is newer than v" + SCHEMA_VERSION
                    + ", storage disabled");
            throw new IllegalStateException("schema v" + version + " is newer than this build's v"
                    + SCHEMA_VERSION + ", refusing to downgrade");
        }
        if (version < SCHEMA_VERSION) {
            if (version < 1) {
                database.execSQL("CREATE TABLE IF NOT EXISTS messages ("
                        + "msg_id TEXT NOT NULL,"
                        + "remote_jid TEXT NOT NULL,"
                        + "from_me INTEGER NOT NULL,"
                        + "deleted_at INTEGER,"
                        + "PRIMARY KEY (msg_id, remote_jid, from_me))");
            }
            if (version < 2) {
                database.execSQL("CREATE TABLE IF NOT EXISTS settings ("
                        + "key TEXT NOT NULL PRIMARY KEY,"
                        + "value TEXT NOT NULL)");
            }
            if (version < 3) {
                // Keyed by feature as well as jid so a second per-chat feature
                // needs storage work no more than once.
                database.execSQL("CREATE TABLE IF NOT EXISTS chat_selection ("
                        + "feature TEXT NOT NULL,"
                        + "jid TEXT NOT NULL,"
                        + "PRIMARY KEY (feature, jid))");
            }
            database.setVersion(SCHEMA_VERSION);
        }
    }

    private static void warmCache(SQLiteDatabase database) {
        Cursor cursor = database.rawQuery("SELECT msg_id FROM messages WHERE deleted_at IS NOT NULL", null);
        try {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (id != null) {
                    deletedIds.add(id);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private static void warmSettings(SQLiteDatabase database) {
        Cursor cursor = database.rawQuery("SELECT key, value FROM settings", null);
        try {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String value = cursor.getString(1);
                if (key != null && value != null) {
                    settings.put(key, value);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private static void warmChatSelection(SQLiteDatabase database) {
        Cursor cursor = database.rawQuery("SELECT feature, jid FROM chat_selection", null);
        try {
            while (cursor.moveToNext()) {
                String feature = cursor.getString(0);
                String jid = cursor.getString(1);
                if (feature != null && jid != null) {
                    chatSelection.computeIfAbsent(feature, key -> ConcurrentHashMap.newKeySet()).add(jid);
                }
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * A named on/off flag, or {@code fallback} when nothing has been stored.
     *
     * Answered from the cache alone, so it is also correct before init and
     * after an init failure: an absent flag reads as its fallback, which is
     * how a hook that has never been configured stays at its default.
     */
    public static boolean getFlag(String key, boolean fallback) {
        String value = settings.get(key);
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Stores a flag. The cache is updated even when persistence fails, so the
     * screen that wrote it keeps showing what the user chose for this run.
     */
    public static void setFlag(String key, boolean value) {
        if (key == null) {
            return;
        }
        settings.put(key, Boolean.toString(value));
        SQLiteDatabase database = db;
        if (database == null) {
            Log.e(TAG, "PatchDb: setFlag before init, kept in memory only: " + key);
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("value", Boolean.toString(value));
            database.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            Log.i(TAG, "PatchDb: " + key + " = " + value);
        } catch (Throwable t) {
            Log.e(TAG, "PatchDb: setFlag failed", t);
        }
    }

    /** A named string setting, or {@code fallback} when nothing has been stored. */
    public static String getString(String key, String fallback) {
        String value = settings.get(key);
        return value == null ? fallback : value;
    }

    /** Stores a string setting. Shares the table, and the caching, with {@link #setFlag}. */
    public static void setString(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        settings.put(key, value);
        SQLiteDatabase database = db;
        if (database == null) {
            Log.e(TAG, "PatchDb: setString before init, kept in memory only: " + key);
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("value", value);
            database.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            Log.i(TAG, "PatchDb: " + key + " = " + value);
        } catch (Throwable t) {
            Log.e(TAG, "PatchDb: setString failed", t);
        }
    }

    /** Whether {@code jid} is one of the chats picked for {@code feature}. */
    public static boolean isChatSelected(String feature, String jid) {
        if (feature == null || jid == null) {
            return false;
        }
        Set<String> chats = chatSelection.get(feature);
        return chats != null && chats.contains(jid);
    }

    /** Every chat picked for {@code feature}; empty, never null. */
    public static Set<String> selectedChats(String feature) {
        Set<String> chats = feature == null ? null : chatSelection.get(feature);
        return chats == null ? Collections.emptySet() : chats;
    }

    public static void setChatSelected(String feature, String jid, boolean selected) {
        if (feature == null || jid == null) {
            return;
        }
        Set<String> chats = chatSelection.computeIfAbsent(feature, key -> ConcurrentHashMap.newKeySet());
        if (selected) {
            chats.add(jid);
        } else {
            chats.remove(jid);
        }
        SQLiteDatabase database = db;
        if (database == null) {
            Log.e(TAG, "PatchDb: setChatSelected before init, kept in memory only: " + feature);
            return;
        }
        try {
            if (selected) {
                ContentValues values = new ContentValues();
                values.put("feature", feature);
                values.put("jid", jid);
                database.insertWithOnConflict("chat_selection", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            } else {
                database.delete("chat_selection", "feature = ? AND jid = ?", new String[]{feature, jid});
            }
        } catch (Throwable t) {
            Log.e(TAG, "PatchDb: setChatSelected failed", t);
        }
    }

    /**
     * Unpicks every chat for {@code feature}, and says how many there were.
     *
     * The set is emptied rather than dropped from the map: {@link
     * #selectedChats} hands out the live set, so anything still holding one
     * has to see the same answer this does.
     */
    public static int clearChatSelection(String feature) {
        if (feature == null) {
            return 0;
        }
        Set<String> chats = chatSelection.get(feature);
        int cleared = chats == null ? 0 : chats.size();
        if (chats != null) {
            chats.clear();
        }
        SQLiteDatabase database = db;
        if (database == null) {
            Log.e(TAG, "PatchDb: clearChatSelection before init, cleared in memory only: " + feature);
            return cleared;
        }
        try {
            database.delete("chat_selection", "feature = ?", new String[]{feature});
            Log.i(TAG, "PatchDb: " + feature + ": " + cleared + " chat(s) unpicked");
        } catch (Throwable t) {
            Log.e(TAG, "PatchDb: clearChatSelection failed", t);
        }
        return cleared;
    }

    /**
     * Notified once a message has been marked deleted and is visible to
     * {@link #isDeleted}. Storage stays UI-agnostic: the listener exists so a
     * renderer can refresh itself, and this class neither knows nor cares what
     * one does.
     */
    public interface Listener {
        void onMessageDeleted(String msgId);
    }

    private static volatile Listener listener;

    public static void setListener(Listener newListener) {
        listener = newListener;
    }

    /** Total by contract: a listener must never break the message pipeline. */
    private static void notifyListener(String msgId) {
        Listener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onMessageDeleted(msgId);
        } catch (Throwable t) {
            Log.e(TAG, "PatchDb: listener failed", t);
        }
    }

    public static void markDeleted(MessageKey key, long whenMs) {
        if (key == null || key.id == null) {
            return;
        }
        deletedIds.add(key.id);
        // After the cache, before persistence: the cache is what the render path
        // reads, so a listener firing here already sees the new state. It fires
        // on the storage-failure path below too -- the icon should appear even
        // when only the in-memory set was updated.
        notifyListener(key.id);
        SQLiteDatabase database = db;
        if (database == null) {
            // Init must have failed; this path means the entry stays in the in-memory set only.
            // No backfill is attempted by design: init runs first, before any message can arrive.
            Log.e(TAG, "PatchDb: markDeleted before init, kept in memory only: " + key);
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put("msg_id", key.id);
            values.put("remote_jid", key.remoteJid == null ? "" : key.remoteJid);
            values.put("from_me", key.fromMe ? 1 : 0);
            values.put("deleted_at", whenMs);
            database.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            Log.i(TAG, "PatchDb: marked deleted " + key);
        } catch (Throwable t) {
            Log.e(TAG, "PatchDb: markDeleted failed", t);
        }
    }

    /**
     * Looked up by id alone, though the full key is stored. The jid
     * representation can differ between the protobuf layer that captures and
     * the model layer that renders; ids are random and effectively unique.
     */
    public static boolean isDeleted(String msgId) {
        return msgId != null && deletedIds.contains(msgId);
    }
}
