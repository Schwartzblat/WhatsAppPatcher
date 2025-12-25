package com.smali_generator.utils;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;

public class Utils {
    private static Application application;
    @SuppressLint("StaticFieldLeak")
    private static Context context;

    @SuppressLint("PrivateApi")
    public static Application getApplication() {
        if (application != null) {
            return application;
        }
        try {
            application = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null, (Object[]) null);
            return application;
        } catch (Exception e) {
            return null;
        }
    }

    public static Context getApplicationContext() {
        if (context != null) {
            return context;
        }
        Application app = getApplication();
        if (app != null) {
            context = app.getApplicationContext();
            return context;
        }
        return null;
    }
}
