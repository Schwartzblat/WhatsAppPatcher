package com.smali_generator.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.smali_generator.search.SearchQuery;
import com.smali_generator.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Everything is best effort: a table that moved yields no senders, and a search
 * with no senders is the search the app already does.
 */
public final class SenderJids {
    private static final String TAG = "PATCH";
    private static final String WA_DB = "wa.db";

    /** Individual chats. A group is never a sender. */
    private static final String USER_SUFFIX = "@s.whatsapp.net";

    /** jid to the name WhatsApp shows for it, or null while unread. */
    private static volatile Map<String, String> namesByJid;

    /**
     * The last few answers, because a search runs on every keystroke.
     *
     * Bounded because typing one more character is a different query, so an
     * unbounded map would grow for the life of the session. Synchronized
     * rather than concurrent: it is tiny, and what it guards is a scan of
     * every jid, which is the cost worth not paying twice.
     */
    private static final int CACHE_SIZE = 8;
    private static final Map<String, List<Long>> CACHE =
            new LinkedHashMap<String, List<Long>>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Long>> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    private SenderJids() {
    }

    /**
     * The jid rows every token names, best first, at most {@code cap} of them.
     *
     * A token is a number if it is digits and separators only and long enough
     * to be selective; otherwise it is a name. Both routes end in the same
     * place: phone jid rows, expanded to the LID rows that are the same person.
     */
    public static List<Long> resolve(List<String> tokens, int minDigits, int minName, int cap) {
        String key = tokens + "|" + minDigits + "/" + minName + "/" + cap;
        synchronized (CACHE) {
            List<Long> cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        // Unmodifiable because the same instance is handed to every caller of
        // this key until it ages out: search runs on WhatsApp's search worker
        // while invalidate() runs from the UI thread, and one caller sorting
        // or clearing what it got back would corrupt every later cache hit.
        List<Long> ids = Collections.unmodifiableList(search(tokens, minDigits, minName, cap));
        synchronized (CACHE) {
            CACHE.put(key, ids);
        }
        return ids;
    }

    private static List<Long> search(List<String> tokens, int minDigits, int minName, int cap) {
        // best() is inside the try too: it is where an out-of-range cap (Task 5
        // draws it from a fixed list today, but nothing here may assume that)
        // would throw, and this runs on WhatsApp's own search thread, where the
        // total-method rule applies as much as it does to the query above it.
        try {
            List<Candidate> candidates = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            JidTable.Snapshot jids = JidTable.snapshot();
            for (String token : tokens) {
                String digits = SearchQuery.digitsOf(token);
                if (!digits.isEmpty()) {
                    if (digits.length() >= minDigits) {
                        byNumber(jids, digits, candidates, seen);
                    }
                } else if (token.length() >= minName) {
                    byName(jids, token, candidates, seen);
                }
            }
            return best(candidates, cap);
        } catch (Throwable t) {
            Log.e(TAG, "SenderJids: resolving the query found no senders", t);
            return Collections.emptyList();
        }
    }

    /** Forgets the contact names and every cached answer. */
    public static void invalidate() {
        namesByJid = null;
        synchronized (CACHE) {
            CACHE.clear();
        }
        JidTable.invalidate();
    }

    /** One jid row, with enough to rank it against the others. */
    private static final class Candidate {
        final long id;
        final int rank;

        Candidate(long id, int rank) {
            this.id = id;
            this.rank = rank;
        }
    }

    private static void byNumber(JidTable.Snapshot jids, String digits,
                                 List<Candidate> candidates, Set<Long> seen) {
        Map<String, String> names = names();
        for (Map.Entry<Long, String> entry : jids.rawById.entrySet()) {
            String raw = entry.getValue();
            // Only phone jids: the digits of a LID are an internal id, and
            // matching them would surface a person nobody searched for.
            if (!raw.endsWith(USER_SUFFIX)) {
                continue;
            }
            String user = raw.substring(0, raw.length() - USER_SUFFIX.length());
            if (!user.contains(digits)) {
                continue;
            }
            int rank = (user.endsWith(digits) ? 0 : 2) + (names.containsKey(raw) ? 0 : 1);
            add(jids, entry.getKey(), rank, candidates, seen);
        }
    }

    private static void byName(JidTable.Snapshot jids, String token,
                               List<Candidate> candidates, Set<Long> seen) {
        String needle = token.toLowerCase(Locale.getDefault());
        for (Map.Entry<String, String> entry : names().entrySet()) {
            String name = entry.getValue().toLowerCase(Locale.getDefault());
            if (!name.contains(needle)) {
                continue;
            }
            Long id = jids.idByRaw.get(entry.getKey());
            if (id == null) {
                continue;
            }
            add(jids, id, name.startsWith(needle) ? 0 : 2, candidates, seen);
        }
    }

    /**
     * Adds a row and every row that is the same person.
     *
     * The linked rows carry the same rank: which of a person's jids the index
     * happened to name a message with is not something the user chose between.
     */
    private static void add(JidTable.Snapshot jids, long id, int rank,
                            List<Candidate> candidates, Set<Long> seen) {
        if (seen.add(id)) {
            candidates.add(new Candidate(id, rank));
        }
        List<Long> linked = jids.linkedIds.get(id);
        if (linked == null) {
            return;
        }
        for (Long other : linked) {
            if (seen.add(other)) {
                candidates.add(new Candidate(other, rank));
            }
        }
    }

    private static List<Long> best(List<Candidate> candidates, int cap) {
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate left, Candidate right) {
                if (left.rank != right.rank) {
                    return left.rank < right.rank ? -1 : 1;
                }
                // Row id last so the order is the same on every keystroke:
                // a set that reshuffles would make the cap cut differently
                // each time and the results flicker.
                return Long.compare(left.id, right.id);
            }
        });
        List<Long> ids = new ArrayList<>(Math.min(cap, candidates.size()));
        for (Candidate candidate : candidates) {
            if (ids.size() >= cap) {
                Log.i(TAG, "SenderJids: " + candidates.size() + " jid(s) matched, using the first " + cap);
                break;
            }
            ids.add(candidate.id);
        }
        return ids;
    }

    /**
     * Every jid WhatsApp has a name for, contacts and push names alike.
     *
     * Read whole and cached: a query runs on a keystroke, and the alternative
     * is SQLite on that path. Deduped by jid, because {@code wa_contacts} is
     * keyed on {@code (jid, raw_contact_id)} with nothing unique about the jid
     * -- a contact the phone holds under several accounts has a row per source.
     */
    private static Map<String, String> names() {
        Map<String, String> cached = namesByJid;
        if (cached == null) {
            cached = readNames();
            namesByJid = cached;
        }
        return cached;
    }

    private static synchronized Map<String, String> readNames() {
        Map<String, String> names = new HashMap<>();
        Context context = Utils.getApplicationContext();
        if (context == null) {
            Log.e(TAG, "SenderJids: no application context, no name is known");
            return names;
        }
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(WA_DB);
            if (!file.exists()) {
                Log.e(TAG, "SenderJids: " + WA_DB + " is not where it was expected");
                return names;
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Set<String> columns = columnsOf(database);
            if (!columns.contains("jid")) {
                Log.e(TAG, "SenderJids: wa_contacts has no jid column, no name is known");
                return names;
            }
            // Both name columns, because a group member who was never saved has
            // only the name they set for themselves. Whichever is present is
            // used; a saved name wins over a push name.
            List<String> wanted = new ArrayList<>();
            if (columns.contains("display_name")) {
                wanted.add("display_name");
            }
            if (columns.contains("wa_name")) {
                wanted.add("wa_name");
            }
            if (wanted.isEmpty()) {
                Log.e(TAG, "SenderJids: wa_contacts has neither name column, no name is known");
                return names;
            }
            readNameRows(database, wanted, names);
            Log.i(TAG, "SenderJids: " + names.size() + " named jid(s) from " + wanted);
        } catch (Throwable t) {
            Log.e(TAG, "SenderJids: could not read " + WA_DB + ", no name is known", t);
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable t) {
                    Log.e(TAG, "SenderJids: close failed", t);
                }
            }
        }
        return names;
    }

    private static void readNameRows(SQLiteDatabase database, List<String> wanted, Map<String, String> names) {
        StringBuilder sql = new StringBuilder("SELECT jid");
        for (String column : wanted) {
            sql.append(", ").append(column);
        }
        // Ordered by _id so that which row wins a jid is the same on every
        // open, rather than whatever SQLite happens to return first.
        sql.append(" FROM wa_contacts WHERE jid IS NOT NULL ORDER BY _id");
        Cursor cursor = database.rawQuery(sql.toString(), null);
        try {
            while (cursor.moveToNext()) {
                String jid = cursor.getString(0);
                // wa_contacts mixes group rows in with contacts. A matched group
                // name would add the group's own jid as a "sender": the term
                // orTerms emits is the bare fts_jid:<token>, unanchored to any
                // one message, so that token alone would surface every message
                // in the group. Keeping only phone jids here is what keeps
                // byName's "a group is never a sender" true.
                if (jid == null || !jid.endsWith(USER_SUFFIX) || names.containsKey(jid)) {
                    continue;
                }
                for (int column = 1; column <= wanted.size(); column++) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) {
                        names.put(jid, name);
                        break;
                    }
                }
            }
        } finally {
            cursor.close();
        }
    }

    private static Set<String> columnsOf(SQLiteDatabase database) {
        Set<String> columns = new LinkedHashSet<>();
        Cursor cursor = database.rawQuery("PRAGMA table_info(wa_contacts)", null);
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
        return columns;
    }
}
