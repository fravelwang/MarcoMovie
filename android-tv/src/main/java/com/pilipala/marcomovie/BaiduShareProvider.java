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
 * 百度分享 → 线路：匿名打开分享页、验提取码、列两级目录、按文件造五段 {@code bd} id。
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
 *   <li>根目录 {@code GET /share/list?…&shorturl=<短id>&root=1}（一页 20 条）拿到 {@code uk} 与
 *       {@code share_id}；子目录用 {@code sekey&uk&shareid&dir=<path>}（一页 100 条）；
 *   <li>最多两层（分享根 + 一层「剧名文件夹」），一条分享最多 {@link #MAX_FILES} 个文件，只收视频，
 *       按文件名自然序排好；
 *   <li>id 是五段 {@code ++} 分隔：{@code bd ++ share_id ++ uk ++ fs_id ++ encode(sekey)}，播放侧由
 *       {@link BaiduShareResolver} 拿它换直链。
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
    private static final int MAX_FILES = 200;
    /** 根目录一页 20 条（照 Flutter 端实测的请求）。 */
    private static final int ROOT_PAGE_SIZE = 20;
    /** 子目录一页 100 条。 */
    private static final int DIR_PAGE_SIZE = 100;
    /** 根目录最多翻几页：一页 20 条，12 页已经超过文件上限。 */
    private static final int ROOT_MAX_PAGES = 12;
    /** 每个子目录最多翻两页（200 条已超过文件上限）。 */
    private static final int DIR_MAX_PAGES = 2;
    /** 最多下钻几个子目录：两层目录、文件上限 200，8 个够用，也不会把详情页拖到几十秒。 */
    private static final int MAX_DIRS = 8;

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
     * 一条分享里的视频（已排序、已造好五段 id）。全程匿名：只有访问者 Cookie，不带账号凭据。
     */
    private static List<FilePlan> files(String shareId, String passcode) throws IOException {
        String shortId = shortId(shareId);
        BaiduShareResolver.Jar jar = new BaiduShareResolver.Jar();
        openSharePage(shareId, passcode, jar);
        String sekey = passcode.isEmpty() ? jar.get("BDCLND") : verify(shortId, passcode, jar);
        List<FilePlan> out = new ArrayList<>();
        Set<String> seenFiles = new LinkedHashSet<>();
        List<Dir> dirs = new ArrayList<>();
        String uk = "";
        String apiShareId = "";
        String lastFingerprint = "";
        for (int page = 1; page <= ROOT_MAX_PAGES && out.size() < MAX_FILES; page++) {
            JsonObject answer =
                    listPage(jar, shortId, uk, apiShareId, "", sekey, page, ROOT_PAGE_SIZE, true);
            if (answer == null) break;
            uk = firstNonEmpty(NetdiskShareHttp.optString(answer, "uk").trim(), uk);
            apiShareId =
                    firstNonEmpty(NetdiskShareHttp.optString(answer, "share_id").trim(), apiShareId);
            JsonArray rows = listOf(answer);
            if (rows == null) {
                AppLog.w(TAG, "provider=baidu root list has no list[] for " + shortId);
                break;
            }
            String fingerprint = fingerprintOf(rows);
            if (!fingerprint.isEmpty() && fingerprint.equals(lastFingerprint)) {
                // 分页没往前走的部署：宁可少收几集，也不无限翻同一页。
                AppLog.w(TAG, "provider=baidu paging repeated at page " + page + " for " + shortId);
                break;
            }
            lastFingerprint = fingerprint;
            collect(rows, out, seenFiles, dirs, apiShareId, uk, sekey);
            if (rows.size() < ROOT_PAGE_SIZE) break;
        }
        if (uk.isEmpty() || apiShareId.isEmpty()) {
            AppLog.w(TAG, "provider=baidu share " + shortId + " has no uk/share_id, cannot build ids");
            return Collections.emptyList();
        }
        // 第二层：根目录里出现的文件夹，最多 MAX_DIRS 个、每个最多两页，不再往下钻。
        int visited = 0;
        for (Dir dir : dirs) {
            if (out.size() >= MAX_FILES || visited >= MAX_DIRS) break;
            visited++;
            String fingerprint = "";
            for (int page = 1; page <= DIR_MAX_PAGES && out.size() < MAX_FILES; page++) {
                JsonObject answer =
                        listPage(
                                jar,
                                shortId,
                                uk,
                                apiShareId,
                                dir.path,
                                sekey,
                                page,
                                DIR_PAGE_SIZE,
                                false);
                if (answer == null) break;
                JsonArray rows = listOf(answer);
                if (rows == null) break;
                String print = fingerprintOf(rows);
                if (!print.isEmpty() && print.equals(fingerprint)) break;
                fingerprint = print;
                collect(rows, out, seenFiles, new ArrayList<>(), apiShareId, uk, sekey);
                if (rows.size() < DIR_PAGE_SIZE) break;
            }
        }
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
                        + " sekey="
                        + (sekey.isEmpty() ? "no" : "yes"));
        return out;
    }

    /** 打开分享页（只为拿访问者 Cookie）；跳转自己跟，跳转只许留在 {@code pan.baidu.com}。 */
    private static void openSharePage(String shareId, String passcode, BaiduShareResolver.Jar jar)
            throws IOException {
        String url =
                BASE
                        + "/s/"
                        + BaiduShareResolver.encodePart(shareId)
                        + (passcode.isEmpty()
                                ? ""
                                : "?pwd=" + BaiduShareResolver.encodePart(passcode));
        BaiduShareResolver.request(
                "GET",
                url,
                null,
                null,
                BaiduShareResolver.UA_SHARE,
                BaiduShareResolver.REFERER,
                jar,
                5);
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
     * 收一层目录：目录记进 {@code dirs}（没有 path 的跳过，没 path 就列不了），文件按判据收下并造 id。
     * 造出来的 id 还要过播放侧的形状判据，过不了就丢（日志只记各段长度，不记明文凭据）。
     */
    private static void collect(
            JsonArray rows,
            List<FilePlan> out,
            Set<String> seenFiles,
            List<Dir> dirs,
            String shareId,
            String uk,
            String sekey) {
        if (rows == null) return;
        for (JsonElement item : rows) {
            if (out.size() >= MAX_FILES) return;
            if (item == null || !item.isJsonObject()) continue;
            JsonObject row = item.getAsJsonObject();
            String fsId = NetdiskShareHttp.optString(row, "fs_id").trim();
            String name = NetdiskShareHttp.optString(row, "server_filename");
            if (fsId.isEmpty() || name.isEmpty()) continue;
            if (NetdiskShareHttp.optInt(row, "isdir", -1) == 1) {
                String path = NetdiskShareHttp.optString(row, "path").trim();
                if (path.isEmpty() || hasDir(dirs, path)) continue;
                dirs.add(new Dir(path));
                continue;
            }
            if (!isVideo(row, name)) continue;
            if (!seenFiles.add(fsId)) continue;
            String id =
                    "bd++"
                            + shareId
                            + "++"
                            + uk
                            + "++"
                            + fsId
                            + "++"
                            + BaiduShareResolver.encodePart(sekey);
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
            out.add(new FilePlan(name, id));
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

    /** 一页的指纹（fs_id 串），用来认出「分页没往前走」。 */
    private static String fingerprintOf(JsonArray rows) {
        if (rows == null || rows.size() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement item : rows) {
            if (item == null || !item.isJsonObject()) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(NetdiskShareHttp.optString(item.getAsJsonObject(), "fs_id"));
        }
        return sb.toString();
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

    /** form 编码（空格转 {@code +}）；提取码可能带怪字符，按标准表单编码最稳。 */
    private static String encodeForm(String value) {
        try {
            return URLEncoder.encode(NetdiskShareHttp.text(value), "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return NetdiskShareHttp.text(value);
        }
    }

    /** 文件名自然序：{@code E02} 排在 {@code E10} 前面。 */
    private static final Comparator<FilePlan> BY_NAME =
            (left, right) ->
                    NetdiskShareHttp.compareName(
                            left == null ? null : left.name, right == null ? null : right.name);

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

    /** 分享里的一个子目录（只要 path，列目录用它）。 */
    private static final class Dir {
        final String path;

        Dir(String path) {
            this.path = path;
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
