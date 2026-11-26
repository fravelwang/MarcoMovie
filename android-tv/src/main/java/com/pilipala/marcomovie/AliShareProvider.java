package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 阿里云盘分享 → 线路：匿名换 share_token、匿名列目录、按文件造四段 {@code al} id。
 *
 * <p>协议照 {@code flutter/lib/core/ali_share.dart} 的 {@code listShare} 与任务卡 2026-11-26 移植，
 * **免登录**——公开分享列目录只要匿名 share_token，账号只在播放直链时才用到（那是
 * {@link AliShareResolver} 的事）：
 * <ol>
 *   <li>换 share_token：{@link AliShareResolver#shareToken(String)}（{@code /v2/share_link/get_share_token}，
 *       {@code share_pwd} 空串；卡里的 {@code get_share_by_anonymous} 作备用）→ {@code share_token}
 *       （有效期短，一次列目录用完即弃）；
 *   <li>列目录：{@code POST /adrive/v3/file/list}（带 {@code x-share-token} 就是匿名列分享；卡里写的
 *       {@code /adrive/v2/file/list_by_anonymous} 实测不存在，见 {@link #LIST_URL}），body
 *       {@code {"share_id":…,"parent_file_id":…,"limit":100,"marker":…}} 分页，{@code next_marker}
 *       为空即到底；
 *   <li>{@code type == "folder"} 往下钻（最多 {@link #MAX_DEPTH} 层、最多 {@link #MAX_REQUESTS} 次
 *       请求、最多收 {@link #MAX_FILES} 个文件，前两条是 Flutter 端的口径）；文件按
 *       {@code category == "video"} 或视频扩展名收，{@code .mp3} 要大于 15MB（照 Flutter）；
 *   <li>id 是四段 {@code ++} 分隔：{@code al ++ share_id ++ file_id ++ nameBase64}，播放侧由
 *       {@link AliShareResolver} 拿它换直链。
 * </ol>
 *
 * <p>造出来的 id 一律先过 {@link AliShareResolver#looksLikeShareId(String)}（与播放侧同一套判据）：
 * 形状对不上的条目宁可丢掉，也不要塞进线路让播放器黑屏后弹一句笼统话。
 *
 * <p>线路最多收 {@link #MAX_FILES} 集：与夸克/UC/百度三家同一个上限（详情页的线路列表要能一秒画完，
 * 1 万集是遍历的安全阀，不是线路的体量）。文件按文件名自然序排好。
 *
 * <p>要提取码的分享（链接上带 {@code ?pwd=}）：本次只做「如实说」，不猜提取码 —— 返回空表并在日志里
 * 写清楚，播放侧如果真拿到这类 id 会说「需要提取码」。
 */
final class AliShareProvider implements NetdiskShareProvider {

    static final String ID = "ali";
    /** 线路名：播放侧按 id 形状认，不看名字；这个名字只是给人看的。 */
    static final String LINE_NAME = "阿里分享";

    private static final String TAG = NetdiskShareHttp.TAG;

    /**
     * 匿名列目录。卡里写的 {@code /adrive/v2/file/list_by_anonymous} 在真实服务上不存在（2026-11-26
     * 本机实测：HTTP 404 {@code code=NotFound "uri(/v2/file/list_by_anonymous) not exist"}），所以照
     * Flutter 端已经在跑的写法走 {@code /adrive/v3/file/list} + {@code x-share-token}（同一个接口，
     * 带 share token 时就是匿名列分享；实测缺 token 时回 401 {@code ShareLinkTokenInvalid}）。
     */
    private static final String LIST_URL = "https://api.aliyundrive.com/adrive/v3/file/list";

    /** 分享根目录的固定 parent_file_id（与 Flutter 端同一个）。 */
    private static final String ROOT_FID = "root";

    private static final int PAGE_SIZE = 100;
    /** 一条分享最多收多少个文件（集数），与夸克/UC/百度一致；收满就停，不再为看不见的集数打接口。 */
    private static final int MAX_FILES = 200;
    /** 遍历上限：目录层数（照 Flutter 的 depth > 20）。 */
    private static final int MAX_DEPTH = 20;
    /** 遍历上限：请求次数（照 Flutter 的 500 次）。 */
    private static final int MAX_REQUESTS = 500;
    /** 单个目录最多翻几页（一页 100 条）。 */
    private static final int MAX_PAGES = 100;
    /**
     * 两次列目录之间的最小间隔。2026-11-26 在真实公开分享上实测：一次性递归几十个目录会被网关回
     * {@code BlockException rule:TooManyRequests}（换个时间点再跑就好了，说明是限速不是协议错）。
     * 线上一部片的分享通常只有几个目录，这点间隔感觉不到；大合集则不会一头撞上限速。
     */
    private static final long REQUEST_GAP_MS = 100L;

    /** 视频扩展名判据（照 Flutter 的 listShare 正则）。 */
    private static final Pattern VIDEO =
            Pattern.compile("\\.(mp4|mkv|m4v|mov|ts|m2ts|avi|webm|flv)$", Pattern.CASE_INSENSITIVE);

    /** 音频只收大文件（照 Flutter：{@code .mp3} 且 ≥ 15MB）。 */
    private static final Pattern AUDIO = Pattern.compile("\\.mp3$", Pattern.CASE_INSENSITIVE);
    private static final long MIN_AUDIO_BYTES = 15L * 1024 * 1024;

    @Override
    public String id() {
        return ID;
    }

    /**
     * 阿里分享的两个域名：{@code aliyundrive.com}（老域名）与 {@code alipan.com}（新域名），带不带
     * {@code www.} 都认。路径要像 {@code /s/<id>}；{@code /s/<id>/folder/<fid>} 也认（从根开始列，
     * 子目录多请求一次不值得为它加一条协议）。
     */
    @Override
    public boolean matches(String url) {
        String host = NetdiskShareHttp.hostOf(url);
        if (!"aliyundrive.com".equals(host)
                && !"www.aliyundrive.com".equals(host)
                && !"alipan.com".equals(host)
                && !"www.alipan.com".equals(host)) {
            return false;
        }
        return NetdiskShareHttp.parseShare(url) != null;
    }

    @Override
    public List<Cms.Line> lines(String shareUrl, String passcode) throws Exception {
        NetdiskShareHttp.ShareRef share = NetdiskShareHttp.parseShare(shareUrl);
        if (share == null) return Collections.emptyList();
        String pwd =
                NetdiskShareHttp.text(passcode).trim().isEmpty()
                        ? share.passcode
                        : passcode.trim();
        if (!pwd.isEmpty()) {
            // 本次不做提取码链路：如实记一行，返回空表（播放侧拿到这类 id 也会说「需要提取码」）。
            AppLog.w(
                    TAG,
                    "provider=ali skip: share needs a passcode share=" + share.pwdId);
            return Collections.emptyList();
        }
        String token;
        try {
            token = AliShareResolver.shareToken(share.pwdId);
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=ali share token failed share="
                            + share.pwdId
                            + ": "
                            + NetdiskShareHttp.describe(error));
            return Collections.emptyList();
        }
        Walk walk = new Walk();
        try {
            walk(share.pwdId, token, ROOT_FID, "", 0, walk);
        } catch (IOException error) {
            AppLog.w(
                    TAG,
                    "provider=ali list failed share="
                            + share.pwdId
                            + ": "
                            + NetdiskShareHttp.describe(error));
        }
        if (walk.files.isEmpty()) {
            AppLog.w(TAG, "provider=ali no file in share " + share.pwdId);
            return Collections.emptyList();
        }
        List<FilePlan> files = new ArrayList<>(walk.files);
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
        AppLog.w(
                TAG,
                "provider=ali share "
                        + share.pwdId
                        + " files="
                        + files.size()
                        + " requests="
                        + walk.requests);
        return Collections.singletonList(line);
    }

    // ------------------------------------------------------------------ 阿里匿名接口

    /** 一页目录；失败抛 IOException（调用方记一行日志、停止这一层）。 */
    private static JsonObject listPage(String shareId, String token, String folderId, String marker)
            throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("share_id", shareId);
        payload.addProperty("parent_file_id", folderId);
        payload.addProperty("limit", PAGE_SIZE);
        payload.addProperty("order_by", "name");
        payload.addProperty("order_direction", "ASC");
        if (!NetdiskShareHttp.text(marker).trim().isEmpty()) payload.addProperty("marker", marker);
        return AliShareResolver.postShare(LIST_URL, payload, AliShareResolver.shareHeaders(token));
    }

    /**
     * 收一层目录，收完再钻子目录（先收完这一层，页面分页与递归不会交错）。三条上限任一触发就停：
     * 层数、请求数、累计文件数。
     */
    private static void walk(
            String shareId, String token, String folderId, String prefix, int depth, Walk walk)
            throws IOException {
        if (depth > MAX_DEPTH) return;
        if (walk.requests >= MAX_REQUESTS || walk.files.size() >= MAX_FILES) return;
        List<String[]> dirs = new ArrayList<>();
        String marker = "";
        Set<String> seenMarkers = new HashSet<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            if (walk.requests >= MAX_REQUESTS || walk.files.size() >= MAX_FILES) break;
            if (walk.requests > 0) pauseBetweenRequests();
            walk.requests++;
            JsonObject body = listPage(shareId, token, folderId, marker);
            JsonElement items = body.get("items");
            if (items == null || !items.isJsonArray()) {
                AppLog.w(TAG, "provider=ali list answered without items, folder=" + folderId);
                break;
            }
            JsonArray rows = items.getAsJsonArray();
            for (JsonElement item : rows) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject row = item.getAsJsonObject();
                String fid = NetdiskShareHttp.optString(row, "file_id").trim();
                String name = NetdiskShareHttp.optString(row, "name").trim();
                if (fid.isEmpty() || name.isEmpty()) continue;
                String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
                if ("folder".equals(NetdiskShareHttp.optString(row, "type"))) {
                    dirs.add(new String[] {fid, fullName});
                    continue;
                }
                if (!isVideo(row, name)) continue;
                String id = AliShareResolver.episodeId(shareId, fid, fullName);
                if (!AliShareResolver.looksLikeShareId(id)) {
                    AppLog.w(
                            TAG,
                            "provider=ali skip: id shape rejected len="
                                    + shareId.length()
                                    + "/"
                                    + fid.length()
                                    + "/"
                                    + fullName.length());
                    continue;
                }
                walk.files.add(new FilePlan(fullName, id));
                if (walk.files.size() >= MAX_FILES) break;
            }
            marker = NetdiskShareHttp.optString(body, "next_marker").trim();
            if (marker.isEmpty()) break;
            if (!seenMarkers.add(marker)) {
                AppLog.w(TAG, "provider=ali paging repeated a marker, stop folder=" + folderId);
                break;
            }
        }
        for (String[] dir : dirs) {
            if (walk.requests >= MAX_REQUESTS || walk.files.size() >= MAX_FILES) return;
            walk(shareId, token, dir[0], dir[1], depth + 1, walk);
        }
    }

    /** 列目录之间歇一下，别把大合集一口气打完（见 {@link #REQUEST_GAP_MS}）。 */
    private static void pauseBetweenRequests() throws IOException {
        try {
            Thread.sleep(REQUEST_GAP_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("阿里分享遍历被中断", interrupted);
        }
    }

    /** 收不收这个文件：{@code category == "video"}，或视频扩展名，或大 mp3（照 Flutter）。 */
    private static boolean isVideo(JsonObject row, String name) {
        if ("video".equals(NetdiskShareHttp.optString(row, "category"))) return true;
        if (VIDEO.matcher(name).find()) return true;
        return AUDIO.matcher(name).find()
                && NetdiskShareHttp.optLong(row, "size", 0L) >= MIN_AUDIO_BYTES;
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

    /** 一次遍历的状态（请求数 + 已收文件）。 */
    private static final class Walk {
        int requests;
        final List<FilePlan> files = new ArrayList<>();
    }
}
