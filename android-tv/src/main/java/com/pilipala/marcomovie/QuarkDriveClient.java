package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drive browsing / direct-link client for 夸克网盘 (Quark), driven by the cookie blob that
 * {@link QuarkQrLogin} already saves. Browsing a netdisk is three calls: list a directory, search
 * by keyword, and swap a file id for a playback URL.
 *
 * <p>Everything here is plain JDK + Gson on purpose (no Android APIs), so the same code can be
 * compiled and exercised on a desktop JDK before it runs on the box. All methods are synchronous
 * and throw {@link IOException}: callers must run them off the UI thread.
 *
 * <p>Protocol measured against the live service on 2026-11-24 (2026-11-24T14:2x host probe):
 * <ul>
 *   <li>list: {@code GET /1/clouddrive/file/sort?...&pdir_fid=<fid>&_page=1&_size=40&_fetch_total=1}
 *   <li>search: {@code GET /1/clouddrive/file/search?...&_page=1&_size=20&_fetch_total=1&q=<kw>}
 *   <li>direct link: {@code POST /1/clouddrive/file/download?pr=ucpro&fr=pc} with
 *       {@code {"fids":["<fid>"]}}
 * </ul>
 * All three answer with the same envelope: {@code {"status":200,"code":0,"metadata":{...},
 * "data":...}}. Only {@code status == 200 && code == 0} is a success; HTTP 401 means the cookie is
 * dead. The direct link that {@link #directUrl} returns is meant to be handed to the player
 * verbatim, together with the same cookie (it also supports HTTP Range requests).
 */
final class QuarkDriveClient implements NetdiskDrive {

    /** Only the cookie matters for the PC endpoints; {@code driveSt} is not used here. */
    private static final String UA = "Mozilla/5.0";
    private static final String REFERER = "https://pan.quark.cn/";
    private static final String ACCEPT = "application/json";
    private static final String BASE = "https://drive-pc.quark.cn/1/clouddrive";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    /** {@code listPage} fetches one page at a time; see {@link #listPage(String, String)}. */
    private static final int LIST_PAGE_SIZE = 40;
    /** {@code search} fetches one page only; see {@link #search(String)}. */
    private static final int SEARCH_PAGE_SIZE = 20;

    private static final String SORT = "file_type:asc,file_name:asc";

    private static final String LOGIN_EXPIRED = "夸克登录已失效，请重新扫码";

    /**
     * One playable transcoded tier. {@code rank} uses the same scale as
     * {@link #resolutionRank(String)} (4k=6 ... normal=1); {@code 0} means the URL is the original
     * file from {@link #directUrl(String)} and its quality is unknown.
     */
    static final class PlayVariant {
        final String url;
        final String resolution;
        final int rank;

        PlayVariant(String url, String resolution, int rank) {
            this.url = url;
            this.resolution = resolution;
            this.rank = rank;
        }
    }

    private final String cookie;

    /** Total reported by the last {@link #list} / {@link #search} call, or -1 when unknown. */
    private int lastTotal = -1;

    /**
     * @param cookie the saved 夸克 cookie header (as produced by {@link QuarkQrLogin}), e.g.
     *     {@code "ctoken=...; __pus=...; __puus=..."}
     */
    QuarkDriveClient(String cookie) {
        this.cookie = cookie == null ? "" : cookie.trim();
    }

    /**
     * Builds a client from the credential {@link QuarkQrLogin#loadSaved()} reads.
     *
     * @return null when nobody is logged in (or the saved blob is unusable)
     */
    static QuarkDriveClient fromSaved() {
        QuarkQrLogin.Credential credential = QuarkQrLogin.loadSaved();
        if (credential == null || credential.cookie == null || credential.cookie.trim().isEmpty()) {
            return null;
        }
        return new QuarkDriveClient(credential.cookie);
    }

    @Override
    public String label() {
        return "夸克";
    }

    @Override
    public String rootDir() {
        return "0";
    }

    /**
     * {@link NetdiskDrive#play(String, int)}: the adaptive tier pick plus the cookie and headers a
     * 夸克 direct URL needs. Same {@link #playVariant} logic the quality guard and the browser used
     * before the drive interface existed, so what the grid renders and the player opens is unchanged.
     */
    @Override
    public Play play(String fileId, int maxRank) throws IOException {
        PlayVariant variant = playVariant(fileId, maxRank);
        if (variant == null) return null;
        return new Play(variant.url, variant.resolution, variant.rank, cookie, UA, REFERER);
    }

    /**
     * Lists one page of a directory. A null or empty {@code dirFid} means the root ({@code "0"});
     * a null/empty {@code cursor} means page 1.
     *
     * <p>Pagination follows the service's own {@code metadata._total}: the browser keeps the cursor
     * of {@link NetdiskDrive.Page#next} to ask for the following page, so the first screen never
     * waits for page 2. {@link #lastTotal()} still reports the total of the last list/search call.
     */
    @Override
    public Page listPage(String dirFid, String cursor) throws IOException {
        String fid = dirFid == null || dirFid.trim().isEmpty() ? "0" : dirFid.trim();
        int page = pageOf(cursor);
        String url =
                BASE
                        + "/file/sort?pr=ucpro&fr=pc&pdir_fid="
                        + QuarkQrLogin.encodeQuery(fid)
                        + "&_page="
                        + page
                        + "&_size="
                        + LIST_PAGE_SIZE
                        + "&_fetch_total=1&_sort="
                        + SORT;
        List<NetdiskEntry> rows = parseEntries(call("GET", url, null));
        int total = lastTotal;
        // 夸克 reports metadata._total, so "more" is exact instead of "the page was full".
        boolean more =
                !rows.isEmpty()
                        && (total >= 0
                                ? (long) page * LIST_PAGE_SIZE < total
                                : rows.size() >= LIST_PAGE_SIZE);
        String next = more ? String.valueOf(page + 1) : null;
        AppLog.w(
                "QuarkDrive",
                "page ok dir="
                        + fid
                        + " page="
                        + page
                        + " n="
                        + rows.size()
                        + " total="
                        + total
                        + " next="
                        + (next == null ? "-" : next));
        return new Page(rows, next, total);
    }

    /** 游标就是页码；空/null 是第 1 页，坏游标退回第 1 页（宁可重取也不跳页）。 */
    private static int pageOf(String cursor) {
        String text = cursor == null ? "" : cursor.trim();
        if (text.isEmpty()) return 1;
        try {
            int page = Integer.parseInt(text);
            return page < 1 ? 1 : page;
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /**
     * Searches the whole drive by keyword (file name). Same envelope and same page-size limitation
     * as {@link #listPage(String, String)}: one page of {@value #SEARCH_PAGE_SIZE} hits is returned, and
     * {@link #lastTotal()} reports {@code metadata._total}.
     */
    List<NetdiskEntry> search(String keyword) throws IOException {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) throw new IOException("搜索关键词不能为空");
        String url =
                BASE
                        + "/file/search?pr=ucpro&fr=pc&_page=1&_size="
                        + SEARCH_PAGE_SIZE
                        + "&_fetch_total=1&q="
                        + QuarkQrLogin.encodeQuery(q);
        return parseEntries(call("GET", url, null));
    }

    /**
     * Swaps a file id for a URL the player can open directly. The URL is tied to the account
     * cookie and must be fetched with the same {@code Cookie} header (it honours {@code Range}).
     */
    String directUrl(String fid) throws IOException {
        String id = fid == null ? "" : fid.trim();
        if (id.isEmpty()) throw new IOException("文件 id 为空，无法获取播放地址");
        JsonObject payload = new JsonObject();
        JsonArray fids = new JsonArray();
        fids.add(id);
        payload.add("fids", fids);
        JsonObject body = call("POST", BASE + "/file/download?pr=ucpro&fr=pc", payload.toString());
        JsonElement data = body.get("data");
        if (data == null || !data.isJsonArray() || data.getAsJsonArray().size() == 0) {
            throw new IOException("夸克网盘未返回播放地址");
        }
        JsonElement first = data.getAsJsonArray().get(0);
        if (first == null || !first.isJsonObject()) {
            throw new IOException("夸克网盘未返回播放地址");
        }
        String url = optString(first.getAsJsonObject(), "download_url");
        if (url.isEmpty() || !url.startsWith("http")) {
            throw new IOException("夸克网盘未返回播放地址");
        }
        return url;
    }

    /**
     * Playback URL for a video, preferring the transcode endpoint.
     *
     * <p>{@code /file/v2/play} works for every file we tried, including a 14.5 GB 4K original, and
     * returns one URL per quality tier. The plain {@code /file/download} link is refused by 夸克 for
     * many files with {@code code=23018 download file size limit} even when they are only 53 MB, so
     * it is only a fallback here.
     *
     * @return the best-quality playable URL
     */
    String playUrl(String fid) throws IOException {
        String id = fid == null ? "" : fid.trim();
        if (id.isEmpty()) throw new IOException("文件 id 为空，无法获取播放地址");
        String transcoded = transcodedUrl(id);
        if (!transcoded.isEmpty()) return transcoded;
        return directUrl(id);
    }

    /**
     * Playback URL for the adaptive quality guard: the best tier at or below {@code maxRank}.
     *
     * <p>Uses the same endpoint and the same original-file fallback as {@link #playUrl(String)},
     * but the caller can cap the tier (for example skip 4K after the box proved it cannot decode
     * it) and learns which tier the URL actually is. The original-file fallback carries
     * {@code rank == 0} because {@code /file/download} does not report a resolution.
     *
     * @return null when the service lists tiers but none at or below {@code maxRank}. The caller
     *     decides whether to relax the cap or keep the current playback. Never null for the
     *     original-file fallback.
     */
    PlayVariant playVariant(String fid, int maxRank) throws IOException {
        String id = fid == null ? "" : fid.trim();
        if (id.isEmpty()) throw new IOException("文件 id 为空，无法获取播放地址");
        List<PlayVariant> variants = transcodedVariants(id);
        if (variants.isEmpty()) return new PlayVariant(directUrl(id), "", 0);
        PlayVariant best = null;
        for (PlayVariant variant : variants) {
            if (variant.rank > maxRank) continue;
            if (best == null || variant.rank > best.rank) best = variant;
        }
        return best;
    }

    /** Highest available tier from {@code /file/v2/play}; empty when the service offers none. */
    private String transcodedUrl(String id) throws IOException {
        PlayVariant best = null;
        for (PlayVariant variant : transcodedVariants(id)) {
            if (best == null || variant.rank > best.rank) best = variant;
        }
        return best == null ? "" : best.url;
    }

    /**
     * Parses every playable tier of {@code /file/v2/play}. A refused transcode is an empty list,
     * not an error, so callers can still fall back to {@link #directUrl(String)} exactly like
     * {@link #playUrl(String)} did before the quality guard existed.
     */
    private List<PlayVariant> transcodedVariants(String id) {
        List<PlayVariant> out = new ArrayList<>();
        JsonObject payload = new JsonObject();
        payload.addProperty("fid", id);
        payload.addProperty("resolutions", "normal,low,high,super,2k,4k");
        payload.addProperty("supports", "fmp4,m3u8");
        JsonObject body;
        try {
            body = call("POST", BASE + "/file/v2/play?pr=ucpro&fr=pc", payload.toString());
        } catch (IOException first) {
            return out;
        }
        JsonElement data = body.get("data");
        if (data == null || !data.isJsonObject()) return out;
        JsonElement list = data.getAsJsonObject().get("video_list");
        if (list == null || !list.isJsonArray()) return out;
        for (JsonElement item : list.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) continue;
            JsonObject variant = item.getAsJsonObject();
            String resolution = optString(variant, "resolution").toLowerCase(Locale.ROOT);
            int rank = resolutionRank(resolution);
            JsonElement info = variant.get("video_info");
            if (info == null || !info.isJsonObject()) continue;
            String url = optString(info.getAsJsonObject(), "url");
            if (url.isEmpty() || !url.startsWith("http")) continue;
            out.add(new PlayVariant(url, resolution, rank));
        }
        return out;
    }

    /** 4k &gt; 2k &gt; super &gt; high &gt; low &gt; normal, matching the Flutter client's ordering. */
    private static int resolutionRank(String resolution) {
        switch (resolution) {
            case "4k":
                return 6;
            case "2k":
                return 5;
            case "super":
                return 4;
            case "high":
                return 3;
            case "low":
                return 2;
            case "normal":
                return 1;
            default:
                return 0;
        }
    }

    /** {@code metadata._total} of the last list/search call; -1 when the service did not report it. */
    int lastTotal() {
        return lastTotal;
    }

    // ---------------------------------------------------------------- response plumbing

    private static final class Response {
        final int http;
        final String body;

        Response(int http, String body) {
            this.http = http;
            this.body = body;
        }
    }

    /**
     * Performs one request and validates the shared envelope. Returns the parsed body (which is
     * guaranteed to carry {@code status == 200 && code == 0}).
     */
    private JsonObject call(String method, String url, String jsonBody) throws IOException {
        Response resp = request(method, url, jsonBody);
        if (resp.http == 401) throw new IOException(LOGIN_EXPIRED);
        // The service keeps the envelope on business errors too (measured: HTTP 400 + code 23018
        // "download file size limit"), so parse the body before falling back to the bare status.
        JsonObject body = parseObjectOrNull(resp.body);
        if (body != null) {
            int status = optInt(body, "status", -1);
            int code = optInt(body, "code", -1);
            if (status == 200 && code == 0) {
                if (resp.http != 200) {
                    throw new IOException("夸克网盘请求失败（HTTP " + resp.http + "）");
                }
                return body;
            }
            String codeText = code + (resp.http == 200 ? "" : "，HTTP " + resp.http);
            String message = optString(body, "message");
            if (message.isEmpty()) {
                throw new IOException(
                        "夸克网盘接口返回错误（code=" + codeText + "，status=" + status + "）");
            }
            throw new IOException("夸克网盘接口返回错误：" + message + "（code=" + codeText + "）");
        }
        if (resp.http != 200) throw new IOException("夸克网盘请求失败（HTTP " + resp.http + "）");
        throw new IOException("夸克网盘响应格式异常");
    }

    /**
     * Raw HTTP hop. No {@code Accept-Encoding} is added on purpose: the body is decoded as UTF-8
     * and never gunzipped, so the service must answer in plain text.
     */
    private Response request(String method, String url, String jsonBody) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", ACCEPT);
            conn.setRequestProperty("Referer", REFERER);
            if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            byte[] payload = null;
            if (jsonBody != null) {
                payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                    out.flush();
                } finally {
                    closeQuietly(out);
                }
            }
            int http = conn.getResponseCode();
            String body = readAll(http >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new Response(http, body);
        } catch (IOException e) {
            throw new IOException("夸克网盘请求失败，请检查网络（" + describe(e) + "）", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private List<NetdiskEntry> parseEntries(JsonObject body) {
        lastTotal = readTotal(body);
        List<NetdiskEntry> out = new ArrayList<>();
        JsonElement data = body.get("data");
        if (data == null || !data.isJsonObject()) return out;
        JsonElement list = data.getAsJsonObject().get("list");
        if (list == null || !list.isJsonArray()) return out;
        for (JsonElement item : list.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            String fid = optString(obj, "fid");
            if (fid.isEmpty()) continue;
            out.add(
                    new NetdiskEntry(
                            fid,
                            optString(obj, "file_name"),
                            optInt(obj, "file_type", -1) == 0,
                            optLong(obj, "size", 0L)));
        }
        return out;
    }

    /** {@code metadata._total} when present; -1 otherwise (the field is only read, never guessed). */
    private static int readTotal(JsonObject body) {
        JsonElement metadata = body.get("metadata");
        if (metadata == null || !metadata.isJsonObject()) return -1;
        return optInt(metadata.getAsJsonObject(), "_total", -1);
    }

    /** Parses a JSON object body; null when the body is empty, not JSON, or not an object. */
    private static JsonObject parseObjectOrNull(String body) {
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el == null || !el.isJsonObject()) return null;
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY_BYTES) break;
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return "";
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long optLong(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
