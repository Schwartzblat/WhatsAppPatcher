package com.smali_generator.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Puts a trash can on a conversation row, and takes it off again.
 *
 * Two strategies, decided per row because rows differ:
 *   1. a compound drawable on the row's timestamp TextView -- adds no child
 *      and no layout params, so custom row layout and RTL stay out of it;
 *   2. a gravity-positioned foreground on the row itself, when no timestamp
 *      can be found.
 *
 * clear() applies both reversals unconditionally: a recycled view may have
 * been decorated by the other strategy. The inline reversal is exhaustive --
 * it resets every marked TextView in the row, not just the first. The
 * timestamp heuristic can pick a different TextView on a later bind of the
 * same recycled row (a body containing "at 5:30", a media duration "0:12",
 * the clock), so a row can carry more than one mark, and clearing only the
 * first would leave a trash can on a message nobody deleted. decorate()
 * clears before it applies, for the same reason.
 *
 * Both clears short-circuit until this process has actually decorated
 * something, so the common case -- every undeleted row, forever -- costs a
 * boolean test rather than a view-tree walk.
 *
 * Main-thread-only: called from row binding on the UI thread. Plain WeakHashMaps
 * are safe because of this single-threaded guarantee.
 */
public final class RowDecorator {
    private static final String TAG = "PATCH";
    private static final String GLYPH = "🗑";
    /** Heuristic: matches text resembling HH:mm or HH.mm timestamps, but can also match
     *  short non-timestamp durations (e.g. "0:12" from voice notes or "0:05" video clips). */
    private static final Pattern TIME_RE = Pattern.compile(".*\\d{1,2}[:.]\\d{2}.*");
    private static final int MAX_DEPTH = 12;
    private static final int MAX_TIMESTAMP_LENGTH = 12;

    /** Foregrounds we displaced, so clear() restores rather than destroys them. */
    private static final Map<View, Drawable> displacedForegrounds = new WeakHashMap<>();

    /** Start (left) drawables we displaced, so clearInline() restores them. */
    private static final Map<View, Drawable> displacedStartDrawables = new WeakHashMap<>();

    /** Compound drawable paddings we overwrote, restored alongside the drawables. */
    private static final Map<View, Integer> displacedPaddings = new WeakHashMap<>();

    /** Glyph bitmap cache, keyed by pixel size. Holds typically 2 entries
     *  (text size and overlay size), so no eviction policy needed. */
    private static final Map<Integer, Bitmap> glyphCache = new HashMap<>();

    private static boolean loggedInline;
    private static boolean loggedOverlay;

    /** Set once a strategy has been applied. Until then the matching clear is a no-op,
     *  which keeps the hot path (every undeleted row, every bind) allocation-free. */
    private static boolean inlineEverApplied;
    private static boolean overlayEverApplied;

    private RowDecorator() {
    }

    /** Our own inline drawable type, so clearInline() can tell ours from WhatsApp's. */
    private static final class TrashDrawable extends BitmapDrawable {
        TrashDrawable(Resources resources, Bitmap bitmap) {
            super(resources, bitmap);
        }
    }

    /** Our own foreground type, so clearOverlay() can tell ours from WhatsApp's. */
    private static final class TrashForeground extends BitmapDrawable {
        TrashForeground(Resources resources, Bitmap bitmap) {
            super(resources, bitmap);
            setGravity(Gravity.TOP | Gravity.END);
        }
    }

