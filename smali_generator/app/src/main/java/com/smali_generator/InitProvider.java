package com.smali_generator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.smali_generator.abprops.AbPropStore;
import com.smali_generator.db.PatchDb;
import com.smali_generator.patches.AbProps;
import com.smali_generator.patches.ActivityHook;
import com.smali_generator.patches.ContactSearchDuplicates;
import com.smali_generator.patches.DecryptProtobuf;
import com.smali_generator.patches.DeletedMessageIndicator;
import com.smali_generator.patches.FirebaseParams;
import com.smali_generator.patches.GroupStats;
import com.smali_generator.patches.MentionEveryone;
import com.smali_generator.patches.MetaAiButton;
import com.smali_generator.patches.PackageManagerHook;
import com.smali_generator.patches.ReadReceipts;
import com.smali_generator.patches.SenderSearch;
import com.smali_generator.patches.SettingsEntryHook;
import com.smali_generator.patches.WhatsAppPlus;
import com.smali_generator.patches.ZipFileHook;
import com.smali_generator.utils.Utils;
import com.smali_generator.wrappers.FMessageKey;

import java.util.concurrent.atomic.AtomicBoolean;


@SuppressWarnings("unused")
public class InitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Log.i("PATCH", "InitProvider: onCreate called");
        on_load();
        return true;
    }

    @Override public Cursor query(@NonNull Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(@NonNull Uri u) { return null; }
    @Override public Uri insert(@NonNull Uri u, ContentValues v) { return null; }
    @Override public int delete(@NonNull Uri u, String s, String[] a) { return 0; }
    @Override public int update(@NonNull Uri u, ContentValues v, String s, String[] a) { return 0; }

    static Class<?>[] wrappers = {
            FMessageKey.class,
    };
    /**
     * Every hook, in load order. Also what the settings screen lists, which is
     * why it is public: a hook added here shows up there with no other change.
     */
    public static final Hook[] hooks = {
            // First, so that the property reads the rest of startup makes are
            // seen. It is off unless switched on, and costs nothing when it is.
            new AbProps(),
            new DecryptProtobuf(),
            new PackageManagerHook(),
            new ZipFileHook(),
            new ActivityHook(),
            new FirebaseParams(),
            new WhatsAppPlus(),
            new DeletedMessageIndicator(),
            new ReadReceipts(),
            new MetaAiButton(),
            new SenderSearch(),
            new ContactSearchDuplicates(),
            new MentionEveryone(),
            new SettingsEntryHook(),
            new GroupStats(),
    };

    static AtomicBoolean is_loaded = new AtomicBoolean(false);

    public static void on_load() {
        if (is_loaded.getAndSet(true)) {
            return;
        }

        Log.i("PATCH", "Patch loaded!");
        PatchDb.init(Utils.getApplicationContext());

        // Before the wrappers and before any hook: this counts the launch as
        // failed until it proves otherwise, so a start that dies inside one of
        // them is a start this notices.
        BootHealth.Mode mode = BootHealth.begin();
        if (mode != BootHealth.Mode.NORMAL) {
            // Before the store is read rather than after, so the overrides are
            // never briefly installed on a launch that is meant to go without
            // them. Held back on both rungs: in safe mode the hook that installs
            // them does not load anyway, but the settings screen reads the same
            // store and has to show what the app is actually seeing.
            AbPropStore.holdBackAll();
        }

        for (Class<?> wrapper : wrappers) {
            try {
                wrapper.getDeclaredMethod("init").invoke(null);
            } catch (Throwable t) {
                Log.e("PATCH", "Wrapper " + wrapper.getSimpleName() + " failed", t);
            }
        }

        for (Hook hook : hooks) {
            try {
                // Safe mode keeps only what the patched app cannot run without
                // -- which includes the settings row, so there is still a way
                // in to turn it back off.
                if (mode == BootHealth.Mode.SAFE && hook.toggleable()) {
                    Log.i("PATCH", "Safe mode: " + hook.getClass().getSimpleName() + " not loaded");
                    continue;
                }
                // Read here rather than inside the hook: a hook that is off is
                // never installed at all, so it costs nothing for the life of
                // the process. That is also why a switch needs a restart.
                if (!hook.isEnabled()) {
                    Log.i("PATCH", "Hook " + hook.getClass().getSimpleName() + " is off, skipping");
                    continue;
                }
                hook.load();
            } catch (Throwable t) {
                Log.e("PATCH", "Hook " + hook.getClass().getSimpleName() + " failed", t);
            }
        }
    }
}