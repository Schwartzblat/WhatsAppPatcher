package com.smali_generator.patches;

import android.util.Log;
import android.widget.TextView;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tags every member of a group when the message says so.
 *
 * A mention is not read out of the message text. It is a list that travels
 * beside it: the composer produces one, the message carries it, and the
 * outgoing {@code ContextInfo} is filled from it. Nothing downstream checks
 * that an entry has a matching {@code @name} in the body, which is what lets a
 * word of the user's own choosing stand in for the app's mention picker.
 *
 * So this hook takes the composer's answer and appends one more entry:
 * WhatsApp's own <b>everyone marker</b>, the singleton that its @all feature
 * puts in that same list. Nothing here invents a wire format or enumerates the
 * group -- the message goes out through the app's own everyone path, as if the
 * marker had been picked from the mention list.
 *
 * <b>What that path costs is worth knowing.</b> The marker is serialized into
 * {@code ContextInfo.nonJidMentions}, not into {@code mentionedJid}, and the
 * two are not gated alike. Sending is not gated at all, so the marker always
 * goes out. Reading it back is: the inbound parser only looks at that field
 * when the app's own @all gating passes, which is an A/B property -- 19652,
 * with 20868 for business accounts, on the builds examined. A recipient whose
 * client has it off drops the marker and is not tagged. It is worth turning on
 * locally too, on the {@link com.smali_generator.ui.AbPropsActivity} screen:
 * with it off this device still sends the marker, but discards it when it
 * reads its own drafts and quoted messages back out of the database.
 *
 * The accessor is also called while typing, not only on send -- the caption
 * composer asks it on every text change. That costs nothing here: the answer
 * grows by a single element that WhatsApp's own picker could equally have put
 * there, so every caller sees a list of a shape it already handles. It does
 * mean the work has to stay cheap, which is why the group lookup happens only
 * once the trigger word is present, and why the log line is throttled.
 */
public class MentionEveryone implements Hook {

    private static final String TAG = "PATCH";

    /**
     * Groups are the only chats an everyone-mention means anything in, and the
     * class naming one lives in an unobfuscated package.
     */
    private static final String GROUP_JID_CLASS = "com.whatsapp.infra.core.jid.GroupJid";

