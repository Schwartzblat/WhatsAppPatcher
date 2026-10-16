package com.smali_generator.utils;

import java.util.Locale;

/**
 * Recognises the links WhatsApp opens to send somebody off to update it.
 *
 * A patched build cannot be updated by the official APK or by the Play Store:
 * either one is signed with a different key, and the install fails. Every
 * "update WhatsApp" button in the app -- the expiry dialogs, the banners, the
 * "this needs a newer version" prompts, about seventeen call sites on
 * 2.26.40.72 -- ends in one of three link forms, so the redirect keys on the
 * link and not on any of those buttons, whose names move every release.
 *
 * Pure string logic on purpose. It runs on every activity start the app makes,
 * and it is the one part of the redirect a JVM test can pin down; android.net.Uri
 * is a stub there, so it parses by hand.
 */
public final class UpdateLinks {

    static final String ACTION_VIEW = "android.intent.action.VIEW";
    static final String WHATSAPP = "com.whatsapp";

    private UpdateLinks() {}

    /**
     * Whether a start is WhatsApp sending the user to update it.
     *
     * @param action the intent's action
     * @param data the intent's data, as {@code Intent.getDataString()}
     * @param targetPackage the intent's package, or null
     * @param targetComponentPackage the package of the intent's component, or null
     */
    public static boolean isWhatsAppUpdate(String action, String data, String targetPackage,
                                           String targetComponentPackage) {
        if (!ACTION_VIEW.equals(action) || data == null) {
            return false;
        }
        // The store's own start has to reach the store, or the redirect would
        // swallow the very intent it sends.
        if (StoreIntent.STORE_PACKAGE.equals(targetPackage)
                || StoreIntent.STORE_PACKAGE.equals(targetComponentPackage)) {
            return false;
        }
        Link link = Link.parse(data);
        if (link == null) {
            return false;
        }
        switch (link.scheme) {
            case "market":
                return link.host.equals("details") && link.path.isEmpty() && WHATSAPP.equals(link.id());
            case "https":
                if (link.host.equals("play.google.com")) {
                    return (link.path.equals("/store/apps/details") || link.path.equals("/store/apps/details/"))
                            && WHATSAPP.equals(link.id());
                }
                // Not the bare host: the FAQ and the legal pages live under
                // other hosts and other paths, and none of them is a download.
                return link.host.equals("www.whatsapp.com")
                        && (link.path.equals("/android") || link.path.startsWith("/android/"));
            default:
                return false;
        }
    }

    /** The parts of a hierarchical link this needs; scheme and host lower-cased, as URIs compare them. */
    private static final class Link {
        final String scheme;
        final String host;
        final String path;
        final String query;

        private Link(String scheme, String host, String path, String query) {
            this.scheme = scheme;
            this.host = host;
            this.path = path;
            this.query = query;
        }

        static Link parse(String data) {
            int hash = data.indexOf('#');
            String link = hash >= 0 ? data.substring(0, hash) : data;
            int separator = link.indexOf("://");
            if (separator <= 0) {
                return null;
            }
            String scheme = link.substring(0, separator).toLowerCase(Locale.ROOT);
            String rest = link.substring(separator + 3);
            int authorityEnd = rest.length();
            for (char end : new char[]{'/', '?'}) {
                int at = rest.indexOf(end);
                if (at >= 0 && at < authorityEnd) {
                    authorityEnd = at;
                }
            }
            String host = rest.substring(0, authorityEnd);
            host = host.substring(host.lastIndexOf('@') + 1);
            int port = host.indexOf(':');
            if (port >= 0) {
                host = host.substring(0, port);
            }
            String tail = rest.substring(authorityEnd);
            int question = tail.indexOf('?');
            String path = question >= 0 ? tail.substring(0, question) : tail;
            String query = question >= 0 ? tail.substring(question + 1) : "";
            return new Link(scheme, host.toLowerCase(Locale.ROOT), path, query);
        }

        /** The first {@code id} parameter, the one Uri.getQueryParameter -- and so the store -- reads. */
        String id() {
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("id=")) {
                    return parameter.substring(3);
                }
            }
            return null;
        }
    }
}
