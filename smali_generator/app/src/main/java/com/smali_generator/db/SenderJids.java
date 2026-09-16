package com.smali_generator.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.smali_generator.search.SearchQuery;
import com.smali_generator.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The jid rows a search query names a person by.
 *
 * WhatsApp resolves a query to contacts through its own contact search, which
 * matches a number by prefix and normalised form -- so a fragment from the
 * middle of a number finds nobody -- and only when a server flag says it may.
 * This resolves the same thing from the two tables directly, which also picks
 * up people who were never saved as contacts.
 *
 * SQLite answers it now, over the same rows. A leading-wildcard LIKE still
 * scans the {@code jid} table -- no index can serve "contains" -- but it scans
 * it in C and keeps nothing, where the version this replaced read all 88,903
 * rows into Java maps and then needed a cache to make that affordable. The
 * cache cost a staleness window, a list shared between the search worker and
 * the UI thread, and a load on the read receipt path that has nothing to do
 * with this feature. Open, query, close: nothing survives the call, so there
 * is nothing here that can go stale.
 *
 * Everything is best effort: a table that moved yields no senders, and a search
 * with no senders is the search the app already does.
 */
public final class SenderJids {
    private static final String TAG = "PATCH";
    private static final String WA_DB = "wa.db";
    private static final String MSGSTORE_DB = "msgstore.db";

    /** Individual chats. A group is never a sender. */
    private static final String USER_SUFFIX = "@s.whatsapp.net";

    private SenderJids() {
    }

    /**
     * The jid rows every token names, best first, naming at most {@code cap} people.
     *
     * A token is a number if it is digits and separators only and long enough
     * to be selective; otherwise it is a name. Both routes end in the same
     * place: phone jid rows, expanded to the LID rows that are the same person.
     */
    public static List<Long> resolve(List<String> tokens, int minDigits, int minName, int cap) {
        try {
            return search(tokens, minDigits, minName, cap);
        } catch (Throwable t) {
            // On WhatsApp's own search worker, inside a query the user is
            // waiting on: "nobody" is always a valid answer, a throw is not.
            Log.e(TAG, "SenderJids: resolving the query found no senders", t);
            return Collections.emptyList();
        }
    }

    private static List<Long> search(List<String> tokens, int minDigits, int minName, int cap) {
        // A cap of zero or less means nobody, and must never reach a LIMIT,
        // where a negative one means no limit at all and would put every match
        // on the device into a single MATCH expression.
        if (tokens == null || cap <= 0) {
            return Collections.emptyList();
        }
        List<String> numbers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String token : tokens) {
            String digits = SearchQuery.digitsOf(token);
            if (!digits.isEmpty()) {
                if (digits.length() >= minDigits) {
                    numbers.add(digits);
                }
            } else if (token != null && token.length() >= minName) {
                names.add(token);
            }
        }
        // Decided before anything opens: most keystrokes carry a word too short
        // to be selective, and those must not open a 72 MB database to find out.
        if (numbers.isEmpty() && names.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> named = names.isEmpty() ? Collections.<String>emptySet() : namedJids(names, cap);
        SQLiteDatabase jids = null;
        try {
            jids = open(MSGSTORE_DB, "no sender is known");
            if (jids == null) {
                return Collections.emptyList();
            }
            // Insertion ordered, so the cap cuts the same rows on every
            // keystroke rather than wherever a hash happened to put them.
            Set<Long> matched = new LinkedHashSet<>();
            for (String digits : numbers) {
                byNumber(jids, digits, cap, matched);
            }
            rowsOf(jids, named, matched);
            if (matched.isEmpty()) {
                return Collections.emptyList();
            }
            List<Long> ids = expand(jids, matched, cap);
            Log.i(TAG, "SenderJids: " + matched.size() + " jid row(s) matched, cap " + cap
                    + ", " + ids.size() + " named after linking");
            return ids;
        } finally {
            close(jids);
        }
    }

