package com.pilipala.marcomovie;

import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cheap first-byte liveness probe for a playback line.
 *
 * <p>One {@code Range: bytes=0-1023} request with a 3 s budget: enough to learn "does this host
 * answer and how fast", without pulling media. Netdisk share ids and page URLs are skipped on
 * purpose — those cannot be judged without the real resolver, and pretending otherwise would mark a
 * healthy source dead.
 */
final class LineProbe {

    static final String TAG = "LineProbe";
    private static final int TIMEOUT_MS = 3000;
    private static final java.util.Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
    /** Last successful first-byte latency per site|line, for ordering and for the log line. */
    private static final Map<String, Long> LATENCY = new ConcurrentHashMap<>();
    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(
                    2,
                    runnable -> {
                        Thread thread = new Thread(runnable, "movie-line-probe");
                        thread.setDaemon(true);
                        return thread;
                    });

    private LineProbe() {}

    /** Probes the first episode of the line when the address is a plain HTTP media link. */
    static void probeAsync(MainActivity a, Cms.Site site, Cms.Video video, Cms.Line line) {
        if (video == null || line == null || line.episodes == null) return;
        Cms.Episode first = null;
        for (Cms.Episode episode : line.episodes) {
            if (episode != null && episode.url != null && !episode.url.trim().isEmpty()) {
                first = episode;
                break;
            }
        }
        if (first == null) return;
        String normalized;
        try {
            normalized = Cms.normalizeHttp(first.url.trim());
        } catch (Exception malformed) {
            return;
        }
        final String url = normalized;
        if (!Cms.isHttp(url) || !looksLikeMedia(url)) return;
        final String key =
                (site == null || site.key == null ? "" : site.key)
                        + "|"
                        + (line.name == null ? "" : line.name);
        if (key.isEmpty()) return;
        if (!"".equals(LineHealth.badge(a, site, line))) return;
        if (!INFLIGHT.add(key)) return;
        final Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (site != null) headers.putAll(site.headers);
        if (first.headers != null) headers.putAll(first.headers);
        POOL.execute(
                () -> {
                    long started = SystemClock.elapsedRealtime();
                    int code = 0;
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new URL(url).openConnection();
                        connection.setConnectTimeout(TIMEOUT_MS);
                        connection.setReadTimeout(TIMEOUT_MS);
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("Range", "bytes=0-1023");
                        for (Map.Entry<String, String> header : headers.entrySet()) {
                            if (header.getKey() == null || header.getValue() == null) continue;
                            if (header.getValue().isEmpty()) continue;
                            connection.setRequestProperty(header.getKey(), header.getValue());
                        }
                        code = connection.getResponseCode();
                    } catch (Throwable ignored) {
                        // Treated as dead below.
                    } finally {
                        if (connection != null) connection.disconnect();
                        INFLIGHT.remove(key);
                    }
                    boolean ok = code >= 200 && code < 400;
                    long took = SystemClock.elapsedRealtime() - started;
                    if (ok) {
                        // Only successes are recorded: a Range/HEAD probe can be refused by a host
                        // that still plays fine through the player (cookie or UA rules), and marking
                        // such a line "片源问题" in the picker would be a lie.
                        LATENCY.put(key, took);
                        LineHealth.markOk(a, site, line);
                    }
                    AppLog.w(
                            TAG,
                            (video.title == null ? "" : video.title)
                                    + " · "
                                    + (line.name == null ? "" : line.name)
                                    + " -> "
                                    + (ok ? "ok" : "dead")
                                    + " http="
                                    + code
                                    + " "
                                    + took
                                    + "ms");
                });
    }

    /** Last measured first-byte latency for this site+line, or -1 when never probed. */
    static long latencyMs(Cms.Site site, Cms.Line line) {
        if (line == null) return -1;
        String key =
                (site == null || site.key == null ? "" : site.key)
                        + "|"
                        + (line.name == null ? "" : line.name);
        Long value = LATENCY.get(key);
        return value == null ? -1 : value;
    }

    /** Probe up to {@code limit} lines, default ordering first; free of charge when nothing to do. */
    static void probeLines(MainActivity a, Cms.Site site, Cms.Video video, int limit) {
        if (video == null || video.lines == null) return;
        int probed = 0;
        for (Cms.Line line : video.lines) {
            if (probed >= limit) break;
            probeAsync(a, site, video, line);
            probed++;
        }
    }

    private static boolean looksLikeMedia(String url) {
        String path = url.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        return path.endsWith(".mp4")
                || path.endsWith(".m3u8")
                || path.endsWith(".m3u")
                || path.endsWith(".mkv")
                || path.endsWith(".flv")
                || path.endsWith(".ts")
                || path.endsWith(".mp3")
                || path.endsWith(".m4a");
    }
}