    /**
     * What has to appear in the message for the group to be tagged.
     *
     * Bounded on both sides so an address book survives contact: a letter or
     * digit before the {@code @} is what tells {@code name@allstate.com} from a
     * mention, and one after it tells {@code @allen} from {@code @all}. The
     * boundaries are Unicode classes rather than {@code \w}, which in Java is
     * ASCII -- otherwise every non-Latin alphabet would read as "not a letter"
     * and email addresses written in one would trigger.
     */
    private static final Pattern TRIGGER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_@])@(?:everyone|all)(?![\\p{L}\\p{N}_])",
            Pattern.CASE_INSENSITIVE);

    /** One line a second at most: the accessor is on the composer's typing path. */
    private static final long LOG_INTERVAL_MS = 1000;

    /** WhatsApp's own everyone marker. Null means the hook was never installed. */
    private static volatile Object everyone;
    private static volatile Class<?> groupJid;

    /** The field holding the chat, once found. Which one it is is renamed every release. */
    private static volatile Field chatField;

    private static volatile long lastLogged;

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native List mentions_backup(Object thiz);

    static List mentions_hook(Object thiz) {
        List mentions = mentions_backup(thiz);
        try {
            Object marker = everyone;
            if (marker == null || !(thiz instanceof TextView)) {
                return mentions;
            }
            CharSequence text = ((TextView) thiz).getText();
            if (text == null || !TRIGGER.matcher(text).find()) {
                return mentions;
            }
            // The user picked Everyone from WhatsApp's own list and the word in
            // the text is the app's rendering of it. Adding a second marker
            // would put the same mention on the message twice.
            if (mentions != null && mentions.contains(marker)) {
                return mentions;
            }
            if (!isGroup(thiz)) {
                return mentions;
            }
            List tagged = mentions == null ? new ArrayList() : new ArrayList(mentions);
            tagged.add(marker);
            log("MentionEveryone: tagging the group, " + tagged.size() + " mention(s) on the message");
            return tagged;
        } catch (Throwable t) {
            // The composer is mid-keystroke or mid-send; a throw here would
            // take the chat screen down. Its own answer is always a safe one.
            log("MentionEveryone: the group was left untagged: " + t);
            return mentions;
        }
    }

    /**
     * Whether the composer is pointed at a group.
     *
     * Asked of the widget's own fields rather than of the screen around it:
     * the chat is held there as a Jid, and a group's Jid has a class of its
     * own. Read by shape, since which field holds it is renamed every release.
     * A one-to-one chat holds a Jid of a different class and so answers no,
     * which is the whole check -- an everyone-mention in a chat with one
     * person has nobody to reach.
     */
    private static boolean isGroup(Object thiz) throws IllegalAccessException {
        Class<?> group = groupJid;
        if (group == null) {
            return false;
        }
        Field cached = chatField;
        if (cached != null && group.isInstance(cached.get(thiz))) {
            return true;
        }
        // Stops at the framework: the chat is held by the app's own classes,
        // and reflecting past them would walk every field of an EditText for
        // nothing -- and into hidden-API territory on the way.
        for (Class<?> owner = thiz.getClass();
                owner != null && !owner.getName().startsWith("android");
                owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                if (group.isInstance(field.get(thiz))) {
                    chatField = field;
                    return true;
                }
            }
        }
        return false;
    }

    private static void log(String message) {
        long now = System.currentTimeMillis();
        if (now - lastLogged < LOG_INTERVAL_MS) {
            return;
        }
        lastLogged = now;
        Log.i(TAG, message);
    }

    public String id() {
        return "mention_everyone";
    }

    public String title() {
        return "Tag everyone";
    }

    public String description() {
        return "Typing @everyone or @all in a group tags every member.";
    }

    public HookCategory category() {
        return HookCategory.MESSAGES;
    }

    public void load() {
        try {
            // Resolved before anything is hooked: without the marker the hook
            // has nothing to add, and an accessor hooked to return its own
            // answer is pure overhead on the typing path.
            Class<?> markerClass = Class.forName("{{MENTION_EVERYONE_CLASS_NAME}}");
            Field marker = markerClass.getDeclaredField("{{MENTION_EVERYONE_FIELD_NAME}}");
            marker.setAccessible(true);
            everyone = marker.get(null);
            if (everyone == null) {
                Log.e(TAG, "MentionEveryone: " + markerClass.getName()
                        + " holds no everyone marker, groups are left untagged");
                return;
            }
            groupJid = Class.forName(GROUP_JID_CLASS);

            Class<?> owner = Class.forName("{{MENTION_ENTRY_CLASS_NAME}}");
            Executable accessor = ArtHooks.find_function(owner,
                    "{{MENTION_GET_METHOD_NAME}}", "{{MENTION_GET_METHOD_SIG}}");
            if (Modifier.isStatic(accessor.getModifiers())) {
                // The replacement's leading parameter is the receiver, so a
                // staticized accessor would be handed the composer in a slot
                // that is not there, and the backup would re-enter the
                // replacement rather than return the app's own list.
                Log.e(TAG, "MentionEveryone: the mention accessor is static on this build,"
                        + " groups are left untagged");
                everyone = null;
                return;
            }
            Method replacement = MentionEveryone.class.getDeclaredMethod("mentions_hook", Object.class);
            Method original = MentionEveryone.class.getDeclaredMethod("mentions_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(accessor, replacement, original);
            if (!hooked) {
                // false means the accessor runs unmodified with no exception
                // anywhere -- the same silence as success.
                everyone = null;
                Log.e(TAG, "MentionEveryone: hook_function returned false, groups are left untagged");
                return;
            }

            Log.i(TAG, "MentionEveryone: hooked on " + owner.getName()
                    + ".{{MENTION_GET_METHOD_NAME}}, everyone marker "
                    + markerClass.getName() + ".{{MENTION_EVERYONE_FIELD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            everyone = null;
            Log.e(TAG, "MentionEveryone: groups are left untagged: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "MentionEveryone: Patch unloaded");
    }
}
