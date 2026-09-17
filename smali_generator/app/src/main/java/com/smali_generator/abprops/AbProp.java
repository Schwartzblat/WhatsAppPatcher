package com.smali_generator.abprops;

import java.util.Locale;

/**
 * One of the app's A/B properties: a number, a type, and the value this build
 * ships for it.
 *
 * The app asks for a property by number alone -- 33604 -- so a number is all
 * there is to key on. Nothing in the APK maps one to a name: the names live on
 * the server, and what a property does is learned by changing it and watching.
 */
public final class AbProp {

    /**
     * What an accessor hands back for a property.
     *
     * Four types for five accessors: the string table and the JSON table both
     * hold their defaults as text and are indistinguishable by value class. It
     * does not matter -- an override is keyed by id, and an id only ever
     * reaches one accessor, so whichever one it lands in reads the stored text
     * its own way.
     */
    public enum Type {
        BOOL("bool"),
        INT("int"),
        FLOAT("float"),
        TEXT("text");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        /** The form stored in the database. It is a key, so it must not change. */
        public String label() {
            return label;
        }

        public static Type byLabel(String label) {
            for (Type type : values()) {
                if (type.label.equals(label)) {
                    return type;
                }
            }
            return null;
        }

        /** The type of a value one of the default tables holds, or null for one
         *  this screen has no way to edit. */
        public static Type of(Object value) {
            if (value instanceof Boolean) {
                return BOOL;
            }
            if (value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte) {
                return INT;
            }
            if (value instanceof Float || value instanceof Double) {
                return FLOAT;
            }
            if (value instanceof CharSequence) {
                return TEXT;
            }
            return null;
        }

        /**
         * What the accessor should hand back for this text, or null when the
         * text is not a value of this type.
         *
         * Null is what keeps a half-typed number out of the app: an override
         * that does not parse is not installed, and the accessor answers as it
         * would have.
         */
        public Object parse(String text) {
            if (text == null) {
                return null;
            }
            try {
                switch (this) {
                    case BOOL:
                        if ("true".equalsIgnoreCase(text)) {
                            return Boolean.TRUE;
                        }
                        if ("false".equalsIgnoreCase(text)) {
                            return Boolean.FALSE;
                        }
                        return null;
                    case INT:
                        return Integer.valueOf(text.trim());
                    case FLOAT:
                        return Float.valueOf(text.trim());
                    default:
                        return text;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public final int id;
    public final Type type;

    /** What the build ships, or null for a property seen at runtime that no
     *  default table holds. */
    public final Object defaultValue;

    /**
     * The id and the shipped default, lowercased, for the search box to match.
     *
     * Built once here rather than per keystroke: the search runs over every one
     * of twenty thousand properties on each character typed, and both halves of
     * this are fixed for the life of the screen. What is not fixed -- the live
     * value and any override -- is checked separately and only when this misses.
     */
    public final String haystack;

    public AbProp(int id, Type type, Object defaultValue) {
        this.id = id;
        this.type = type;
        this.defaultValue = defaultValue;
        this.haystack = (id + " " + (defaultValue == null ? "" : defaultValue))
                .toLowerCase(Locale.US);
    }

    public String defaultText() {
        return defaultValue == null ? "unknown" : format(defaultValue);
    }

    /** Values go on one line of a list row, so a long one is cut rather than
     *  allowed to push the id off the screen. */
    public static String format(Object value) {
        if (value == null) {
            return "none";
        }
        String text = String.valueOf(value);
        return text.length() <= 60 ? text : text.substring(0, 59) + "…";
    }
}
