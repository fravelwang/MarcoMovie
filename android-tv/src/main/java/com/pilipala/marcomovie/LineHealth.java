package com.pilipala.marcomovie;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers the outcome of the last playback attempt on every line so the line picker can mark a
 * line before the user taps it.
 *
 * <p>Key {@code line_health:<siteKey>:<lineName>} in {@link Prefs#FILE}; value {@code ok} after a
 * prepared playback, or {@code <Attribution>|<epochSeconds>} (for example {@code SOURCE|1795500000})
 * after a failure. Writes happen only on a playback success or failure, never while rendering.
 *
 * <p>Retention is capped at {@link #MAX_ENTRIES}. {@code line_health_order} keeps the keys in write
 * order because SharedPreferences does not promise key order; the oldest key is dropped first. A
 * missing or corrupt order list rebuilds itself on the next write, so a stale value is only ever
 * dropped, never fatal.
 */
final class LineHealth {
    static final String PREFIX = "line_health:";
    private static final String ORDER_KEY = "line_health_order";
    static final int MAX_ENTRIES = 300;
    private static final String OK = "ok";

    private LineHealth() {}

    /** Records that this line reached the prepared state. */
    static void markOk(Context context, Cms.Site site, Cms.Line line) {
        markOk(context, site == null ? null : site.key, line == null ? null : line.name);
    }

    static void markOk(Context context, String siteKey, String lineName) {
        write(context, siteKey, lineName, OK);
    }

    /** Records why this line failed; {@code null} is stored as {@code Attribution.UNKNOWN}. */
    static void markFailed(
            Context context,
            Cms.Site site,
            Cms.Line line,
            PlaybackFailure.Attribution kind) {
        markFailed(
                context,
                site == null ? null : site.key,
                line == null ? null : line.name,
                kind);
    }

    static void markFailed(
            Context context, String siteKey, String lineName, PlaybackFailure.Attribution kind) {
        PlaybackFailure.Attribution attributed =
                kind == null ? PlaybackFailure.Attribution.UNKNOWN : kind;
        write(
                context,
                siteKey,
                lineName,
                attributed.name() + "|" + (System.currentTimeMillis() / 1000L));
    }

    /** Short label for the picker; an empty string means "no recorded outcome yet". */
    static String badge(Context context, Cms.Site site, Cms.Line line) {
        return badge(context, site == null ? null : site.key, line == null ? null : line.name);
    }

    static String badge(Context context, String siteKey, String lineName) {
        String key = key(siteKey, lineName);
        if (context == null || key == null) return "";
        String raw;
        try {
            raw = prefs(context).getString(key, "");
        } catch (RuntimeException ignored) {
            // A malformed value must never break the picker: show no badge instead.
            return "";
        }
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (OK.equals(value)) return "可用";
        int bar = value.indexOf('|');
        String name = bar < 0 ? value : value.substring(0, bar);
        try {
            return label(PlaybackFailure.Attribution.valueOf(name.trim()));
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    /** One label per attribution class; UNKNOWN deliberately shows nothing. */
    private static String label(PlaybackFailure.Attribution kind) {
        switch (kind) {
            case LINE:
                return "线路问题";
            case PARSE:
                return "解析问题";
            case SOURCE:
                return "片源问题";
            case DEVICE:
                return "设备问题";
            default:
                return "";
        }
    }

    private static void write(Context context, String siteKey, String lineName, String value) {
        String key = key(siteKey, lineName);
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
            // Rebuilding the order is enough; a corrupt list must not crash a playback callback.
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

    /** Returns null when either half is missing, so a nameless line is never stored. */
    private static String key(String siteKey, String lineName) {
        String site = clean(siteKey);
        String line = clean(lineName);
        if (site.isEmpty() || line.isEmpty()) return null;
        return PREFIX + site + ":" + line;
    }

    /** One-line, length-bounded label so a stray long name cannot bloat the preference file. */
    private static String clean(String value) {
        if (value == null) return "";
        String text = value.trim().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}
