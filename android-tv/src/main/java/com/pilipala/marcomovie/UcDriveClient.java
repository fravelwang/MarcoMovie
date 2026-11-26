package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drive browser for UC 网盘: list a directory with {@code /1/clouddrive/file/sort} and turn a file
 * in that listing into a playable direct link through {@code /1/clouddrive/file/download}.
 *
 * <p>Protocol measured on the host on 2026-11-27 (see
 * {@code 14-master/agent-use/tmp/cd480b58/T-02-uc-ali-侦察.md}):
 *
 * <ul>
 *   <li>list: {@code GET https://pc-api.uc.cn/1/clouddrive/file/sort?pr=UCBrowser&fr=pc&
 *       pdir_fid=0&_page=1&_size=40&_fetch_total=1&_fetch_sub_dirs=0&
 *       _sort=file_type:asc,file_name:asc}. Cookie only needs {@code __pus}; dropping
 *       {@code pr}/{@code fr} makes the service answer 401 with a WAF body that looks like an
 *       expired login, so they stay in the URL verbatim.</li>
 *   <li>direct link: {@code POST /1/clouddrive/file/download?entry=ft&fr=pc&pr=UCBrowser&
 *       uc_param_str=&__dt=&__t=} with {@code {"fids":["<fid>"]}} → {@code data[0].download_url}.
 *       The CDN URL only serves with the short-lived {@code __puus}, which is not in the box's
 *       {@code uc.json}; {@code GET /1/clouddrive/config?pr=UCBrowser&fr=pc} returns it in
 *       {@code Set-Cookie}. {@link UcShareResolver} already implements that refresh, so this class
 *       reuses it instead of writing a second copy.</li>
 * </ul>
 *
 * <p>Two UC quirks this class handles: a directory fid answers {@code 200/code 0/data[]} (empty
 * array is a failure, not "no link"), and UC files may have no extension at all — playability comes
 * from {@code format_type}/{@code category}, which is what {@link NetdiskEntry#videoHint} carries to
 * the grid.
 *
 * <p>Threading: synchronous, throws {@link IOException}, caller runs it off the UI thread.
 */
final class UcDriveClient implements NetdiskDrive {

    private static final String TAG = "UcDrive";

    private static final String LIST_BASE = "https://pc-api.uc.cn/1/clouddrive/file/sort";

    /** One page, the size the measured URL asks for. */
    private static final int LIST_PAGE_SIZE = 40;

    private static final String ROOT = "0";

    private final UcLogin.Credential credential;

    UcDriveClient(UcLogin.Credential credential) {
        this.credential = credential;
    }

    @Override
    public String label() {
        return "UC";
    }

    @Override
    public String rootDir() {
        return ROOT;
    }

    @Override
    public List<NetdiskEntry> list(String dir) throws IOException {
        String pdir = NetdiskShareHttp.text(dir).trim();
        if (pdir.isEmpty()) pdir = ROOT;
        String cookie = credential == null ? "" : credential.pus;
        cookie = cookie.isEmpty() ? "" : "__pus=" + cookie;
        if (cookie.isEmpty()) {
            throw new IOException("UC 网盘还没登录，请先扫码或粘贴 Cookie");
        }
        String url =
                LIST_BASE
                        + "?pr=UCBrowser&fr=pc&pdir_fid="
                        + NetdiskShareHttp.encodeQuery(pdir)
                        + "&_page=1&_size="
                        + LIST_PAGE_SIZE
                        + "&_fetch_total=1&_fetch_sub_dirs=0&_sort="
                        + NetdiskShareHttp.encodeQuery("file_type:asc,file_name:asc");
        UcShareResolver.Response answer;
        try {
            answer = UcShareResolver.call(url, null, cookie);
        } catch (IOException error) {
            throw new IOException(
                    "UC 网盘请求失败，请检查网络（" + NetdiskShareHttp.describe(error) + "）", error);
        }
        if (answer.http == 401 || answer.http == 403) {
            throw new IOException("UC 登录已失效，请在网盘中心重新扫码或粘贴 Cookie");
        }
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            throw new IOException("UC 网盘响应格式异常（HTTP " + answer.http + "）");
        }
        int status = NetdiskShareHttp.optInt(body, "status", answer.http);
        int code = NetdiskShareHttp.optInt(body, "code", -1);
        if (status != 200 || code != 0) {
            throw new IOException(ucError(body, answer.http));
        }
        List<NetdiskEntry> out = new ArrayList<>();
        JsonElement data = body.get("data");
        JsonElement list =
                data != null && data.isJsonObject() ? data.getAsJsonObject().get("list") : null;
        if (list != null && list.isJsonArray()) {
            for (JsonElement item : list.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject row = item.getAsJsonObject();
                String fid = NetdiskShareHttp.optString(row, "fid").trim();
                String name = NetdiskShareHttp.optString(row, "file_name").trim();
                if (fid.isEmpty() || name.isEmpty()) continue;
                boolean isDir = NetdiskShareHttp.optInt(row, "file_type", -1) == 0;
                out.add(
                        new NetdiskEntry(
                                fid,
                                name,
                                isDir,
                                NetdiskShareHttp.optLong(row, "size", 0L),
                                isVideoRow(row)));
            }
        }
        AppLog.w(
                TAG,
                "list ok dir="
                        + pdir
                        + " n="
                        + out.size()
                        + " total="
                        + total(body));
        return out;
    }

    /**
     * {@link NetdiskDrive#play(String, int)}: UC has one original file per id (no quality tiers), so
     * {@code maxRank} does not take part and {@link Play#rank} is 0.
     */
    @Override
    public Play play(String fileId, int maxRank) throws IOException {
        String fid = NetdiskShareHttp.text(fileId).trim();
        if (fid.isEmpty()) throw new IOException("文件 id 为空，无法获取播放地址");
        Map<String, String> cookies = UcLogin.apiCookies(credential);
        if (cookies.isEmpty()) throw new IOException("UC 网盘还没登录，请先扫码或粘贴 Cookie");
        // Same cookie rule as UcShareResolver's account path: config first, with a single account
        // key (both __pus and __puus together are rejected), then use whatever it rotates back.
        if (cookies.containsKey("__pus")) cookies.remove("__puus");
        UcShareResolver.Response config =
                UcShareResolver.call(
                        UcShareResolver.CONFIG_URL, null, UcShareResolver.cookieHeader(cookies));
        if (config.http == 404 || config.http == 405 || config.http == 501) {
            // Reference implementation sends GET; a deployment that only accepts POST gets one retry.
            config =
                    UcShareResolver.call(
                            UcShareResolver.CONFIG_URL, "{}", UcShareResolver.cookieHeader(cookies));
        }
        cookies.putAll(config.sessionCookies);
        if (config.http == 401 || config.http == 403) {
            throw new IOException("UC 登录已失效，请在网盘中心重新扫码或粘贴 Cookie");
        }
        AppLog.w(TAG, "config ok keys=" + cookies.keySet());
        JsonObject payload = new JsonObject();
        JsonArray fids = new JsonArray();
        fids.add(fid);
        payload.add("fids", fids);
        UcShareResolver.Response answer =
                UcShareResolver.call(
                        UcShareResolver.DOWNLOAD_URL,
                        payload.toString(),
                        UcShareResolver.cookieHeader(cookies));
        cookies.putAll(answer.sessionCookies);
        if (answer.http == 401 || answer.http == 403) {
            throw new IOException("UC 登录已失效，请在网盘中心重新扫码或粘贴 Cookie");
        }
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            throw new IOException("UC 网盘响应格式异常（HTTP " + answer.http + "）");
        }
        int status = NetdiskShareHttp.optInt(body, "status", answer.http);
        int code = NetdiskShareHttp.optInt(body, "code", -1);
        if (status != 200 || code != 0) throw new IOException(ucError(body, answer.http));
        // A directory fid answers 200/code 0 with an empty data[]: treat it as "no link", not success.
        String url = UcShareResolver.downloadUrl(body);
        if (url.isEmpty()) throw new IOException("UC 网盘没有给出播放地址（可能是文件夹或空文件）");
        String cookie = UcShareResolver.cookieHeader(cookies);
        if (cookie.isEmpty()) {
            throw new IOException("UC 登录已失效，请在网盘中心重新扫码或粘贴 Cookie");
        }
        AppLog.w(
                TAG,
                "play ok fid="
                        + fid
                        + " host="
                        + NetdiskShareHttp.hostOf(url)
                        + " cookie_keys="
                        + cookies.keySet());
        return new Play(url, "", 0, cookie, UcShareResolver.UA, UcShareResolver.REFERER);
    }

    /** UC marks videos with {@code format_type} / {@code category}; the name may have no extension. */
    private static boolean isVideoRow(JsonObject row) {
        String format = NetdiskShareHttp.optString(row, "format_type").toLowerCase(Locale.ROOT);
        String category = NetdiskShareHttp.optString(row, "category").toLowerCase(Locale.ROOT);
        String objCategory =
                NetdiskShareHttp.optString(row, "obj_category").toLowerCase(Locale.ROOT);
        return format.startsWith("video/")
                || category.contains("video")
                || objCategory.contains("video");
    }

    private static int total(JsonObject body) {
        JsonElement metadata = body == null ? null : body.get("metadata");
        return metadata != null && metadata.isJsonObject()
                ? NetdiskShareHttp.optInt(metadata.getAsJsonObject(), "_total", -1)
                : -1;
    }

    /** One human-readable line for a failed envelope (status/code always included). */
    private static String ucError(JsonObject body, int http) {
        String message = NetdiskShareHttp.optString(body, "message").trim();
        int status = NetdiskShareHttp.optInt(body, "status", http);
        int code = NetdiskShareHttp.optInt(body, "code", -1);
        return "UC 网盘接口返回错误"
                + (message.isEmpty() ? "" : "：" + message)
                + "（code="
                + code
                + "，status="
                + status
                + "）";
    }
}
