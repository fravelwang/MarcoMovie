package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UC 网盘分享的原生解析：把爬虫给的「四段 id」换成可播直链。
 *
 * <p>背景：虎斑这类 csp_MacCMS 站的详情页把网盘分享本身当线路给出来，UC 线路的 episode id 是四段
 * {@code ++} 分隔的分享坐标。插件换不出直链，只会回一句「PC端未登录或Cookie失效」；而
 * {@link QuarkShareResolver} 只认五段。于是这类线路以前一律播不了。这里用**免登录**的 UC 快速下载
 * 接口把同一份分享换成直链（2026-11-26 在宿主机实测通：接口 HTTP 200 + {@code code 0}，带回
 * {@code data[0].download_url} 与 {@code Set-Cookie: __pugs=…}；对直链带上这个 Cookie 发 Range GET
 * 得 206 + {@code video/mp4}，首字节 {@code 1A 45 DF A3}）。
 *
 * <p>id 形状（四段；样例见任务卡 2026-11-26）：
 * <pre>
 *   [0] fid               分享内文件 id，32 hex
 *   [1] share_fid_token   分享内文件令牌，32 hex
 *   [2] pwd_id            分享 id，12~13 hex（实测样例 13 位）
 *   [3] stoken            分享令牌，44 字符 base64
 * </pre>
 * 判据只卡量级（见 {@code parseIdent}），不卡精确长度：站点之间的小差异不该把整条线路判成
 * 「不是 UC」，再被插件那句笼统话盖过去。也不要求站名或线路名带「UC」：四段形状本身足够区分
 * （夸克是五段），多一层名字判据只会在「UC原画13」这种线路名上误判。
 *
 * <p>账号兜底（2026-11-26 加）：游客接口被限流时会回 401/403，这时如果本机有 UC 凭据
 * （{@code files/netdisk/uc.json}，见 {@link UcLogin}），就按 Flutter 端 {@code _resolveUcAccount}
 * 那套再换一次直链：{@code config} 刷新短效 Cookie → 带账号 Cookie 请求 {@code file/download}。
 * 游客流程本身一字未改，账号路只是它的接续分支。
 *
 * <p>线程：{@link #resolve(String, String, String)} 会打网络（单请求 8 秒超时，游客路最多两次请求，
 * 走账号兜底再多两次），必须在后台线程调用。它同步执行、永不抛，失败返回 null，原因放在
 * {@link #lastFailureReason()}，与 {@link QuarkShareResolver} 同一套口径。
 */
final class UcShareResolver {

    private static final String TAG = "UcShare";

    /**
     * 快速下载接口认的客户端串（实测：换成普通浏览器 UA 会被拒）；对直链也必须用同一个 UA。
     *
     * <p>包内共享：分享侧 {@link UcShareProvider} 的 token / detail 请求也用这一串（实测值，抄一份
     * 就会漂）。
     */
    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100"
                    + " Safari/537.36 Channel/pckk_other_ch";

    /** 接口与直链都要的 Referer（实测值，缺失会被当外站）；分享侧同样共享这一串。 */
    static final String REFERER = "https://fast.uc.cn/";

    private static final String ACCEPT = "application/json";

    /** 免登录换直链的唯一入口；query 是实测那串，空的 {@code __dt}/{@code __t} 也照发。 */
    private static final String DOWNLOAD_URL =
            "https://pc-api.uc.cn/1/clouddrive/file/download"
                    + "?entry=ft&fr=pc&pr=UCBrowser&uc_param_str=&__dt=&__t=";

    /**
     * 账号路的第一步：让服务端刷新短效 Cookie（{@code __pus}/{@code __puus} 会从 Set-Cookie 回带）。
     * 与 Flutter 端 {@code _resolveUcAccount} 同一个地址、同一串 query。
     *
     * <p>动词：任务卡写的是 POST，但参考实现实际发的是 GET——Flutter 的 {@code _json} 只在带
     * body/form 时才用 POST，config 那次没有 body，所以是 GET。这里按参考实现先发 GET；若服务端
     * 明确回「方法/路由不对」（404/405/501），再补一次 POST，免得两种口径哪一种落空都白跑。
     */
    private static final String CONFIG_URL =
            "https://pc-api.uc.cn/1/clouddrive/config?pr=UCBrowser&fr=pc";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    /**
     * 响应里下发的免登录 Cookie。直链不带它就是 403（实测），所以拿到之后要转给播放器。
     * 匹配形态：{@code __pugs=xxx; Path=/; …}，也容忍逗号分隔的多条 Set-Cookie。
     */
    private static final Pattern PUGS_COOKIE = Pattern.compile("(?:^|[;,\\s])__pugs=([^;,\\s]+)");

    /** 账号 Cookie：{@code __pus} 是身份、{@code __puus} 是短效会话，config 会把它们轮换回来。 */
    private static final Pattern PUS_COOKIE = Pattern.compile("(?:^|[;,\\s])__pus=([^;,\\s]+)");

    private static final Pattern PUUS_COOKIE = Pattern.compile("(?:^|[;,\\s])__puus=([^;,\\s]+)");

    /** 分享已取消 / 已过期（与 Flutter 端 {@code _checkUc} 同一组码）。 */
    private static final String SHARE_GONE = "UC 分享失效（链接被取消或已过期），请换一条线路";

    /** 提取码缺失或错误。 */
    private static final String NEED_PASSWORD = "UC 分享需要提取码，这条线路播不了，请换一条线路";

    /** 接口要账号，而本机没有 UC 凭据（游客路走不通时的原话）。 */
    private static final String LOGIN_REQUIRED =
            "这条 UC 分享需要登录账号才能播放（顶部「网盘」→ UC，粘贴含 __pus 的 Cookie）";

    /** 有凭据但服务端不认（过期 / 被撤销 / 账号切换）：让 Boss 重新粘贴。 */
    private static final String LOGIN_EXPIRED = "UC 登录已失效，请在网盘中心重新粘贴 Cookie";

    /** 接口没有直链，但也不是明确的失效码。 */
    private static final String NO_DIRECT_URL = "UC 分享没有返回直链，请换一条线路";

    /**
     * 上一次 {@link #resolve(String, String, String)} 拒绝播放的原因，成功或没轮到它时为空。
     * 普通 volatile 字段而不是 {@code ThreadLocal}：调用方在 UI 线程里读，与夸克那边同款
     * （见 {@link QuarkShareResolver#lastFailureReason()}）。
     */
    private static volatile String lastRefusalReason = "";

    private UcShareResolver() {}

    /**
     * 把一条 UC 网盘分享 id 换成可播直链；不是 UC 形状的 id、接口拒绝、直链不可用一律返回 null。
     * 调用方拿到 null 之后读 {@link #lastFailureReason()} 拿原因。
     *
     * @param siteKey 站点 api/key，只用于日志（判据是 id 形状，不看站名）
     * @param lineName 玩家选的线路名，例如 {@code UC原画13}；用作剧集名
     * @param rawEpisodeId 插件拿到的 episode url，四段 {@code ++} 分隔
     */
    static Cms.Episode resolve(String siteKey, String lineName, String rawEpisodeId) {
        lastRefusalReason = "";
        Ident ident = parseIdent(rawEpisodeId);
        if (ident == null) return null;
        AppLog.w(
                TAG,
                "start site="
                        + text(siteKey).trim()
                        + " line="
                        + text(lineName).trim()
                        + " pwd_id="
                        + ident.pwdId
                        + " fid="
                        + ident.fid);
        try {
            Response answer = call(DOWNLOAD_URL, downloadBody(ident), "");
            String guestRefusal = refusalFor(answer);
            if (guestRefusal.isEmpty()) {
                String url = downloadUrl(parseObjectOrNull(answer.body));
                if (url.isEmpty()) {
                    guestRefusal = NO_DIRECT_URL;
                    AppLog.w(TAG, "refused: answer carried no download_url");
                } else {
                    // 直链是带签名的凭据，日志里只留主机名。
                    AppLog.w(
                            TAG,
                            "download_url host="
                                    + hostOf(url)
                                    + " cookie="
                                    + (answer.guestCookie.isEmpty() ? "no" : "yes"));
                    if (answer.guestCookie.isEmpty()) {
                        AppLog.w(
                                TAG,
                                "no __pugs cookie in the answer, the direct link will probably 403");
                    }
                    String guestCookie =
                            answer.guestCookie.isEmpty() ? "" : "__pugs=" + answer.guestCookie;
                    // 直链 + Cookie 的组合先自己验一次：这里失败的话，播放器只会黑屏后弹一句笼统话。
                    String probe = probeRefusal(url, guestCookie);
                    if (probe.isEmpty()) return episode(lineName, url, guestCookie);
                    guestRefusal = probe;
                    AppLog.w(TAG, "refused by the direct link: " + probe);
                }
            } else {
                AppLog.w(TAG, "guest refused: " + guestRefusal);
            }
            // 游客路走不通、且这原因正是「要账号」：本机有凭据就用账号 Cookie 再换一次。
            // 其余原因（分享失效 / 要提取码 / 网络问题）不重试：换账号也救不回来。
            if (needsAccount(guestRefusal)) return resolveWithAccount(ident, lineName);
            lastRefusalReason = guestRefusal;
            return null;
        } catch (IOException error) {
            lastRefusalReason = "UC 分享访问失败（" + describe(error) + "）";
            AppLog.w(TAG, "resolve failed: " + lastRefusalReason);
        } catch (RuntimeException error) {
            lastRefusalReason = "UC 分享访问失败（解析异常）";
            AppLog.w(TAG, "resolve failed unexpectedly", error);
        }
        return null;
    }

    /** 这条失败原因是不是「要账号」（游客接口 401/403，或直链带游客 Cookie 被 401/403）。 */
    private static boolean needsAccount(String refusal) {
        return LOGIN_REQUIRED.equals(refusal);
    }

    /**
     * 账号兜底：照 Flutter 端 {@code _resolveUcAccount} 移植。先 {@code config} 刷新短效 Cookie，
     * 再带账号 Cookie 请求同一条 {@code file/download}，拿到直链后用账号 Cookie（不是 {@code __pugs}）
     * 作剧集头。
     *
     * <p>Cookie 口径按参考实现：第一次请求只带一个账号键——{@code __pus} 存在就丢掉 {@code __puus}
     * （两者同带会被服务端判冲突）；config 的 Set-Cookie 里轮换出来的新值随后面的 download 用。
     *
     * <p>失败原因分两类：401/403 是「登录已失效」（让 Boss 重新粘贴），分享类码仍是「分享失效」。
     *
     * @return 成功返回剧集；失败返回 null 并写好 {@link #lastRefusalReason}
     */
    private static Cms.Episode resolveWithAccount(Ident ident, String lineName) {
        UcLogin.Credential credential = UcLogin.loadSaved();
        Map<String, String> cookies = UcLogin.apiCookies(credential);
        if (cookies.isEmpty()) {
            lastRefusalReason = LOGIN_REQUIRED;
            AppLog.w(TAG, "refused: guest path needs an account, no uc.json credential");
            return null;
        }
        if (cookies.containsKey("__pus")) cookies.remove("__puus");
        AppLog.w(TAG, "account fallback: config, keys=" + cookies.keySet());
        try {
            Response config = call(CONFIG_URL, null, cookieHeader(cookies));
            if (config.http == 404 || config.http == 405 || config.http == 501) {
                // 参考实现用 GET；万一服务端只收 POST（任务卡的口径），补一次（空 JSON 体）。
                AppLog.w(TAG, "config GET answered " + config.http + ", retrying as POST");
                config = call(CONFIG_URL, "{}", cookieHeader(cookies));
            }
            // 服务端可能在这里轮换 __pus/__puus，新值给后面的 download 用。
            cookies.putAll(config.sessionCookies);
            String refusal = configRefusal(config);
            if (!refusal.isEmpty()) {
                lastRefusalReason = refusal;
                AppLog.w(TAG, "account config refused: " + refusal);
                return null;
            }
            AppLog.w(TAG, "account config ok, keys=" + cookies.keySet());
            Response answer = call(DOWNLOAD_URL, downloadBody(ident), cookieHeader(cookies));
            cookies.putAll(answer.sessionCookies);
            refusal = accountRefusalFor(answer);
            if (!refusal.isEmpty()) {
                lastRefusalReason = refusal;
                AppLog.w(TAG, "account download refused: " + refusal);
                return null;
            }
            String url = downloadUrl(parseObjectOrNull(answer.body));
            if (url.isEmpty()) {
                lastRefusalReason = NO_DIRECT_URL;
                AppLog.w(TAG, "refused: account answer carried no download_url");
                return null;
            }
            String cookie = cookieHeader(cookies);
            if (cookie.isEmpty()) {
                lastRefusalReason = LOGIN_EXPIRED;
                AppLog.w(TAG, "refused: account answer left no usable cookie");
                return null;
            }
            AppLog.w(TAG, "account download_url host=" + hostOf(url));
            String probe = probeRefusal(url, cookie);
            if (!probe.isEmpty()) {
                lastRefusalReason = accountRefusalFor(probe);
                AppLog.w(TAG, "refused by the account direct link: " + lastRefusalReason);
                return null;
            }
            return episode(lineName, url, cookie);
        } catch (IOException error) {
            lastRefusalReason = "UC 账号访问失败（" + describe(error) + "）";
            AppLog.w(TAG, "account resolve failed: " + lastRefusalReason);
        } catch (RuntimeException error) {
            lastRefusalReason = "UC 账号访问失败（解析异常）";
            AppLog.w(TAG, "account resolve failed unexpectedly", error);
        }
        return null;
    }

    /** 账号路把 401/403 翻成「登录已失效」；其余原因沿用游客路那套话。 */
    private static String accountRefusalFor(Response answer) {
        return accountRefusalFor(refusalFor(answer));
    }

    /** 同上的字符串版（直链探测只回一句话）。 */
    private static String accountRefusalFor(String refusal) {
        return LOGIN_REQUIRED.equals(refusal) ? LOGIN_EXPIRED : refusal;
    }

    /**
     * config 的判据比 download 松：它只负责刷新短效 Cookie，HTTP 2xx 就算过；正文里带
     * {@code code}/{@code status} 时才要求 code 0 / status 200（缺字段不算失败——参考实现也是
     * 「不带这两个键就不判」）。
     */
    private static String configRefusal(Response answer) {
        if (answer.http == 401 || answer.http == 403) return LOGIN_EXPIRED;
        if (answer.http < 200 || answer.http >= 300) {
            return "UC 账号访问失败（HTTP " + answer.http + "）";
        }
        JsonObject body = parseObjectOrNull(answer.body);
        if (body == null) return "";
        int status = optInt(body, "status", 200);
        int code = optInt(body, "code", 0);
        if (status == 200 && code == 0) return "";
        if (code == 41006 || code == 41010 || code == 41011 || code == 41012 || status == 404) {
            return SHARE_GONE;
        }
        if (code == 41008 || code == 41009) return NEED_PASSWORD;
        String message = optString(body, "message");
        return LOGIN_EXPIRED
                + (message.isEmpty() ? "" : "（" + message + "）")
                + "（code="
                + code
                + "，status="
                + status
                + "）";
    }

    /** {@code 名=值; 名=值}；空 map 返回 ""（那就干脆不发 Cookie 头）。 */
    private static String cookieHeader(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            String name = text(entry.getKey()).trim();
            String value = text(entry.getValue()).trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(name).append('=').append(value);
        }
        return sb.toString();
    }

    /**
     * 这个 episode id 是不是 UC 分享坐标（四段，各段形状都对得上）。只读判据、不动网络，供调用方
     * 在失败分支里选「读哪家的原因」用。
     */
    static boolean looksLikeShareId(String rawEpisodeId) {
        return parseIdent(rawEpisodeId) != null;
    }

    /**
     * 上一次解析拒绝播放的原因，成功时为 ""。只在 {@link #resolve(String, String, String)} 返回
     * null 之后立刻读才有意义：每次解析开头都会清空。
     */
    static String lastFailureReason() {
        String reason = lastRefusalReason;
        return reason == null ? "" : reason;
    }

    /** 四段 {@code ++} 分享坐标。 */
    private static final class Ident {
        final String fid;
        final String fileToken;
        final String pwdId;
        final String stoken;

        Ident(String fid, String fileToken, String pwdId, String stoken) {
            this.fid = fid;
            this.fileToken = fileToken;
            this.pwdId = pwdId;
            this.stoken = stoken;
        }
    }

    /**
     * @return null 当且仅当不是四段、或某一段形状与实测量级对不上（那就不是 UC 线路）
     */
    private static Ident parseIdent(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return null;
        String[] segments = value.split("\\+\\+", -1);
        if (segments.length != 4) return null;
        // 段长只卡量级（实测 32/32/13/44），不卡精确长度：站点之间的小差异不该把整条线路判成
        // 「不是 UC」而被插件那句笼统话盖过去。第 1 段两种字母表都放行，实测是 hex。
        boolean shaped =
                isHex(segments[0], 8, 128)
                        && (isHex(segments[1], 8, 128) || isToken(segments[1]))
                        && isHex(segments[2], 6, 64)
                        && isToken(segments[3]);
        if (!shaped) {
            // 四段但形状不对：记长度就够定位，不记明文（那几段是分享凭据）。
            AppLog.w(
                    TAG,
                    "skip: four segments but shapes do not look like a UC share id, len="
                            + segments[0].length()
                            + "/"
                            + segments[1].length()
                            + "/"
                            + segments[2].length()
                            + "/"
                            + segments[3].length());
            return null;
        }
        return new Ident(segments[0], segments[1], segments[2], segments[3]);
    }

    /** 换直链的请求体：{@code fids} / {@code pwd_id} / {@code stoken} / {@code fids_token}。 */
    private static String downloadBody(Ident ident) {
        JsonObject payload = new JsonObject();
        JsonArray fids = new JsonArray();
        fids.add(ident.fid);
        payload.add("fids", fids);
        payload.addProperty("pwd_id", ident.pwdId);
        payload.addProperty("stoken", ident.stoken);
        JsonArray tokens = new JsonArray();
        tokens.add(ident.fileToken);
        payload.add("fids_token", tokens);
        return payload.toString();
    }

    /**
     * 一次请求的结果：状态码 + 正文 + 响应里下发的 {@code __pugs} 值（没有则为空）+ 响应里轮换出来的
     * 账号 Cookie（{@code __pus}/{@code __puus}，没有则为空 map）。
     */
    private static final class Response {
        final int http;
        final String body;
        final String guestCookie;
        final Map<String, String> sessionCookies;

        Response(
                int http,
                String body,
                String guestCookie,
                Map<String, String> sessionCookies) {
            this.http = http;
            this.body = body;
            this.guestCookie = guestCookie;
            this.sessionCookies = sessionCookies;
        }
    }

    /**
     * 把接口答案翻成一句人话，能播就返回 ""。错误码口径与 Flutter 端 {@code _checkUc} 一致：
     * 41006/41010/41011/41012 或 HTTP 404 是分享失效；41008/41009 要提取码；401/403 要账号。
     */
    private static String refusalFor(Response answer) {
        if (answer.http == 401 || answer.http == 403) return LOGIN_REQUIRED;
        if (answer.http == 404) return SHARE_GONE;
        JsonObject body = parseObjectOrNull(answer.body);
        if (body == null) {
            return "UC 分享访问失败（HTTP " + answer.http + "）";
        }
        int status = optInt(body, "status", answer.http);
        int code = optInt(body, "code", -1);
        if (status == 200 && code == 0) return "";
        if (code == 41006 || code == 41010 || code == 41011 || code == 41012 || status == 404) {
            return SHARE_GONE;
        }
        if (code == 41008 || code == 41009) return NEED_PASSWORD;
        String message = optString(body, "message");
        return "UC 分享访问失败"
                + (message.isEmpty() ? "" : "（" + message + "）")
                + "（code="
                + code
                + "，status="
                + status
                + "）";
    }

    /** {@code data[0].download_url}；有的部署给 {@code data.download_url}，两种都认。 */
    private static String downloadUrl(JsonObject body) {
        JsonElement data = body == null ? null : body.get("data");
        if (data == null || data.isJsonNull()) return "";
        if (data.isJsonArray()) {
            JsonArray rows = data.getAsJsonArray();
            if (rows.size() == 0) return "";
            JsonElement first = rows.get(0);
            return first != null && first.isJsonObject()
                    ? optString(first.getAsJsonObject(), "download_url").trim()
                    : "";
        }
        if (data.isJsonObject()) return optString(data.getAsJsonObject(), "download_url").trim();
        return "";
    }

    /**
     * 用 Range 取直链头 1 KB，验证「直链 + Cookie」真的能出数据；能出就返回 ""。
     *
     * <p>{@code cookieHeader} 是整行 Cookie（游客路是 {@code __pugs=…}，账号路是 {@code __pus=…}）。
     * 只把确定性的拒绝算成失败（401/403 视为需要账号、404/410 视为分享失效）。网络抖动、5xx、
     * 意外跳转一律放行：播放器还会自己发一次请求，没必要在解析阶段替它下结论。
     */
    private static String probeRefusal(String url, String cookieHeader) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Range", "bytes=0-1023");
            if (!text(cookieHeader).isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            int http = conn.getResponseCode();
            AppLog.w(TAG, "probe http=" + http + " type=" + text(conn.getContentType()));
            if (http == 401 || http == 403) return LOGIN_REQUIRED;
            if (http == 404 || http == 410) return SHARE_GONE;
            return "";
        } catch (IOException error) {
            AppLog.w(TAG, "probe failed, letting the player try: " + describe(error));
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 造可播的剧集。口径与 {@link QuarkShareResolver} 的 {@code episode(...)} 一致：非 HLS 用
     * {@code _forceMpv} 交给 MPV，HLS 标成 netdisk 的 HLS 让它走直连（带 Cookie）；两者都
     * {@code _noCache}，因为签名直链很短命，媒体缓存会把它写坏。
     *
     * <p>{@code cookieHeader} 是整行 Cookie：游客路传 {@code __pugs=…}，账号路传 {@code __pus=…}；
     * UA / Referer 两条路不变（任务卡第 3 节第 2 条）。
     */
    private static Cms.Episode episode(String lineName, String url, String cookieHeader) {
        Cms.Episode episode = new Cms.Episode();
        String name = text(lineName).trim();
        if (name.isEmpty()) name = "UC网盘";
        episode.name = name;
        episode.url = url;
        if (!text(cookieHeader).isEmpty()) {
            episode.headers.put("Cookie", cookieHeader);
        }
        episode.headers.put("User-Agent", UA);
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

    // ---------------------------------------------------------------- HTTP plumbing

    /**
     * 一个 JSON 请求。{@code jsonBody} 为 null 时发 GET（config 就是这条），否则发 POST。
     * 不跟随跳转、不加 {@code Accept-Encoding}（正文按 UTF-8 解，不解 gzip）。非 2xx 也把正文读回来，
     * 由 {@link #refusalFor(Response)} 统一翻译。
     *
     * @param cookieHeader 要发的 Cookie 头；空串就不发这一行
     */
    private static Response call(String url, String jsonBody, String cookieHeader)
            throws IOException {
        boolean post = jsonBody != null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(post ? "POST" : "GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", ACCEPT);
            conn.setRequestProperty("Referer", REFERER);
            if (!text(cookieHeader).isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            if (post) {
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json");
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
            String body = readAll(http >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new Response(http, body, pugsCookie(conn), sessionCookies(conn));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 Set-Cookie 里挑出 {@code __pugs} 的值；响应没给就返回 ""。 */
    private static String pugsCookie(HttpURLConnection conn) {
        return setCookieValue(conn, PUGS_COOKIE);
    }

    /** 响应里轮换出来的账号 Cookie（{@code __pus}/{@code __puus}）；没给就是空 map。 */
    private static Map<String, String> sessionCookies(HttpURLConnection conn) {
        Map<String, String> out = new LinkedHashMap<>();
        String pus = setCookieValue(conn, PUS_COOKIE);
        if (!pus.isEmpty()) out.put("__pus", pus);
        String puus = setCookieValue(conn, PUUS_COOKIE);
        if (!puus.isEmpty()) out.put("__puus", puus);
        return out;
    }

    /** 按给定形态从所有 Set-Cookie 头里取第一个命中的值；没有返回 ""。 */
    private static String setCookieValue(HttpURLConnection conn, Pattern pattern) {
        Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) return "";
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String key = field.getKey();
            if (key == null || !key.equalsIgnoreCase("Set-Cookie")) continue;
            List<String> values = field.getValue();
            if (values == null) continue;
            for (String value : values) {
                Matcher matcher = pattern.matcher(text(value));
                if (matcher.find()) return matcher.group(1);
            }
        }
        return "";
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

    private static JsonObject parseObjectOrNull(String body) {
        try {
            JsonElement element = JsonParser.parseString(text(body));
            if (element == null || !element.isJsonObject()) return null;
            return element.getAsJsonObject();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String describe(IOException error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            return element.getAsString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean isHex(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper) return false;
        }
        return true;
    }

    /** base64 / URL-safe base64 字符集；stoken 本身当不透明串处理。 */
    private static boolean isToken(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum && c != '+' && c != '/' && c != '=' && c != '_' && c != '-') return false;
        }
        return true;
    }
}
