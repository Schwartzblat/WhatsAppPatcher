package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.abprops.AbProp;
import com.smali_generator.abprops.AbPropStore;
import com.smali_generator.db.PatchDb;
import com.smali_generator.ui.GroupHistoryActivity;

import java.util.Locale;

/**
 * Sends more of a group's history to somebody you add, and accepts more of it.
 *
 * WhatsApp offers the history itself: adding a member puts up a "Send messages"
 * dialog whose radio buttons are "Last 25 / 50 / 75 / 100 (all)". Those numbers
 * are not a list anywhere -- they are arithmetic over how many messages are
 * eligible (up to four buckets plus an "all" entry), so the dialog widens on its
 * own once more messages are eligible. What decides that is two of the app's own
 * A/B properties, read on the sending device:
 *
 *   18405   ships 100        the LIMIT on the query that gathers the messages
 *   18406   ships 1209600    a WHERE on timestamp, applied *after* that LIMIT
 *
 * The order is why both have to move together. The query takes the newest 18405
 * messages and only then drops the ones older than the window, so widening the
 * window alone changes nothing -- the newest hundred are the newest hundred --
 * and raising the count alone hands back rows the window then throws away.
 *
 * So this is a hook with nothing of its own to hook: the whole feature is four
 * numbers answered differently, which is why it rides {@link AbProps}'s funnel
 * rather than redirecting anything itself. That funnel is the app's hottest read
 * path, which is what keeps this off by default.
 *
 * The other two numbers are the same feature seen from the receiving end, and
 * they are the reason this cannot be done from one device alone. A recipient
 * caps what it will take out of a bundle with its own copies:
 *
 *   19811   ships 100        stops inserting at this many, max_limit_reached
 *   21313   ships 1209600    floor is anchor.timestamp - 2 x this, older ones
 *                            dropped as timestamp_too_old
 *
 * Both are read on *their* device, so what a recipient keeps is theirs to
 * decide, and what it drops it drops silently -- the bundle is built, sent, and
 * quietly trimmed on arrival.
 *
 * What they ship is not what they do. 100 and 1209600 are the default tables'
 * values, but measured against a second real account on 2026-09-29 a bundle
 * whose oldest message was 88 days old arrived whole and readable, which needs
 * that account's 21313 to be at least ~44 days -- the server raises these, as it
 * had already raised 18406 to 30 days on the sending side. So the send half
 * alone is what makes this work; the accept half below is a fallback for a peer
 * whose server was less generous, and a lift for history *this* device is sent.
 *
 * Every setting defaults to {@link #LEAVE_ALONE} rather than to the shipped
 * number, and that is not tidiness. Measured on the test account, the server had
 * already set 18406 to 30 days: a screen that defaulted to "14 days" would have
 * been offering to *shorten* the window while claiming to be leaving it alone.
 * The shipped values below are labels of last resort, for a property this
 * account has not been seen to read.
 */
public class GroupHistorySharing implements Hook {

    private static final String TAG = "PATCH";

    /** A setting of this means no override: whatever the app says, including
     *  whatever the server has told it since. */
    public static final int LEAVE_ALONE = 0;

    /** The LIMIT on the sender's gather query, and so the ceiling on what the
     *  dialog can offer. */
    public static final int SEND_MAX_PROP = 18405;

    /** The sender's window, in seconds. Filters what that LIMIT already took. */
    public static final int SEND_WINDOW_PROP = 18406;

    /** How many messages this device will insert out of a bundle it is sent. */
    public static final int ACCEPT_MAX_PROP = 19811;

    /** Half the age this device will accept: the floor is the join message's
     *  timestamp less twice this, in seconds. */
    public static final int ACCEPT_HALF_WINDOW_PROP = 21313;

    /** What 2.26.37.74's default tables hold, for labelling a property nothing
     *  has watched being read. Not a default for any setting here. */
    public static final int SHIPPED_SEND_MAX = 100;
    public static final int SHIPPED_SEND_DAYS = 14;
    public static final int SHIPPED_ACCEPT_MAX = 100;
    public static final int SHIPPED_ACCEPT_DAYS = 28;

    // Settings keys. Database keys, so they do not change.
    public static final String SEND_MAX_KEY = "group_history_send_max";
    public static final String SEND_DAYS_KEY = "group_history_send_days";
    public static final String ACCEPT_MAX_KEY = "group_history_accept_max";
    public static final String ACCEPT_DAYS_KEY = "group_history_accept_days";

    private static final int SECONDS_PER_DAY = 86400;

    /**
     * What the screen offers, leading with "leave it alone".
     *
     * Kept short deliberately: the bundle is one deflated protobuf uploaded as
     * media, and a recipient that will not take it says nothing about why.
     */
    public static final int[] SEND_MAX_CHOICES = {LEAVE_ALONE, 100, 250, 500, 1000, 2500};
    public static final int[] SEND_DAY_CHOICES = {LEAVE_ALONE, 14, 30, 60, 90, 180, 366};
    public static final int[] ACCEPT_MAX_CHOICES = {LEAVE_ALONE, 100, 500, 1000, 2500};

