package com.smali_generator.utils;

import android.annotation.SuppressLint;
import android.app.Application;

public class Utils {
    private static Application application;

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
}
