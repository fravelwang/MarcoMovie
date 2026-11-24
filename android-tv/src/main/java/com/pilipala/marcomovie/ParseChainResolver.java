package com.pilipala.marcomovie;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Ordered fallback over the parse interfaces loaded from {@code config.parses}.
 *
 * <p>Only the playback branches that already required an external parser use this class; direct
 * streams, netdisk links, live channels and Emby playback never reach it. An interface is accepted
 * only when its HTTP response yields an http(s) m3u8/mp4 URL (JSON field or embedded page value);
 * a page that carries no extractable stream is handed back to the caller for WebView sniffing so
 * the existing page-based flow keeps working.
 */
final class ParseChainResolver {
    /**
     * Preference key holding the name of the interface that most recently produced a playable
     * stream. Stored by name because that is what the configuration editor shows and what users
     * recognise; a renamed interface simply loses its priority.
     */
    static final String PREF_LAST_OK = "parse_chain_last_ok";

    /**
     * Free public parse endpoints rot quickly: in practice one of the first few replies still
     * works. Five attempts cover a remembered endpoint plus the configured order while keeping the
     * worst case bounded (also capped by {@link #TOTAL_TIMEOUT_MS}).
     */
    static final int MAX_ATTEMPTS = 5;

    /** Per-interface budget so one dead host cannot consume the whole chain. */
    static final long PER_ATTEMPT_TIMEOUT_MS = 8000L;

    /**
     * Slice of the per-interface budget used by the HTTP probe. The remainder is left for the
     * WebView sniff of a page-like reply, so one interface never exceeds its 8s slot.
     */
    static final long PROBE_PHASE_TIMEOUT_MS = 6000L;

    /**
     * The source's own {@code playUrl} prefix is usually a player page, which used to get the full
     * WebView budget. Only a short JSON probe is spent before handing it to the WebView.
     */
    static final long PREFERRED_PROBE_TIMEOUT_MS = 2500L;

    /** Whole-chain budget because the user should never stare at the loading pill indefinitely. */
    static final long TOTAL_TIMEOUT_MS = 25000L;

