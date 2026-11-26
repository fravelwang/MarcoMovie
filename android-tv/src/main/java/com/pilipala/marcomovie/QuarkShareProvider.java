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
 * <p>这段逻辑原来长在 {@link NetdiskShareLines} 里，协议一层一个字没改；2026-11-26 只换了遍历口径
 * （原来只肯下钻一层、每层只翻一页，集数会不全）：
 * <ul>
 *   <li>换个 stoken 走 {@code POST /share/sharepage/token}，列目录走
 *       {@code GET /share/sharepage/detail}，都要本机夸克账号的 cookie（{@link QuarkQrLogin}）；
 *   <li>遍历口径（三家 provider 统一，2026-11-26 定）：根目录 + 最多 {@link #MAX_DEPTH} 层子目录，
 *       每层最多 {@link #MAX_PAGES} 页 × {@link #SHARE_PAGE_SIZE} 条，一条分享最多 {@link #MAX_FILES}
 *       个文件；每走完一层打一行 {@code walk dir=… rows=… videos=…}，上机就能分清"分享里只有 14 个"
 *       与"我们只找到 14 个"。子目录一律走，不再只在"这层没文件且只有一个目录"时才下钻（那正是集数
 *       不全的来源：根目录里只要混了一个文件，子目录里的集数就被整段跳过）；
 *   <li>只收 {@code file_type == 1}、{@code size > 0}、fid 是 32 hex、{@code share_fid_token} 是
 *       token 形状的条目，按「目录 + 文件名」的自然序排好（界面上仍只显示文件名）；
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
    private static final int MAX_FILES = 500;
    /** 夸克列表接口一页 100 条。 */
    private static final int SHARE_PAGE_SIZE = 100;
    /** 每层最多翻几页。 */
    private static final int MAX_PAGES = 3;
    /** 根目录 + 最多 3 层子目录。 */
    private static final int MAX_DEPTH = 3;
    /** 一次遍历最多打几个目录请求（病态分享不至于把详情页拖住；正常分享用不到这么多）。 */
    private static final int MAX_REQUESTS = 150;

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
     * 遍历口径见类注释：根 + 最多 3 层子目录、每层最多 3 页 × 100 条、最多 500 个文件。
     */
    private static List<FilePlan> filesOf(String cookie, String pwdId, String passcode) {
        Token token = shareToken(cookie, pwdId, passcode);
        if (token == null) return Collections.emptyList();
        Walk walk = new Walk();
        walk(cookie, pwdId, token.stoken, ROOT_FID, "", 0, walk);
        if (walk.files.isEmpty()) {
            AppLog.w(TAG, "provider=quark no file in share " + pwdId);
            return Collections.emptyList();
        }
        if (walk.files.size() > 1) Collections.sort(walk.files, BY_NAME);
        List<FilePlan> out = new ArrayList<>(walk.files.size());
        for (ShareFile file : walk.files) {
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
                        + out.size()
                        + " requests="
                        + walk.requests);
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
    private static JsonArray listPage(
            String cookie, String pwdId, String stoken, String dirFid, int page) {
        String url =
                BASE
                        + "/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id="
                        + NetdiskShareHttp.encodeQuery(pwdId)
                        + "&stoken="
                        + NetdiskShareHttp.encodeQuery(stoken)
                        + "&pdir_fid="
                        + NetdiskShareHttp.encodeQuery(dirFid)
                        + "&force=0&_page="
                        + page
                        + "&_size="
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
                            + " page="
                            + page
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return null;
        }
    }

    /**
     * 递归走一层目录：先翻完这一层（最多 {@link #MAX_PAGES} 页），再逐个进子目录（最多
     * {@link #MAX_DEPTH} 层）。每层打一行 {@code walk dir=… rows=… videos=…}（{@code rows} 是这一层
     * 列出来的条目数，{@code videos} 是累计已收条目数；非视频的条目由 {@link NetdiskShareLines} 按扩展名
     * 丢掉，所以这里不做视频判据），上机抓一条日志就能判断"分享里到底有多少集"。
     */
    private static void walk(
            String cookie,
            String pwdId,
            String stoken,
            String dirFid,
            String dirPath,
            int depth,
            Walk walk) {
        if (depth > MAX_DEPTH) return;
        if (walk.done()) return;
        List<String[]> dirs = new ArrayList<>();
        int rows = 0;
        String lastFingerprint = "";
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (walk.done()) break;
            walk.requests++;
            JsonArray list = listPage(cookie, pwdId, stoken, dirFid, page);
            if (list == null || list.size() == 0) break;
            String fingerprint = NetdiskShareHttp.fingerprint(list, "fid");
            if (!fingerprint.isEmpty() && fingerprint.equals(lastFingerprint)) {
                AppLog.w(
                        TAG,
                        "provider=quark paging repeated dir="
                                + NetdiskShareHttp.walkDir(dirPath)
                                + " page="
                                + page);
                break;
            }
            lastFingerprint = fingerprint;
            rows += list.size();
            collectFiles(pwdId, stoken, list, dirPath, walk.files, dirs);
        }
        AppLog.w(
                TAG,
                "walk dir="
                        + NetdiskShareHttp.walkDir(dirPath)
                        + " rows="
                        + rows
                        + " videos="
                        + walk.files.size());
        for (String[] dir : dirs) {
            if (walk.done()) return;
            walk(cookie, pwdId, stoken, dir[0], dir[1], depth + 1, walk);
        }
    }

    /**
     * 收文件，顺手记下子目录（fid + 路径）。只认 {@code file_type == 1}（文件）且 {@code size > 0}，
     * 且 fid 是 32 hex、share_fid_token 是 token 形状 —— 形状不全的条目送给播放器也换不出直链。
     */
    private static void collectFiles(
            String pwdId,
            String stoken,
            JsonArray list,
            String dirPath,
            List<ShareFile> out,
            List<String[]> dirs) {
        if (list == null) return;
        for (JsonElement item : list) {
            if (out.size() >= MAX_FILES) return;
            if (item == null || !item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            int type = NetdiskShareHttp.optInt(obj, "file_type", -1);
            if (type == 0) {
                String fid = NetdiskShareHttp.optString(obj, "fid").trim();
                String dirName = NetdiskShareHttp.optString(obj, "file_name").trim();
                if (!fid.isEmpty()) {
                    dirs.add(
                            new String[] {
                                fid,
                                NetdiskShareHttp.joinPath(
                                        dirPath, dirName.isEmpty() ? fid : dirName)
                            });
                }
                continue;
            }
            if (type != 1) continue;
            String fid = NetdiskShareHttp.optString(obj, "fid").trim();
            String token = NetdiskShareHttp.optString(obj, "share_fid_token").trim();
            long size = NetdiskShareHttp.optLong(obj, "size", 0L);
            if (!NetdiskShareHttp.isHex(fid, 32) || !NetdiskShareHttp.isToken(token) || size <= 0) {
                continue;
            }
            String name = NetdiskShareHttp.optString(obj, "file_name");
            out.add(
                    new ShareFile(
                            fid,
                            token,
                            name,
                            size,
                            NetdiskShareHttp.joinPath(dirPath, name)));
        }
    }

    /** 文件名自然序：{@code E02} 排在 {@code E10} 前面；排序键带目录，界面上仍只显示文件名。 */
    private static final Comparator<ShareFile> BY_NAME =
            (left, right) ->
                    NetdiskShareHttp.compareName(
                            left == null ? null : left.sortKey, right == null ? null : right.sortKey);

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

    /** 分享里的一个文件（还没拼成 id）。{@code sortKey} 带目录，{@code name} 只给界面。 */
    private static final class ShareFile {
        final String fid;
        final String token;
        final String name;
        final long size;
        final String sortKey;

        ShareFile(String fid, String token, String name, long size, String sortKey) {
            this.fid = fid;
            this.token = token;
            this.name = name;
            this.size = size;
            this.sortKey = sortKey;
        }
    }

    /** 一次遍历的状态：请求数、已收文件、以及「两条上限任一到了」的判据。 */
    private static final class Walk {
        int requests;
        final List<ShareFile> files = new ArrayList<>();

        boolean done() {
            return requests >= MAX_REQUESTS || files.size() >= MAX_FILES;
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
