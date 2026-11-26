package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 百度分享 → 线路：匿名打开分享页、验提取码、按层遍历目录、按文件造六段 {@code bd} id（末段是网页下载接口的分享页凭据）。
 *
 * <p>协议照 Flutter 端已经在跑的 {@code _baiduFiles}（{@code flutter/lib/core/cloud_share.dart}）
 * 移植，**免登录**——公开分享列目录不需要账号，只有播放直链才要（那是
 * {@link BaiduShareResolver} 的事）：
 * <ol>
 *   <li>{@code GET https://pan.baidu.com/s/<shareId>?pwd=<pwd>}：拿访问者 Cookie（BAIDUID 等），
 *       跳转自己跟（提取码页会 302），跳转只许留在 {@code pan.baidu.com}；
 *   <li>有提取码时 {@code POST https://pan.baidu.com/share/verify?…&surl=<短id>&channel=chunlei&web=1…}
 *       （form: {@code pwd=…&vcode=&vcode_str=}）→ {@code randsk} 就是 {@code sekey}，同时写进
 *       Cookie 罐的 {@code BDCLND}；
 *   <li>根目录 {@code GET /share/list?…&shorturl=<短id>&root=1} 拿到 {@code uk} 与 {@code share_id}；
 *       子目录用 {@code sekey&uk&shareid&dir=<path>}（两级都用同一页长 {@link #PAGE_SIZE}）；
 *   <li>遍历口径（三家 provider 统一，2026-11-26 定）：根目录 + 最多 {@link #MAX_DEPTH} 层子目录，
 *       每层最多 {@link #MAX_PAGES} 页 × {@link #PAGE_SIZE} 条，一条分享最多 {@link #MAX_FILES} 个文件，
 *       只收视频，按「目录 + 文件名」的自然序排好（界面上仍只显示文件名），每层打一行
 *       {@code walk dir=… rows=… videos=…}；
 *   <li>id 是六段 {@code ++} 分隔：{@code bd ++ share_id ++ uk ++ fs_id ++ encode(sekey) ++
 *       encode(surl|sign|timestamp)}，播放侧由 {@link BaiduShareResolver} 拿它换直链；第 6 段是网页
 *       下载接口要的分享页凭据（{@link BaiduShareResolver.ShareTicket}），取不到时留空。
 * </ol>
 *
 * <p>造出来的 id 一律先过 {@link BaiduShareResolver#looksLikeShareId(String)}（与播放侧同一套判据）：
 * 形状对不上的条目宁可丢掉，也不要塞进线路让播放器黑屏后弹一句笼统话。UA / Referer / 编码器直接引用
 * {@link BaiduShareResolver} 的包内静态，不抄第二份 —— 那几串是实测值，抄一份就会漂。
 */
final class BaiduShareProvider implements NetdiskShareProvider {

    static final String ID = "baidu";
    /** 线路名：播放侧按 id 形状认，不看名字；这个名字只是给人看的。 */
    static final String LINE_NAME = "百度分享";

    private static final String TAG = NetdiskShareHttp.TAG;
    private static final String LABEL = "百度";
    private static final String HOST = "pan.baidu.com";
    private static final String BASE = "https://pan.baidu.com";

    /** 一条分享最多收多少个文件（剧集）。 */
    private static final int MAX_FILES = 500;
    /** 每层一页 100 条（根与子目录同一个口径）。 */
    private static final int PAGE_SIZE = 100;
    /** 每层最多翻几页。 */
    private static final int MAX_PAGES = 3;
    /** 根目录 + 最多 3 层子目录。 */
    private static final int MAX_DEPTH = 3;
    /** 一次遍历最多打几个目录请求（病态分享不至于把详情页拖住）。 */
    private static final int MAX_REQUESTS = 150;

    /** 视频扩展名判据（与 Flutter 端 {@code _video} 同一组）。 */
    private static final Pattern VIDEO =
            Pattern.compile(
                    "\\.(mp4|mkv|ts|m3u8|mov|avi|flv|wmv|webm|m4v|m2ts|mts|rmvb|mpg|mpeg|iso)$",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return ID;
    }

    /** 百度分享的域名；{@code pan.baidu.com} 之外都不认。路径要像 {@code /s/<id>}。 */
    @Override
    public boolean matches(String url) {
        if (!HOST.equals(NetdiskShareHttp.hostOf(url))) return false;
        return parseShare(url) != null;
    }

    @Override
    public List<Cms.Line> lines(String shareUrl, String passcode) throws Exception {
        ShareRef share = parseShare(shareUrl);
        if (share == null) return Collections.emptyList();
        String pwd =
                NetdiskShareHttp.text(passcode).trim().isEmpty()
                        ? share.passcode
                        : passcode.trim();
        List<FilePlan> files;
        try {
            files = files(share.id, pwd);
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=baidu failed share="
                            + share.id
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return Collections.emptyList();
        }
        if (files.isEmpty()) {
            AppLog.w(TAG, "provider=baidu no file in share " + share.id);
            return Collections.emptyList();
        }
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

    // ------------------------------------------------------------------ 百度分享接口

    /**
     * 一条分享里的视频（已排序、已造好六段 id）。全程匿名：只有访问者 Cookie，不带账号凭据。
     *
     * <p>遍历口径（三家 provider 统一，2026-11-26 定）：根目录 + 最多 {@link #MAX_DEPTH} 层子目录，
     * 每层最多 {@link #MAX_PAGES} 页 × {@link #PAGE_SIZE} 条，一条分享最多 {@link #MAX_FILES} 个文件，
     * 每走完一层打一行 {@code walk dir=… rows=… videos=…}。子目录一律走（以前只在根目录里出现的文件夹
     * 才走一层，且最多 8 个、每个 2 页，深一层或多几个目录就整段丢集数）。
     */
    private static List<FilePlan> files(String shareId, String passcode) throws IOException {
        String shortId = shortId(shareId);
        BaiduShareResolver.Jar jar = new BaiduShareResolver.Jar();
        String html = openSharePage(shareId, passcode, jar);
        String sekey = passcode.isEmpty() ? jar.get("BDCLND") : verify(shortId, passcode, jar);
        // 网页下载接口要的 sign/timestamp：解析分享时页面正开着，顺手取下来随 id 带进播放侧。
        // 两种情形要再开一次分享页：首屏常常是提取码页（页里没有 yunData）；有提取码时，验码之后
        // 页面才是浏览器里「点下载」那一刻的样子（sign 与当时的状态配同一份最稳）。再开时带着刚
        // 换到的 BDCLND，取不到就沿用首屏那份。
        BaiduShareResolver.ShareTicket ticket = BaiduShareResolver.sharePageTicket(html, shareId);
        if (!ticket.hasSignature() || !passcode.isEmpty()) {
            String again = openSharePage(shareId, "", jar);
            BaiduShareResolver.ShareTicket retry =
                    BaiduShareResolver.sharePageTicket(again, shareId);
            if (retry.hasSignature()) ticket = retry;
        }
        AppLog.w(
                TAG,
                "provider=baidu share "
                        + shortId
                        + " web_ticket="
                        + (ticket.hasSignature() ? "yes" : "no")
                        + " surl="
                        + shareId);
        Walk walk = new Walk();
        List<Dir> dirs = new ArrayList<>();
        String uk = "";
        String apiShareId = "";
        int rootRows = 0;
        String lastFingerprint = "";
        // 根：除了收文件，还要从这里拿 uk / share_id（后面的子目录请求要它们作参数）。
        for (int page = 1; page <= MAX_PAGES && !walk.done(); page++) {
            walk.requests++;
            JsonObject answer =
                    listPage(jar, shortId, "", "", "", sekey, page, PAGE_SIZE, true);
            if (answer == null) break;
            uk = firstNonEmpty(NetdiskShareHttp.optString(answer, "uk").trim(), uk);
            apiShareId =
                    firstNonEmpty(NetdiskShareHttp.optString(answer, "share_id").trim(), apiShareId);
            JsonArray rows = listOf(answer);
            if (rows == null) {
                AppLog.w(TAG, "provider=baidu root list has no list[] for " + shortId);
                break;
            }
            // 空页就是到头了；注意不能用「这页不满」当停止条件 —— 有的部署会把 num 截到 20。
            if (rows.size() == 0) break;
            String fingerprint = NetdiskShareHttp.fingerprint(rows, "fs_id");
            if (!fingerprint.isEmpty() && fingerprint.equals(lastFingerprint)) {
                // 分页没往前走的部署：宁可少收几集，也不无限翻同一页。
                AppLog.w(TAG, "provider=baidu paging repeated at page " + page + " for " + shortId);
                break;
            }
            lastFingerprint = fingerprint;
            rootRows += rows.size();
            collect(rows, walk, dirs, apiShareId, uk, sekey, ticket, "", 1);
        }
        AppLog.w(
                TAG,
                "walk dir=/ rows=" + rootRows + " videos=" + walk.files.size());
        if (uk.isEmpty() || apiShareId.isEmpty()) {
            AppLog.w(TAG, "provider=baidu share " + shortId + " has no uk/share_id, cannot build ids");
            return Collections.emptyList();
        }
        // 子目录：一层层往下走（队列长度在遍历中增长），最多 MAX_DEPTH 层、每层最多 MAX_PAGES 页。
        for (int index = 0; index < dirs.size(); index++) {
            if (walk.done()) break;
            Dir dir = dirs.get(index);
            int rows = 0;
            String fingerprint = "";
            for (int page = 1; page <= MAX_PAGES && !walk.done(); page++) {
                walk.requests++;
                JsonObject answer =
                        listPage(
                                jar,
                                shortId,
                                uk,
                                apiShareId,
                                dir.path,
                                sekey,
                                page,
                                PAGE_SIZE,
                                false);
                if (answer == null) break;
                JsonArray pageRows = listOf(answer);
                if (pageRows == null || pageRows.size() == 0) break;
                String print = NetdiskShareHttp.fingerprint(pageRows, "fs_id");
                if (!print.isEmpty() && print.equals(fingerprint)) break;
                fingerprint = print;
                rows += pageRows.size();
                collect(
                        pageRows,
                        walk,
                        dirs,
                        apiShareId,
                        uk,
                        sekey,
                        ticket,
                        stripLeadingSlash(dir.path),
                        dir.depth + 1);
            }
            AppLog.w(
                    TAG,
                    "walk dir=" + dir.path + " rows=" + rows + " videos=" + walk.files.size());
        }
        List<FilePlan> out = walk.files;
        if (out.size() > 1) Collections.sort(out, BY_NAME);
        if (out.size() > MAX_FILES) out = new ArrayList<>(out.subList(0, MAX_FILES));
        AppLog.w(
                TAG,
                "provider=baidu share "
                        + shortId
                        + " files="
                        + out.size()
                        + " dirs="
                        + dirs.size()
                        + " requests="
                        + walk.requests
                        + " sekey="
                        + (sekey.isEmpty() ? "no" : "yes"));
        return out;
    }

    /**
     * 打开分享页：拿访问者 Cookie，并把正文交回去（里面可能有 {@code window.yunData} /
     * {@code locals.mset} 的 {@code sign}/{@code timestamp}）。跳转自己跟，跳转只许留在
     * {@code pan.baidu.com}。
     */
    private static String openSharePage(
            String shareId, String passcode, BaiduShareResolver.Jar jar) throws IOException {
        String url =
                BASE
                        + "/s/"
                        + BaiduShareResolver.encodePart(shareId)
                        + (passcode.isEmpty()
                                ? ""
                                : "?pwd=" + BaiduShareResolver.encodePart(passcode));
        BaiduShareResolver.Response answer =
                BaiduShareResolver.request(
                        "GET",
                        url,
                        null,
                        null,
                        BaiduShareResolver.UA_SHARE,
                        BaiduShareResolver.REFERER,
                        jar,
                        5);
        return NetdiskShareHttp.text(answer == null ? null : answer.body);
    }

    /** 验提取码换 {@code randsk}（即 sekey）；失败抛 IOException，只由上层记日志。 */
    private static String verify(String shortId, String passcode, BaiduShareResolver.Jar jar)
            throws IOException {
        String url =
                BASE
                        + "/share/verify?t="
                        + System.currentTimeMillis()
                        + "&surl="
                        + BaiduShareResolver.encodePart(shortId)
                        + "&channel=chunlei&web=1&bdstoken=&logid=&clienttype=0";
        String referer =
                BASE
                        + "/share/init?surl="
                        + BaiduShareResolver.encodePart(shortId)
                        + "&pwd="
                        + BaiduShareResolver.encodePart(passcode);
        String form = "pwd=" + encodeForm(passcode) + "&vcode=&vcode_str=";
        BaiduShareResolver.Response answer =
                BaiduShareResolver.request(
                        "POST",
                        url,
                        form,
                        "application/x-www-form-urlencoded",
                        BaiduShareResolver.UA_SHARE,
                        referer,
                        jar,
                        0);
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            throw new IOException("百度分享提取码验证失败（HTTP " + answer.http + "）");
        }
        int errno = NetdiskShareHttp.optInt(body, "errno", -1);
        if (errno != 0) {
            throw new IOException("百度分享提取码验证失败（errno=" + errno + "）");
        }
        String randsk = NetdiskShareHttp.optString(body, "randsk").trim();
        if (randsk.isEmpty()) throw new IOException("百度分享提取码验证未完成");
        // 之后列子目录要带 BDCLND；照 Flutter 端，cookie 里放 raw 值。
        jar.put("BDCLND", randsk);
        return randsk;
    }

    /**
     * 一次目录请求；网络失败或 {@code errno != 0} 返回 null（调用方当成「这层没东西」）。
     *
     * <p>{@code sekey} 的口径与 Flutter 端一致：这里直接编码 raw 值（那边是 Uri 把 raw 再编码一次）。
     */
    private static JsonObject listPage(
            BaiduShareResolver.Jar jar,
            String shortId,
            String uk,
            String shareId,
            String dirPath,
            String sekey,
            int page,
            int pageSize,
            boolean root) {
        StringBuilder url = new StringBuilder(BASE).append("/share/list");
        if (root) {
            url.append("?page=")
                    .append(page)
                    .append("&num=")
                    .append(pageSize)
                    .append("&desc=1&showempty=0&order=time&view_mode=1")
                    .append("&channel=chunlei&web=1&bdstoken=&clienttype=0")
                    .append("&shorturl=")
                    .append(BaiduShareResolver.encodePart(shortId))
                    .append("&root=1");
        } else {
            url.append("?page=")
                    .append(page)
                    .append("&num=")
                    .append(pageSize)
                    .append("&sekey=")
                    .append(BaiduShareResolver.encodePart(sekey))
                    .append("&uk=")
                    .append(BaiduShareResolver.encodePart(uk))
                    .append("&shareid=")
                    .append(BaiduShareResolver.encodePart(shareId))
                    .append("&dir=")
                    .append(BaiduShareResolver.encodePart(dirPath));
        }
        try {
            BaiduShareResolver.Response answer =
                    BaiduShareResolver.request(
                            "GET",
                            url.toString(),
                            null,
                            null,
                            BaiduShareResolver.UA_SHARE,
                            BaiduShareResolver.REFERER,
                            jar,
                            0);
            JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
            if (body == null) {
                AppLog.w(TAG, "provider=baidu list answered non-JSON, http=" + answer.http);
                return null;
            }
            int errno = NetdiskShareHttp.optInt(body, "errno", -1);
            if (errno != 0) {
                AppLog.w(
                        TAG,
                        "provider=baidu list errno="
                                + errno
                                + " dir="
                                + (dirPath.isEmpty() ? "/" : dirPath)
                                + " page="
                                + page);
                return null;
            }
            return body;
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=baidu list failed dir="
                            + (dirPath.isEmpty() ? "/" : dirPath)
                            + " page="
                            + page
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return null;
        }
    }

    /**
     * 收一层目录：目录记进 {@code dirs}（没有 path 的跳过，没 path 就列不了；超过 {@link #MAX_DEPTH}
     * 层的不再入队），文件按判据收下并造 id（第 6 段是网页下载接口的分享页凭据，见 {@link
     * BaiduShareResolver.ShareTicket}）。{@code sortPath} 是这一层在排序键里的目录名（不带前导
     * {@code /}，根目录为空串）。造出来的 id 还要过播放侧的形状判据，过不了就丢（日志只记各段长度，
     * 不记明文凭据）。
     */
    private static void collect(
            JsonArray rows,
            Walk walk,
            List<Dir> dirs,
            String shareId,
            String uk,
            String sekey,
            BaiduShareResolver.ShareTicket ticket,
            String sortPath,
            int childDepth) {
        if (rows == null) return;
        for (JsonElement item : rows) {
            if (walk.done()) return;
            if (item == null || !item.isJsonObject()) continue;
            JsonObject row = item.getAsJsonObject();
            String fsId = NetdiskShareHttp.optString(row, "fs_id").trim();
            String name = NetdiskShareHttp.optString(row, "server_filename");
            if (fsId.isEmpty() || name.isEmpty()) continue;
            if (NetdiskShareHttp.optInt(row, "isdir", -1) == 1) {
                String path = NetdiskShareHttp.optString(row, "path").trim();
                if (path.isEmpty() || hasDir(dirs, path)) continue;
                if (childDepth > MAX_DEPTH) continue;
                dirs.add(new Dir(path, childDepth));
                continue;
            }
            if (!isVideo(row, name)) continue;
            if (!walk.seen.add(fsId)) continue;
            String id =
                    "bd++"
                            + shareId
                            + "++"
                            + uk
                            + "++"
                            + fsId
                            + "++"
                            + BaiduShareResolver.encodePart(sekey)
                            + "++"
                            + BaiduShareResolver.encodePart(ticket.encode());
            if (!BaiduShareResolver.looksLikeShareId(id)) {
                AppLog.w(
                        TAG,
                        "provider=baidu skip: id shape rejected len="
                                + shareId.length()
                                + "/"
                                + uk.length()
                                + "/"
                                + fsId.length()
                                + "/"
                                + sekey.length());
                continue;
            }
            walk.files.add(new FilePlan(name, id, NetdiskShareHttp.joinPath(sortPath, name)));
        }
    }

    /** 视频判据：接口标了 {@code category == 1}，或文件名是视频扩展名。 */
    private static boolean isVideo(JsonObject row, String name) {
        if (NetdiskShareHttp.optInt(row, "category", -1) == 1) return true;
        return VIDEO.matcher(NetdiskShareHttp.text(name)).find();
    }

    private static JsonArray listOf(JsonObject body) {
        if (body == null || !body.has("list")) return null;
        JsonElement list = body.get("list");
        if (list == null || !list.isJsonArray()) return null;
        return list.getAsJsonArray();
    }

    private static boolean hasDir(List<Dir> dirs, String path) {
        for (Dir dir : dirs) {
            if (dir.path.equals(path)) return true;
        }
        return false;
    }

    private static String firstNonEmpty(String value, String fallback) {
        String text = NetdiskShareHttp.text(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    /** 百度分享的短 id：URL 里那个 id 去掉前导 {@code 1}；{@code surl} 参数要的就是它。 */
    private static String shortId(String shareId) {
        String id = NetdiskShareHttp.text(shareId).trim();
        return id.startsWith("1") ? id.substring(1) : id;
    }

    /** 去掉服务端路径的前导 {@code /}：排序键不要它，否则子目录里的文件会排到根目录文件前面。 */
    private static String stripLeadingSlash(String path) {
        String value = NetdiskShareHttp.text(path).trim();
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') start++;
        return value.substring(start);
    }

    /** form 编码（空格转 {@code +}）；提取码可能带怪字符，按标准表单编码最稳。 */
    private static String encodeForm(String value) {
        try {
            return URLEncoder.encode(NetdiskShareHttp.text(value), "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return NetdiskShareHttp.text(value);
        }
    }

    /** 文件名自然序：{@code E02} 排在 {@code E10} 前面；排序键带目录，界面上仍只显示文件名。 */
    private static final Comparator<FilePlan> BY_NAME =
            (left, right) ->
                    NetdiskShareHttp.compareName(
                            left == null ? null : left.sortKey, right == null ? null : right.sortKey);

    /**
     * 从分享链接里取出「分享 id + 提取码」。
     *
     * <p>没有用 {@link NetdiskShareHttp#parseShare}：那个只认字母数字，而百度分享 id 允许
     * {@code -} 与 {@code _}（Flutter 端的正则也是 {@code [A-Za-z0-9_-]{4,80}}）；共用一份会在
     * 带连字符的分享上悄悄截断 id。两边形状不同，判据就分开写。
     */
    private static ShareRef parseShare(String url) {
        String value = NetdiskShareHttp.text(url).trim();
        int marker = value.indexOf("/s/");
        if (marker < 0) return null;
        int start = marker + 3;
        int end = start;
        while (end < value.length() && isIdChar(value.charAt(end))) end++;
        String id = value.substring(start, end);
        if (id.length() < 4) return null;
        String passcode = "";
        int query = value.indexOf('?', end);
        if (query >= 0) {
            for (String pair : value.substring(query + 1).split("[&#]")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim();
                if ("pwd".equalsIgnoreCase(name)
                        || "passcode".equalsIgnoreCase(name)
                        || "password".equalsIgnoreCase(name)) {
                    passcode = pair.substring(eq + 1).trim();
                    break;
                }
            }
        }
        return new ShareRef(id, passcode);
    }

    private static boolean isIdChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '-'
                || c == '_';
    }

    // ------------------------------------------------------------------ 小数据结构

    /** 一条分享链接里那两样东西：{@code /s/<id>} 的 id 与 {@code ?pwd=} 提取码。 */
    private static final class ShareRef {
        final String id;
        final String passcode;

        ShareRef(String id, String passcode) {
            this.id = id;
            this.passcode = passcode;
        }
    }

    /** 分享里的一个子目录：{@code path} 是接口给的完整路径（列目录用它），{@code depth} 是它在树里的层数。 */
    private static final class Dir {
        final String path;
        final int depth;

        Dir(String path, int depth) {
            this.path = path;
            this.depth = depth;
        }
    }

    /** 造好六段 id 的一集。{@code sortKey} 带目录，{@code name} 只给界面。 */
    private static final class FilePlan {
        final String name;
        final String id;
        final String sortKey;

        FilePlan(String name, String id, String sortKey) {
            this.name = name;
            this.id = id;
            this.sortKey = sortKey;
        }
    }

    /** 一次遍历的状态：请求数、已收文件（按 fs_id 去重）、以及「两条上限任一到了」的判据。 */
    private static final class Walk {
        int requests;
        final List<FilePlan> files = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();

        boolean done() {
            return requests >= MAX_REQUESTS || files.size() >= MAX_FILES;
        }
    }
}