    public static void decorate(View row) {
        if (row == null) {
            return;
        }
        try {
            TextView timestamp = findTimestamp(row, 0);
            if (timestamp != null) {
                clearOverlay(row);
                // Exhaustively, and before applying: the winning TextView within one
                // recycled row can differ from the last bind, so decorating without
                // clearing would leave the previous mark stranded.
                clearInline(row);
                int size = (int) timestamp.getTextSize();
                Drawable[] current = timestamp.getCompoundDrawablesRelative();
                if (!(current[0] instanceof TrashDrawable)) {
                    displacedStartDrawables.put(timestamp, current[0]);
                    displacedPaddings.put(timestamp, timestamp.getCompoundDrawablePadding());
                }
                timestamp.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        new TrashDrawable(timestamp.getResources(), glyph(size)),
                        current[1], current[2], current[3]);
                timestamp.setCompoundDrawablePadding(size / 4);
                inlineEverApplied = true;
                if (!loggedInline) {
                    loggedInline = true;
                    Log.i(TAG, "RowDecorator: inline strategy active");
                }
                return;
            }
            clearInline(row);
            if (!(row.getForeground() instanceof TrashForeground)) {
                displacedForegrounds.put(row, row.getForeground());
                row.setForeground(new TrashForeground(row.getResources(), glyph(overlaySize(row.getContext()))));
            }
            overlayEverApplied = true;
            if (!loggedOverlay) {
                loggedOverlay = true;
                Log.i(TAG, "RowDecorator: no timestamp found, overlay strategy active");
            }
        } catch (Throwable t) {
            Log.e(TAG, "RowDecorator: decorate failed", t);
        }
    }

    public static void clear(View row) {
        if (row == null) {
            return;
        }
        try {
            clearInline(row);
            clearOverlay(row);
        } catch (Throwable t) {
            Log.e(TAG, "RowDecorator: clear failed", t);
        }
    }

    private static void clearInline(View row) {
        if (!inlineEverApplied) {
            return;
        }
        try {
            clearInlineIn(row, 0);
        } catch (Throwable t) {
            Log.e(TAG, "RowDecorator: clearInline failed", t);
        }
    }

    /** Resets *every* TextView we marked, not the first one found: one row can
     *  carry several marks when the timestamp heuristic changes its mind
     *  between binds of the same recycled view. */
    private static void clearInlineIn(View view, int depth) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Drawable[] current = text.getCompoundDrawablesRelative();
            if (current[0] instanceof TrashDrawable) {
                text.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        displacedStartDrawables.remove(text),
                        current[1], current[2], current[3]);
                Integer padding = displacedPaddings.remove(text);
                if (padding != null) {
                    text.setCompoundDrawablePadding(padding);
                }
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                clearInlineIn(group.getChildAt(i), depth + 1);
            }
        }
    }

    private static void clearOverlay(View row) {
        if (!overlayEverApplied) {
            return;
        }
        try {
            if (row.getForeground() instanceof TrashForeground) {
                row.setForeground(displacedForegrounds.remove(row));
            }
        } catch (Throwable t) {
            Log.e(TAG, "RowDecorator: clearOverlay failed", t);
        }
    }

    /** Depth-limited, children last-first: the timestamp sits at the end of the row. */
    private static TextView findTimestamp(View view, int depth) {
        if (view == null || depth > MAX_DEPTH) {
            return null;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0 && text.length() <= MAX_TIMESTAMP_LENGTH
                    && TIME_RE.matcher(text).matches()) {
                return (TextView) view;
            }
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                TextView found = findTimestamp(group.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int overlaySize(Context context) {
        return (int) (16 * context.getResources().getDisplayMetrics().density);
    }

    /**
     * The icon, built in code -- stitch injects dex and lib/ only, so no
     * drawable resource of ours ever reaches the APK. Swap the body to change
     * the icon; android.R.drawable.ic_menu_delete is the tintable alternative.
     * Note a colour emoji is drawn by the system font in its own colours, so
     * it does not take the timestamp's text colour.
     */
    private static synchronized Bitmap glyph(int sizePx) {
        int size = Math.max(8, sizePx);
        if (glyphCache.containsKey(size)) {
            return glyphCache.get(size);
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        Rect bounds = new Rect();
        paint.getTextBounds(GLYPH, 0, GLYPH.length(), bounds);
        int width = Math.max(1, bounds.width() + 2);
        int height = Math.max(1, bounds.height() + 2);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawText(GLYPH, 1 - bounds.left, 1 - bounds.top, paint);
        glyphCache.put(size, bitmap);
        return bitmap;
    }
}