    /** Rows whose number contains {@code digits}, the ones ending with it first. */
    private static void byNumber(SQLiteDatabase jids, String digits, int cap, Set<Long> matched) {
        // The suffix in the pattern is what keeps a LID out of a number match:
        // its digits are an internal id nobody dialled, and matching them
        // surfaces a person nobody searched for. It holds no digit itself, so
        // the needle can only match inside the number.
        //
        // digitsOf() guarantees [0-9]*, so this is the one pattern here built
        // without escaping: it cannot carry a % or a _.
        String contains = "%" + digits + "%" + USER_SUFFIX;
        String endsWith = "%" + digits + USER_SUFFIX;
        int before = matched.size();
        // The CASE is the rank: a number ending with what was typed beats one
        // that merely contains it, and _id breaks the tie so the cut lands in
        // the same place on every keystroke.
        collect(jids.rawQuery("SELECT _id FROM jid WHERE raw_string LIKE ?"
                + " ORDER BY CASE WHEN raw_string LIKE ? THEN 0 ELSE 1 END, _id"
                + " LIMIT " + cap, new String[]{contains, endsWith}), matched);
        int found = matched.size() - before;
        if (found > 0) {
            Log.i(TAG, "SenderJids: a number matched " + found + " jid row(s)");
        }
    }

    /** The rows for jid strings the contact store named. */
    private static void rowsOf(SQLiteDatabase jids, Set<String> named, Set<Long> matched) {
        if (named.isEmpty()) {
            return;
        }
        // Bound, unlike the row ids in expand(): these are strings out of a
        // table whose contents this code does not control.
        StringBuilder sql = new StringBuilder("SELECT _id FROM jid WHERE raw_string IN (");
        for (int i = 0; i < named.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY _id");
        collect(jids.rawQuery(sql.toString(), named.toArray(new String[0])), matched);
    }

    /**
     * The phone jids WhatsApp holds a name containing one of {@code names} for.
     *
     * Its own try/catch, not the caller's: a {@code wa.db} that moved must
     * still leave the number half of the search working, and that half never
     * touches this database.
     */
    private static Set<String> namedJids(List<String> names, int cap) {
        Set<String> jids = new LinkedHashSet<>();
        SQLiteDatabase contacts = null;
        try {
            contacts = open(WA_DB, "no name is known");
            if (contacts == null) {
                return jids;
            }
            List<String> columns = nameColumns(contacts);
            if (columns.isEmpty()) {
                return jids;
            }
            StringBuilder like = new StringBuilder();
            for (String column : columns) {
                if (like.length() > 0) {
                    like.append(" OR ");
                }
                // ESCAPE because a typed name can carry a % or a _, and an
                // unescaped one silently widens the match: "a_b" would find
                // "axb". The Java comparison this replaced never had to care.
                like.append(column).append(" LIKE ? ESCAPE '\\'");
            }
            // The jid LIKE is the group exclusion, and it is load-bearing:
            // wa_contacts carries @g.us rows too, and the term this feature
            // appends names a jid rather than any one message, so a single
            // matched group name would drag in every message in that group.
            //
            // Grouped rather than DISTINCT: wa_contacts is keyed on
            // (jid, raw_contact_id) with nothing unique about the jid, so a
            // contact the phone holds under several accounts has a row per
            // source, and MIN(_id) fixes which of them decides the order.
            String sql = "SELECT jid, MIN(_id) AS first_row FROM wa_contacts"
                    + " WHERE jid LIKE '%" + USER_SUFFIX + "' AND (" + like + ")"
                    + " GROUP BY jid ORDER BY first_row LIMIT " + cap;
            String[] args = new String[columns.size()];
            for (String name : names) {
                if (jids.size() >= cap) {
                    break;
                }
                Arrays.fill(args, "%" + escaped(name) + "%");
                collectJids(contacts.rawQuery(sql, args), jids);
            }
            if (!jids.isEmpty()) {
                Log.i(TAG, "SenderJids: a name matched " + jids.size() + " contact(s) from " + columns);
            }
        } catch (Throwable t) {
            Log.e(TAG, "SenderJids: could not read " + WA_DB + ", no name is known", t);
        } finally {
            close(contacts);
        }
        return jids;
    }

    /**
     * The name columns to search, or empty with the reason already logged.
     *
     * Probed rather than assumed, so a release that renamed one degrades to no
     * name search instead of throwing. {@code _id} is probed for the same
     * reason though nothing selects it: the query orders by it.
     */
    private static List<String> nameColumns(SQLiteDatabase contacts) {
        Set<String> columns = new LinkedHashSet<>();
        Cursor cursor = contacts.rawQuery("PRAGMA table_info(wa_contacts)", null);
        try {
            int name = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                String column = name < 0 ? null : cursor.getString(name);
                if (column != null) {
                    columns.add(column);
                }
            }
        } finally {
            cursor.close();
        }
        List<String> wanted = new ArrayList<>();
        if (!columns.contains("jid") || !columns.contains("_id")) {
            Log.e(TAG, "SenderJids: wa_contacts has no jid or _id column, no name is known");
            return wanted;
        }
        // Both, because a group member who was never saved as a contact has
        // only the name they set for themselves.
        if (columns.contains("display_name")) {
            wanted.add("display_name");
        }
        if (columns.contains("wa_name")) {
            wanted.add("wa_name");
        }
        if (wanted.isEmpty()) {
            Log.e(TAG, "SenderJids: wa_contacts has neither name column, no name is known");
        }
        return wanted;
    }

