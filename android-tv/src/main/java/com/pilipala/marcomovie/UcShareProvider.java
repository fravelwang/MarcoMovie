package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * UC 分享 → 线路：免登录换 stoken、列目录、按文件造四段 id。
 *
 * <p>协议照 {@link UcShareResolver} 里在宿主机实测通的那一套（2026-11-26：token / detail 都走
 * {@code https://pc-api.uc.cn/1/clouddrive/…}，{@code pr=UCBrowser}，UA 用 uc-cloud-drive 串，
 * Referer {@code https://fast.uc.cn/}；免登录，不带任何 cookie）：
 * <ul>
 *   <li>换 stoken：{@code POST /share/sharepage/token?pr=UCBrowser&fr=pc&uc_param_str=&__dt=&__t=}，
 *       body {@code {"pwd_id":"…","passcode":"…"}} → {@code data.stoken}；
 *   <li>列目录：{@code GET /share/sharepage/detail?...&pdir_fid=&force=0&_page=1&_size=100&_fetch_total=1&_sort=file_type:asc,file_name:asc}
 *       → {@code data.list[]}（{@code file_type == 1} 是文件、{@code 0} 是目录）；
 *   <li>分享根只有一个目录时下钻一层（最多两层，与夸克那边同一个口径），一条分享最多
 *       {@link #MAX_FILES} 个文件，按文件名自然序排好；
 *   <li>id 是四段 {@code ++} 分隔：{@code fid ++ share_fid_token ++ pwd_id ++ stoken}，播放侧由
 *       {@link UcShareResolver} 拿它换直链。
 * </ul>
 *
 * <p>造出来的 id 一律先过 {@link UcShareResolver#looksLikeShareId(String)}（与播放侧同一套判据）：
 * 形状对不上的条目宁可丢掉，也不要塞进线路让播放器黑屏后弹一句笼统话。UA 与 Referer 直接引用
 * {@link UcShareResolver} 的包内常量，不抄第二份 —— 那两串是实测值，抄一份就会漂。
 */
final class UcShareProvider implements NetdiskShareProvider {

    static final String ID = "uc";
    /** 线路名：这条线路的 episode 是 UC 四段 id，播放侧按 id 形状认，不看名字。 */
    static final String LINE_NAME = "UC分享";

    private static final String TAG = NetdiskShareHttp.TAG;
    private static final String LABEL = "UC";
    private static final String BASE = "https://pc-api.uc.cn/1/clouddrive";
    /**
     * 实测那串 query：{@code pr=UCBrowser} 是必须的；空的 {@code __dt} / {@code __t} 也照发
     * （照 Flutter 端与实测请求的样子）。
     */
    private static final String QUERY = "?pr=UCBrowser&fr=pc&uc_param_str=&__dt=&__t=";

    private static final String SORT = "file_type:asc,file_name:asc";
    private static final String ROOT_FID = "0";
    private static final int PAGE_SIZE = 100;
    /** 一条分享最多收多少个文件（剧集），与夸克那边一致。 */
    private static final int MAX_FILES = 200;
    /** 最多两层：分享根 + 一层「剧名文件夹」。 */
    private static final int MAX_DEPTH = 1;

    @Override
    public String id() {
        return ID;
    }

    /** UC 分享链接的两个入口：{@code drive.uc.cn/s/…}（页面里常见）与 {@code fast.uc.cn/s/…}。 */
    @Override
    public boolean matches(String url) {
        String host = NetdiskShareHttp.hostOf(url);
        return "drive.uc.cn".equals(host) || "fast.uc.cn".equals(host);
    }

    @Override
    public List<Cms.Line> lines(String shareUrl, String passcode) throws Exception {
        NetdiskShareHttp.ShareRef share = NetdiskShareHttp.parseShare(shareUrl);
        if (share == null) return Collections.emptyList();
        String stoken = shareToken(share.pwdId, share.passcode);
        if (stoken.isEmpty()) return Collections.emptyList();
        List<FilePlan> files = new ArrayList<>();
        walk(share.pwdId, stoken, ROOT_FID, 0, files);
        if (files.isEmpty()) {
            AppLog.w(TAG, "provider=uc no file in share " + share.pwdId);
            return Collections.emptyList();
        }
        if (files.size() > 1) Collections.sort(files, BY_NAME);
        if (files.size() > MAX_FILES) files = new ArrayList<>(files.subList(0, MAX_FILES));
        Cms.Line line = new Cms.Line();
        line.name = LINE_NAME;
        for (FilePlan file : files) {
            Cms.Episode episode = new Cms.Episode();
            episode.name = file.name;
            episode.url = file.id;
            line.episodes.add(episode);
        }
        return Collections.singletonList(line);
    }

    // ------------------------------------------------------------------ UC 接口

    /** 换 stoken；失败（含 404 / 41011「分享已失效」/ 41008 要提取码）返回空串，只记日志。 */
    private static String shareToken(String pwdId, String passcode) {
        JsonObject payload = new JsonObject();
        payload.addProperty("pwd_id", pwdId);
        payload.addProperty("passcode", NetdiskShareHttp.text(passcode));
        try {
            JsonObject body =
                    NetdiskShareHttp.call(
                            LABEL,
                            "POST",
                            BASE + "/share/sharepage/token" + QUERY,
                            payload.toString(),
                            UcShareResolver.UA,
                            UcShareResolver.REFERER,
                            "",
                            "");
            JsonElement data = body.get("data");
            if (data == null || !data.isJsonObject()) return "";
            String stoken = NetdiskShareHttp.optString(data.getAsJsonObject(), "stoken").trim();
            if (stoken.isEmpty()) {
                AppLog.w(TAG, "provider=uc token answered without stoken for " + pwdId);
                return "";
            }
            return stoken;
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=uc token failed for "
                            + pwdId
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return "";
        }
    }

    /** 一页目录；失败返回 null（调用方当成「这层没东西」）。 */
    private static JsonArray listPage(String pwdId, String stoken, String dirFid) {
        String url =
                BASE
                        + "/share/sharepage/detail"
                        + QUERY
                        + "&pwd_id="
                        + NetdiskShareHttp.encodeQuery(pwdId)
                        + "&stoken="
                        + NetdiskShareHttp.encodeQuery(stoken)
                        + "&pdir_fid="
                        + NetdiskShareHttp.encodeQuery(dirFid)
                        + "&force=0&_page=1&_size="
                        + PAGE_SIZE
                        + "&_fetch_total=1&_sort="
                        + NetdiskShareHttp.encodeQuery(SORT);
        try {
            return NetdiskShareHttp.dataList(
                    NetdiskShareHttp.call(
                            LABEL,
                            "GET",
                            url,
                            null,
                            UcShareResolver.UA,
                            UcShareResolver.REFERER,
                            "",
                            ""));
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=uc list failed for "
                            + pwdId
                            + " dir="
                            + dirFid
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return null;
        }
    }

    /**
     * 收一层目录；这一层一个文件都没有、又只有一个子目录时往下走一层（{@code MAX_DEPTH} 之内）。
     *
     * <p>为什么只在"这层没文件"时下钻：分享要么是「集数文件平铺」，要么是「剧名文件夹 / 集数文件」；
     * 前一种根目录里就有文件，再钻只会钻进花絮目录、把集数排乱。
     */
    private static void walk(
            String pwdId, String stoken, String dirFid, int depth, List<FilePlan> out) {
        JsonArray list = listPage(pwdId, stoken, dirFid);
        if (list == null) return;
        List<String> dirs = new ArrayList<>();
        int before = out.size();
        collectFiles(pwdId, stoken, list, out, dirs);
        if (out.size() == before && dirs.size() == 1 && depth < MAX_DEPTH) {
            walk(pwdId, stoken, dirs.get(0), depth + 1, out);
        }
    }

    /**
     * 收文件，顺手记下子目录 fid。{@code file_type}：0 是目录，1 是文件；造出来的四段 id 还要过
     * 播放侧的形状判据，过不了就丢（并在日志里只记各段长度，不记明文凭据）。
     */
    private static void collectFiles(
            String pwdId, String stoken, JsonArray list, List<FilePlan> out, List<String> dirs) {
        if (list == null) return;
        for (JsonElement item : list) {
            if (item == null || !item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            int type = NetdiskShareHttp.optInt(obj, "file_type", -1);
            if (type == 0) {
                String fid = NetdiskShareHttp.optString(obj, "fid").trim();
                if (!fid.isEmpty()) dirs.add(fid);
                continue;
            }
            if (type != 1) continue;
            String fid = NetdiskShareHttp.optString(obj, "fid").trim();
            String fileToken = NetdiskShareHttp.optString(obj, "share_fid_token").trim();
            String name = NetdiskShareHttp.optString(obj, "file_name");
            if (fid.isEmpty() || fileToken.isEmpty()) continue;
            String id = fid + "++" + fileToken + "++" + pwdId + "++" + stoken;
            if (!UcShareResolver.looksLikeShareId(id)) {
                AppLog.w(
                        TAG,
                        "provider=uc skip: id shape rejected len="
                                + fid.length()
                                + "/"
                                + fileToken.length()
                                + "/"
                                + pwdId.length()
                                + "/"
                                + stoken.length());
                continue;
            }
            out.add(new FilePlan(name, id));
        }
    }

    /** 文件名自然序：{@code E02} 排在 {@code E10} 前面。 */
    private static final Comparator<FilePlan> BY_NAME =
            (left, right) ->
                    NetdiskShareHttp.compareName(
                            left == null ? null : left.name, right == null ? null : right.name);

    /** 造好四段 id 的一集。 */
    private static final class FilePlan {
        final String name;
        final String id;

        FilePlan(String name, String id) {
            this.name = name;
            this.id = id;
        }
    }
}
