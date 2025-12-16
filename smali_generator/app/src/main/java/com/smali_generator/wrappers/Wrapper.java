package com.smali_generator.wrappers;

import android.util.Log;

import java.lang.reflect.Field;

public abstract class Wrapper {
    final String TAG = "PATCH";
    public Object object;
    static void init() {

    }

    Class<?> get_type_class() {
        return null;
    }

    Object get_field(String field_name) {
        try {
            Field field = this.get_type_class().getDeclaredField(field_name);
            field.setAccessible(true);
            return field.get(this.object);
        } catch (Exception e) {
            Log.e(TAG, "E2EMessageParams: get_field " + field_name + " error: " + e.getMessage());
        }
        return null;
    }

    Object get_field(Field field) {
        try {
            return field.get(this.object);
        } catch (Exception e) {
            Log.e(TAG, "E2EMessageParams: get_field " + field.getName() + " error: " + e.getMessage());
        }
        return null;
    }

    public Object get(Field field) {
        return get_field(field);
    }
    public Object get(String field_name) {
        try {
            Field field = this.get_type_class().getDeclaredField(field_name);
            field.setAccessible(true);
            return get_field(field);
        } catch (Exception e) {
            Log.e(TAG, "get_field " + field_name + " error: " + e.getMessage());
            return null;
        }
    }

    public void set(Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(this.object, value);
        } catch (Exception e) {
            Log.e(TAG, "set_field " + field.getName() + " error: " + e.getMessage());
        }
    }

    public void set(String field_name, Object value) {
        try {
            Field field = this.get_type_class().getDeclaredField(field_name);
            field.setAccessible(true);
            set(field, value);
        } catch (Exception e) {
            Log.e(TAG, "set_field " + field_name + " error: " + e.getMessage());
        }
    }

}
