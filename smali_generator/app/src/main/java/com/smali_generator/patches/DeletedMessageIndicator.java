package com.smali_generator.patches;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.db.PatchDb;
import com.smali_generator.ui.RowDecorator;
import com.smali_generator.wrappers.FMessageKey;

import java.lang.ref.WeakReference;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Marks messages whose "delete for everyone" DecryptProtobuf suppressed.
 *
 * The conversation list is a CursorAdapter, so there is no row builder that
 * receives the FMessage as a parameter -- rows are bound through the framework
 * getView(int, View, ViewGroup). We hook that and annotate its result, so this
 * uses the three-argument hook_function with a backup.
 *
 * The message for a row therefore has to be fetched, not received: the adapter
 * exposes exactly one position -> FMessage accessor (RowBinderFinder pins it),
 * which we resolve once here and invoke per row. Resolving it per row would
 * put a reflective lookup on every scroll frame.
 */
public class DeletedMessageIndicator implements Hook {
    private static final String TAG = "PATCH";

    /**
     * The adapter's position -> FMessage accessor, resolved once in load().
     * Left null when it could not be resolved, in which case the hook is never
     * installed -- rows then render exactly as the app intended.
     */
    private static Method itemMethod;

    /** The render path runs per row: a failure there logs once and stays quiet. */
    private static boolean loggedDecorateFailure;

    private static boolean loggedRefreshFailure;

    /**
     * The adapter most recently asked to render a row, so a revoke arriving
     * while its conversation is open can make it re-bind. Weak: it belongs to an
     * Activity that may go away, and this class outlives it.
     */
    private static volatile WeakReference<Object> adapterRef;

    private static volatile Handler mainHandler;

    /** Empty by design: only its entry point and signature matter. */
    static View get_view_backup(Object thiz, int position, View convertView, ViewGroup parent) {
        return null;
    }

    static View get_view_hook(Object thiz, int position, View convertView, ViewGroup parent) {
        View row = get_view_backup(thiz, position, convertView, parent);
        try {
            // Identity-checked rather than re-wrapped: allocating a WeakReference
            // per row would put garbage on the scroll path for no gain.
            WeakReference<Object> cached = adapterRef;
            if (cached == null || cached.get() != thiz) {
                adapterRef = new WeakReference<>(thiz);
            }
            if (itemMethod == null) {
                return row;
            }
            // idOf, not from: the full key would allocate a jid String per row
            // per frame, and nothing downstream reads anything but the id.
            String id = FMessageKey.idOf(itemMethod.invoke(thiz, position));
            if (id != null && PatchDb.isDeleted(id)) {
                RowDecorator.decorate(row);
            } else {
                // Explicit clear: this view may be a recycled deleted row.
                RowDecorator.clear(row);
            }
        } catch (Throwable t) {
            // Once only: this runs on every bind, and a permanently broken
            // accessor would otherwise flood logcat on every scroll frame.
            if (!loggedDecorateFailure) {
                loggedDecorateFailure = true;
                Log.e(TAG, "DeletedMessageIndicator: decorate failed (logged once)", t);
            }
        }
        return row;
    }

    private static Handler mainHandler() {
        Handler handler = mainHandler;
        if (handler == null) {
            synchronized (DeletedMessageIndicator.class) {
                handler = mainHandler;
                if (handler == null) {
                    handler = new Handler(Looper.getMainLooper());
                    mainHandler = handler;
                }
            }
        }
        return handler;
    }

    private static void logRefreshFailureOnce(Throwable t) {
        if (!loggedRefreshFailure) {
            loggedRefreshFailure = true;
            Log.e(TAG, "DeletedMessageIndicator: refresh failed (logged once)", t);
        }
    }

