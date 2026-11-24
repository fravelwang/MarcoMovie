package com.pilipala.marcomovie;

import java.util.Locale;
import java.util.Map;

/**
 * Remembers which artwork hosts actually delivered bytes on this box.
 *
 * <p>Measured problem: the aggregated library wall pulls posters from many small hosts, and a share
 * of them answer TCP but then time out or fail certificate validation. Without memory every card
 * retries the dead host first on every visit. This keeps a short, bounded record so a card can pick
 * a poster whose host is known to work, and so a dead host is not retried for hours.
 *
 * <p>Stored as {@code poster_host:<host>} = {@code ok|<epoch>} or {@code fail|<epoch>} in
 * {@link Prefs#FILE}. Entries older than {@link #TTL_MS} are ignored, so a host that recovers is
 * retried eventually.
 */
final class PosterHostHealth {

    private static final long TTL_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_ENTRIES = 200;

    static final String OK = "ok";
    static final String FAIL = "fail";

    private PosterHostHealth() {}

    /** Host part of an image address, lower case, without scheme or port. */
    static String hostOf(String address) {
        if (address == null) return "";
        String value = address.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.lastIndexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.lastIndexOf(':');
        if (colon > 0) value = value.substring(0, colon);
        return value.toLowerCase(Locale.ROOT);
    }

    private static String key(String host) {
        return "poster_host:" + host;
    }

    static void markOk(MainActivity a, String address) {
        write(a, hostOf(address), OK);
    }

    static void markFailed(MainActivity a, String address) {
        write(a, hostOf(address), FAIL);
    }

    /**
     * True when this host failed recently and has not succeeded since. Unknown hosts are not
     * suspect: never penalise a host the wall has not tried yet.
     */
    static boolean suspect(MainActivity a, String address) {
        String host = hostOf(address);
        if (host.isEmpty() || a == null || a.prefs == null) return false;
        String raw = a.prefs.getString(key(host), "");
        if (raw == null || raw.isEmpty()) return false;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 2) return false;
        long at;
        try {
            at = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException ignored) {
            return false;
        }
        if (System.currentTimeMillis() - at > TTL_MS) return false;
        return FAIL.equals(parts[0]);
    }

    private static void write(MainActivity a, String host, String state) {
        if (host.isEmpty() || a == null || a.prefs == null) return;
        Map<String, ?> all = a.prefs.getAll();
        if (all != null && all.size() > MAX_ENTRIES) {
            // Cheap bound: drop the whole map when it grows past the cap instead of tracking order.
            android.content.SharedPreferences.Editor editor = a.prefs.edit();
            for (String name : all.keySet()) {
                if (name != null && name.startsWith("poster_host:")) editor.remove(name);
            }
            editor.apply();
        }
        a.prefs
                .edit()
                .putString(key(host), state + "|" + System.currentTimeMillis())
                .apply();
    }
}
