package com.smali_generator.db;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * The photo WhatsApp already has on file for a jid.
 *
 * WhatsApp keeps a small JPEG per contact at {@code files/Avatars/<raw jid>.j}
 * -- the thumbnail its own lists draw, a couple of kilobytes each -- and this
 * device's own at {@code me.j}. Those are this process's files, so they are
 * read rather than asked for.
 *
 * They are named by the *phone* jid. Of the 160 avatars on the device this was
 * written against, 127 were {@code @s.whatsapp.net} and not one was
 * {@code @lid}, while every sender a group's messages name is a LID -- so a
 * lookup that does not translate through {@link LidJids} finds nothing at all.
 *
 * Most people have no photo: 127 files against 5,778 contacts. A miss is the
 * common case, so it is remembered too -- the screen redraws on every keystroke
 * of its search field, and without that it would stat the filesystem once per
 * row per letter.
 *
 * One instance per screen rather than one per process: the cache then dies with
 * the screen that built it, so a photo changed since is picked up on the next
 * open instead of lasting as long as WhatsApp's process does. Not thread-safe;
 * it is built and read on the main thread as the rows are.
 */
public final class Avatars {
    private static final String TAG = "PATCH";
    private static final String DIRECTORY = "Avatars";
    private static final String SUFFIX = ".j";
    private static final String MINE = "me.j";

    /**
     * How large a decoded photo is kept, on its longest side.
     *
     * Enough for the biggest one drawn -- a 56dp card avatar on a 3x screen --
     * and no more: these are decoded on the main thread while rows are built,
     * and a group can have hundreds of members.
     */
    private static final int MAX_PIXELS = 192;

    /** Roughly twenty photos at {@link #MAX_PIXELS}; a big group is a lot of rows. */
    private static final int CACHE_BYTES = 2 * 1024 * 1024;

    private final Context context;

    private final LruCache<String, Bitmap> photos = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Whom this has already looked for and not found. */
    private final Set<String> missing = new HashSet<>();

    public Avatars(Context context) {
        this.context = context;
    }

    /** This device's own photo, or null. */
    public Bitmap mine() {
        return read(MINE, MINE);
    }

    /**
     * The photo for a jid, or null when WhatsApp has none.
     *
     * Tried on the jid itself and then on the phone jid it translates to,
     * for the same reason {@link ParticipantNames} does it in that order: the
     * table being read is keyed on whichever form wrote the row.
     */
    public Bitmap photo(String rawJid) {
        // A jid is always user@server. The stats reader's ME and UNKNOWN keys
        // are sentinels, not jids, and have no file to go looking for.
        if (rawJid == null || rawJid.indexOf('@') < 0) {
            return null;
        }
        Bitmap photo = read(rawJid, rawJid + SUFFIX);
        if (photo != null) {
            return photo;
        }
        String phone = LidJids.phoneJid(rawJid);
        return phone == null || phone.equals(rawJid) ? null : read(phone, phone + SUFFIX);
    }

    private Bitmap read(String key, String fileName) {
        Bitmap cached = photos.get(key);
        if (cached != null) {
            return cached;
        }
        if (missing.contains(key)) {
            return null;
        }
        Bitmap decoded = decode(new File(new File(context.getFilesDir(), DIRECTORY), fileName));
        if (decoded == null) {
            missing.add(key);
            return null;
        }
        photos.put(key, decoded);
        return decoded;
    }

    private static Bitmap decode(File file) {
        try {
            if (!file.exists()) {
                return null;
            }
            BitmapFactory.Options measure = new BitmapFactory.Options();
            measure.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), measure);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(Math.max(measure.outWidth, measure.outHeight));
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Throwable t) {
            // A half-written or corrupt thumbnail costs one blank disc.
            Log.e(TAG, "Avatars: a profile photo could not be decoded", t);
            return null;
        }
    }

    private static int sampleSize(int longest) {
        int sample = 1;
        while (longest / (sample * 2) >= MAX_PIXELS) {
            sample *= 2;
        }
        return sample;
    }
}
