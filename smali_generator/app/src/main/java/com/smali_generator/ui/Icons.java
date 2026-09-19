package com.smali_generator.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.InputStream;

/**
 * The patcher's own icons, read out of the module's assets.
 *
 * stitch copies the module's whole {@code assets/} tree into the target APK
 * ({@code patcher.py}), so these arrive as files of WhatsApp's own and
 * {@code context.getAssets()} finds them with no loader in between. That is
 * also why they are namespaced under {@code patcher/}: an asset whose name
 * collides with one of the host's replaces it.
 *
 * The art is a black mask on nothing, so **the tint is not decoration** -- an
 * untinted glyph is invisible against WhatsApp's dark theme. Every ink pixel
 * being greyscale is what makes {@code SRC_IN} lossless here, and is the thing
 * {@code IconsTest} guards: art that arrived in colour would come out a flat
 * silhouette, on a screen nobody looks at twice.
 *
 * A glyph that cannot be loaded comes back null and the row that asked goes out
 * without one -- the same bargain the rest of the injected UI makes. Unlike a
 * compiled-in constant this failure is a runtime one, so the test asserts the
 * files are in the source tree and the patch run is what proves they reached
 * the APK.
 */
public final class Icons {
    private static final String TAG = "PATCH";

    static final String PATCH = "patcher/patch.png";
    static final String GRAPH = "patcher/datagraph.png";

    /**
     * What the art is decoded to, in dp.
     *
     * Comfortably above the largest icon an injected row asks for (32dp, which
     * is {@code InjectedRow}'s own cap): the size is measured off a host row
     * *after* this drawable has been built, and a bitmap decoded too small
     * cannot be sharpened afterwards. The cost of the headroom is a few tens of
     * kilobytes that are released with the screen.
     */
    private static final int DECODE_SIZE_DP = 48;

    /** Said once: a failure here repeats on every bind of every row. */
    private static boolean loggedFailure;

    private Icons() {
    }

    /** The Patcher row's icon, or null if it could not be loaded. */
    public static Drawable patcher(Context context, int color) {
        return glyph(context, PATCH, color);
    }

    /** The group Statistics row's icon, or null if it could not be loaded. */
    public static Drawable statistics(Context context, int color) {
        return glyph(context, GRAPH, color);
    }

    /**
     * Not cached, deliberately.
     *
     * A row is built when a screen opens, which is human-paced; a static
     * Bitmap would instead be held for the life of WhatsApp's process to save
     * a decode nobody is waiting on. The patch is a guest here.
     */
    private static Drawable glyph(Context context, String asset, int color) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream bounds = context.getAssets().open(asset)) {
                BitmapFactory.decodeStream(bounds, null, options);
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize(options.outWidth, options.outHeight,
                    Palette.dp(context, DECODE_SIZE_DP));
            Bitmap bitmap;
            try (InputStream in = context.getAssets().open(asset)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                report(asset + ": decoded to nothing");
                return null;
            }
            // Without this the drawable reports an intrinsic size scaled from
            // the bitmap's nominal density to the screen's, which is a number
            // unrelated to anything here. The rows size the icon themselves;
            // anything else that uses it should see the pixels it actually has.
            bitmap.setDensity(Bitmap.DENSITY_NONE);

            BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
            drawable.setFilterBitmap(true);
            drawable.setTintList(ColorStateList.valueOf(color));
            return drawable;
        } catch (Throwable t) {
            report(asset + ": " + t);
            return null;
        }
    }

    /**
     * The largest power-of-two reduction that still leaves the art at least as
     * big as it will be drawn -- what {@link BitmapFactory} will honour, and
     * the difference between holding 512x512 and a sixteenth of it.
     */
    private static int sampleSize(int width, int height, int wanted) {
        int smallest = Math.min(width, height);
        int sample = 1;
        while (wanted > 0 && smallest / (sample * 2) >= wanted) {
            sample *= 2;
        }
        return sample;
    }

    private static void report(String what) {
        if (loggedFailure) {
            return;
        }
        loggedFailure = true;
        Log.e(TAG, "Icons: " + what + " (logged once)");
    }
}
