package com.smali_generator.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which activity starts are WhatsApp sending somebody to update it.
 *
 * A false positive swallows a start the user asked for and opens the store
 * instead, so every near miss -- another app's listing, another WhatsApp page,
 * a look-alike host -- has to stay false.
 */
public class UpdateLinksTest {

    private static final String VIEW = "android.intent.action.VIEW";
    private static final String STORE = "site.androidmod.store";

    private static boolean update(String data) {
        return UpdateLinks.isWhatsAppUpdate(VIEW, data, null, null);
    }

    @Test
    public void the_play_store_listing_by_market_link() {
        assertTrue(update("market://details?id=com.whatsapp"));
        assertTrue(update("market://details?id=com.whatsapp&referrer=utm_source%3Dexpired"));
    }

    @Test
    public void the_play_store_listing_on_the_web() {
        assertTrue(update("https://play.google.com/store/apps/details?id=com.whatsapp"));
        assertTrue(update("https://play.google.com/store/apps/details?id=com.whatsapp&hl=en"));
        assertTrue(update("https://play.google.com/store/apps/details?hl=en&id=com.whatsapp"));
    }

    @Test
    public void anything_under_the_website_download_path() {
        // What the expiry dialogs and the update banners open on a website build.
        assertTrue(update("https://www.whatsapp.com/android/current/WhatsApp.apk"));
        // The per-account source the self-updater builds from the last digit of the number.
        assertTrue(update("https://www.whatsapp.com/android/7/WhatsApp.apk"));
        assertTrue(update("https://www.whatsapp.com/android"));
        assertTrue(update("https://www.whatsapp.com/android/"));
        assertTrue(update("https://www.whatsapp.com/android?lang=en"));
    }

    @Test
    public void scheme_and_host_are_case_insensitive() {
        assertTrue(update("HTTPS://WWW.WhatsApp.com/android/current/WhatsApp.apk"));
        assertTrue(update("Market://Details?id=com.whatsapp"));
    }

    @Test
    public void a_link_already_addressed_to_an_app_is_still_an_update() {
        // WhatsApp's intent launcher pins a single matching browser or Play
        // Store as the component before starting it.
        assertTrue(UpdateLinks.isWhatsAppUpdate(VIEW, "market://details?id=com.whatsapp",
                "com.android.vending", null));
        assertTrue(UpdateLinks.isWhatsAppUpdate(VIEW, "https://www.whatsapp.com/android/current/WhatsApp.apk",
                null, "com.android.chrome"));
    }

    @Test
    public void other_apps_listings_are_left_alone() {
        assertFalse(update("market://details?id=com.whatsapp.w4b"));
        assertFalse(update("market://details?id=com.garmin.android.apps.connectmobile"));
        assertFalse(update("https://play.google.com/store/apps/details?id=com.whatsapp.w4b"));
        assertFalse(update("https://play.google.com/store/apps/details?id=com.whatsappx"));
        assertFalse(update("https://play.google.com/store/apps/details?xid=com.whatsapp"));
        assertFalse(update("https://play.google.com/store/apps/dev?id=com.whatsapp"));
        assertFalse(update("market://search?q=com.whatsapp"));
    }

    @Test
    public void other_whatsapp_pages_and_look_alike_hosts_are_left_alone() {
        assertFalse(update("https://www.whatsapp.com/androidx"));
        assertFalse(update("https://www.whatsapp.com/download"));
        assertFalse(update("https://www.whatsapp.com/legal/android"));
        assertFalse(update("https://faq.whatsapp.com/android/troubleshooting"));
        assertFalse(update("https://www.whatsapp.com.example.net/android/current/WhatsApp.apk"));
        assertFalse(update("http://www.whatsapp.com/android/current/WhatsApp.apk"));
        assertFalse(update("https://wa.me/15551234567"));
    }

    @Test
    public void only_a_view_is_an_update() {
        String link = "market://details?id=com.whatsapp";
        assertFalse(UpdateLinks.isWhatsAppUpdate("android.intent.action.SEND", link, null, null));
        assertFalse(UpdateLinks.isWhatsAppUpdate("android.intent.action.INSTALL_PACKAGE", link, null, null));
        assertFalse(UpdateLinks.isWhatsAppUpdate(null, link, null, null));
    }

    @Test
    public void no_data_or_no_scheme_is_not_a_link() {
        assertFalse(update(null));
        assertFalse(update(""));
        assertFalse(update("www.whatsapp.com/android/current/WhatsApp.apk"));
    }

    @Test
    public void the_stores_own_intent_passes_through() {
        // StoreIntent.open goes back through the same hook; this is what stops it looping.
        assertFalse(UpdateLinks.isWhatsAppUpdate("site.androidmod.store.action.UPDATE_APP", null, STORE, null));
        assertFalse(UpdateLinks.isWhatsAppUpdate(VIEW, "market://details?id=com.whatsapp", STORE, null));
        assertFalse(UpdateLinks.isWhatsAppUpdate(VIEW, "https://www.whatsapp.com/android/current/WhatsApp.apk",
                null, STORE));
    }
}
