package com.pilipala.marcomovie;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Loopback Range proxy for signed netdisk direct links (Quark / UC / Baidu / Ali).
 *
 * <p>One player Range request is answered by up to {@link #MAX_CHUNKS} parallel upstream Range
 * connections: a 512 KiB prefix is flushed as soon as it arrives, then {@link #CHUNK_BYTES}
 * blocks are consumed through a four-connection sliding window, so a single TCP connection no
 * longer caps a 4K remux. Everything is streamed through bounded per-chunk queues (4 x 8 x 64 KiB
 * in flight, at most ~64 MiB of upstream range in flight); nothing is cached on disk and no whole
 * file is ever held in memory.
 *
 * <p>Every failure degrades to something that still plays: {@link #wrap} returns {@code null}
 * whenever the loopback listener is not usable, and an upstream that ignores Range is relayed on
 * one connection exactly like the player would have fetched it directly. A chunk that still fails
 * aborts the response so the player reconnects (it retries by itself).
 *
 * <p>The target and its signed headers live in a small in-memory session table ({@link #SESSIONS},
 * 10 minute TTL, 64 entries); the loopback URL only carries a short {@code sid} plus the listener
 * {@code _mm_token}. Netdisk signed URLs run 3-4 KB, and ffmpeg/mpv refuse URLs that long, so the
 * loopback URL must stay small. No Cookie is ever logged.
 */
public final class NetdiskRangeProxy {
    static final String TAG = "RangeProxy";
    static final String ROUTE = "/range";
    static final int MAX_CHUNKS = 4;
    static final long PREFIX_BYTES = 512L * 1024L;
    static final long MIN_CHUNK_BYTES = 1024L * 1024L;
    /** Steady-state block size: a window of four of these keeps every connection busy and idle
     * for at most a few seconds, so an upstream idle timeout can never bite mid-movie. */
    static final long CHUNK_BYTES = 16L * 1024L * 1024L;
    /** Explicit bounded client ranges above this are clamped; open-ended ranges stream fully. */
    static final long MAX_BOUNDED_RANGE = 64L * 1024L * 1024L;
    /** Loopback URLs must stay short: ffmpeg/mpv reject multi-KB URLs outright. */
    static final int MAX_LOCAL_URL = 200;
    /** A session survives 10 idle minutes; streaming requests keep refreshing it. */
    static final long SESSION_TTL_MS = 10L * 60L * 1000L;
    static final int MAX_SESSIONS = 64;

    private static final int BLOCK_BYTES = 64 * 1024;
    private static final int QUEUE_BLOCKS = 8;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;

    /** Netdisk direct links only need these; anything else stays with the player. */
    private static final String[] FORWARD_HEADERS = {
        "Cookie", "User-Agent", "Referer", "Origin", "Authorization"
    };

    /** Target URL plus the signed headers that must reach the CDN on every Range request. */
    static final class Session {
        final String target;
        final Map<String, String> headers;
        long lastUsedMs;

        Session(String target, Map<String, String> headers) {
            this.target = target;
            this.headers =
                    headers.isEmpty()
                            ? Collections.<String, String>emptyMap()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.lastUsedMs = System.currentTimeMillis();
        }
    }

    /** Access-ordered so the eldest entry is the least recently used session. */
    private static final Map<String, Session> SESSIONS =
            Collections.synchronizedMap(new LinkedHashMap<String, Session>(32, 0.75f, true));

    private NetdiskRangeProxy() {}

    static String registerSession(String target, Map<String, String> headers) {
        String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long now = System.currentTimeMillis();
        synchronized (SESSIONS) {
            pruneExpiredLocked(now);
            while (SESSIONS.size() >= MAX_SESSIONS) {
                Iterator<Map.Entry<String, Session>> eldest = SESSIONS.entrySet().iterator();
                if (!eldest.hasNext()) break;
                eldest.next();
                eldest.remove();
            }
            SESSIONS.put(sid, new Session(target, headers));
        }
        return sid;
    }

    static Session findSession(String sid) {
        if (sid == null || sid.isEmpty()) return null;
        long now = System.currentTimeMillis();
        synchronized (SESSIONS) {
            Session session = SESSIONS.get(sid);
            if (session == null) return null;
            if (now - session.lastUsedMs > SESSION_TTL_MS) {
                SESSIONS.remove(sid);
                return null;
            }
            session.lastUsedMs = now;
            return session;
        }
    }

    static void touchSession(String sid) {
        if (sid == null || sid.isEmpty()) return;
        synchronized (SESSIONS) {
            Session session = SESSIONS.get(sid);
            if (session != null) session.lastUsedMs = System.currentTimeMillis();
        }
    }

    private static void pruneExpiredLocked(long now) {
        Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> entry = it.next();
            if (now - entry.getValue().lastUsedMs > SESSION_TTL_MS) it.remove();
        }
    }

    public static boolean isAvailable() {
        return ProxyAddress.port > 0
                && ProxyAddress.token != null
                && !ProxyAddress.token.isEmpty();
    }

    public static String wrap(Cms.Episode episode) {
        return wrap(episode, null);
    }

    /**
     * Builds the loopback URL for a netdisk direct link, or returns {@code null} to keep the
     * player on the original URL. Never throws.
     */
    public static String wrap(Cms.Episode episode, Map<String, String> playerHeaders) {
        if (episode == null || !isAvailable()) return null;
        String url;
        try {
            url = Cms.normalizeHttp(episode.url);
        } catch (Exception invalid) {
            return null;
        }
        if (!Cms.isHttp(url) || isLoopback(url)) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : episode.headers.entrySet()) {
            putForwarded(headers, entry.getKey(), entry.getValue());
        }
        if (playerHeaders != null) {
            for (Map.Entry<String, String> entry : playerHeaders.entrySet()) {
                putForwarded(headers, entry.getKey(), entry.getValue());
            }
        }
        String sid = registerSession(url, headers);
        StringBuilder local =
                new StringBuilder(ProxyAddress.base() + ROUTE + "?sid=").append(sid);
        String token = ProxyAddress.token;
        if (token != null && !token.isEmpty()) {
            local.append("&_mm_token=").append(token);
        }
        if (local.length() > MAX_LOCAL_URL) {
            AppLog.w(
                    TAG,
                    "wrap skipped sid="
                            + sid
                            + " reason=loopback-url-too-long len="
                            + local.length());
            return null;
        }
        LocalProxy.rangeRouteHandler = NetdiskRangeProxy::handle;
        AppLog.w(
                TAG,
                "session sid="
                        + sid
                        + " host="
                        + hostOf(url)
                        + " headers="
                        + headers.size()
                        + " cookie="
                        + (hasHeader(headers, "Cookie") ? "yes" : "no"));
        return local.toString();
    }

    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null ? "?" : host;
        } catch (Exception ignored) {
            return "?";
        }
    }

    private static boolean isLoopback(String url) {
        try {
            return ProxyAddress.isLoopbackHost(new URL(url).getHost());
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void putForwarded(Map<String, String> target, String name, String value) {
        if (name == null || value == null || value.isEmpty()) return;
        boolean allowed = false;
        for (String candidate : FORWARD_HEADERS) {
            if (candidate.equalsIgnoreCase(name.trim())) {
                allowed = true;
                break;
            }
        }
        if (!allowed || !LocalProxy.safe(name) || !LocalProxy.safe(value)) return;
        for (String existing : new ArrayList<>(target.keySet())) {
            if (existing.equalsIgnoreCase(name.trim())) target.remove(existing);
        }
        target.put(name.trim(), value);
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static Map<String, String> decodeHeaders(String blob) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (blob == null || blob.isEmpty()) return headers;
        for (String line : blob.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            if (!LocalProxy.safe(name) || !LocalProxy.safe(value)) continue;
            putForwarded(headers, name, value);
        }
        return headers;
    }

    static boolean handle(
            String path,
            String method,
            Map<String, String> queryParams,
            Map<String, String> requestHeaders,
            OutputStream out)
            throws IOException {
        if (!ROUTE.equals(path)) {
            LocalProxy.simple(out, 404, "Not found");
            return false;
        }
        String sid = queryParams.get("sid");
        String logSid = sid == null || sid.isEmpty() ? "?" : sid;
        String rangeHeader = requestHeaders != null ? requestHeaders.get("range") : null;
        AppLog.w(
                TAG,
                "request sid="
                        + logSid
                        + " range="
                        + (rangeHeader == null || rangeHeader.isEmpty() ? "-" : rangeHeader));
        String target = queryParams.get("u");
        Map<String, String> headers;
        if (target == null) {
            Session session = findSession(sid);
            if (session == null) {
                AppLog.w(TAG, "session expired sid=" + logSid);
                LocalProxy.simple(out, 404, "Range session expired");
                return false;
            }
            target = session.target;
            headers = session.headers;
        } else {
            // Compatibility for hand-made loopback URLs that still carry u/h.
            headers = decodeHeaders(queryParams.get("h"));
        }
        if (!Cms.isHttp(target) || isLoopback(target)) {
            LocalProxy.simple(out, 400, "Invalid range target");
            return false;
        }
        boolean head = "HEAD".equalsIgnoreCase(method);
        long[] requested = parseRange(rangeHeader);
        if (requested == null) {
            // Suffix or multi-part ranges are rare for video; let one direct connection answer.
            return relayDirect(
                    target, headers, method, rangeHeader, out, logSid, "unsupported-range");
        }
        return serve(target, headers, method, head, rangeHeader, requested, out, logSid);
    }

    private static boolean serve(
            String target,
            Map<String, String> headers,
            String method,
            boolean head,
            String rangeHeader,
            long[] requested,
            OutputStream out,
            String sid)
            throws IOException {
        long start = requested[0];
        long requestedEnd = requested[1];
        boolean ranged = rangeHeader != null && !rangeHeader.trim().isEmpty();
        Probe probe;
        try {
            probe = probe(target, headers, start);
        } catch (IOException probeError) {
            return relayDirect(target, headers, method, rangeHeader, out, sid, "probe-failed");
        }
        if (!probe.rangeSupported || probe.total <= 0 || start > probe.total - 1) {
            if (start > 0 && probe.rangeSupported && probe.total > 0) {
                String resp =
                        "HTTP/1.1 416 Range Not Satisfiable\r\nConnection: close\r\n"
                                + "Content-Range: bytes */"
                                + probe.total
                                + "\r\nContent-Length: 0\r\n\r\n";
                out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return false;
            }
            return relayDirect(target, headers, method, rangeHeader, out, sid, "range-unsupported");
        }
        long total = probe.total;
        long end;
        if (requestedEnd >= 0) {
            end = Math.min(requestedEnd, total - 1);
            if (end - start + 1 > MAX_BOUNDED_RANGE) {
                end = start + MAX_BOUNDED_RANGE - 1;
            }
        } else {
            // Open-ended ranges (player asks bytes=N- or no Range at all) stream to EOF: a
            // truncated 206 would look like end-of-file to ffmpeg/mpv mid-movie.
            end = total - 1;
        }
        long length = end - start + 1;
        if (head) {
            writeHead(out, ranged ? 206 : 200, probe.contentType, length, ranged,
                    "bytes " + start + "-" + end + "/" + total);
            out.flush();
            return false;
        }
        List<long[]> ranges = plan(start, end);
        if (ranges.size() <= 1) {
            AppLog.w(TAG, "single sid=" + sid + " bytes=" + length);
            return serveSingle(target, headers, ranged, start, end, total, out, sid);
        }
        return serveParallel(target, headers, probe.contentType, ranged, start, end, total, out, sid);
    }

    /**
     * Splits [start,end] into blocks: a 512 KiB first block so the first frame can be flushed
     * immediately, then {@link #CHUNK_BYTES} blocks consumed through a four-connection sliding
     * window. Small ranges stay on one connection because the handshake would cost more than the
     * parallelism gains.
     */
    static List<long[]> plan(long start, long end) {
        List<long[]> blocks = new ArrayList<>();
        long length = end - start + 1;
        if (length <= PREFIX_BYTES + 3 * MIN_CHUNK_BYTES) {
            blocks.add(new long[] {start, end});
            return blocks;
        }
        long cursor = start;
        long prefixEnd = Math.min(end, start + PREFIX_BYTES - 1);
        blocks.add(new long[] {start, prefixEnd});
        cursor = prefixEnd + 1;
        while (cursor <= end) {
            long blockEnd = Math.min(end, cursor + CHUNK_BYTES - 1);
            blocks.add(new long[] {cursor, blockEnd});
            cursor = blockEnd + 1;
        }
        return blocks;
    }

    private static boolean serveParallel(
            String target,
            Map<String, String> headers,
            String contentType,
            boolean ranged,
            long start,
            long end,
            long total,
            OutputStream out,
            String sid)
            throws IOException {
        List<long[]> ranges = plan(start, end);
        AppLog.w(
                TAG,
                "parallel sid="
                        + sid
                        + " chunks="
                        + ranges.size()
                        + " window="
                        + MAX_CHUNKS
                        + " bytes="
                        + (end - start + 1)
                        + " start="
                        + start
                        + " end="
                        + end);
        writeHead(
                out,
                ranged ? 206 : 200,
                contentType,
                end - start + 1,
                ranged,
                "bytes " + start + "-" + end + "/" + total);
        List<RangeChunk> active = new ArrayList<>(MAX_CHUNKS);
        int next = 0;
        try {
            while (true) {
                while (active.size() < MAX_CHUNKS && next < ranges.size()) {
                    long[] range = ranges.get(next++);
                    touchSession(sid);
                    RangeChunk chunk = new RangeChunk(target, headers, range[0], range[1]);
                    Thread worker = new Thread(chunk, "movie-range-" + sid);
                    worker.setDaemon(true);
                    worker.start();
                    active.add(chunk);
                }
                if (active.isEmpty()) break;
                RangeChunk head = active.remove(0);
                try {
                    try {
                        drainChunk(head, out);
                    } catch (IOException failure) {
                        head.abort();
                        long resume = head.start + head.delivered;
                        if (resume > head.end) throw failure;
                        AppLog.w(
                                TAG,
                                "chunk retry sid="
                                        + sid
                                        + " offset="
                                        + resume
                                        + " reason="
                                        + failure);
                        RangeChunk retry =
                                new RangeChunk(target, headers, resume, head.end);
                        Thread worker = new Thread(retry, "movie-range-retry-" + sid);
                        worker.setDaemon(true);
                        worker.start();
                        try {
                            drainChunk(retry, out);
                        } finally {
                            retry.abort();
                        }
                    }
                } finally {
                    head.abort();
                }
            }
            out.flush();
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("range interrupted", interrupted);
        } finally {
            for (RangeChunk chunk : active) chunk.abort();
        }
    }

    private static void drainChunk(RangeChunk chunk, OutputStream out)
            throws IOException, InterruptedException {
        long remaining = chunk.length();
        while (remaining > 0) {
            byte[] block = chunk.queue.poll(250, TimeUnit.MILLISECONDS);
            if (block == null) {
                Throwable failure = chunk.error;
                if (failure != null) throw new IOException("range chunk failed", failure);
                continue;
            }
            int n = (int) Math.min((long) block.length, remaining);
            out.write(block, 0, n);
            remaining -= n;
            chunk.delivered += n;
        }
    }

    private static boolean serveSingle(
            String target,
            Map<String, String> headers,
            boolean ranged,
            long start,
            long end,
            long total,
            OutputStream out,
            String sid)
            throws IOException {
        HttpURLConnection conn = open(target, headers, "bytes=" + start + "-" + end);
        try {
            int code = conn.getResponseCode();
            long[] cr = LocalProxy.parseContentRange(conn.getHeaderField("Content-Range"));
            if (code != 206 || cr == null || cr[0] != start || cr[1] > end) {
                return relayDirect(
                        target,
                        headers,
                        "GET",
                        ranged ? "bytes=" + start + "-" : null,
                        out,
                        sid,
                        "single-not-206");
            }
            writeHead(
                    out,
                    ranged ? 206 : 200,
                    conn.getContentType(),
                    end - start + 1,
                    ranged,
                    "bytes " + start + "-" + end + "/" + total);
            long remaining = end - start + 1;
            try (InputStream in = conn.getInputStream()) {
                copyExact(in, out, remaining);
            }
            out.flush();
            return false;
        } finally {
            RequestScope.detach(conn);
            conn.disconnect();
        }
    }

    private static boolean relayDirect(
            String target,
            Map<String, String> headers,
            String method,
            String rangeHeader,
            OutputStream out,
            String sid,
            String reason)
            throws IOException {
        AppLog.w(TAG, "fallback to direct sid=" + sid + " reason=" + reason);
        HttpURLConnection conn = null;
        try {
            conn = open(target, headers, rangeHeader);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                LocalProxy.simple(out, code, "Upstream HTTP " + code);
                return false;
            }
            String contentType = conn.getContentType();
            long length = parseContentLength(conn);
            String contentRange = code == 206 ? conn.getHeaderField("Content-Range") : null;
            StringBuilder head =
                    new StringBuilder("HTTP/1.1 ")
                            .append(code)
                            .append(code == 206 ? " Partial Content" : " OK")
                            .append("\r\nConnection: close\r\nAccept-Ranges: bytes\r\n")
                            .append("Content-Type: ")
                            .append(
                                    contentType != null && LocalProxy.safe(contentType)
                                            ? contentType
                                            : "application/octet-stream")
                            .append("\r\n");
            if (contentRange != null && LocalProxy.safe(contentRange)) {
                head.append("Content-Range: ").append(contentRange).append("\r\n");
            }
            if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n");
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if ("HEAD".equalsIgnoreCase(method)) {
                out.flush();
                return false;
            }
            try (InputStream in = conn.getInputStream()) {
                if (length >= 0) {
                    copyExact(in, out, length);
                } else {
                    byte[] buffer = new byte[BLOCK_BYTES];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (n > 0) out.write(buffer, 0, n);
                    }
                }
            }
            out.flush();
            return false;
        } finally {
            if (conn != null) {
                RequestScope.detach(conn);
                conn.disconnect();
            }
        }
    }

    private static void copyExact(InputStream in, OutputStream out, long length)
            throws IOException {
        byte[] buffer = new byte[BLOCK_BYTES];
        long remaining = length;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min((long) buffer.length, remaining));
            if (n < 0) throw new IOException("upstream truncated, missing " + remaining + " bytes");
            if (n == 0) continue;
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static void writeHead(
            OutputStream out,
            int code,
            String contentType,
            long length,
            boolean ranged,
            String contentRange)
            throws IOException {
        StringBuilder head =
                new StringBuilder("HTTP/1.1 ")
                        .append(code)
                        .append(code == 206 ? " Partial Content" : " OK")
                        .append("\r\nConnection: close\r\nAccept-Ranges: bytes\r\n")
                        .append("Content-Type: ")
                        .append(
                                contentType != null && LocalProxy.safe(contentType)
                                        ? contentType
                                        : "application/octet-stream")
                        .append("\r\n");
        if (ranged && contentRange != null && LocalProxy.safe(contentRange)) {
            head.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Probe probe(String url, Map<String, String> headers, long start)
            throws IOException {
        long probeStart = Math.max(0L, start);
        HttpURLConnection conn =
                open(url, headers, "bytes=" + probeStart + "-" + (probeStart + 1));
        try {
            Probe result = new Probe();
            result.code = conn.getResponseCode();
            result.contentType = conn.getContentType();
            long[] cr = LocalProxy.parseContentRange(conn.getHeaderField("Content-Range"));
            if (result.code == 206 && cr != null && cr[0] == probeStart && cr[2] > 0) {
                result.rangeSupported = true;
                result.total = cr[2];
            }
            AppLog.w(TAG, "probe http=" + result.code + " range=" + result.rangeSupported);
            return result;
        } finally {
            RequestScope.detach(conn);
            conn.disconnect();
        }
    }

    private static final class Probe {
        int code;
        boolean rangeSupported;
        long total = -1L;
        String contentType;
    }

    private static HttpURLConnection open(
            String url, Map<String, String> headers, String range) throws IOException {
        HttpURLConnection conn = ProxyAddress.openHttpConnection(new URL(url));
        RequestScope.attach(conn);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        boolean hasUa = false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || key.equalsIgnoreCase("Host")) continue;
            if (key.equalsIgnoreCase("User-Agent")) hasUa = true;
            conn.setRequestProperty(key, value);
        }
        if (!hasUa) conn.setRequestProperty("User-Agent", "MarcoMovie/0.2.0");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (range != null && !range.isEmpty()) conn.setRequestProperty("Range", range);
        return conn;
    }

    private static long parseContentLength(HttpURLConnection conn) {
        String header = conn.getHeaderField("Content-Length");
        if (header != null) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1L;
    }

    /** Returns {start, end} for a single "bytes=a-b"/"bytes=a-" range, or null when unsupported. */
    static long[] parseRange(String header) {
        if (header == null || header.trim().isEmpty()) return new long[] {0L, -1L};
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) return null;
        String spec = value.substring(6).trim();
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        if (startPart.isEmpty()) return null;
        try {
            long start = Long.parseLong(startPart);
            if (start < 0) return null;
            long end = -1L;
            if (!endPart.isEmpty()) {
                end = Long.parseLong(endPart);
                if (end < start) return null;
            }
            return new long[] {start, end};
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** One upstream Range connection feeding a bounded queue consumed in order. */
    private static final class RangeChunk implements Runnable {
        private final String url;
        private final Map<String, String> headers;
        final long start;
        final long end;
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_BLOCKS);
        private volatile HttpURLConnection connection;
        volatile Throwable error;
        volatile boolean aborted;
        /** Bytes this block has already handed to the player (consumer thread only). */
        long delivered;

        RangeChunk(String url, Map<String, String> headers, long start, long end) {
            this.url = url;
            this.headers = headers;
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1;
        }

        void abort() {
            aborted = true;
            HttpURLConnection conn = connection;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void run() {
            try {
                // Some CDNs cap how much one Range response may return. Keep asking for the rest
                // of this block instead of aborting the response the player is already reading.
                long cursor = start;
                while (cursor <= end && !aborted) {
                    HttpURLConnection conn = open(url, headers, "bytes=" + cursor + "-" + end);
                    connection = conn;
                    try {
                        if (aborted) throw new IOException("range chunk cancelled");
                        int code = conn.getResponseCode();
                        long[] cr =
                                LocalProxy.parseContentRange(
                                        conn.getHeaderField("Content-Range"));
                        if (code != 206 || cr == null || cr[0] != cursor || cr[1] < cr[0]) {
                            throw new IOException(
                                    "range chunk HTTP "
                                            + code
                                            + " content-range="
                                            + (cr == null ? "-" : cr[0] + "-" + cr[1]));
                        }
                        // A server may return more than we asked for; never read past this block.
                        long available = Math.min(cr[1] - cursor + 1L, end - cursor + 1L);
                        try (InputStream in = conn.getInputStream()) {
                            long remaining = available;
                            byte[] buffer = new byte[BLOCK_BYTES];
                            while (remaining > 0 && !aborted) {
                                int n =
                                        in.read(
                                                buffer,
                                                0,
                                                (int)
                                                        Math.min(
                                                                (long) buffer.length, remaining));
                                if (n < 0) {
                                    throw new IOException("range chunk truncated at " + cursor);
                                }
                                if (n == 0) continue;
                                byte[] block = Arrays.copyOf(buffer, n);
                                while (!aborted
                                        && !queue.offer(block, 250, TimeUnit.MILLISECONDS)) {
                                    // Consumer is slower than the network; keep the window small.
                                }
                                remaining -= n;
                            }
                            if (remaining > 0) throw new IOException("range chunk aborted");
                        }
                        cursor += available;
                    } finally {
                        RequestScope.detach(conn);
                        conn.disconnect();
                        if (connection == conn) connection = null;
                    }
                }
            } catch (Throwable failure) {
                error = failure;
            }
        }
    }
}
