package com.smali_generator.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
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
    private static final int SCHEMA_VERSION = 1;

    /** Message ids known to have been deleted. Read on every row bind. */
    private static final Set<String> deletedIds = ConcurrentHashMap.newKeySet();

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
