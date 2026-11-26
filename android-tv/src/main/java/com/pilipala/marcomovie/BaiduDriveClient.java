package com.pilipala.marcomovie;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Drive browser for 百度网盘: list a directory with {@code /api/list} and turn a file in that
 * listing into a playable direct link through {@link BaiduShareResolver#ownFileDlink}.
 *
 * <p>Protocol measured on the box on 2026-11-26/27 (see
 * {@code 14-master/records/2026-11-26-网盘支持矩阵.md}):
 *
 * <ul>
 *   <li>list: {@code GET https://pan.baidu.com/api/list?dir=%2F&order=time&desc=1&showempty=0&
 *       web=1&page=1&num=100&channel=chunlei&app_id=250528&clienttype=0&bdstoken=…}
 *       → {@code {"errno":0,"list":[{"fs_id":…,"server_filename":…,"isdir":0,"size":…,
 *       "path":"/…"}]}}。Cookie 必须含 {@code PANWEB=1}，否则 {@code errno -9}（
 *       {@link BaiduShareResolver#accountCookie} 已经补上）；{@code bdstoken} 用
 *       {@link BaiduShareResolver#ownBdstoken} 取。</li>
 *   <li>play: {@code /api/filemetas?dlink=1&fsids=[…]} + 302 landing, delegated to
 *       {@link BaiduShareResolver} so the share path and the browse path share one implementation.</li>
 * </ul>
 *
 * <p>{@link NetdiskEntry#fid} carries two different kinds of id on purpose: a folder row holds its
 * {@code /path} (that is what {@code /api/list} takes to descend) and a file row holds its
 * {@code fs_id} (that is what {@code filemetas} takes to resolve). Both are stable across
 * listings, so the browser and the watch history never guess.
 *
 * <p>Threading: synchronous, throws {@link IOException}, caller runs it off the UI thread.
 */
final class BaiduDriveClient implements NetdiskDrive {

    private static final String TAG = "BaiduDrive";

    private static final String LIST_BASE = "https://pan.baidu.com/api/list";
    /** One page of 100 rows, per the measured URL in the support matrix. */
    private static final int LIST_PAGE_SIZE = 100;

    private static final String ROOT = "/";

    private final BaiduLogin.Credential credential;

    BaiduDriveClient(BaiduLogin.Credential credential) {
        this.credential = credential;
    }

    @Override
    public String label() {
        return "百度";
    }

    @Override
    public String rootDir() {
        return ROOT;
    }

    @Override
    public List<NetdiskEntry> list(String dir) throws IOException {
        String path = normalizeDir(dir);
        String bdstoken = BaiduShareResolver.ownBdstoken(credential);
        if (bdstoken.isEmpty()) {
            throw new IOException("百度网盘登录已失效（取不到 bdstoken），请重新扫码登录");
        }
        String url =
                LIST_BASE
                        + "?dir="
                        + NetdiskShareHttp.encodeQuery(path)
                        + "&order=time&desc=1&showempty=0&web=1&page=1&num="
                        + LIST_PAGE_SIZE
                        + "&channel=chunlei&app_id=250528&clienttype=0&bdstoken="
                        + NetdiskShareHttp.encodeQuery(bdstoken);
        String cookie = BaiduShareResolver.accountCookie(credential, "");
        BaiduShareResolver.Response answer;
        try {
            answer =
                    BaiduShareResolver.fetch(
                            "GET",
                            url,
                            null,
                            null,
                            BaiduShareResolver.UA_SHARE,
                            BaiduShareResolver.REFERER,
                            cookie);
        } catch (IOException error) {
            throw new IOException(
                    "百度网盘请求失败，请检查网络（" + NetdiskShareHttp.describe(error) + "）", error);
        }
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            throw new IOException("百度网盘响应格式异常（HTTP " + answer.http + "）");
        }
        int errno = NetdiskShareHttp.optInt(body, "errno", -1);
        if (errno != 0) {
            String said = message(body);
            if (errno == -9) {
                throw new IOException("百度网盘拒绝了列目录（errno=-9，Cookie 可能已失效），请重新扫码登录");
            }
            throw new IOException("百度网盘列目录失败：errno=" + errno + (said.isEmpty() ? "" : "，" + said));
        }
        List<NetdiskEntry> out = new ArrayList<>();
        JsonElement list = body.get("list");
        if (list != null && list.isJsonArray()) {
            for (JsonElement item : list.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject row = item.getAsJsonObject();
                String name = NetdiskShareHttp.optString(row, "server_filename").trim();
                if (name.isEmpty()) continue;
                boolean isDir = NetdiskShareHttp.optInt(row, "isdir", 0) != 0;
                String key;
                if (isDir) {
                    key = NetdiskShareHttp.optString(row, "path").trim();
                    if (key.isEmpty()) key = NetdiskShareHttp.joinPath(path, name);
                } else {
                    key = NetdiskShareHttp.optString(row, "fs_id").trim();
                }
                if (key.isEmpty()) continue;
                out.add(
                        new NetdiskEntry(
                                key,
                                name,
                                isDir,
                                NetdiskShareHttp.optLong(row, "size", 0L)));
            }
        }
        AppLog.w(
                TAG,
                "list ok dir="
                        + path
                        + " n="
                        + out.size()
                        + " total="
                        + NetdiskShareHttp.optInt(body, "total", -1));
        return out;
    }

    /**
     * {@link NetdiskDrive#play(String, int)}: 百度的直链只有一个原文件，没有画质档位，所以
     * {@code maxRank} 不参与选择（{@link Play#rank} 恒为 0，画质守卫不会对百度做降档）。
     */
    @Override
    public Play play(String fileId, int maxRank) throws IOException {
        String fsId = NetdiskShareHttp.text(fileId).trim();
        if (fsId.isEmpty()) throw new IOException("文件 id 为空，无法获取播放地址");
        BaiduShareResolver.OwnFile link = BaiduShareResolver.ownFileDlink(credential, fsId);
        if (!link.ok()) {
            throw new IOException(
                    link.error.isEmpty() ? "百度网盘没有给出播放地址" : link.error);
        }
        AppLog.w(
                TAG,
                "play ok fs_id=" + fsId + " host=" + NetdiskShareHttp.hostOf(link.url));
        return new Play(link.url, "", 0, link.cookie, link.ua, link.referer);
    }

    /** 目录参数：空/根都给 {@code /}，相对路径补前导斜杠（{@code /api/list} 只认绝对路径）。 */
    private static String normalizeDir(String dir) {
        String path = NetdiskShareHttp.text(dir).trim();
        if (path.isEmpty()) return ROOT;
        return path.startsWith("/") ? path : "/" + path;
    }

    /** 百度那句给人看的话：{@code show_msg} 优先，其次 {@code errmsg}。 */
    private static String message(JsonObject body) {
        String show = NetdiskShareHttp.optString(body, "show_msg").trim();
        return show.isEmpty() ? NetdiskShareHttp.optString(body, "errmsg").trim() : show;
    }
}
