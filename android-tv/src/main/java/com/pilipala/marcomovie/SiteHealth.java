package com.pilipala.marcomovie;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers whether a whole site answered the last library-wall fetch so the next round can spend
 * its 15 s budget on sites that already worked instead of re-trying known-bad entries first.
 *
 * <p>Key {@code site_health:<siteKey>} in {@link Prefs#FILE}; value {@code ok|<epochSeconds>} after
 * the site returned a listing (an empty one still counts) or {@code fail|<epochSeconds>} after a
 * timeout or an exception. Writes happen only while aggregating, never while rendering.
 *
 * <p>Retention is capped at {@link #MAX_ENTRIES}. {@code site_health_order} keeps the keys in write
 * order because SharedPreferences does not promise key order; the oldest key is dropped first. A
 * missing or corrupt order list rebuilds itself on the next write, so a stale entry is only ever
 * dropped, never fatal.
 */
final class SiteHealth {
    static final String PREFIX = "site_health:";
    static final String OK = "ok";
    static final String FAIL = "fail";
    private static final String ORDER_KEY = "site_health_order";
    static final int MAX_ENTRIES = 500;

    private SiteHealth() {}

    static void markOk(Context context, Cms.Site site) {
        markOk(context, site == null ? null : site.key);
    }

    static void markOk(Context context, String siteKey) {
        write(context, siteKey, OK + "|" + (System.currentTimeMillis() / 1000L));
    }

    static void markFailed(Context context, Cms.Site site) {
        markFailed(context, site == null ? null : site.key);
    }

    static void markFailed(Context context, String siteKey) {
        write(context, siteKey, FAIL + "|" + (System.currentTimeMillis() / 1000L));
    }

    /** {@link #OK}, {@link #FAIL}, or an empty string when the site has no record yet. */
    static String state(Context context, String siteKey) {
        return parseState(read(context, siteKey));
    }

    /** Epoch seconds of the last failure, or 0 when the last record is a success or missing. */
    static long failedAt(Context context, String siteKey) {
        return parseFailedAt(read(context, siteKey));
    }

    /** Value parser kept free of Android types so offline checks can exercise it directly. */
    static String parseState(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        int bar = value.indexOf('|');
        String name = bar < 0 ? value : value.substring(0, bar);
        return OK.equals(name) || FAIL.equals(name) ? name : "";
    }

    static long parseFailedAt(String raw) {
        String value = raw == null ? "" : raw.trim();
        int bar = value.indexOf('|');
        if (bar < 0 || !FAIL.equals(value.substring(0, bar))) return 0L;
        try {
            return Long.parseLong(value.substring(bar + 1).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String read(Context context, String siteKey) {
        String key = key(siteKey);
        if (context == null || key == null) return "";
        String raw;
        try {
            raw = prefs(context).getString(key, "");
        } catch (RuntimeException ignored) {
            // A malformed value must never break the wall: treat it as "no record".
            return "";
        }
        return raw == null ? "" : raw.trim();
    }

    private static void write(Context context, String siteKey, String value) {
        String key = key(siteKey);
        if (context == null || key == null) return;
        SharedPreferences store = prefs(context);
        List<String> order = readOrder(store);
        order.remove(key);
        order.add(key);
        SharedPreferences.Editor editor = store.edit().putString(key, value);
        while (order.size() > MAX_ENTRIES) {
            editor.remove(order.remove(0));
        }
        editor.putString(ORDER_KEY, join(order));
        editor.apply();
    }

    private static List<String> readOrder(SharedPreferences store) {
        List<String> order = new ArrayList<>();
        String raw;
        try {
            raw = store.getString(ORDER_KEY, "");
        } catch (RuntimeException ignored) {
            // Rebuilding the order is enough; a corrupt list must not break aggregation.
            return order;
        }
        if (raw == null || raw.isEmpty()) return order;
        for (String part : raw.split("\n")) {
            String entry = part == null ? "" : part.trim();
            if (entry.startsWith(PREFIX) && !order.contains(entry)) order.add(entry);
        }
        return order;
    }

    private static String join(List<String> order) {
        StringBuilder out = new StringBuilder();
        for (String key : order) {
            if (out.length() > 0) out.append('\n');
            out.append(key);
        }
        return out.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
    }

    /** Returns null when the key is empty, so a nameless site is never stored. */
    private static String key(String siteKey) {
        String site = clean(siteKey);
        return site.isEmpty() ? null : PREFIX + site;
    }

    /** One-line, length-bounded key so a stray long name cannot bloat the preference file. */
    private static String clean(String value) {
        if (value == null) return "";
        String text = value.trim().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}
