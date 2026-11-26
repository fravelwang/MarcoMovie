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
 * 夸克分享 → 线路：本机登录态换 stoken、列目录、按文件造五段 id。
 *
 * <p>这段逻辑原来长在 {@link NetdiskShareLines} 里，本次搬到 provider 里，**行为不变**：
 * <ul>
 *   <li>换个 stoken 走 {@code POST /share/sharepage/token}，列目录走
 *       {@code GET /share/sharepage/detail}，都要本机夸克账号的 cookie（{@link QuarkQrLogin}）；
 *   <li>根目录只有一个目录时往下走一层（分享常是「剧名文件夹 / 集数文件」两级），再深不猜；
 *   <li>只收 {@code file_type == 1}、{@code size > 0}、fid 是 32 hex、{@code share_fid_token} 是
 *       token 形状的条目，一条分享最多 {@link #MAX_FILES} 个文件，按文件名自然序排好；
 *   <li>id 是五段 {@code ++} 分隔：{@code fid ++ share_fid_token ++ pwd_id ++ stoken ++ size}，
 *       形状与播放侧 {@link QuarkShareResolver} 的 {@code parseIdent} 完全对齐。
 * </ul>
 *
 * <p>没登录夸克时返回空表（并且只记一行日志）：这是"用户还没扫码"，不是"这部片没有分享"，
 * 调用方因此不写负缓存，扫码后立刻能再试。
 */
final class QuarkShareProvider implements NetdiskShareProvider {

    static final String ID = "quark";
    /** 线路名带「夸克」二字，播放侧 {@link QuarkShareResolver} 才会认这条线路。 */
    static final String LINE_NAME = "夸克分享";

    private static final String TAG = NetdiskShareHttp.TAG;
    private static final String LABEL = "夸克";
    private static final String UA = "Mozilla/5.0";
    private static final String REFERER = "https://pan.quark.cn/";
    private static final String ORIGIN = "https://pan.quark.cn";
    private static final String BASE = "https://drive-pc.quark.cn/1/clouddrive";
    private static final String SORT = "file_type:asc,file_name:asc";
    private static final String ROOT_FID = "0";

    /** 一条分享最多收多少个文件（剧集）。 */
    private static final int MAX_FILES = 200;
    /** 夸克列表接口一页 100 条。 */
    private static final int SHARE_PAGE_SIZE = 100;

    @Override
    public String id() {
        return ID;
    }

    /**
     * 夸克分享链接的域名。分享链接事实上都在 {@code pan.quark.cn}；{@code drive.quark.cn} 是同一个
     * 盘的另一个入口，顺手认了，不影响本来能用的链接。
     */
    @Override
    public boolean matches(String url) {
        String host = NetdiskShareHttp.hostOf(url);
        return "pan.quark.cn".equals(host) || "drive.quark.cn".equals(host);
    }

    /**
     * 本机有没有存夸克账号 cookie。只读本地、不动网络；调用方（{@link NetdiskShareLines}）用它
     * 区分缓存键：没登录时捞到的只可能是别家的分享，扫码后要能立刻再试，不能吃 10 分钟前的负结果。
     */
    static boolean credentialReady() {
        return !cookie().isEmpty();
    }

    private static String cookie() {
        QuarkQrLogin.Credential credential = QuarkQrLogin.loadSaved();
        return credential == null ? "" : NetdiskShareHttp.text(credential.cookie).trim();
    }

    @Override
    public List<Cms.Line> lines(String shareUrl, String passcode) throws Exception {
        NetdiskShareHttp.ShareRef share = NetdiskShareHttp.parseShare(shareUrl);
        if (share == null) return Collections.emptyList();
        String cookie = cookie();
        if (cookie.isEmpty()) {
            AppLog.w(TAG, "provider=quark skip: no saved 夸克 credential share=" + share.pwdId);
            return Collections.emptyList();
        }
        List<FilePlan> files = filesOf(cookie, share.pwdId, share.passcode);
        if (files.isEmpty()) return Collections.emptyList();
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

    // ------------------------------------------------------------------ 夸克接口

    /**
     * 一条分享里的文件（已排好序、已造好五段 id）。
     *
     * <p>换 stoken 走 {@code POST /share/sharepage/token}；列表走 {@code GET /share/sharepage/detail}。
     * 根目录只有一个文件夹时往下走一层，再深就不猜了（卡里定的上限两层）。
     */
    private static List<FilePlan> filesOf(String cookie, String pwdId, String passcode) {
        Token token = shareToken(cookie, pwdId, passcode);
        if (token == null) return Collections.emptyList();
        List<ShareFile> files = new ArrayList<>();
        JsonArray root = listPage(cookie, pwdId, token.stoken, ROOT_FID);
        List<String> dirs = new ArrayList<>();
        collectFiles(root, files, dirs);
        if (files.isEmpty() && dirs.size() == 1) {
            JsonArray inner = listPage(cookie, pwdId, token.stoken, dirs.get(0));
            collectFiles(inner, files, new ArrayList<>());
        }
        if (files.isEmpty()) {
            AppLog.w(TAG, "provider=quark no file in share " + pwdId);
            return Collections.emptyList();
        }
        if (files.size() > 1) Collections.sort(files, BY_NAME);
        List<FilePlan> out = new ArrayList<>(files.size());
        for (ShareFile file : files) {
            if (out.size() >= MAX_FILES) break;
            StringBuilder id = new StringBuilder(80);
            id.append(file.fid)
                    .append("++")
                    .append(file.token)
                    .append("++")
                    .append(pwdId)
                    .append("++")
                    .append(token.stoken)
                    .append("++")
                    .append(file.size);
            out.add(new FilePlan(file.name, id.toString()));
        }
        AppLog.w(
                TAG,
                "provider=quark share "
                        + pwdId
                        + " title="
                        + NetdiskShareHttp.text(token.title)
                        + " files="
                        + out.size());
        return out;
    }

    /** 换 stoken；失败（含 404/41011「分享地址已失效」）返回 null，只记日志。 */
    private static Token shareToken(String cookie, String pwdId, String passcode) {
        JsonObject payload = new JsonObject();
        payload.addProperty("pwd_id", pwdId);
        payload.addProperty("passcode", NetdiskShareHttp.text(passcode));
        try {
            JsonObject body =
                    NetdiskShareHttp.call(
                            LABEL,
                            "POST",
                            BASE + "/share/sharepage/token?pr=ucpro&fr=pc",
                            payload.toString(),
                            UA,
                            REFERER,
                            ORIGIN,
                            cookie);
            JsonElement data = body.get("data");
            if (data == null || !data.isJsonObject()) return null;
            JsonObject obj = data.getAsJsonObject();
            String stoken = NetdiskShareHttp.optString(obj, "stoken").trim();
            if (stoken.isEmpty()) {
                AppLog.w(TAG, "provider=quark token answered without stoken for " + pwdId);
                return null;
            }
            return new Token(stoken, NetdiskShareHttp.optString(obj, "title"));
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=quark token failed for "
                            + pwdId
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return null;
        }
    }

    /** 一页目录；失败返回 null（调用方当成「这层没东西」）。 */
    private static JsonArray listPage(String cookie, String pwdId, String stoken, String dirFid) {
        String url =
                BASE
                        + "/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id="
                        + NetdiskShareHttp.encodeQuery(pwdId)
                        + "&stoken="
                        + NetdiskShareHttp.encodeQuery(stoken)
                        + "&pdir_fid="
                        + NetdiskShareHttp.encodeQuery(dirFid)
                        + "&force=0&_page=1&_size="
                        + SHARE_PAGE_SIZE
                        + "&_fetch_total=1&_sort="
                        + NetdiskShareHttp.encodeQuery(SORT);
        try {
            return NetdiskShareHttp.dataList(
                    NetdiskShareHttp.call(LABEL, "GET", url, null, UA, REFERER, ORIGIN, cookie));
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=quark list failed for "
                            + pwdId
                            + " dir="
                            + dirFid
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return null;
        }
    }

    /**
     * 收文件，顺手记下子目录 fid。只认 {@code file_type == 1}（文件）且 {@code size > 0}，
     * 且 fid 是 32 hex、share_fid_token 是 token 形状 —— 形状不全的条目送给播放器也换不出直链。
     */
    private static void collectFiles(JsonArray list, List<ShareFile> out, List<String> dirs) {
        if (list == null) return;
        for (JsonElement item : list) {
            if (out.size() >= MAX_FILES) return;
            if (item == null || !item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            int type = NetdiskShareHttp.optInt(obj, "file_type", -1);
            if (type == 0) {
                String fid = NetdiskShareHttp.optString(obj, "fid");
                if (!fid.isEmpty()) dirs.add(fid);
                continue;
            }
            if (type != 1) continue;
            String fid = NetdiskShareHttp.optString(obj, "fid").trim();
            String token = NetdiskShareHttp.optString(obj, "share_fid_token").trim();
            long size = NetdiskShareHttp.optLong(obj, "size", 0L);
            if (!NetdiskShareHttp.isHex(fid, 32) || !NetdiskShareHttp.isToken(token) || size <= 0) {
                continue;
            }
            out.add(new ShareFile(fid, token, NetdiskShareHttp.optString(obj, "file_name"), size));
        }
    }

    /** 文件名自然序：{@code E02} 排在 {@code E10} 前面。 */
    private static final Comparator<ShareFile> BY_NAME =
            (left, right) ->
                    NetdiskShareHttp.compareName(
                            left == null ? null : left.name, right == null ? null : right.name);

    // ------------------------------------------------------------------ 小数据结构

    /** 换来的分享 token + 标题（标题只用于日志）。 */
    private static final class Token {
        final String stoken;
        final String title;

        Token(String stoken, String title) {
            this.stoken = stoken;
            this.title = title;
        }
    }

    /** 分享里的一个文件（还没拼成 id）。 */
    private static final class ShareFile {
        final String fid;
        final String token;
        final String name;
        final long size;

        ShareFile(String fid, String token, String name, long size) {
            this.fid = fid;
            this.token = token;
            this.name = name;
            this.size = size;
        }
    }

    /** 造好五段 id 的一集。 */
    private static final class FilePlan {
        final String name;
        final String id;

        FilePlan(String name, String id) {
            this.name = name;
            this.id = id;
        }
    }
}