    /** Even numbers of days only: 21313 is half the window the app compares
     *  against, so an odd one would lose half a day in the halving and come back
     *  from {@link #acceptDaysFor} as something else. */
    public static final int[] ACCEPT_DAY_CHOICES = {LEAVE_ALONE, 28, 60, 180, 366};

    private static final String SOURCE = "group history";

    public String id() {
        return "group_history";
    }

    public String title() {
        return "Send more group history";
    }

    public String description() {
        return "More messages, further back, when you add someone to a group.";
    }

    public HookCategory category() {
        return HookCategory.MESSAGES;
    }

    /** Off unless asked for: the values are answered on the app's hottest read
     *  path, and most sessions have no use for a wider history bundle. */
    public boolean defaultEnabled() {
        return false;
    }

    public Class<?> configScreen() {
        return GroupHistoryActivity.class;
    }

    public String configSummary() {
        int max = sendMax();
        int days = sendDays();
        if (max == LEAVE_ALONE && days == LEAVE_ALONE) {
            return "The app's own limits";
        }
        if (days == LEAVE_ALONE) {
            return String.format(Locale.US, "Up to %d messages", max);
        }
        if (max == LEAVE_ALONE) {
            return String.format(Locale.US, "%d days back", days);
        }
        return String.format(Locale.US, "Up to %d messages, %d days back", max, days);
    }

    public void load() {
        publish();
        // Last, so that a read arriving with the redirect already in place finds
        // the values rather than the app's own.
        AbProps.installFunnel();
        if (!AbProps.funnelHooked()) {
            Log.e(TAG, "GroupHistorySharing: the funnel is not hooked, so none of this is in force");
        }
    }

    /**
     * Puts the configured values into the override layer, and takes out the ones
     * set back to leaving the app alone.
     *
     * Also called by the screen, so that editing a value takes effect at the next
     * read rather than at the next launch -- which matters, because the app reads
     * these when the dialog is put up and not at startup. Nothing here throws: it
     * is reached from {@code load()} during startup, and from a click.
     */
    public static void publish() {
        try {
            int sendMax = sendMax();
            int sendDays = sendDays();
            int acceptMax = acceptMax();
            int acceptDays = acceptDays();

            override(SEND_MAX_PROP, sendMax);
            override(SEND_WINDOW_PROP, sendDays == LEAVE_ALONE
                    ? LEAVE_ALONE : sendWindowSeconds(sendDays));
            override(ACCEPT_MAX_PROP, acceptMax);
            override(ACCEPT_HALF_WINDOW_PROP, acceptDays == LEAVE_ALONE
                    ? LEAVE_ALONE : acceptHalfWindowSeconds(acceptDays));

            Log.i(TAG, "GroupHistorySharing: send " + describe(sendMax, "messages") + " from "
                    + describe(sendDays, "days") + ", accept " + describe(acceptMax, "messages")
                    + " from " + describe(acceptDays, "days") + "; "
                    + AbPropStore.featureCount() + " override(s) in force");
        } catch (Throwable t) {
            Log.e(TAG, "GroupHistorySharing: the values were not published", t);
        }
    }

    private static String describe(int setting, String unit) {
        return setting == LEAVE_ALONE ? "the app's own " + unit : setting + " " + unit;
    }

    /** Overrides a property, or stops overriding it when the setting says to
     *  leave the app alone. */
    private static void override(int prop, int value) {
        AbPropStore.feature(prop, AbProp.Type.INT,
                value == LEAVE_ALONE ? null : String.valueOf(value), SOURCE);
    }

    /** The sender's window as the property holds it. */
    public static int sendWindowSeconds(int days) {
        return days * SECONDS_PER_DAY;
    }

    /** Halved, because the app doubles it: the floor a receiver compares against
     *  is the join message's timestamp less twice this property. */
    public static int acceptHalfWindowSeconds(int days) {
        return days * SECONDS_PER_DAY / 2;
    }

    /** The days a value of 21313 amounts to -- the inverse of
     *  {@link #acceptHalfWindowSeconds}, for showing the app's own answer. */
    public static int acceptDaysFor(long halfWindowSeconds) {
        return (int) (halfWindowSeconds * 2 / SECONDS_PER_DAY);
    }

    public static int sendMax() {
        return PatchDb.getInt(SEND_MAX_KEY, LEAVE_ALONE);
    }

    public static int sendDays() {
        return PatchDb.getInt(SEND_DAYS_KEY, LEAVE_ALONE);
    }

    public static int acceptMax() {
        return PatchDb.getInt(ACCEPT_MAX_KEY, LEAVE_ALONE);
    }

    public static int acceptDays() {
        return PatchDb.getInt(ACCEPT_DAYS_KEY, LEAVE_ALONE);
    }

    public void unload() {
        Log.i(TAG, "GroupHistorySharing: Patch unloaded");
    }
}
