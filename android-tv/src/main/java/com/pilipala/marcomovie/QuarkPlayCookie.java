package com.pilipala.marcomovie;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 夸克播放直链要带的「新鲜 Cookie」。
 *
 * <p>{@code /file/v2/play} 每次应答都在 {@code Set-Cookie} 里轮换 {@code __puus} 并下发
 * {@code Video-Auth}，视频 CDN 缺了这两个就回 412（2026-11-27 实测：只带保存的旧 Cookie 是 412，
 * 把同一次响应的 Set-Cookie 合进来后 206 video/mp4）。保存的 Cookie 只够调 {@code drive-pc}
 * 的目录接口，所以每次播放都要补这一跳。
 *
 * <p>两条播放链路共用这一份实现：分享线（{@link QuarkShareResolver}）与网盘中心自己的文件
 * （{@link QuarkDriveClient#play(String, int)} 建 episode 时）。
 *
 * <p>纯 JDK：不依赖 Android，失败只回原 Cookie，绝不让播放因为补 Cookie 而失败。
 */
final class QuarkPlayCookie {

    private static final String BASE = "https://drive-pc.quark.cn/1/clouddrive";
    private static final String UA = "Mozilla/5.0";
    private static final String REFERER = "https://pan.quark.cn/";
    private static final String ACCEPT = "application/json";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    /** Only these two are session credentials the CDN checks; everything else stays as saved. */
    private static final String[] SESSION_NAMES = {"__puus", "Video-Auth"};

    private QuarkPlayCookie() {}

    /**
     * Fetches a fresh play session cookie for one file.
     *
     * @param cookie the saved account cookie header
     * @param fid the file id about to be played (must live in the user's own drive)
     * @param deadlineEpochMs when the caller's own budget runs out (0 = no external cap)
     * @return the saved cookie with {@code __puus} / {@code Video-Auth} replaced, or the saved
     *     cookie unchanged when the refresh call fails or answers nothing usable
     */
    static String refresh(String cookie, String fid, long deadlineEpochMs) {
        String base = text(cookie);
        String id = text(fid);
        if (base.isEmpty() || id.isEmpty()) return cookie;
        long remaining = deadlineEpochMs <= 0 ? Long.MAX_VALUE : deadlineEpochMs - System.currentTimeMillis();
        if (remaining <= 0) return cookie;
        JsonObject payload = new JsonObject();
        payload.addProperty("fid", id);
        payload.addProperty("resolutions", "normal,low,high,super,2k,4k");
        payload.addProperty("supports", "fmp4,m3u8");
        List<String> updates = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + "/file/v2/play?pr=ucpro&fr=pc").openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remaining));
            conn.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remaining));
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", ACCEPT);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Cookie", base);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            int http = conn.getResponseCode();
            // The body is not needed; draining it keeps the connection reusable.
            readAll(http >= 400 ? conn.getErrorStream() : conn.getInputStream());
            updates = sessionPairs(conn.getHeaderFields());
        } catch (IOException error) {
            AppLog.w("QuarkPlay", "play cookie refresh failed, keeping the saved cookie: " + error);
            return cookie;
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (updates.isEmpty()) return cookie;
        AppLog.w("QuarkPlay", "play cookie refreshed names=" + names(updates));
        return merge(base, updates);
    }

    /**
     * The {@code __puus} / {@code Video-Auth} pairs of one response.
     *
     * <p>{@code conn.getHeaderFields()} keys are case-insensitive in practice but not normalised,
     * so the lookup compares ignoring case. Values keep only {@code name=value}.
     */
    static List<String> sessionPairs(Map<String, List<String>> headers) {
        List<String> out = new ArrayList<>();
        if (headers == null) return out;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())) continue;
            if (header.getValue() == null) continue;
            for (String raw : header.getValue()) {
                String pair = raw == null ? "" : raw.split(";", 2)[0].trim();
                if (pair.isEmpty()) continue;
                String name = pair.split("=", 2)[0].trim();
                for (String wanted : SESSION_NAMES) {
                    if (wanted.equals(name)) {
                        out.add(pair);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** {@code __puus,Video-Auth}: names only, the values are credentials. */
    static String names(List<String> pairs) {
        StringBuilder sb = new StringBuilder();
        for (String pair : pairs) {
            String name = pair.split("=", 2)[0].trim();
            if (sb.length() > 0) sb.append(',');
            sb.append(name);
        }
        return sb.toString();
    }

    /** Replaces every pair whose name appears in {@code updates}, then appends the new pairs. */
    static String merge(String cookie, List<String> updates) {
        List<String> pairs = new ArrayList<>();
        for (String part : text(cookie).split(";")) {
            String pair = part.trim();
            if (pair.isEmpty()) continue;
            String name = pair.split("=", 2)[0].trim();
            boolean replaced = false;
            for (String update : updates) {
                if (update.split("=", 2)[0].trim().equals(name)) {
                    replaced = true;
                    break;
                }
            }
            if (!replaced) pairs.add(pair);
        }
        pairs.addAll(updates);
        return String.join("; ", pairs);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // Closing a request body must never fail the call.
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ignored) {
            return "";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }
}