    /**
     * Ask the conversation last rendered to re-bind its visible rows.
     *
     * Without this, a message already on screen when its revoke arrives keeps
     * its pre-revoke binding: suppressing the delete is precisely why WhatsApp
     * has no reason to re-bind that row, so the icon would not appear until the
     * next bind -- a scroll away and back, or reopening the chat.
     *
     * Safe to call out of band. The adapter extends CursorAdapter, hence
     * BaseAdapter, and overrides neither notifyDataSetChanged nor
     * registerDataSetObserver; WhatsApp itself never calls notifyDataSetChanged,
     * refreshing through changeCursor/onContentChanged instead. So this cannot
     * contend with the app's own refresh path.
     *
     * Runs on the decrypt thread, so the actual notify is posted to the main
     * looper. A no-op when nothing has rendered yet, or when the conversation
     * that rendered has since been collected.
     */
    public static void refreshVisibleRows() {
        try {
            WeakReference<Object> cached = adapterRef;
            Object adapter = cached == null ? null : cached.get();
            if (!(adapter instanceof BaseAdapter)) {
                return;
            }
            final BaseAdapter target = (BaseAdapter) adapter;
            mainHandler().post(() -> {
                try {
                    target.notifyDataSetChanged();
                } catch (Throwable t) {
                    logRefreshFailureOnce(t);
                }
            });
        } catch (Throwable t) {
            logRefreshFailureOnce(t);
        }
    }

    public void load() {
        try {
            Class<?> adapter = Class.forName("{{ROW_BINDER_CLASS_NAME}}");

            Method item = adapter.getDeclaredMethod("{{ROW_ITEM_METHOD_NAME}}", int.class);
            item.setAccessible(true);

            // Loud sanity check on the finder's pick. The accessor must hand us
            // something the key wrapper can read; anything else means the
            // finder resolved a different method and the feature would sit
            // installed and inert. Either direction of assignability is
            // accepted: FMESSAGE_CLASS comes from a different anchor than the
            // adapter's declared return type, and the design notes that one may
            // legitimately be a subtype of the other. An unrelated type is
            // still rejected, which is what this check exists to catch.
            Class<?> fmessageClass = Class.forName("{{FMESSAGE_CLASS}}");
            Class<?> returnType = item.getReturnType();
            if (!fmessageClass.isAssignableFrom(returnType)
                    && !returnType.isAssignableFrom(fmessageClass)) {
                Log.e(TAG, "DeletedMessageIndicator: item accessor "
                        + "{{ROW_ITEM_METHOD_NAME}}{{ROW_ITEM_METHOD_SIG}} returns "
                        + returnType.getName() + ", unrelated to " + fmessageClass.getName()
                        + " -- not hooking");
                return;
            }

            Method replacement = DeletedMessageIndicator.class.getDeclaredMethod(
                    "get_view_hook", Object.class, int.class, View.class, ViewGroup.class);
            Method backup = DeletedMessageIndicator.class.getDeclaredMethod(
                    "get_view_backup", Object.class, int.class, View.class, ViewGroup.class);
            Executable original = ArtHooks.find_function(
                    adapter, "{{ROW_BINDER_METHOD_NAME}}", "{{ROW_BINDER_METHOD_SIG}}");
            if (original == null) {
                Log.e(TAG, "DeletedMessageIndicator: row binder not found");
                return;
            }

            // Only after everything else succeeded: a non-null itemMethod is
            // the hook's signal that decoration is safe to attempt.
            itemMethod = item;
            if (!ArtHooks.hook_function(original, replacement, backup)) {
                itemMethod = null;
                Log.e(TAG, "DeletedMessageIndicator: hook_function refused the row binder");
                return;
            }
            // Registered only after the hook is installed: without it a refresh
            // would re-bind rows that nothing decorates.
            PatchDb.setListener(msgId -> refreshVisibleRows());

            Log.i(TAG, "DeletedMessageIndicator: hooked row binder, item accessor "
                    + item.getName() + " -> " + returnType.getName());
        } catch (Throwable t) {
            itemMethod = null;
            Log.e(TAG, "DeletedMessageIndicator: load failed", t);
        }
    }

    public void unload() {
        Log.i(TAG, "DeletedMessageIndicator: Patch unloaded");
    }
}