    /**
     * The first {@code cap} matches, plus the rows that are the same people.
     *
     * A group sender is addressed by LID while a contact is matched by their
     * phone number, so naming one of a person's rows and not the other names
     * none of their messages in groups. The expansion follows the cap rather
     * than competing with it: the cap counts people, and a person's other jid
     * rows are the same person.
     */
    private static List<Long> expand(SQLiteDatabase jids, Set<Long> matched, int cap) {
        List<Long> ids = new ArrayList<>(Math.min(cap, matched.size()));
        // Inlined rather than bound: these are row ids just read out of this
        // same database, so there is nothing to escape, and binding each of
        // them twice would bring the statement within reach of SQLite's limit
        // on bound variables.
        StringBuilder seeds = new StringBuilder();
        for (Long id : matched) {
            if (ids.size() >= cap) {
                break;
            }
            ids.add(id);
            if (seeds.length() > 0) {
                seeds.append(',');
            }
            seeds.append(id.longValue());
        }
        Set<Long> seen = new LinkedHashSet<>(ids);
        Cursor cursor = jids.rawQuery("SELECT lid_row_id, jid_row_id FROM jid_map"
                + " WHERE jid_row_id IN (" + seeds + ") OR lid_row_id IN (" + seeds + ")", null);
        try {
            while (cursor.moveToNext()) {
                // Both ends: which of them was the seed is not worth asking,
                // since the seed is already in the set and the other is the
                // partner this is here for.
                for (int column = 0; column < 2; column++) {
                    long id = cursor.getLong(column);
                    if (seen.add(id)) {
                        ids.add(id);
                    }
                }
            }
        } finally {
            cursor.close();
        }
        return ids;
    }

    private static void collect(Cursor cursor, Set<Long> ids) {
        try {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        } finally {
            cursor.close();
        }
    }

    private static void collectJids(Cursor cursor, Set<String> jids) {
        try {
            while (cursor.moveToNext()) {
                jids.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
    }

    /** A LIKE needle out of text somebody typed. */
    private static String escaped(String token) {
        StringBuilder needle = new StringBuilder(token.length() + 4);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                needle.append('\\');
            }
            needle.append(c);
        }
        return needle.toString();
    }

    /** The database, read only, or null with the reason already logged. */
    private static SQLiteDatabase open(String name, String consequence) {
        Context context = Utils.getApplicationContext();
        if (context == null) {
            Log.e(TAG, "SenderJids: no application context, " + consequence);
            return null;
        }
        File file = context.getDatabasePath(name);
        if (!file.exists()) {
            Log.e(TAG, "SenderJids: " + name + " is not where it was expected");
            return null;
        }
        return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    private static void close(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (Throwable t) {
            Log.e(TAG, "SenderJids: close failed", t);
        }
    }
}
