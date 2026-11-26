package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 阿里云盘分享的原生解析：把「四段 al id」换成可播直链。
 *
 * <p>背景：详情页的下载区把阿里分享本身当线路给出来，插件换不出直链（它没有账号凭据），只会回一句
 * 笼统的失败话。这里用**匿名 share_token 列目录 + 账号转存 + 账号换直链**把同一份分享换成可播地址。
 * 协议照 {@code flutter/lib/core/ali_share.dart}（{@code listShare} / {@code resolve} / {@code _save}）
 * 与任务卡 2026-11-26 移植，**没有自己发明参数**：
 *
 * <ol>
 *   <li>匿名换 share_token：{@code POST https://api.aliyundrive.com/v2/share_link/get_share_token}
 *       body {@code {"share_id":"…","share_pwd":""}} → {@code share_token}（有效期短，用完即弃，
 *       不缓存到下一轮播放；卡里写的 {@code adrive/v3/share_link/get_share_by_anonymous} 作为备用）；
 *   <li>匿名列目录与 provider 共用（{@code /adrive/v2/file/list_by_anonymous}，头 {@code x-share-token}），
 *       见 {@link AliShareProvider}；
 *   <li>转存：先 {@code POST /v2/file/get} 确认文件存在（不是目录），在账号盘里找/建
 *       {@code MarcoMovie分享播放/ali_<sha256(share_id\nfile_id)>}，同目录里 {@code content_hash+size}
 *       相同的文件直接复用（不重复转存），否则 {@code POST /adrive/v2/batch} 走 {@code /file/copy}；
 *   <li>直链：{@code POST https://bj29.api.aliyunpds.com/v2/file/get_download_url}
 *       body {@code {"drive_id":…,"file_id":…,"expire_sec":14400}}（带账号头）→ {@code url}；
 *       备用 {@code POST /v2/file/get_video_preview_play_info}（{@code live_transcoding} 清晰度列表，
 *       取最高的那档）。
 * </ol>
 *
 * <p>id 形状（四段 {@code ++} 分隔，第一段固定 {@code al}；与 UC 的四段、百度/夸克的五段都不同）：
 * <pre>
 *   [0] 固定 "al"
 *   [1] share_id    分享 id（{@code /s/<id>} 里那段）
 *   [2] file_id     分享内文件 id
 *   [3] nameBase64  剧集名，base64url 无填充（不含 {@code +}，所以不会撞 {@code ++} 分隔符）
 * </pre>
 * 第 3 段只是给人看的剧集名（解析成功时用 lineName，lineName 为空才用它），不放任何凭据。
 *
 * <p>直链只带公开媒体头（{@code Referer: https://www.aliyundrive.com/}、{@code User-Agent:
 * Mozilla/5.0}），不带账号 token —— 与 Flutter 端 {@code _mediaHeaders} 一致；CDN 不认账号头。
 *
 * <p>线程：{@link #resolve(String, String, String)} 会打网络（匿名换 token + 转存 + 换直链，单请求
 * 8 秒连接、10 秒读超时），必须在后台线程调用。它同步执行、永不抛，失败返回 null，原因放
 * {@link #lastFailureReason()}，与 {@link UcShareResolver} / {@link BaiduShareResolver} 同一套口径。
 */
final class AliShareResolver {

    private static final String TAG = "AliShare";

    /** 接口认的公开 UA（照 Flutter 的 {@code _mediaHeaders}；直链也用它）。 */
    static final String UA = "Mozilla/5.0";

    /** 接口与直链的 Referer（照 Flutter 的 {@code _mediaHeaders}）。 */
    static final String REFERER = "https://www.aliyundrive.com/";

    /** 阿里客户端串（照 Flutter 端；分享接口不强制，但带上与被抓到的请求一致）。 */
    private static final String CANARY = "client=Android,app=adrive,version=v4.1.4";

    private static final String API = "https://api.aliyundrive.com";

    /**
     * 匿名换 share_token 的主入口：{@code /v2/share_link/get_share_token}（Flutter 端在跑的那一个，
     * 返回 {@code share_token}；社区整理的 adrive 接口文档里也是这样）。
     */
    private static final String SHARE_TOKEN_URL = API + "/v2/share_link/get_share_token";

    /**
     * 备用入口：卡里写的 {@code /adrive/v3/share_link/get_share_by_anonymous}（同一个分享的另一个口子）。
     * 它主要返回分享标题/创作者/file_infos，老文档的响应里没有 {@code share_token}，所以放在备用位。
     */
    static final String SHARE_INFO_URL = API + "/adrive/v3/share_link/get_share_by_anonymous";

    /** 账号盘自己的目录列表（转存前找目录 / 找已转存的文件）。 */
    private static final String OWN_LIST_URL = API + "/adrive/v3/file/list";

    /** 分享内单个文件的元信息（转存前确认它不是目录）。 */
    private static final String FILE_GET_URL = API + "/v2/file/get";

    /** 建播放目录。 */
    private static final String FILE_CREATE_URL = API + "/v2/file/create";

    /** 批量接口：转存就是里面的一个 {@code /file/copy}（照 Flutter 的 {@code _save}）。 */
    private static final String BATCH_URL = API + "/adrive/v2/batch";

    /** 账号换直链（卡里给的域名，与 Flutter 端同一个）。 */
    private static final String DOWNLOAD_URL = "https://bj29.api.aliyunpds.com/v2/file/get_download_url";

    /** 备用：转码清晰度列表（拿不到直链时用最高一档）。 */
    private static final String PREVIEW_URL = API + "/v2/file/get_video_preview_play_info";

    /** 播放目录名：与 Flutter 端同一个，转存过的文件两边互相复用。 */
    private static final String FOLDER_NAME = "MarcoMovie分享播放";

    private static final int PAGE_SIZE = 100;
    /** 账号盘一个目录最多翻几页（20 × 100 条已经远超播放目录的体量）。 */
    private static final int OWN_MAX_PAGES = 20;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    /** 需要登录：本地没有 ali.json。 */
    private static final String LOGIN_REQUIRED = "需要登录阿里云盘才能播放这条分享（顶部「网盘」→ 阿里，粘贴 refresh_token）";

    /** 有凭据但接口回 401（登录失效）。 */
    private static final String LOGIN_EXPIRED = "阿里云盘登录已失效，请在网盘中心重新粘贴 refresh_token";

    /** 分享已取消 / 已过期 / 被删。 */
    private static final String SHARE_GONE = "阿里分享已失效（链接被取消或已过期），请换一条线路";

    /** 分享要提取码（本次不做提取码链路，如实说）。 */
    private static final String NEED_PASSWORD = "阿里分享需要提取码，这条线路播不了，请换一条线路";

    /** 接口没有直链，但也不是明确的失效码。 */
    private static final String NO_DIRECT_URL = "阿里云盘没有返回播放地址，请换一条线路";

    /** {@code content_hash} 只认 40/64 位小写 hex（照 Flutter 的 {@code _contentHash}）。 */
    private static final Pattern CONTENT_HASH = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");

    /**
     * 已转存过的文件：{@code 账号|drive|share|fid → 自己盘里的 file_id}。进程内缓存，key 里带账号，
     * 换账号不会串。上限只为防长跑进程无限涨。
     */
    private static final Map<String, String> SAVED = new ConcurrentHashMap<>();

    /** 已经找过/建过的目录：{@code 账号|drive|parent|name → folder_id}。 */
    private static final Map<String, String> FOLDERS = new ConcurrentHashMap<>();

    private static final int CACHE_MAX_ENTRIES = 256;

    /** 上一次解析拒绝播放的原因，成功或没轮到它时为空（与 UC/百度那边同款，UI 线程读）。 */
    private static volatile String lastRefusalReason = "";

    private AliShareResolver() {}

    // ------------------------------------------------------------------ 对外入口

    /**
     * 把一条阿里分享 id 换成可播直链；不是阿里形状的 id、没登录、接口拒绝、直链不可用一律返回 null。
     * 调用方拿到 null 之后读 {@link #lastFailureReason()} 拿原因。
     *
     * @param siteKey 站点 api/key，只用于日志（判据是 id 形状，不看站名）
     * @param lineName 玩家选的线路名，例如 {@code 阿里分享}；用作剧集名
     * @param rawEpisodeId 插件拿到的 episode url，四段 {@code ++} 分隔、第一段 {@code al}
     */
    static Cms.Episode resolve(String siteKey, String lineName, String rawEpisodeId) {
        lastRefusalReason = "";
        Ident ident = parseIdent(rawEpisodeId);
        if (ident == null) return null;
        AliLogin.Credential credential = AliLogin.loadSaved();
        if (credential == null) {
            lastRefusalReason = LOGIN_REQUIRED;
            AppLog.w(TAG, "refused: no saved refresh_token in files/netdisk/ali.json");
            return null;
        }
        AppLog.w(
                TAG,
                "start site="
                        + text(siteKey).trim()
                        + " line="
                        + text(lineName).trim()
                        + " share="
                        + ident.shareId
                        + " fid="
                        + ident.fileId);
        try {
            AliLogin.Session session = AliLogin.session();
            String token = shareToken(ident.shareId);
            String key = session.accountId + "|" + session.driveId + "|" + ident.shareId + "|" + ident.fileId;
            String ownId = SAVED.get(key);
            if (ownId == null || ownId.isEmpty()) {
                ownId = save(session, ident.shareId, token, ident.fileId);
                if (!ownId.isEmpty()) remember(SAVED, key, ownId);
            }
            String url = downloadUrl(session, ownId);
            if (url.isEmpty()) url = previewUrl(session, ownId);
            if (url.isEmpty()) {
                // 转存记录可能已经过期（Boss 在自己的盘里删过播放目录）：清掉缓存，下次重转存。
                SAVED.remove(key);
                lastRefusalReason = NO_DIRECT_URL;
                AppLog.w(TAG, "refused: no playable url for own file");
                return null;
            }
            // 直链是带签名的凭据，日志里只留主机名。
            AppLog.w(TAG, "direct url host=" + hostOf(url));
            return episode(lineName, ident.name, url);
        } catch (IOException error) {
            String message = NetdiskShareHttp.describe(error);
            lastRefusalReason = message.isEmpty() ? "阿里云盘访问失败，请稍后重试" : message;
            AppLog.w(TAG, "resolve failed: " + lastRefusalReason);
        } catch (RuntimeException error) {
            lastRefusalReason = "阿里云盘访问失败（解析异常）";
            AppLog.w(TAG, "resolve failed unexpectedly", error);
        }
        return null;
    }

    /**
     * 这个 episode id 是不是阿里分享坐标（四段、第一段 {@code al}、各段形状都对得上）。只读判据、
     * 不动网络，供调用方在失败分支里选「读哪家的原因」用。
     */
    static boolean looksLikeShareId(String rawEpisodeId) {
        return parseIdent(rawEpisodeId) != null;
    }

    /**
     * 上一次解析拒绝播放的原因，成功时为 ""。只在 {@link #resolve} 返回 null 之后立刻读才有意义：
     * 每次解析开头都会清空。
     */
    static String lastFailureReason() {
        String reason = lastRefusalReason;
        return reason == null ? "" : reason;
    }

    /**
     * 造一条剧集 id：{@code al ++ share_id ++ file_id ++ nameBase64}。分享侧（{@link AliShareProvider}）
     * 与播放侧共用，形状只写这一处。
     */
    static String episodeId(String shareId, String fileId, String name) {
        return "al"
                + "++"
                + text(shareId).trim()
                + "++"
                + text(fileId).trim()
                + "++"
                + encodeName(name);
    }

    /** 剧集名 → base64url（无填充，字母表里没有 {@code +}）。 */
    static String encodeName(String name) {
        return DandanplayProvider.base64UrlEncodeNoPad(
                text(name).trim().getBytes(StandardCharsets.UTF_8));
    }

    static String decodeName(String encoded) {
        String value = text(encoded);
        if (value.isEmpty()) return "";
        try {
            return new String(
                    DandanplayProvider.base64UrlDecode(value), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException error) {
            return "";
        }
    }

    // ------------------------------------------------------------------ id 形状

    /** 四段 {@code ++} 分享坐标。{@code name} 是第 3 段解出来的人类可读文件名（可能为空）。 */
    private static final class Ident {
        final String shareId;
        final String fileId;
        final String name;

        Ident(String shareId, String fileId, String name) {
            this.shareId = shareId;
            this.fileId = fileId;
            this.name = name;
        }
    }

    /**
     * @return null 当且仅当不是四段、第一段不是 {@code al}、或某一段形状与量级对不上（那就不是阿里线路）
     */
    private static Ident parseIdent(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return null;
        // -1：第 3 段即使是空串也保留；它不含 '+'，所以不会被切成五段。
        String[] segments = value.split("\\+\\+", -1);
        if (segments.length != 4) return null;
        if (!"al".equals(segments[0])) return null;
        boolean shaped =
                isIdentifier(segments[1], 1, 64)
                        && isIdentifier(segments[2], 1, 160)
                        && isBase64Url(segments[3]);
        if (!shaped) {
            // 四段但形状不对：记长度就够定位，不记明文（前两段是分享坐标）。
            AppLog.w(
                    TAG,
                    "skip: four segments but shapes do not look like an ali share id, len="
                            + segments[1].length()
                            + "/"
                            + segments[2].length()
                            + "/"
                            + segments[3].length());
            return null;
        }
        return new Ident(segments[1], segments[2], decodeName(segments[3]));
    }

    /** 分享 id / 文件 id 的字母表：阿里这两段都是 alnum 加 {@code -_}。 */
    private static boolean isIdentifier(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum && c != '_' && c != '-') return false;
        }
        return true;
    }

    /** base64url 字母表（含 {@code =} 以容忍带填充的写法）。空串放行（无名剧集）。 */
    private static boolean isBase64Url(String value) {
        if (value == null || value.length() > 16384) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum && c != '_' && c != '-' && c != '=') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 阿里接口

    /**
     * 匿名换 share_token。有效期很短（服务端下发几十分钟到两小时），所以每次播放都重新换一次，
     * 不落盘也不跨播放复用。
     *
     * <p>两个口子都试，主备与「卡里写的接口」相反：主入口是 Flutter 端在跑的
     * {@code /v2/share_link/get_share_token}（响应里明确有 {@code share_token}），备用才是卡里的
     * {@code get_share_by_anonymous}（它主要回分享标题/file_infos，老文档的响应里没有 share_token）。
     * 两个口子都实测存在（2026-11-26 本机：不存在的 share_id 两边都回 404 {@code NotFound.ShareLink}）。
     *
     * <p>{@code share_pwd} 一律发空串：要提取码的分享在 provider 那一层就不再往下走（本次范围不猜
     * 提取码），这里只负责「无密码分享能不能换到 token」。
     *
     * @throws IOException 分享失效 / 要提取码 / 网络失败；消息已是给人看的一句话
     */
    static String shareToken(String shareId) throws IOException {
        String id = text(shareId).trim();
        JsonObject payload = new JsonObject();
        payload.addProperty("share_id", id);
        payload.addProperty("share_pwd", "");
        IOException firstError = null;
        try {
            String token = tokenOf(postShare(SHARE_TOKEN_URL, payload, null));
            if (!token.isEmpty()) return token;
            firstError = new IOException("阿里分享没有返回可用的 share_token");
        } catch (IOException error) {
            firstError = error;
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("share_id", id);
        try {
            String token = tokenOf(postShare(SHARE_INFO_URL, fallback, null));
            if (!token.isEmpty()) {
                AppLog.w(TAG, "share token came from the fallback endpoint");
                return token;
            }
        } catch (IOException second) {
            AppLog.w(TAG, "share token fallback failed: " + NetdiskShareHttp.describe(second));
        }
        throw firstError;
    }

    /** 取 {@code share_token} 并校验形状；不合法返回 ""。 */
    private static String tokenOf(JsonObject body) {
        String token = NetdiskShareHttp.optString(body, "share_token").trim();
        if (token.isEmpty() || token.length() > 4096) return "";
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) return "";
        return token;
    }

    /** 匿名请求的头：{@code x-share-token}（列表与分享内文件元信息都要）。 */
    static Map<String, String> shareHeaders(String shareToken) {
        Map<String, String> out = new LinkedHashMap<>();
        String token = text(shareToken).trim();
        if (!token.isEmpty()) out.put("x-share-token", token);
        return out;
    }

    /**
     * 转存（若已转存过则复用），返回自己盘里的 {@code file_id}。
     *
     * <p>复用判据是「同一个播放目录里 {@code content_hash} 与 {@code size} 都相同」，不是文件名：
     * 同名不同内容绝不能当成同一集（照 Flutter 的 {@code _save}）。
     */
    private static String save(
            AliLogin.Session session, String shareId, String shareToken, String fileId)
            throws IOException {
        JsonObject sourcePayload = new JsonObject();
        sourcePayload.addProperty("share_id", shareId);
        sourcePayload.addProperty("file_id", fileId);
        JsonObject source = postShare(FILE_GET_URL, sourcePayload, shareHeaders(shareToken));
        if ("folder".equals(NetdiskShareHttp.optString(source, "type"))
                || !fileId.equals(NetdiskShareHttp.optString(source, "file_id").trim())) {
            throw new IOException("阿里分享文件不存在或不是可播放文件");
        }
        String playbackFolder = ensureFolder(session, "root", FOLDER_NAME);
        String folder = ensureFolder(session, playbackFolder, "ali_" + sha256Hex(shareId + "\n" + fileId));
        String hash = contentHash(source);
        long size = NetdiskShareHttp.optLong(source, "size", 0L);
        if (!hash.isEmpty() && size > 0) {
            String existing = findFile(session, folder, hash, size);
            if (!existing.isEmpty()) {
                AppLog.w(TAG, "reuse saved file " + existing);
                return existing;
            }
        }
        JsonObject answer = post(BATCH_URL, copyBody(shareId, fileId, folder, session.driveId), shareHeaders(shareToken));
        String ownId = copiedFileId(answer);
        if (!ownId.isEmpty()) return ownId;
        throw new IOException("阿里分享转存失败，请检查剩余空间及分享权限");
    }

    /** {@code /adrive/v2/batch} 的请求体：一条 {@code /file/copy}（照 Flutter 的 {@code _save}）。 */
    private static JsonObject copyBody(String shareId, String fileId, String folder, String driveId) {
        JsonObject body = new JsonObject();
        body.addProperty("file_id", fileId);
        body.addProperty("share_id", shareId);
        body.addProperty("auto_rename", true);
        body.addProperty("to_parent_file_id", folder);
        body.addProperty("to_drive_id", driveId);

        JsonObject headers = new JsonObject();
        headers.addProperty("Content-Type", "application/json");

        JsonObject request = new JsonObject();
        request.add("body", body);
        request.add("headers", headers);
        request.addProperty("id", "0");
        request.addProperty("method", "POST");
        request.addProperty("url", "/file/copy");

        JsonArray requests = new JsonArray();
        requests.add(request);

        JsonObject payload = new JsonObject();
        payload.add("requests", requests);
        payload.addProperty("resource", "file");
        return payload;
    }

    /** {@code responses[0].body.file_id}；形状不对返回 ""。 */
    private static String copiedFileId(JsonObject answer) {
        JsonElement responses = answer == null ? null : answer.get("responses");
        if (responses == null || !responses.isJsonArray()) return "";
        JsonArray rows = responses.getAsJsonArray();
        if (rows.size() != 1) return "";
        JsonElement first = rows.get(0);
        if (first == null || !first.isJsonObject()) return "";
        JsonObject result = first.getAsJsonObject();
        JsonElement body = result.get("body");
        if (body == null || !body.isJsonObject()) return "";
        JsonObject payload = body.getAsJsonObject();
        if (!NetdiskShareHttp.optString(payload, "code").trim().isEmpty()) return "";
        int status = NetdiskShareHttp.optInt(result, "status", 0);
        if (status != 0 && (status < 200 || status >= 300)) return "";
        return NetdiskShareHttp.optString(payload, "file_id").trim();
    }

    /**
     * 账号换直链；拿不到返回 ""（调用方接着试转码列表）。
     *
     * <p>这里不抛：直链拿不到是常见情况（会员/权限），备用接口还有一次机会。
     */
    private static String downloadUrl(AliLogin.Session session, String fileId) throws IOException {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("drive_id", session.driveId);
            payload.addProperty("file_id", fileId);
            payload.addProperty("expire_sec", 14400);
            JsonObject body = post(DOWNLOAD_URL, payload, session.headers());
            String url = mediaUrl(NetdiskShareHttp.optString(body, "url"));
            if (url.isEmpty()) AppLog.w(TAG, "get_download_url answered without url");
            return url;
        } catch (IOException error) {
            // 登录失效要说清楚，不能退化成「没有返回播放地址」。
            if (LOGIN_EXPIRED.equals(NetdiskShareHttp.describe(error))) throw error;
            AppLog.w(TAG, "get_download_url failed: " + NetdiskShareHttp.describe(error));
            return "";
        }
    }

    /**
     * 备用直链：{@code live_transcoding_task_list} 里按 {@code template_height} 从高到低取第一档可用的。
     * 清晰度数量在日志里记一行（只有档数和最高档，不记 URL）。
     */
    private static String previewUrl(AliLogin.Session session, String fileId) throws IOException {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("drive_id", session.driveId);
            payload.addProperty("file_id", fileId);
            payload.addProperty("category", "live_transcoding");
            payload.addProperty("get_subtitle_info", true);
            payload.addProperty("url_expire_sec", 14400);
            JsonObject body = post(PREVIEW_URL, payload, session.headers());
            JsonElement info = body.get("video_preview_play_info");
            if (info == null || !info.isJsonObject()) {
                AppLog.w(TAG, "preview answered without video_preview_play_info");
                return "";
            }
            JsonElement tasks = info.getAsJsonObject().get("live_transcoding_task_list");
            if (tasks == null || !tasks.isJsonArray()) {
                AppLog.w(TAG, "preview answered without live_transcoding_task_list");
                return "";
            }
            List<JsonObject> available = new ArrayList<>();
            for (JsonElement item : tasks.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject task = item.getAsJsonObject();
                String status = NetdiskShareHttp.optString(task, "status").trim();
                if (!status.isEmpty() && !"finished".equals(status)) continue;
                if (mediaUrl(NetdiskShareHttp.optString(task, "url")).isEmpty()) continue;
                available.add(task);
            }
            if (available.isEmpty()) {
                AppLog.w(TAG, "preview answered without a usable transcode task");
                return "";
            }
            java.util.Collections.sort(
                    available,
                    (left, right) ->
                            Integer.compare(
                                    NetdiskShareHttp.optInt(right, "template_height", 0),
                                    NetdiskShareHttp.optInt(left, "template_height", 0)));
            JsonObject best = available.get(0);
            AppLog.w(
                    TAG,
                    "preview variants="
                            + available.size()
                            + " best="
                            + NetdiskShareHttp.optInt(best, "template_height", 0)
                            + "P");
            return mediaUrl(NetdiskShareHttp.optString(best, "url"));
        } catch (IOException error) {
            if (LOGIN_EXPIRED.equals(NetdiskShareHttp.describe(error))) throw error;
            AppLog.w(TAG, "preview failed: " + NetdiskShareHttp.describe(error));
            return "";
        }
    }

    /** 在账号盘某个目录里找同名文件夹；没有就建一个（建失败时再看一次，可能是并发建好了）。 */
    private static String ensureFolder(AliLogin.Session session, String parent, String name)
            throws IOException {
        String key = session.accountId + "|" + session.driveId + "|" + parent + "|" + name;
        String cachedFolder = FOLDERS.get(key);
        if (cachedFolder != null && !cachedFolder.isEmpty()) return cachedFolder;
        String existing = findFolder(session, parent, name);
        if (!existing.isEmpty()) {
            remember(FOLDERS, key, existing);
            return existing;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("drive_id", session.driveId);
            payload.addProperty("parent_file_id", parent);
            payload.addProperty("name", name);
            payload.addProperty("type", "folder");
            payload.addProperty("check_name_mode", "refuse");
            JsonObject created = post(FILE_CREATE_URL, payload, session.headers());
            String id = NetdiskShareHttp.optString(created, "file_id").trim();
            if (!id.isEmpty()) {
                remember(FOLDERS, key, id);
                return id;
            }
        } catch (IOException error) {
            // 另一条播放可能刚建好同名目录：再找一次，找到就复用，找不到才把原错误抛出去。
            String raced = findFolder(session, parent, name);
            if (!raced.isEmpty()) {
                remember(FOLDERS, key, raced);
                return raced;
            }
            throw error;
        }
        throw new IOException("阿里播放目录创建失败（" + name + "）");
    }

    private static String findFolder(AliLogin.Session session, String parent, String name)
            throws IOException {
        for (JsonObject row : listOwn(session, parent)) {
            if (!"folder".equals(NetdiskShareHttp.optString(row, "type"))) continue;
            if (!name.equals(NetdiskShareHttp.optString(row, "name").trim())) continue;
            String id = NetdiskShareHttp.optString(row, "file_id").trim();
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    /** 播放目录里按 {@code content_hash + size} 找已转存的文件；没有返回 ""。 */
    private static String findFile(AliLogin.Session session, String parent, String hash, long size)
            throws IOException {
        for (JsonObject row : listOwn(session, parent)) {
            if ("folder".equals(NetdiskShareHttp.optString(row, "type"))) continue;
            if (!hash.equals(contentHash(row))) continue;
            if (NetdiskShareHttp.optLong(row, "size", 0L) != size) continue;
            String id = NetdiskShareHttp.optString(row, "file_id").trim();
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    /** 账号盘一个目录的全部条目（分页，最多 {@link #OWN_MAX_PAGES} 页）。 */
    private static List<JsonObject> listOwn(AliLogin.Session session, String parent)
            throws IOException {
        List<JsonObject> out = new ArrayList<>();
        String marker = "";
        Set<String> seen = new LinkedHashSet<>();
        for (int page = 0; page < OWN_MAX_PAGES; page++) {
            JsonObject payload = new JsonObject();
            payload.addProperty("drive_id", session.driveId);
            payload.addProperty("parent_file_id", parent);
            payload.addProperty("limit", PAGE_SIZE);
            payload.addProperty("order_by", "name");
            payload.addProperty("order_direction", "ASC");
            if (!marker.isEmpty()) payload.addProperty("marker", marker);
            JsonObject body = post(OWN_LIST_URL, payload, session.headers());
            JsonElement items = body.get("items");
            if (items == null || !items.isJsonArray()) return out;
            for (JsonElement item : items.getAsJsonArray()) {
                if (item != null && item.isJsonObject()) out.add(item.getAsJsonObject());
            }
            marker = NetdiskShareHttp.optString(body, "next_marker").trim();
            if (marker.isEmpty()) return out;
            if (!seen.add(marker)) {
                AppLog.w(TAG, "own drive paging repeated a marker, stop");
                return out;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 造剧集

    /**
     * 造可播的剧集。口径与 {@link UcShareResolver} / {@link BaiduShareResolver} 一致：非 HLS 用
     * {@code _forceMpv} 交给 MPV，HLS 标成 netdisk 的 HLS 让它走直连；两者都 {@code _noCache}，
     * 因为签名直链很短命，媒体缓存会把它写坏。头只有公开媒体头（不带账号 token）。
     */
    private static Cms.Episode episode(String lineName, String fallbackName, String url) {
        Cms.Episode episode = new Cms.Episode();
        String name = text(lineName).trim();
        if (name.isEmpty()) name = text(fallbackName).trim();
        if (name.isEmpty()) name = "阿里分享";
        episode.name = name;
        episode.url = url;
        episode.headers.put("User-Agent", UA);
        episode.headers.put("Referer", REFERER);
        boolean hls = looksLikeHls(url);
        try {
            episode.media.put("_noCache", true);
            if (hls) {
                episode.media.put("mimeType", "application/x-mpegURL");
                episode.media.put("_netdiskHls", true);
            } else {
                // 网盘直链不再强制 mpv：盒子上的 mpv 打开这类签名直链会 audio=eof 卡住；
                // media3 + 默认优先 AAC 音轨已经能播（12:42、15:29 实测 4K 出画面）。
                episode.media.put("_forceMpv", false);
            }
        } catch (org.json.JSONException ignored) {
            // org.json 的受检异常；每个标记都是可选的。
        }
        return episode;
    }

    /** 直链是不是 HLS 播放列表（忽略 query）。 */
    private static boolean looksLikeHls(String url) {
        String path = text(url).trim().toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        return path.endsWith(".m3u8") || path.endsWith(".m3u");
    }

    /** 只留主机名：直链的 query 是凭据，不是诊断材料。 */
    private static String hostOf(String url) {
        try {
            String host = new URL(text(url).trim()).getHost();
            return host == null || host.isEmpty() ? "-" : host;
        } catch (IOException | RuntimeException ignored) {
            return "-";
        }
    }

    /** 直链形状判据（照 Flutter 的 {@code _mediaUrl}）：只认 https、有主机、不带 userinfo。 */
    private static String mediaUrl(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return "";
        try {
            URL url = new URL(value);
            if (!"https".equals(text(url.getProtocol()).toLowerCase(Locale.ROOT))) return "";
            if (text(url.getHost()).isEmpty()) return "";
            if (!text(url.getUserInfo()).isEmpty()) return "";
        } catch (IOException | RuntimeException error) {
            return "";
        }
        return value;
    }

    private static String contentHash(JsonObject obj) {
        String hash = NetdiskShareHttp.optString(obj, "content_hash").trim().toLowerCase(Locale.ROOT);
        return CONTENT_HASH.matcher(hash).matches() ? hash : "";
    }

    /** 小写十六进制 SHA-256（目录名用，与 Flutter 的 {@code sha256.convert(...)} 同形）。 */
    private static String sha256Hex(String value) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
        byte[] sum = digest.digest(text(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(sum.length * 2);
        for (byte b : sum) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    // ---------------------------------------------------------------- HTTP plumbing

    /** 公共头：UA / Accept / Referer / X-Canary（所有阿里接口都带）。 */
    private static Map<String, String> apiHeaders() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("User-Agent", UA);
        out.put("Accept", "application/json");
        out.put("Referer", REFERER);
        out.put("X-Canary", CANARY);
        return out;
    }

    /**
     * 一个 JSON POST（不跟随跳转）。非 2xx 也把正文读回来，由 {@link #refusalFor} 统一翻译成一句
     * 人话；返回值已确认没有错误 {@code code}。
     *
     * <p>包内可见：分享侧 {@link AliShareProvider} 的匿名换 token / 列目录也走这里，错误口径只写一份。
     */
    static JsonObject post(String url, JsonObject payload, Map<String, String> headers)
            throws IOException {
        return post(url, payload, headers, false);
    }

    /**
     * 分享坐标（{@code x-share-token}）的请求：401 在这个上下文里是「share token 失效」，不是
     * 「登录失效」。为什么要靠调用方标这一位：2026-11-26 实测 Java 的 {@link HttpURLConnection} 收
     * 401 时 {@code getErrorStream()} 为 null（同一请求 curl 能拿到 {@code ShareLinkTokenInvalid}），
     * 所以不能只靠响应体里的 code 区分这两种 401。
     */
    static JsonObject postShare(String url, JsonObject payload, Map<String, String> headers)
            throws IOException {
        return post(url, payload, headers, true);
    }

    private static JsonObject post(
            String url, JsonObject payload, Map<String, String> headers, boolean shareScoped)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            RequestScope.attach(conn);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            Map<String, String> merged = apiHeaders();
            if (headers != null) merged.putAll(headers);
            for (Map.Entry<String, String> header : merged.entrySet()) {
                String key = text(header.getKey()).trim();
                String value = text(header.getValue()).trim();
                if (!key.isEmpty() && !value.isEmpty()) conn.setRequestProperty(key, value);
            }
            JsonObject body = payload == null ? new JsonObject() : payload;
            byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(raw.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(raw);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            int http = conn.getResponseCode();
            String reply = readAll(http >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JsonObject parsed = NetdiskShareHttp.parseObjectOrNull(reply);
            String code = NetdiskShareHttp.optString(parsed, "code").trim();
            // 先看 code：401 既可能是「账号 token 失效」也可能是「share token 失效」，两者的意思完全不同。
            if (!code.isEmpty() && !"0".equals(code)) {
                throw new IOException(refusalFor(code, NetdiskShareHttp.optString(parsed, "message")));
            }
            if (http == 401) throw new IOException(shareScoped ? SHARE_GONE : LOGIN_EXPIRED);
            if (http == 404) throw new IOException(SHARE_GONE);
            if (parsed == null) {
                throw new IOException("阿里接口响应异常（HTTP " + http + "）");
            }
            if (http < 200 || http >= 300) {
                throw new IOException("阿里接口访问失败（HTTP " + http + "）");
            }
            return parsed;
        } finally {
            if (conn != null) {
                RequestScope.detach(conn);
                conn.disconnect();
            }
        }
    }

    /**
     * 错误码 → 一句人话。口径照 Flutter 的 {@code _post}：空间不足单独说（并提示清播放目录），
     * sharelink.* 是分享失效；其余只报 code/message，不猜含义。
     */
    private static String refusalFor(String code, String message) {
        String lowerCode = text(code).toLowerCase(Locale.ROOT);
        String lowerMessage = text(message).toLowerCase(Locale.ROOT);
        if (lowerCode.contains("quota")
                || lowerCode.contains("exceeded")
                || lowerCode.contains("capacity")
                || lowerMessage.contains("quota")
                || lowerMessage.contains("空间不足")
                || lowerMessage.contains("容量不足")) {
            return "阿里云盘剩余空间不足，请清理「" + FOLDER_NAME + "」目录后重试";
        }
        // 网关限速（2026-11-26 真实公开分享上实测过 BlockException / rule:TooManyRequests）。
        if (lowerCode.contains("blockexception")
                || lowerMessage.contains("toomanyrequests")
                || lowerMessage.contains("too many requests")) {
            return "阿里云盘请求过于频繁，请稍后再试";
        }
        // 账号 token 失效（AccessTokenInvalid / AccessTokenExpired）：这一档要 Boss 重新登录。
        if (lowerCode.contains("accesstoken")) return LOGIN_EXPIRED;
        // 分享本身失效：取消 / 过期 / 被删 / 不认这条链接。
        if (lowerCode.contains("sharelink.forbidden")
                || lowerCode.contains("sharelink.cancelled")
                || lowerCode.contains("sharelink.expired")
                || lowerCode.contains("sharelink.notfound")
                || lowerCode.contains("notfound.sharelink")) {
            return SHARE_GONE;
        }
        // share token 失效（过期/被换掉）：还是分享这一侧的事，重新换一次 token 即可，别说成登录失效。
        if (lowerCode.contains("sharelinktoken")) {
            return SHARE_GONE;
        }
        // 路径不存在是我们自己的协议错误，别伪装成分享失效（2026-11-26 就踩过 list_by_anonymous）。
        if ("notfound".equals(lowerCode)) {
            return "阿里接口路径不可用（code=NotFound），请更新应用";
        }
        if (lowerCode.contains("password")
                || lowerMessage.contains("password")
                || lowerMessage.contains("提取码")) {
            return NEED_PASSWORD;
        }
        return "阿里接口返回错误（code="
                + text(code).trim()
                + (text(message).trim().isEmpty() ? "" : "，message=" + text(message).trim())
                + "）";
    }

    private static void remember(Map<String, String> cache, String key, String value) {
        if (cache.size() >= CACHE_MAX_ENTRIES) cache.clear();
        if (key != null && value != null && !value.isEmpty()) cache.put(key, value);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_BODY_BYTES) break;
                out.write(buffer, 0, read);
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
            // 没有可做的事
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // 没有可做的事
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
