package com.smali_generator.db;

/**
 * Turns the LID a chat is addressed by into the phone jid a pick is keyed on.
 *
 * WhatsApp has moved one-to-one chats onto LIDs. On the device this was
 * written against, 5167 of 5169 individual chats were addressed as
 * {@code <digits>@lid} and exactly one as {@code <digits>@s.whatsapp.net}, and
 * every jid the messaging pipeline carries -- including the one a read receipt
 * is decided for -- is in that form. {@code wa_contacts}, which the chat
 * picker reads, holds no LID at all: 3187 phone jids and not one {@code @lid}.
 *
 * So the two halves of a per-chat feature name the same chat differently, and
 * a list keyed on one silently never matches the other. Groups escaped it --
 * they are {@code @g.us} on both sides -- which is why picking a group worked
 * and picking a person did nothing.
 *
 * The pairs themselves are read by {@link JidTable}, which the sender search
 * shares -- both features want the same two tables out of {@code msgstore.db},
 * so the knowledge of how to read them lives there once rather than twice in
 * two places that could drift apart. Only the knowledge, though: this asks for
 * {@link JidTable#phoneByLid()}, the cheap tier, which is the one
 * {@code jid_map} join this class always did and nothing more. Deciding a read
 * receipt must not pull in the whole jid table on behalf of a search feature
 * the user may never have switched on.
 *
 * Best effort like everything else that reads WhatsApp's own tables: a schema
 * that moved degrades to an empty map, which leaves every LID untranslated --
 * exactly the behaviour from before this class existed.
 */
public final class LidJids {
    private static final String LID_SERVER = "lid";
    private static final String HOSTED_LID_SUFFIX = ".lid";

    private LidJids() {
    }

    /**
     * The phone jid for {@code raw}, or {@code raw} itself.
     *
     * Unchanged for a group, a newsletter or a chat still addressed by phone
     * number, and unchanged for a LID WhatsApp has no phone number for -- that
     * one cannot be picked in the first place, since the picker's own source is
     * the contact store.
     */
    public static String phoneJid(String raw) {
        if (raw == null || !isLid(raw)) {
            return raw;
        }
        String phone = JidTable.phoneByLid().get(raw);
        return phone == null ? raw : phone;
    }

    /**
     * Forgets the map so the next lookup rereads it.
     *
     * A contact that got its LID after this process started -- a chat opened
     * for the first time today -- would otherwise not match a pick until the
     * next restart. The reread is left to whatever asks next rather than done
     * here, so the screen that calls this never waits on msgstore.
     */
    public static void invalidate() {
        JidTable.invalidate();
    }

    /** {@code @lid}, and the {@code @hosted.lid} variant business chats use. */
    private static boolean isLid(String raw) {
        int at = raw.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String server = raw.substring(at + 1);
        return server.equals(LID_SERVER) || server.endsWith(HOSTED_LID_SUFFIX);
    }
}
