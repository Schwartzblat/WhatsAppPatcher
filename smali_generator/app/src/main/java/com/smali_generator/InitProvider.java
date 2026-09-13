package com.smali_generator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.smali_generator.db.PatchDb;
import com.smali_generator.patches.ActivityHook;
import com.smali_generator.patches.DecryptProtobuf;
import com.smali_generator.patches.DeletedMessageIndicator;
import com.smali_generator.patches.FirebaseParams;
import com.smali_generator.patches.PackageManagerHook;
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
    static Hook[] hooks = {
            new DecryptProtobuf(),
            new PackageManagerHook(),
            new ZipFileHook(),
            new ActivityHook(),
            new FirebaseParams(),
            new WhatsAppPlus(),
            new DeletedMessageIndicator(),
    };

    static AtomicBoolean is_loaded = new AtomicBoolean(false);

    public static void on_load() {
        if (is_loaded.getAndSet(true)) {
            return;
        }

        Log.i("PATCH", "Patch loaded!");
        PatchDb.init(Utils.getApplicationContext());

        for (Class<?> wrapper : wrappers) {
            try {
                wrapper.getDeclaredMethod("init").invoke(null);
            } catch (Throwable t) {
                Log.e("PATCH", "Wrapper " + wrapper.getSimpleName() + " failed", t);
            }
        }

        for (Hook hook : hooks) {
            try {
                hook.load();
            } catch (Throwable t) {
                Log.e("PATCH", "Hook " + hook.getClass().getSimpleName() + " failed", t);
            }
        }
    }
}