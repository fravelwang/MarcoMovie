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

    /**
     * 一次失败只记数，不立刻把线路标坏：连续失败 {@link #BAD_AFTER_FAILURES} 次才算"坏"，而且这个
     * 标记 {@link #BAD_TTL_MS} 后自动过期。以前一次失败就永久标坏，网盘线路（刚转存完第一次取直链
     * 失败）会一直显示不可用，必须手删 prefs 才能恢复。
     */
    static void markFailed(
            Context context, String siteKey, String lineName, PlaybackFailure.Attribution kind) {
        PlaybackFailure.Attribution attributed =
                kind == null ? PlaybackFailure.Attribution.UNKNOWN : kind;
        String key = key(siteKey, lineName);
        if (context == null || key == null) return;
        int count = 1;
        try {
            String previous = prefs(context).getString(key, "");
            Failure previousFailure = Failure.parse(previous);
            if (previousFailure != null
                    && previousFailure.kind == attributed
                    && !previousFailure.expired(System.currentTimeMillis())) {
                count = previousFailure.count + 1;
            }
        } catch (RuntimeException ignored) {
            // A malformed value just starts the count over.
        }
        write(
                context,
                siteKey,
                lineName,
                attributed.name() + "|" + (System.currentTimeMillis() / 1000L) + "|" + count);
    }

    /** Failure marks are only trusted for this long; afterwards the line is "unknown" again. */
    static final long BAD_TTL_MS = 10L * 60L * 1000L;

    /** Consecutive failures needed before the picker calls a line broken. */
    static final int BAD_AFTER_FAILURES = 2;

    /** Parsed value of one failure mark: {@code KIND|epochSeconds|count}. */
    private static final class Failure {
        final PlaybackFailure.Attribution kind;
        final long atMs;
        final int count;

        Failure(PlaybackFailure.Attribution kind, long atMs, int count) {
            this.kind = kind;
            this.atMs = atMs;
            this.count = count;
        }

        boolean expired(long nowMs) {
            return nowMs - atMs > BAD_TTL_MS;
        }

        static Failure parse(String value) {
            String raw = value == null ? "" : value.trim();
            if (raw.isEmpty() || OK.equals(raw)) return null;
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 2) return null;
            try {
                PlaybackFailure.Attribution kind = PlaybackFailure.Attribution.valueOf(parts[0].trim());
                long at = Long.parseLong(parts[1].trim()) * 1000L;
                int count = parts.length >= 3 ? Math.max(1, Integer.parseInt(parts[2].trim())) : 1;
                return new Failure(kind, at, count);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
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
        Failure failure = Failure.parse(value);
        if (failure == null) return "";
        // 一次失败不标坏；过期标记也不标坏（下一次失败会重新计数）。
        if (failure.count < BAD_AFTER_FAILURES) return "";
        if (failure.expired(System.currentTimeMillis())) return "";
        return label(failure.kind);
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
