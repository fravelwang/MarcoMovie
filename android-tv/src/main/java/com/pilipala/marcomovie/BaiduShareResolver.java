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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 百度网盘分享的原生解析：把「五段 bd id」换成可播直链。
 *
 * <p>背景：详情页的下载区把百度分享本身当线路给出来，插件换不出直链（它没有账号凭据），只会回
 * 一句笼统的失败话。这里用**账号 Cookie + 官方签名**把同一份分享换成直链。协议照仓库里已经能跑的
 * Flutter 实现 {@code flutter/lib/core/cloud_share.dart}（{@code _baiduFiles} / {@code
 * _resolveBaidu}，约 370–650 行）移植，**没有自己发明参数**：
 *
 * <ol>
 *   <li>取 {@code uid}：{@code GET https://mbd.baidu.com/userx/v1/info/get?appname=baiduboxapp
 *       &fields=["bg_image","member","uid","avatar","avatar_member"]}，账号 Cookie，媒体 UA；
 *   <li>算签名：{@code rand = sha1( hexSha1(BDUSS) + uid + RAND_KEY + time + DEVICE + RAND_TAIL )}，
 *       全部是实测常量（见下面的常量注释）；
 *   <li>取直链：{@code GET https://pan.baidu.com/share/list?shareid=…&uk=…&fid=…&sekey=…
 *       &origin=dlna&devuid=<DEVICE>&clienttype=1&channel=<CHANNEL>&version=<VERSION>&time=…&rand=…}
 *       ，Cookie 带账号 Cookie 与 {@code BDCLND=<sekey>}，UA 用媒体串 → {@code list[0].dlink}。
 * </ol>
 *
 * <p>id 形状（五段 {@code ++} 分隔，第一段固定 {@code bd}；与 UC 的四段、夸克的五段都不同形）：
 * <pre>
 *   [0] 固定 "bd"
 *   [1] share_id   列表接口回的数字分享 id（不是 URL 里那个带前导 1 的短 id）
 *   [2] uk         分享所有者的 uk
 *   [3] fs_id      分享内文件 id
 *   [4] sekey      提取码验证换来的 randsk，**百分号编码过**（保证段里不出现 + 和 %），可为空
 * </pre>
 * 第 [4] 段必须编码：raw {@code randsk} 是 URL 编码过的 base64，可能含 {@code +}，直接拼进 id 会
 * 撞上 {@code ++} 分隔符。这里约定「id 里放 encode 后的值」，播放时先解一次还原 raw，再按 Flutter
 * 的 {@code _decodeSekeyForQuery} 口径解第二次得到请求参数 —— 这条链路与 Flutter 端发出的
 * {@code sekey} 字节一致（见 {@link #shareListUrl}）。
 *
 * <p>线程：{@link #resolve(String, String, String)} 会打两次网络（单请求 8 秒超时），必须在后台线程
 * 调用。同步执行、永不抛，失败返回 null，原因放 {@link #lastFailureReason()}，与
 * {@link UcShareResolver} 同一套口径。
 */
final class BaiduShareResolver {

    private static final String TAG = "BaiduShare";

    /** 分享页 / 提取码验证认的网盘客户端串（实测值，抄一份就会漂）。 */
    static final String UA_SHARE =
            "netdisk;12.24.6;V2118A;android-android;12;JSbridge4.4.0;jointBridge;1.1.0";

    /** 取 uid 与取直链用的媒体 UA（实测值；直链本身也要同一个 UA 才能播）。 */
    static final String UA_MEDIA = "netdisk;P2SP;2.2.91.136;android-android;";

    /** 取 uid 的那个接口认不带尾分号的同一串。 */
    private static final String UA_MEDIA_NO_TAIL = "netdisk;P2SP;2.2.91.136;android-android";

    /** 直链与分享页的 Referer（实测值，缺失会被当外站）。 */
    static final String REFERER = "https://pan.baidu.com/";

    /** 签名里的设备串（实测值，与 Flutter 端同一个）。 */
    static final String DEVICE = "73CED981D0F186D12BC18CAE1684FFD5|VSRCQTF6W";

    /** 签名里的 app key（实测值）。 */
    private static final String RAND_KEY = "ebrcUYiuxaZv2XGu7KIYKxUrqfnOfpDF";

    /** 签名尾巴：版本号 + 固定 hex（实测值）。 */
    private static final String RAND_TAIL = "11.30.2ae5821440fab5e1a61a025f014bd8972";

    private static final String VERSION = "11.30.2";
    private static final String CHANNEL = "android_12_zhao_bd-netdisk_1024266h";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    /** 需要登录：本地没有 BDUSS。本次不做扫码，只把话说清楚。 */
    private static final String LOGIN_REQUIRED = "需要登录百度网盘才能播放这条分享（顶部「网盘」→ 百度，粘贴含 BDUSS 的 Cookie）";

    /** 有 BDUSS 但取不到 uid（账号侧不认这份 Cookie）。 */
    private static final String LOGIN_EXPIRED = "百度网盘登录已失效，请在网盘中心重新粘贴 Cookie";

    /** 分享已取消 / 已过期 / 被删（与 Flutter 端 {@code _checkBaidu} 同一组 errno）。 */
    private static final String SHARE_GONE = "百度分享已失效（链接被取消或已过期），请换一条线路";

    /** 接口没给 dlink，但也不是明确的失效码。 */
    private static final String NO_DIRECT_URL = "百度网盘没有返回播放地址，请换一条线路";

    /** 上一次解析拒绝播放的原因，成功或没轮到它时为空（与 UC 那边同款，UI 线程读）。 */
    private static volatile String lastRefusalReason = "";

    private BaiduShareResolver() {}

    // ------------------------------------------------------------------ 对外入口

    /**
     * 把一条百度分享 id 换成可播直链；不是百度形状的 id、没登录、接口拒绝、直链不可用一律返回 null。
     * 调用方拿到 null 之后读 {@link #lastFailureReason()} 拿原因。
     *
     * @param siteKey 站点 api/key，只用于日志（判据是 id 形状，不看站名）
     * @param lineName 玩家选的线路名，例如 {@code 百度分享}；用作剧集名
     * @param rawEpisodeId 插件拿到的 episode url，五段 {@code ++} 分隔、第一段 {@code bd}
     */
    static Cms.Episode resolve(String siteKey, String lineName, String rawEpisodeId) {
        lastRefusalReason = "";
        Ident ident = parseIdent(rawEpisodeId);
        if (ident == null) return null;
        BaiduLogin.Credential credential = BaiduLogin.loadSaved();
        if (credential == null || credential.bduss.isEmpty()) {
            lastRefusalReason = LOGIN_REQUIRED;
            AppLog.w(TAG, "refused: no saved BDUSS in files/netdisk/baidu.json");
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
                        + " uk="
                        + ident.uk
                        + " fid="
                        + ident.fsId);
        try {
            String uid = uid(credential);
            if (uid.isEmpty()) {
                lastRefusalReason = LOGIN_EXPIRED;
                AppLog.w(TAG, "refused: account answered without uid");
                return null;
            }
            String time = String.valueOf(System.currentTimeMillis());
            String rand =
                    sha1Hex(
                            sha1Hex(credential.bduss)
                                    + uid
                                    + RAND_KEY
                                    + time
                                    + DEVICE
                                    + RAND_TAIL);
            String cookie = accountCookie(credential, ident.sekey);
            Response answer =
                    fetch(
                            "GET",
                            shareListUrl(
                                    ident.shareId, ident.uk, ident.fsId, ident.sekey, time, rand),
                            null,
                            null,
                            UA_MEDIA,
                            REFERER,
                            cookie);
            JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
            if (body == null) {
                lastRefusalReason =
                        "百度网盘没有返回可用数据（HTTP " + answer.http + "），请换一条线路";
                AppLog.w(TAG, "refused: answer is not JSON, http=" + answer.http);
                return null;
            }
            String refusal = refusalFor(body);
            if (!refusal.isEmpty()) {
                lastRefusalReason = refusal;
                AppLog.w(TAG, "refused: " + refusal);
                return null;
            }
            String dlink = mediaUrl(dlinkOf(body, ident.fsId));
            if (dlink.isEmpty()) {
                lastRefusalReason = NO_DIRECT_URL;
                AppLog.w(TAG, "refused: answer carried no usable dlink");
                return null;
            }
            // 直链是带签名的凭据，日志里只留主机名。
            AppLog.w(TAG, "dlink host=" + hostOf(dlink));
            return episode(lineName, dlink, cookie);
        } catch (IOException error) {
            lastRefusalReason = "百度网盘访问失败（" + NetdiskShareHttp.describe(error) + "）";
            AppLog.w(TAG, "resolve failed: " + lastRefusalReason);
        } catch (RuntimeException error) {
            lastRefusalReason = "百度网盘访问失败（解析异常）";
            AppLog.w(TAG, "resolve failed unexpectedly", error);
        }
        return null;
    }

    /**
     * 这个 episode id 是不是百度分享坐标（五段、第一段 {@code bd}、中间三段数字）。只读判据、不动
     * 网络，供调用方在失败分支里选「读哪家的原因」用。
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

    // ------------------------------------------------------------------ id 形状

    /** 五段 {@code ++} 分享坐标。{@code sekey} 是 id 里的编码形态（百分号编码过）。 */
    private static final class Ident {
        final String shareId;
        final String uk;
        final String fsId;
        final String sekey;

        Ident(String shareId, String uk, String fsId, String sekey) {
            this.shareId = shareId;
            this.uk = uk;
            this.fsId = fsId;
            this.sekey = sekey;
        }
    }

    /**
     * @return null 当且仅当不是五段、第一段不是 {@code bd}、或某一段形状与量级对不上
     */
    private static Ident parseIdent(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return null;
        // limit=5：末段（sekey）里就算出现 "++" 也整段收在第 5 段，不会被切成六段。
        String[] segments = value.split("\\+\\+", 5);
        if (segments.length != 5) return null;
        if (!"bd".equals(segments[0])) return null;
        boolean shaped =
                isDigits(segments[1], 1, 32)
                        && isDigits(segments[2], 1, 32)
                        && isDigits(segments[3], 1, 32)
                        && isSafeIdPart(segments[4]);
        if (!shaped) {
            // 形状不对：记长度就够定位，不记明文（后三段是分享凭据）。
            AppLog.w(
                    TAG,
                    "skip: five segments but shapes do not look like a baidu share id, len="
                            + segments[1].length()
                            + "/"
                            + segments[2].length()
                            + "/"
                            + segments[3].length()
                            + "/"
                            + segments[4].length());
            return null;
        }
        // 第 5 段在 id 里是编码形态；这里先还原成 raw randsk（可能与 Flutter 端的 session 值一致）。
        return new Ident(
                segments[1], segments[2], segments[3], percentDecode(segments[4]));
    }

    /**
     * 百分号编码一个 id 段 / query 值。空格转 {@code %20}、{@code *} 也转义，所以结果里不会有
     * {@code +} 与裸 {@code %}，可以安全放进 {@code ++} 分隔的 id。
     */
    static String encodePart(String value) {
        return NetdiskShareHttp.encodeQuery(value);
    }

    /**
     * 百分号解码，但**不把 {@code +} 当空格**（Dart 的 {@code Uri.decodeComponent} 就是这样；Java
     * 的 {@link java.net.URLDecoder} 会把 {@code +} 解成空格，直接用会把 sekey 解坏）。
     */
    static String percentDecode(String value) {
        String text = text(value);
        if (text.indexOf('%') < 0) return text;
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '%' && i + 2 < raw.length) {
                int hi = Character.digit((char) raw[i + 1], 16);
                int lo = Character.digit((char) raw[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 数字串（长度在量级之内）。 */
    private static boolean isDigits(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** id 末段只允许本类编码器会产生的那几个字符；空串也放行（无提取码的分享）。 */
    private static boolean isSafeIdPart(String value) {
        if (value == null) return false;
        if (value.length() > 1024) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum && c != '%' && c != '.' && c != '-' && c != '_' && c != '~') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 百度接口

    /**
     * 取直链的请求地址（包内可见的纯函数，桌面上可以直接核对 query 字节）。
     *
     * <p>{@code rawSekey} 是 {@link Ident#sekey} 解出来的 raw randsk；{@code sekey} 参数的口径照
     * Flutter 端：先 {@link #percentDecode} 再编码，于是线上字节与 Flutter 发出的一致（见类注释
     * 最后一段）。
     */
    static String shareListUrl(
            String shareId, String uk, String fsId, String rawSekey, String time, String rand) {
        String sekey = encodePart(percentDecode(rawSekey));
        StringBuilder url = new StringBuilder("https://pan.baidu.com/share/list");
        url.append("?shareid=").append(encodePart(shareId));
        url.append("&uk=").append(encodePart(uk));
        url.append("&fid=").append(encodePart(fsId));
        url.append("&sekey=").append(sekey);
        url.append("&origin=dlna");
        url.append("&devuid=").append(encodePart(DEVICE));
        url.append("&clienttype=1");
        url.append("&channel=").append(encodePart(CHANNEL));
        url.append("&version=").append(encodePart(VERSION));
        url.append("&time=").append(encodePart(text(time)));
        url.append("&rand=").append(encodePart(text(rand)));
        return url.toString();
    }

    /** 账号 Cookie 头：规范化的账号 Cookie（BDUSS/STOKEN/PTOKEN/BAIDUID）+ 分享的 BDCLND。 */
    static String accountCookie(BaiduLogin.Credential credential, String sekey) {
        String base = NetdiskShareHttp.text(credential == null ? null : credential.cookie).trim();
        String raw = text(sekey).trim();
        if (raw.isEmpty()) return base;
        return base.isEmpty() ? "BDCLND=" + raw : base + "; BDCLND=" + raw;
    }

    /** {@code GET /userx/v1/info/get} → {@code data.fields.uid}；拿不到返回 ""。 */
    private static String uid(BaiduLogin.Credential credential) throws IOException {
        String fields = "[\"bg_image\",\"member\",\"uid\",\"avatar\",\"avatar_member\"]";
        String url =
                "https://mbd.baidu.com/userx/v1/info/get?appname=baiduboxapp"
                        + "&fields="
                        + encodePart(fields)
                        + "&client=&clientfrom=&lang=zh-cn&tpl=&ttt=";
        Response answer =
                fetch(
                        "GET",
                        url,
                        null,
                        null,
                        UA_MEDIA_NO_TAIL,
                        "",
                        NetdiskShareHttp.text(credential.cookie).trim());
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            AppLog.w(TAG, "uid answer is not JSON, http=" + answer.http);
            return "";
        }
        // 这个接口正常会说 errno=0；没有 errno 的部署也认，只要真拿到 uid。
        if (body.has("errno") && NetdiskShareHttp.optInt(body, "errno", -1) != 0) {
            AppLog.w(TAG, "uid errno=" + NetdiskShareHttp.optInt(body, "errno", -1));
            return "";
        }
        return uidOf(body);
    }

    private static String uidOf(JsonObject body) {
        JsonElement data = body == null ? null : body.get("data");
        if (data == null || !data.isJsonObject()) return "";
        JsonElement fields = data.getAsJsonObject().get("fields");
        if (fields == null || !fields.isJsonObject()) return "";
        return NetdiskShareHttp.optString(fields.getAsJsonObject(), "uid").trim();
    }

    /**
     * errno 翻译。能播返回 ""；错误码口径与 Flutter 端 {@code _checkBaidu} 一致：
     * -7 / -9 / -21 / -144 / -145 是分享失效，别的只报 errno（不猜含义）。
     */
    private static String refusalFor(JsonObject body) {
        int errno = NetdiskShareHttp.optInt(body, "errno", -1);
        if (errno == 0) return "";
        if (errno == -7 || errno == -9 || errno == -21 || errno == -144 || errno == -145) {
            return SHARE_GONE;
        }
        return "百度网盘返回错误（errno=" + errno + "），可能登录已失效，请在网盘中心重新粘贴 Cookie";
    }

    /** {@code list[]} 里 {@code fs_id} 对得上的那条的 {@code dlink}；对不上返回 ""。 */
    private static String dlinkOf(JsonObject body, String fsId) {
        JsonElement list = body == null ? null : body.get("list");
        if (list == null || !list.isJsonArray()) return "";
        JsonArray rows = list.getAsJsonArray();
        String lone = "";
        int withId = 0;
        for (JsonElement item : rows) {
            if (item == null || !item.isJsonObject()) continue;
            JsonObject row = item.getAsJsonObject();
            String rowId = NetdiskShareHttp.optString(row, "fs_id").trim();
            if (!rowId.isEmpty()) withId++;
            String dlink = NetdiskShareHttp.optString(row, "dlink").trim();
            if (rowId.equals(fsId) && !dlink.isEmpty()) return dlink;
            if (rows.size() == 1 && rowId.isEmpty()) lone = dlink;
        }
        // 只认两种：fs_id 对得上，或者唯一一行没带 fs_id（有的部署就这样）。别的一律不猜。
        if (withId == 0 && rows.size() == 1 && !lone.isEmpty()) return lone;
        return "";
    }

    /** 直链形状判据（照 Flutter 的 {@code _mediaUrl}）：只认 http(s)、有主机、不带 userinfo、不是图片。 */
    private static String mediaUrl(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return "";
        try {
            URL url = new URL(value);
            String protocol = text(url.getProtocol()).toLowerCase(Locale.ROOT);
            if (!"http".equals(protocol) && !"https".equals(protocol)) return "";
            if (text(url.getHost()).isEmpty()) return "";
            if (!text(url.getUserInfo()).isEmpty()) return "";
        } catch (IOException | RuntimeException error) {
            return "";
        }
        String path = value.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        if (path.endsWith(".jpg")
                || path.endsWith(".jpeg")
                || path.endsWith(".png")
                || path.endsWith(".webp")
                || path.endsWith(".gif")) {
            return "";
        }
        return value;
    }

    /**
     * 造可播的剧集。口径与 {@link UcShareResolver} 一致：非 HLS 用 {@code _forceMpv} 交给 MPV，
     * HLS 标成 netdisk 的 HLS 让它走直连（带 Cookie）；两者都 {@code _noCache}，因为签名直链很短命，
     * 媒体缓存会把它写坏。
     */
    private static Cms.Episode episode(String lineName, String url, String cookie) {
        Cms.Episode episode = new Cms.Episode();
        String name = text(lineName).trim();
        if (name.isEmpty()) name = "百度分享";
        episode.name = name;
        episode.url = url;
        episode.headers.put("Cookie", cookie);
        episode.headers.put("User-Agent", UA_MEDIA);
        episode.headers.put("Referer", REFERER);
        boolean hls = looksLikeHls(url);
        try {
            episode.media.put("_noCache", true);
            if (hls) {
                episode.media.put("mimeType", "application/x-mpegURL");
                episode.media.put("_netdiskHls", true);
            } else {
                episode.media.put("_forceMpv", true);
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

    // ------------------------------------------------------------------ 签名

    /** 小写十六进制 SHA-1（与 Dart 的 {@code sha1.convert(...).toString()} 同形）。 */
    static String sha1Hex(String value) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 unavailable", error);
        }
        byte[] digest = md.digest(text(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    // ---------------------------------------------------------------- HTTP plumbing

    /** 一次请求的结果：状态码、正文、响应里下发的 Set-Cookie、Location。 */
    static final class Response {
        final int http;
        final String body;
        final List<String> setCookies;
        final String location;

        Response(int http, String body, List<String> setCookies, String location) {
            this.http = http;
            this.body = body;
            this.setCookies = setCookies;
            this.location = location;
        }

        boolean isRedirect() {
            return http >= 300 && http < 400 && http != 304 && !location.isEmpty();
        }
    }

    /**
     * 分享页这条链路要的 Cookie 罐：只收访问者那三个名字（{@code BAIDUID} / {@code BAIDUID_BFESS} /
     * {@code BDCLND}）。账号 Cookie（BDUSS/STOKEN/PTOKEN）**从不**放进这里 —— 匿名列目录不需要，
     * 也不该给分享页。
     */
    static final class Jar {
        private static final List<String> KEPT =
                Arrays.asList("BAIDUID", "BAIDUID_BFESS", "BDCLND");

        private final Map<String, String> values = new LinkedHashMap<>();

        String get(String name) {
            String value = values.get(text(name).trim());
            return value == null ? "" : value;
        }

        void put(String name, String value) {
            String key = text(name).trim();
            String val = text(value).trim();
            if (key.isEmpty() || val.isEmpty()) return;
            values.put(key, val);
        }

        /** 吸收响应里的 Set-Cookie；只留 {@link #KEPT} 里那三个名字。 */
        void absorb(List<String> setCookies) {
            if (setCookies == null) return;
            for (String line : setCookies) {
                String value = text(line);
                int eq = value.indexOf('=');
                if (eq <= 0) continue;
                String name = value.substring(0, eq).trim();
                if (!KEPT.contains(name)) continue;
                int semi = value.indexOf(';', eq);
                String val = (semi < 0 ? value.substring(eq + 1) : value.substring(eq + 1, semi)).trim();
                put(name, val);
            }
        }

        String header() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return sb.toString();
        }
    }

    private static final int MAX_REDIRECTS = 5;

    /**
     * 带 Cookie 罐的请求：跟随跳转（分享页会 302 到提取码页），每一跳都把 Set-Cookie 吸进罐里，
     * 下一跳带上。{@code hops} 为 0 时只看第一跳。
     */
    static Response request(
            String method,
            String url,
            String body,
            String contentType,
            String userAgent,
            String referer,
            Jar jar,
            int hops) throws IOException {
        String current = url;
        String verb = text(method).trim().isEmpty() ? "GET" : method;
        for (int hop = 0; hop <= Math.max(0, hops); hop++) {
            Response answer =
                    fetch(
                            verb,
                            current,
                            body,
                            contentType,
                            userAgent,
                            referer,
                            jar == null ? "" : jar.header());
            if (jar != null) jar.absorb(answer.setCookies);
            if (!answer.isRedirect()) return answer;
            URL target;
            try {
                target = new URL(new URL(current), answer.location);
            } catch (IOException | RuntimeException error) {
                throw new IOException("百度分享返回了无法识别的跳转", error);
            }
            String host = hostOf(target.toString());
            if (!"pan.baidu.com".equals(host)) {
                throw new IOException("百度分享返回了异常跳转（" + host + "）");
            }
            current = target.toString();
            // 跳转之后一律 GET：这条链路上要跟的只有分享页。
            verb = "GET";
            body = null;
        }
        throw new IOException("百度分享跳转次数过多");
    }

    /**
     * 一个请求（不跟随跳转）。非 2xx 也把正文读回来，由调用方统一翻译；不加
     * {@code Accept-Encoding}（正文按 UTF-8 解，不解 gzip）。
     */
    static Response fetch(
            String method,
            String url,
            String body,
            String contentType,
            String userAgent,
            String referer,
            String cookie) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(text(method).trim().isEmpty() ? "GET" : method);
            conn.setRequestProperty("User-Agent", text(userAgent));
            conn.setRequestProperty("Accept", "application/json");
            if (!text(referer).isEmpty()) conn.setRequestProperty("Referer", referer);
            if (!text(cookie).isEmpty()) conn.setRequestProperty("Cookie", cookie.trim());
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty(
                        "Content-Type",
                        text(contentType).isEmpty()
                                ? "application/x-www-form-urlencoded"
                                : contentType);
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
            String reply = readAll(http >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new Response(
                    http, reply, setCookies(conn), text(conn.getHeaderField("Location")).trim());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 所有 {@code Set-Cookie} 头，原样返回（由 Cookie 罐自己挑名字）。 */
    private static List<String> setCookies(HttpURLConnection conn) {
        List<String> out = new ArrayList<>();
        Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) return out;
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String key = field.getKey();
            if (key == null || !key.equalsIgnoreCase("Set-Cookie")) continue;
            List<String> values = field.getValue();
            if (values == null) continue;
            out.addAll(values);
        }
        return out;
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