    private static final ExecutorService PROBE_EXECUTOR =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "parse-chain");
                        t.setDaemon(true);
                        return t;
                    });

    private ParseChainResolver() {}

    /** One ordered entry of the chain: a configured parse interface or the source's own prefix. */
    static final class Candidate {
        final String name;
        final String url;
        final int type;
        final boolean fromConfig;
        final Map<String, String> headers = new LinkedHashMap<>();

        Candidate(
                String name,
                String url,
                int type,
                boolean fromConfig,
                Map<String, String> headers) {
            this.name = name == null || name.trim().isEmpty() ? "解析接口" : name.trim();
            this.url = url;
            this.type = type;
            this.fromConfig = fromConfig;
            if (headers != null) this.headers.putAll(headers);
        }
    }

    /** Result of one HTTP probe. */
    static final class Probe {
        boolean ok;
        Cms.Episode episode;
        boolean pageLike;
        String reason = "";
    }

    interface ProbeCallback {
        /** Invoked on a background thread. */
        void done(Probe probe);
    }

    /**
     * Builds the ordered, de-duplicated and capped attempt list. The remembered interface goes
     * first (that is the whole point of the preference), then the source's own parse prefix, then
     * the configured order.
     */
    static List<Candidate> candidates(MainActivity a, Cms.Site site, String preferredPrefix) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Cms.Parse> parses = a != null && a.config != null ? a.config.parses : null;
        String remembered =
                a != null && a.prefs != null ? a.prefs.getString(PREF_LAST_OK, "") : "";
        if (parses != null && remembered != null && !remembered.isEmpty()) {
            for (Cms.Parse p : parses) {
                if (p != null && remembered.equals(p.name) && Cms.isHttp(p.url)) {
                    add(out, seen, p.name, p.url, p.type, true, p.headers);
                    break;
                }
            }
        }
        if (preferredPrefix != null && Cms.isHttp(preferredPrefix)) {
            String name =
                    site != null && site.name != null && !site.name.isEmpty()
                            ? site.name
                            : "来源解析";
            add(out, seen, name, preferredPrefix, 1, false, site != null ? site.headers : null);
        }
        if (parses != null) {
            for (Cms.Parse p : parses) {
                if (p != null && Cms.isHttp(p.url)) {
                    add(out, seen, p.name, p.url, p.type, true, p.headers);
                }
            }
        }
        return out;
    }

    private static void add(
            List<Candidate> out,
            Set<String> seen,
            String name,
            String url,
            int type,
            boolean fromConfig,
            Map<String, String> headers) {
        if (out.size() >= MAX_ATTEMPTS) return;
        String key = url == null ? "" : url.trim();
        if (key.isEmpty() || !seen.add(key)) return;
        out.add(new Candidate(name, key, type, fromConfig, headers));
    }

    /**
     * Appends the page address to the interface prefix. Type 0 pastes the page URL as configured
     * (that is what the WebView page flow has always done); JSON-style types URL-encode it, which
     * matches the pre-existing parser requests.
     *
     * <p>TODO(verify): TVBox type-4 semantics are not documented in this repository; it is treated
     * like a JSON parser that may also return a player page, which is the safest superset.
     */
    static String targetUrl(Candidate c, String pageUrl) {
        String page = pageUrl == null ? "" : pageUrl.trim();
        if (c.type == 0) return c.url + page;
        try {
            return c.url + URLEncoder.encode(page, "UTF-8");
        } catch (Exception unsupported) {
            return c.url + page;
        }
    }

    /**
     * Probes one interface on the calling background thread. The HTTP client has its own generous
     * timeouts and UA/DoH retries, so the fetch runs on a worker and the caller waits at most
     * {@code timeoutMs}; a hung host only costs one attempt budget.
     */
    static Probe probe(Cms.Site site, Cms.Episode ep, Candidate c, long timeoutMs) {
        Probe out = new Probe();
        String target = targetUrl(c, ep == null ? "" : ep.url);
        Future<String> future = null;
        String body;
        try {
            future = PROBE_EXECUTOR.submit(() -> Cms.fetch(target, c.headers));
            body = future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            if (future != null) future.cancel(true);
            out.reason = "请求超时";
            return out;
        } catch (InterruptedException cancelled) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            out.reason = "已取消";
            return out;
        } catch (Exception error) {
            Throwable cause =
                    error.getCause() != null && error.getCause() != error
                            ? error.getCause()
                            : error;
            out.reason = "请求失败 " + shortMessage(cause);
            return out;
        }
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            out.reason = "空响应";
            return out;
        }
        if (text.startsWith("{")) {
            try {
                JSONObject jo = new JSONObject(text);
                Cms.Episode rich = null;
                if (site != null) {
                    try {
                        // Reuse the existing JSON result adapter so qualities, subtitles and
                        // inline header suffixes keep behaving exactly as before.
                        rich = MediaOptions.episode(site, ep, jo);
                    } catch (Exception ignored) {
                    }
                }
                String media = normalizeMedia(rich == null ? "" : rich.url);
                if (media.isEmpty()) {
                    media =
                            normalizeMedia(
                                    firstNonEmpty(
                                            Cms.optSafeString(jo, "url", ""),
                                            Cms.optSafeString(jo, "playUrl", "")));
                }
                if (isPlayable(media)) {
                    out.episode = episodeFromJson(ep, c, rich, media, target);
                    out.ok = true;
                    out.reason = "JSON 返回播放地址";
                    return out;
                }
                out.reason = "JSON 未返回播放地址";
                return out;
            } catch (Exception notJson) {
                // Some interfaces wrap the player in a JSON-looking shell; fall through and let
                // the page/URL extraction below have a chance.
            }
        }
        String embedded = extractEmbeddedStreamUrl(target, text);
        if (isPlayable(embedded)) {
            out.episode = episodeFromPage(ep, c, embedded, target);
            out.ok = true;
            out.reason = "页面中抽取到播放地址";
            return out;
        }
        out.pageLike = looksLikePage(text);
        out.reason = out.pageLike ? "页面未找到播放地址" : "响应中没有播放地址";
        return out;
    }

    /**
     * Runs {@link #probe} on the probe pool and reports through the callback.
     *
     * <p>Attempts are deliberately serial. The remembered interface must win deterministically,
     * the configured order is the user's own priority, and most probes finish far below the 8s
     * cap, so parallel fan-out would only multiply traffic to already-flaky free endpoints and
     * make it unclear which interface actually worked. The per-attempt and whole-chain caps keep
     * the serial wait bounded.
     */
    static void probeAsync(
            final Cms.Site site,
            final Cms.Episode ep,
            final Candidate c,
            final long timeoutMs,
            final ProbeCallback callback) {
        if (callback == null) return;
        PROBE_EXECUTOR.execute(
                () -> {
                    Probe result;
                    try {
                        result = probe(site, ep, c, timeoutMs);
                    } catch (Throwable unexpected) {
                        result = new Probe();
                        result.reason = "请求失败 " + shortMessage(unexpected);
                    }
                    callback.done(result);
                });
    }

    /** Appends the one diagnostic line the on-device log needs per interface. */
    static void logAttempt(String name, boolean ok, String reason) {
        AppLog.w(
                "ParseChain",
                (name == null || name.trim().isEmpty() ? "解析接口" : name.trim())
                        + " -> "
                        + (ok ? "ok" : "fail")
                        + " "
                        + (reason == null ? "" : reason));
    }

    /** Remembers the interface that actually played, so the next attempt starts there. */
    static void remember(MainActivity a, String name) {
        if (a == null || a.prefs == null || name == null || name.trim().isEmpty()) return;
        try {
            a.prefs.edit().putString(PREF_LAST_OK, name.trim()).apply();
        } catch (Exception ignored) {
        }
    }

    private static Cms.Episode episodeFromJson(
            Cms.Episode ep, Candidate c, Cms.Episode rich, String media, String target) {
        Cms.Episode resolved = rich == null ? new Cms.Episode() : rich;
        if (ep != null) resolved.name = ep.name;
        resolved.url = media;
        mergeMissing(resolved.headers, c.headers);
        if (!hasHeader(resolved.headers, "Referer") && Cms.isHttp(target)) {
            resolved.headers.put("Referer", target);
        }
        return resolved;
    }

    private static Cms.Episode episodeFromPage(
            Cms.Episode ep, Candidate c, String media, String target) {
        Cms.Episode resolved = new Cms.Episode();
        resolved.name = ep == null ? "" : ep.name;
        resolved.url = media;
        resolved.headers.putAll(c.headers);
        if (Cms.isHttp(target)) resolved.headers.put("Referer", target);
        return resolved;
    }

    private static boolean isPlayable(String url) {
        return url != null && !url.isEmpty() && Cms.isHttp(url) && Cms.isDirectStreamUrl(url);
    }

    private static String normalizeMedia(String value) {
        if (value == null) return "";
        String url = value.trim().replace("\\/", "/");
        if (url.startsWith("//")) url = "https:" + url;
        return isPlayable(url) ? url : "";
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }

    private static void mergeMissing(Map<String, String> target, Map<String, String> extra) {
        if (extra == null) return;
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            String key = entry.getKey();
            if (key == null || hasHeader(target, key)) continue;
            target.put(key, entry.getValue());
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String key) {
        for (String existing : headers.keySet()) {
            if (key.equalsIgnoreCase(existing)) return true;
        }
        return false;
    }

    private static boolean looksLikePage(String body) {
        String head = body.length() > 2048 ? body.substring(0, 2048) : body;
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("<!doctype")
                || lower.contains("<html")
                || lower.contains("<head")
                || lower.contains("<body")
                || lower.contains("<script")
                || lower.contains("<video")
                || lower.contains("<div");
    }

    private static String shortMessage(Throwable error) {
        if (error == null) return "未知错误";
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) return error.getClass().getSimpleName();
        String line = detail.replace('\n', ' ').replace('\r', ' ').trim();
        return line.length() > 80 ? line.substring(0, 80) : line;
    }

    /** Extracts a quoted http(s) m3u8/mp4 value from a player page or inline script. */
    static String extractEmbeddedStreamUrl(String baseUrl, String body) {
        if (body == null || body.isEmpty()) return "";
        // Player scripts frequently escape slashes in JSON strings (https:\/\/cdn...).
        String source = body.replace("\\/", "/");
        Matcher m =
                Pattern.compile(
                                "(?:const|var|let)?\\s*(?:url|main|playurl|src|vid)\\s*[:=]\\s*[\"']([^\"']+\\.(?:m3u8|mp4)(?:\\?[^\"']*)?)[\"']",
                                Pattern.CASE_INSENSITIVE)
                        .matcher(source);
        if (!m.find()) {
            m =
                    Pattern.compile(
                                    "[\"']((?:https?:)?//[^\"']+\\.(?:m3u8|mp4)(?:\\?[^\"']*)?|/[^\"'\\s<>]+\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>]*)?)[\"']",
                                    Pattern.CASE_INSENSITIVE)
                            .matcher(source);
            if (!m.find()) return "";
        }
        String found = m.group(1).trim().replace("\\/", "/");
        if (found.isEmpty()) return "";
        try {
            if (found.startsWith("//")) return "https:" + found;
            if (Cms.isHttp(found)) return found;
            if (baseUrl != null && Cms.isHttp(baseUrl)) {
                return new java.net.URI(baseUrl).resolve(found).toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
