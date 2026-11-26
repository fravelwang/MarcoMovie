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
 * 百度网盘分享的原生解析：把「六段 bd id」换成可播直链。
 *
 * <p>背景：详情页的下载区把百度分享本身当线路给出来，插件换不出直链（它没有账号凭据），只会回
 * 一句笼统的失败话。这里用**账号 Cookie + 网页端那条成熟路径**把同一份分享换成直链，**没有自己
 * 发明签名**：
 *
 * <ol>
 *   <li><b>先转存到自己网盘、再取自己文件的直链（主路）</b>（2026-11-26 Boss 定的口径：分享就该先存
 *       到网盘再播，像夸克那样，播前清理）：{@code GET /api/gettemplatevariable} 换 {@code bdstoken}
 *       → {@code POST /api/create?a=commit} 备好 {@code /MarcoMovie} → {@code POST /api/filemanager?
 *       opera=delete} 清掉上一份副本 → {@code POST /share/transfer} 把这一集转存进去拿到
 *       {@code to_fs_id} → {@code GET /api/filemetas?dlink=1&fsids=[to_fs_id]} 取直链。取直链只要
 *       账号 Cookie，**不需要 {@code sign} / {@code timestamp} / {@code access_token}**（2026-11-26
 *       深夜实测，见 {@link #filemetas}）。上一版误判成「网页接口要 sign」，是因为把参数名写成了
 *       {@code target}（百度当年那个形状），百度回 {@code errno 12 / 31021}；改成 {@code fsids} 就
 *       {@code errno 0}。这条路拿到的直链是自己盘里的文件，最稳；任何一步失败就往下走；
 *   <li><b>网页接口（第二条）</b>：{@code POST https://pan.baidu.com/api/sharedownload?sign=…&
 *       timestamp=…&channel=chunlei&web=1&app_id=250528&clienttype=0}，表单
 *       {@code encrypt=0&product=share&uk=…&primaryid=…&fid_list=[…]&extra={"sekey":"…"}}，Cookie
 *       带账号凭据与 {@code BDCLND=<sekey>}，UA 用 {@code netdisk;…} 串，Referer
 *       {@code https://pan.baidu.com/} → {@code list[0].dlink}。{@code sign} / {@code timestamp}
 *       来自分享页的 {@code window.yunData} / {@code locals.mset}：解析分享时抓到的那份随 id 第 6 段
 *       带过来（{@link #sharePageTicket}），失效或没带就再开一次分享页（带 {@code BDCLND}）现取；
 *       HTTP 拿不到签名时再用应用自己的 WebView（真浏览器）把页面跑一遍取 {@code window.yunData}
 *       —— 百度对没有 JS 的 HTTP 客户端只回壳页面（见 {@link WebPageProbe}）；
 *   <li><b>旧接口（兜底）</b>：{@code GET https://pan.baidu.com/share/list?shareid=…&uk=…&fid=…
 *       &sekey=…&origin=dlna&devuid=<DEVICE>&clienttype=1&channel=<CHANNEL>&version=<VERSION>&time=…
 *       &rand=…}，签名 {@code rand = sha1( hexSha1(BDUSS) + uid + RAND_KEY + time + DEVICE +
 *       RAND_TAIL )}。2026-11-26 现场它回 {@code errno=8001 need verify}（账号是网页扫码登录的，
 *       手机客户端形态的签名对不上，被风控），只在前面两条都失败之后才走；
 *   <li><b>兜底要的 uid</b>：三个入口按顺序试 —— {@code GET https://pan.baidu.com/api/
 *       gettemplatevariable?clienttype=0&app_id=250528&web=1&fields=["uk"]} → {@code GET https://
 *       pan.baidu.com/rest/2.0/xpan/nas?method=uinfo&…} → {@code GET https://mbd.baidu.com/userx/v1/
 *       info/get?appname=baiduboxapp&fields=[…]}；都带账号 Cookie，UA 用 {@code netdisk;…} 串。盒子
 *       （Android 11）上实测 {@code mbd.baidu.com} 连不上（8 秒超时），所以它只做兜底。
 * </ol>
 *
 * <p>三条都失败时，失败原因里把**三条路各自的 errno 与百度原话**一起写出来（{@link #combinedReason}）。
 *
 * <p>id 形状（六段 {@code ++} 分隔，第一段固定 {@code bd}；与 UC 的四段、夸克的五段都不同形）：
 * <pre>
 *   [0] 固定 "bd"
 *   [1] share_id   列表接口回的数字分享 id（不是 URL 里那个带前导 1 的短 id）
 *   [2] uk         分享所有者的 uk
 *   [3] fs_id      分享内文件 id
 *   [4] sekey      提取码验证换来的 randsk，**百分号编码过**（保证段里不出现 + 和 %），可为空
 *   [5] 网页凭据   百分号编码过的 {@code surl|sign|timestamp}（{@link ShareTicket}），可为空
 * </pre>
 * 第 [4] 段必须编码：raw {@code randsk} 是 URL 编码过的 base64，可能含 {@code +}，直接拼进 id 会
 * 撞上 {@code ++} 分隔符。这里约定「id 里放 encode 后的值」，播放时先解一次还原 raw，再按 Flutter
 * 的 {@code _decodeSekeyForQuery} 口径解第二次得到请求参数 —— 这条链路与 Flutter 端发出的
 * {@code sekey} 字节一致（见 {@link #shareListUrl}）。
 *
 * <p>第 [5] 段是网页接口用的 {@code surl}（分享页短 id，用来重开分享页刷新 sign）、{@code sign} 与
 * {@code timestamp}。老的五段 id（升级前解析出来的、历史记录里存着的）仍然认，只是没有第 [5] 段，
 * 走不了网页接口 —— 那时只剩兜底那条，失败原因里会写明。第 [5] 段也可以只带 {@code surl}
 * （{@code surl||}）：解析分享时没取到签名，播放时还能靠它再开一次分享页（WebView）。
 *
 * <p>线程：{@link #resolve(String, String, String)} 会打两三次网络（单请求 8 秒超时），必须在后台线程
 * 调用。同步执行、永不抛，失败返回 null，原因放 {@link #lastFailureReason()}，与
 * {@link UcShareResolver} 同一套口径。
 *
 * <p>**一次解析的日志**（tag {@code BaiduShare}）：网页接口打了什么（{@code sign} 只记长度、{@code
 * sekey} 打码）、百度响应体原文（{@code dlink} / {@code sign} 打码，保留 {@code show_msg} /
 * {@code errmsg}）、拿到 dlink 时打一行 {@code sharedownload ok dlink host=…}；失败时把两条路各自的
 * 原因合起来报给用户。
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

    /**
     * 开放平台 {@code xpan/multimedia?method=filemetas&dlink=1} 给的直链，下载时要带这一串 UA
     * （百度开放平台文档对 {@code dlink} 的要求；带客户端串会被当外站）。
     */
    static final String UA_DLINK = "pan.baidu.com";

    /**
     * 转存到自己网盘的固定目录（Boss 2026-11-26 定：目录就叫 {@code /MarcoMovie}，与夸克的
     * {@code MarcoPlayer} 区分开；第一次用时不存在就建）。这个目录**只放本应用转存的临时副本**，
     * 所以每次转存前可以整个清空（与 {@link QuarkShareResolver} 的清目录同一套口径）。
     */
    static final String SAVE_DIR = "/MarcoMovie";

    /**
     * 「转存成功、但取直链那一步失败」留下的副本，这么多毫秒内重试直接复用，不重复转存
     * （任务卡第 2.2 节「播放失败重试时复用已转存的副本」）。超过这个时长就当过期，重新清理 + 转存。
     */
    private static final long COPY_REUSE_MS = 10 * 60 * 1000L;

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
    /**
     * 8001 之后补问 {@code mbd.baidu.com} 那次的超时。盒子上这个域有时候 8 秒才超时，而这一刻用户
     * 已经等了一次失败：给 4 秒，问到就多一个 uid 可比，问不到就按原来的原因报。
     */
    private static final int MBD_PROBE_TIMEOUT_MS = 4000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    /** 需要登录：本地没有 BDUSS。本次不做扫码，只把话说清楚。 */
    private static final String LOGIN_REQUIRED = "需要登录百度网盘才能播放这条分享（顶部「网盘」→ 百度，粘贴含 BDUSS 的 Cookie）";

    /** 分享已取消 / 已过期 / 被删（与 Flutter 端 {@code _checkBaidu} 同一组 errno）。 */
    private static final String SHARE_GONE = "百度分享解析失败：分享已失效（链接被取消或已过期），请换一条线路";

    /**
     * 分享页在应用自己的 WebView（真浏览器）里也没给出 {@code yunData} 时的失败注脚：HTTP 与 WebView 两条
     * 路都没拿到 {@code sign}/{@code timestamp} 时拼进失败原因，让用户知道这一页可能要求验证。
     */
    private static final String WEBVIEW_NO_YUN_DATA = "WebView 也没拿到 yunData（分享页可能要求验证）";

    /**
     * 在分享页里取 {@code sign}/{@code timestamp} 的表达式：优先 {@code window.yunData}，其次
     * {@code locals.mset}（页面里另一种写法）。只回 {@code sign}/{@code timestamp} 两个字段（整份
     * {@code yunData} 带着文件列表，几十上百 KB，没必要全塞过 JS 桥）；一处都取不到时回空串，
     * 让 {@link WebPageProbe} 触发那次 1.5 秒重试。
     */
    static final String SHARE_PAGE_JS =
            "(function(){try{"
                    + "function pick(v){if(v==null)return null;"
                    + "if(typeof v==='string'){try{v=JSON.parse(v);}catch(e){return null;}}"
                    + "if(typeof v!=='object')return null;"
                    + "if(v.sign==null&&v.timestamp==null)return null;"
                    + "var o={};if(v.sign!=null)o.sign=String(v.sign);"
                    + "if(v.timestamp!=null)o.timestamp=String(v.timestamp);"
                    + "return JSON.stringify(o);}"
                    + "var r=pick(window.yunData);if(r)return r;"
                    + "var l=window.locals;if(l){"
                    + "r=pick(l.mset)||pick(l._mset);if(r)return r;"
                    + "try{if(typeof l.get==='function'){r=pick(l.get('mset'));if(r)return r;}}catch(e){}"
                    + "}"
                    + "return '';}catch(e){return '';}})()";

    /**
     * 现场（2026-11-26）{@code /share/list} 回的 errno。登录态是好的（uid 接口当时回了
     * {@code uid ok}），所以先按「签名/参数不对」处理：换 {@code mbd.baidu.com} 那个 uid（Flutter 端
     * 就是拿它签的）再签一次；仍失败就把百度自己那句话（{@code show_msg} / {@code errmsg}）连同 errno
     * 一起报出来，一次上机就能定案。
     */
    private static final int ERRNO_SIGNATURE_REJECTED = 8001;

    /** 转存时「文件已存在 / 已转存」的 errno：不是失败，用它回的现成 fs_id（实测）。 */
    private static final int ERRNO_ALREADY_SAVED = 4;

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
     * @param rawEpisodeId 插件拿到的 episode url，五段或六段 {@code ++} 分隔、第一段 {@code bd}
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
                        + ident.fsId
                        + " web_ticket="
                        + (ident.ticket.hasSignature() ? "yes" : "no")
                        + " surl="
                        + (ident.ticket.surl.isEmpty() ? "no" : "yes")
                        + ")");
        try {
            // 主路：先转存到自己网盘的 /MarcoMovie（播前清理上一份副本），再用账号 Cookie 取自己文件
            // 的直链。任何一步失败都往下走，不在这里把播放挡死。
            Attempt save = saveAttempt(credential, ident);
            if (save.ok()) return save.episode(lineName);
            AppLog.w(TAG, "save path failed: " + save.describe() + "（errno=" + save.errno + "）");
            // 第二条：网页端 sharedownload（账号 Cookie 是网页扫码登录的，这条路不吃客户端签名）。
            Attempt web = webAttempt(credential, ident);
            if (web.ok()) return web.episode(lineName);
            AppLog.w(TAG, "web path failed: " + web.describe() + "（errno=" + web.errno + "）");
            // 兜底：原来那条手机客户端形态的 share/list?origin=dlna，原样保留。
            Attempt legacy = legacyAttempt(credential, ident);
            if (legacy.ok()) return legacy.episode(lineName);
            lastRefusalReason = combinedReason(save, web, legacy);
            AppLog.w(
                    TAG,
                    "refused: "
                            + lastRefusalReason
                            + "（save errno="
                            + save.errno
                            + "，web errno="
                            + web.errno
                            + "，dlna errno="
                            + legacy.errno
                            + "）");
            return null;
        } catch (RuntimeException error) {
            lastRefusalReason = "百度分享解析失败：百度网盘访问失败（解析异常），请换一条线路";
            AppLog.w(TAG, "resolve failed unexpectedly", error);
        }
        return null;
    }

    // ------------------------------------------------- 网盘中心：自己盘里的文件（原生浏览）

    /**
     * 一次「把**自己盘里**的文件换成可播直链」的结果，供 {@link BaiduDriveClient} 用。直链落地在
     * {@code *.baidupcs.com}，播放时**必须**带 {@link #ua}（{@code pan.baidu.com}）与 {@link #cookie}，
     * 这两项由这里一起交出去，调用方不用再猜。
     */
    static final class OwnFile {
        final String url;
        final String cookie;
        final String ua;
        final String referer;
        final String error;

        OwnFile(String url, String cookie, String ua, String referer, String error) {
            this.url = text(url).trim();
            this.cookie = text(cookie).trim();
            this.ua = text(ua).trim();
            this.referer = text(referer).trim();
            this.error = text(error).trim();
        }

        boolean ok() {
            return !url.isEmpty();
        }

        static OwnFile failed(String reason) {
            return new OwnFile("", "", UA_DLINK, REFERER, reason);
        }
    }

    /**
     * 网盘中心「百度」牌面的入口：把**自己网盘里**一个 {@code fs_id} 换成可播直链。
     *
     * <p>只复用本类已有的 {@link #filemetas}（{@code /api/filemetas?dlink=1&fsids=[…]} + 跟掉 302），
     * 不复制实现、也不走分享那三条路：这条入口没有 share id，只有 fs_id。
     */
    static OwnFile ownFileDlink(BaiduLogin.Credential credential, String fsId) {
        String id = text(fsId).trim();
        if (id.isEmpty()) return OwnFile.failed("文件 id 为空，无法获取播放地址");
        if (credential == null || text(credential.cookie).trim().isEmpty()) {
            return OwnFile.failed("百度网盘还没登录，请先扫码登录");
        }
        Attempt attempt = filemetas(credential, "", id);
        if (!attempt.ok()) {
            AppLog.w(TAG, "own file dlink failed fs_id=" + id + "：" + attempt.describe());
            return OwnFile.failed(attempt.describe());
        }
        return new OwnFile(attempt.dlink, attempt.cookie, attempt.ua, REFERER, "");
    }

    /**
     * {@code bdstoken}：{@code /api/list} 这类网页端读操作也要它（2026-11-26 实测）。失败返回空串，
     * 原因已由 {@link #bdstokenOf} 记进日志。
     */
    static String ownBdstoken(BaiduLogin.Credential credential) {
        TemplateVars vars = bdstokenOf(credential);
        if (vars.bdstoken.isEmpty()) AppLog.w(TAG, "bdstoken unavailable: " + vars.reason);
        return vars.bdstoken;
    }

    /**
     * 一次「取直链」的完整结果。成功时 {@code dlink} 非空；失败时留着 HTTP 状态、errno 与百度
     * 自己那句话（{@code show_msg} / {@code errmsg}），由 {@link #describe()} 拼成一段原因，
     * 再由 {@link #combinedReason} 把三条路合起来报给用户。
     */
    private static final class Attempt {
        String dlink = "";
        String cookie = "";
        String error = "";
        /** 百度自己那句话：{@code show_msg}，没有就 {@code errmsg}；日志与提示都用它定案。 */
        String show = "";
        /** 附在失败原因末尾的补充（例如「WebView 也没拿到 yunData」）；正常为空。 */
        String hint = "";
        /** 这一条路真的走了（没授权时 save 那条会记 {@code false}，报原因时就不提它）。 */
        boolean attempted;
        /** 播放这条 dlink 时要带的 UA（{@code xpan} 的直链与网页接口的不是同一串）。 */
        String ua = UA_MEDIA;
        int http;
        int errno;

        boolean ok() {
            return !dlink.isEmpty();
        }

        Cms.Episode episode(String lineName) {
            AppLog.w(TAG, "dlink host=" + hostOf(dlink) + " ua=" + ua);
            return BaiduShareResolver.episode(lineName, dlink, cookie, ua);
        }

        /**
         * 这一路失败的原因片段（不带「百度分享解析失败」前缀、不带路名，由调用方拼）：优先自己记的错误
         * 说明（网络 / 形状），其次 HTTP 状态，再其次是百度的 errno 与它自己那句话。
         */
        String describe() {
            String reason;
            if (!error.isEmpty()) {
                reason = error;
            } else if (http >= 400) {
                reason = "返回 HTTP " + http;
            } else if (errno != 0) {
                String said = text(show).trim();
                StringBuilder sb = new StringBuilder("返回错误（errno=").append(errno);
                if (!said.isEmpty()) sb.append("，").append(said);
                sb.append("）");
                if (isAuthErrno(errno)) sb.append("，可能登录已失效，可在网盘中心重新粘贴 Cookie");
                reason = sb.toString();
            } else {
                reason = "没有返回播放地址（errno=0）";
            }
            if (!hint.isEmpty()) reason += "，" + hint;
            return reason;
        }
    }

    /**
     * 兜底那条：先取账号 uid（网页接口不需要它，所以只在兜底时才打这几个网络），再按手机客户端形态
     * 签名取直链。8001 时照旧换 {@code mbd.baidu.com} 的 uid 再签一次。
     */

    /** 从响应的一串 {@code Set-Cookie} 里挑出某个名字的值；没有就是空串。 */
    static String cookieValue(List<String> setCookies, String name) {
        if (setCookies == null || setCookies.isEmpty() || name == null) return "";
        for (String raw : setCookies) {
            String value = text(raw).trim();
            if (value.isEmpty()) continue;
            int eq = value.indexOf('=');
            if (eq <= 0) continue;
            String key = value.substring(0, eq).trim();
            if (!key.equalsIgnoreCase(name)) continue;
            int end = value.indexOf(';', eq);
            return (end < 0 ? value.substring(eq + 1) : value.substring(eq + 1, end)).trim();
        }
        return "";
    }

    /**
     * 用账号 Cookie 重开一次分享页，取响应里的 {@code BDCLND} —— 它就是百度发给下载端的 sekey，
     * 有时效；过期后 dlna 接口会从"能列目录"变成 need verify。
     *
     * @return 新的 sekey；没拿到返回 ""（调用方沿用 id 里那份）
     */
    private static String refreshSekey(BaiduLogin.Credential credential, Ident ident) {
        if (credential == null || ident == null || ident.ticket.surl.isEmpty()) return "";
        try {
            String url = "https://pan.baidu.com/s/" + encodePart(ident.ticket.surl);
            String cookie = credential.cookie;
            if (!ident.sekey.isEmpty()) cookie = cookie + "; BDCLND=" + ident.sekey;
            Response answer =
                    fetch(
                            "GET",
                            url,
                            null,
                            null,
                            UA_SHARE,
                            "https://pan.baidu.com/",
                            cookie,
                            CONNECT_TIMEOUT_MS,
                            READ_TIMEOUT_MS);
            if (answer == null) return "";
            return cookieValue(answer.setCookies, "BDCLND");
        } catch (java.io.IOException error) {
            AppLog.w(TAG, "sekey refresh failed: " + error.getMessage());
            return "";
        } catch (RuntimeException error) {
            AppLog.w(TAG, "sekey refresh failed", error);
            return "";
        }
    }

    private static Attempt legacyAttempt(BaiduLogin.Credential credential, Ident ident) {
        UidResult account = uid(credential);
        if (account.uid.isEmpty()) {
            Attempt out = new Attempt();
            out.error = "取不到账号 uid（" + account.reason + "）";
            return out;
        }
        AppLog.w(
                TAG,
                "sign uid="
                        + maskId(account.uid)
                        + " src="
                        + account.source
                        + " bdclnd="
                        + (ident.sekey.isEmpty() ? "no" : "yes")
                        + " clock_skew_ms="
                        + account.skewMs);
        // sekey（BDCLND）有有效期：过期后同一个接口会从「能列目录」变成 need verify/-9。
        // 播放前用账号 Cookie 重开一次分享页，拿新的 BDCLND 再签；拿不到就沿用 id 里那份。
        Ident signed = ident;
        String freshSekey = refreshSekey(credential, ident);
        if (!freshSekey.isEmpty() && !freshSekey.equals(ident.sekey)) {
            signed =
                    new Ident(
                            ident.shareId, ident.uk, ident.fsId, freshSekey, ident.ticket);
            AppLog.w(TAG, "sekey refreshed len=" + freshSekey.length());
        }
        Attempt first = attempt(credential, signed, account.uid, account.skewMs);
        if (first.ok()) return first;
        // 8001：先换个 uid 再签一次（pan 域两个入口给的是 uk，Flutter 端签名用的是 mbd 的 uid）。
        // 只在 8001 上多做这一次网络，别的失败码照旧一条就报，不拖慢失败提示。
        if (first.errno == ERRNO_SIGNATURE_REJECTED) {
            UidResult alt = uidFromMbd(credential);
            if (alt.uid.isEmpty()) {
                AppLog.w(TAG, "mbd uid unavailable: " + alt.reason);
            } else {
                AppLog.w(
                        TAG,
                        "uid_match="
                                + (alt.uid.equals(account.uid) ? "yes" : "no")
                                + " retry with mbd uid "
                                + maskId(alt.uid));
                if (!alt.uid.equals(account.uid)) {
                    Attempt second = attempt(credential, signed, alt.uid, alt.skewMs);
                    if (second.ok()) return second;
                    first = second;
                }
            }
        }
        return first;
    }

    // ---------------------------------- 主路：先转存到 /MarcoMovie，再取自己文件的直链

    /**
     * 上一次「转存成功、但取直链那一步失败」留下的副本。重试时直接拿它的 {@code to_fs_id} 再取一次
     * 直链，不重复转存（任务卡 2.2「播放失败重试时复用已转存的副本」）；复用成功就清掉，下一次新播放
     * 照旧先清理再转存。
     */
    private static final class CopyStamp {
        final String episodeFsId;
        final String toFsId;
        final String path;
        final long at;

        CopyStamp(String episodeFsId, String toFsId, String path, long at) {
            this.episodeFsId = text(episodeFsId).trim();
            this.toFsId = text(toFsId).trim();
            this.path = text(path).trim();
            this.at = at;
        }
    }

    /** 解析跑在线程池上，这份「待复用副本」也是：只在 {@link #saveAttempt} 里读写。 */
    private static volatile CopyStamp reusableCopy;

    /**
     * 主路：把这一集转存到自己网盘的 {@link #SAVE_DIR}，再用开放平台的 {@code filemetas} 取直链。
     *
     * <p>为什么这条路最稳：直链指向的是**自己盘里的文件**，不依赖分享页的 {@code sign}（那套只在网页
     * 里活着）；而转存与删除本来就吃账号 Cookie，父会话 2026-11-26 已在盒子上实测 {@code errno 0}。
     *
     * <p>顺序照任务卡 2.2：取 {@code bdstoken} → 建目录（幂等）→ 清掉目录里上一份副本 → 转存本集
     * （记 {@code to_fs_id}）→ 用 {@code access_token} 取直链。日志固定几行，上机一眼对账：
     * {@code bdstoken ok …} / {@code delete old copy n=…} / {@code transfer ok to_fs_id=…} /
     * {@code filemetas ok dlink host=…}。
     *
     * <p>失败不抛也不挡播放：转存的 errno、百度原话都留在 {@link Attempt#errno} / {@link Attempt#show}
     * 里，由 {@link #combinedReason} 与另外两条路一起报。**转存成功而取直链失败**时把副本记进
     * {@link #reusableCopy}，下次重试直接复用。
     */
    private static Attempt saveAttempt(BaiduLogin.Credential credential, Ident ident) {
        Attempt out = new Attempt();
        out.attempted = true;
        // 0) 上一次转存成功、取直链失败留下的副本：先拿它再取一次，不重复转存。
        CopyStamp reuse = reusableCopy;
        if (reuse != null
                && reuse.episodeFsId.equals(ident.fsId)
                && !reuse.toFsId.isEmpty()
                && System.currentTimeMillis() - reuse.at < COPY_REUSE_MS) {
            Attempt reused = filemetas(credential, ident, reuse.toFsId);
            if (reused.ok()) {
                AppLog.w(TAG, "reuse transferred copy fs_id=" + reuse.toFsId + " path=" + reuse.path);
                reusableCopy = null;
                return reused;
            }
            AppLog.w(TAG, "reuse transferred copy failed: " + reused.describe() + "，重新转存");
        }
        // 1) bdstoken：网页端的写操作（建目录 / 删除 / 转存）都要它，账号 Cookie 换。
        TemplateVars vars = bdstokenOf(credential);
        if (vars.bdstoken.isEmpty()) {
            out.error = "取不到 bdstoken（" + vars.reason + "）";
            AppLog.w(TAG, "save path stopped: " + out.error);
            return out;
        }
        // 2) 目录 /MarcoMovie：不存在就建（已存在也算成功）。
        ensureSaveDir(credential, vars.bdstoken);
        // 3) 播前清理：这个目录只放本应用转存的临时副本，整个清空（与夸克清 MarcoPlayer 同一套口径）。
        int cleaned = clearSaveDir(credential, vars.bdstoken);
        // 3b) 删除是异步的（async=2）：等目录真的空了再转存，否则 transfer 会回 errno 4，并把**正在
        // 被删的那份**当成「已转存」复用，filemetas 拿到的 dlink 随后 404（2026-11-27 实测）。
        if (cleaned > 0) waitSaveDirEmpty(credential, vars.bdstoken);
        // 4) 转存本集，拿 to_fs_id。
        TransferResult copy = transfer(credential, vars.bdstoken, ident);
        if (!copy.ok()) {
            out.errno = copy.errno;
            out.show = copy.show;
            out.error = copy.error.isEmpty() ? "转存失败" : copy.error;
            AppLog.w(TAG, "transfer failed after clean=" + cleaned + "：" + out.describe());
            return out;
        }
        // 5) 用自己盘里那份的 fs_id 取直链（只要账号 Cookie，见 filemetas）。
        Attempt dlink = filemetas(credential, ident, copy.toFsId);
        if (!dlink.ok()) {
            // 副本已经在盘里：记下来，播放失败重试时直接用它，不重复转存。
            reusableCopy =
                    new CopyStamp(ident.fsId, copy.toFsId, copy.path, System.currentTimeMillis());
            AppLog.w(TAG, "copy kept for retry to_fs_id=" + copy.toFsId + " path=" + copy.path);
        }
        return dlink;
    }

    /** {@code gettemplatevariable} 回来的两样东西：{@code bdstoken}（写操作要用）与账号 {@code uk}。 */
    private static final class TemplateVars {
        final String bdstoken;
        final String uk;
        final String reason;

        TemplateVars(String bdstoken, String uk, String reason) {
            this.bdstoken = text(bdstoken).trim();
            this.uk = text(uk).trim();
            this.reason = text(reason).trim();
        }

        static TemplateVars failed(String reason) {
            return new TemplateVars("", "", reason);
        }
    }

    /**
     * 取 {@code bdstoken}：{@code GET /api/gettemplatevariable?fields=["bdstoken","uk"]}（父会话 2026-11-26
     * 在盒子上实测 {@code errno 0}）。返回结构两种都认：{@code result:[{"key":"bdstoken","value":…}]}
     * 与直接给字段的部署。
     */
    private static TemplateVars bdstokenOf(BaiduLogin.Credential credential) {
        String url =
                "https://pan.baidu.com/api/gettemplatevariable?clienttype=0&app_id=250528&web=1&fields="
                        + encodePart("[\"bdstoken\",\"uk\"]");
        Response answer;
        try {
            answer = fetch("GET", url, null, null, UA_SHARE, REFERER, accountCookie(credential, ""));
        } catch (IOException error) {
            return TemplateVars.failed("连不上（" + NetdiskShareHttp.describe(error) + "）");
        }
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            return TemplateVars.failed("返回的不是 JSON（HTTP " + answer.http + "）");
        }
        int errno = NetdiskShareHttp.optInt(body, "errno", -1);
        String token = templateValue(body, "bdstoken");
        if (token.isEmpty()) {
            String said = baiduMessage(body);
            return TemplateVars.failed(
                    "errno=" + errno + (said.isEmpty() ? "" : "，" + said) + " 没有 bdstoken");
        }
        String uk = templateValue(body, "uk");
        AppLog.w(
                TAG,
                "bdstoken ok len="
                        + token.length()
                        + " uk="
                        + (uk.isEmpty() ? "-" : maskId(uk))
                        + " errno="
                        + errno);
        return new TemplateVars(token, uk, "");
    }

    /**
     * 从 {@code gettemplatevariable} 的返回里取一个字段：顶层 / {@code data} / {@code result} 对象 /
     * {@code result[]} 键值行。
     *
     * <p>{@code result} 现在回的是**对象**（{@code {"errno":0,"result":{"bdstoken":"…","uk":…}}},
     * 2026-11-26 盒子与 PC 实测），老部署回过 {@code [{"key":…,"value":…}]} 数组，两种都认。
     * 只认数组时 {@code bdstoken} 永远取不到，主路第一步就走不动（当晚就是这样卡住的）。
     */
    private static String templateValue(JsonObject body, String name) {
        if (body == null || text(name).isEmpty()) return "";
        String direct = NetdiskShareHttp.optString(body, name).trim();
        if (!direct.isEmpty()) return direct;
        JsonElement data = body.get("data");
        if (data != null && data.isJsonObject()) {
            String nested = NetdiskShareHttp.optString(data.getAsJsonObject(), name).trim();
            if (!nested.isEmpty()) return nested;
        }
        JsonElement result = body.get("result");
        if (result != null && result.isJsonObject()) {
            String nested = NetdiskShareHttp.optString(result.getAsJsonObject(), name).trim();
            if (!nested.isEmpty()) return nested;
        }
        if (result != null && result.isJsonArray()) {
            for (JsonElement item : result.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject row = item.getAsJsonObject();
                if (!name.equalsIgnoreCase(NetdiskShareHttp.optString(row, "key").trim())) continue;
                String value = NetdiskShareHttp.optString(row, "value").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    /**
     * 备好 {@link #SAVE_DIR}：{@code POST /api/create?a=commit}，表单 {@code path=/MarcoMovie&isdir=1&
     * block_list=[]}。已经存在时百度回 {@code errno -8}，同样算成功；失败只记日志（转存那一步会再报
     * 一次真实的错），不在这里终止。
     */
    private static boolean ensureSaveDir(BaiduLogin.Credential credential, String bdstoken) {
        String url =
                "https://pan.baidu.com/api/create?a=commit&bdstoken="
                        + encodePart(bdstoken)
                        + "&channel=chunlei&web=1&app_id=250528&clienttype=0";
        String form =
                "path="
                        + encodePart(SAVE_DIR)
                        + "&isdir=1&block_list="
                        + encodePart("[]");
        try {
            Response answer =
                    fetch(
                            "POST",
                            url,
                            form,
                            "application/x-www-form-urlencoded",
                            UA_SHARE,
                            REFERER,
                            accountCookie(credential, ""));
            JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
            int errno = body == null ? -1 : NetdiskShareHttp.optInt(body, "errno", -1);
            boolean ready = errno == 0 || errno == -8;
            AppLog.w(
                    TAG,
                    "mkdir "
                            + SAVE_DIR
                            + " errno="
                            + errno
                            + (ready ? " ok" : "（建目录没成功，转存那步会报真错）"));
            return ready;
        } catch (IOException error) {
            AppLog.w(TAG, "mkdir failed: " + NetdiskShareHttp.describe(error));
            return false;
        }
    }

    /** 一次目录列举的结果；失败时 {@code errno}/{@code error} 说原因，{@code entries} 为空。 */
    private static final class DirListing {
        int errno;
        String error = "";
        final List<JsonObject> entries = new ArrayList<>();

        boolean ok() {
            return errno == 0;
        }
    }

    /** {@code GET /api/list?dir=…&order=time&desc=1}：按时间倒序列一页（200 条够一个临时目录）。 */
    private static DirListing listDir(
            BaiduLogin.Credential credential, String bdstoken, String dir) {
        DirListing out = new DirListing();
        String url =
                "https://pan.baidu.com/api/list?dir="
                        + encodePart(dir)
                        + "&order=time&desc=1&start=0&limit=200&bdstoken="
                        + encodePart(bdstoken)
                        + "&web=1&app_id=250528&clienttype=0";
        Response answer;
        try {
            answer = fetch("GET", url, null, null, UA_SHARE, REFERER, accountCookie(credential, ""));
        } catch (IOException error) {
            out.error = "连不上（" + NetdiskShareHttp.describe(error) + "）";
            return out;
        }
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            out.error = "返回的不是 JSON（HTTP " + answer.http + "）";
            return out;
        }
        out.errno = NetdiskShareHttp.optInt(body, "errno", -1);
        if (out.errno != 0) {
            String said = baiduMessage(body);
            out.error = "errno=" + out.errno + (said.isEmpty() ? "" : "，" + said);
            return out;
        }
        JsonElement list = body.get("list");
        if (list != null && list.isJsonArray()) {
            for (JsonElement item : list.getAsJsonArray()) {
                if (item != null && item.isJsonObject()) out.entries.add(item.getAsJsonObject());
            }
        }
        return out;
    }

    /** 播前清理之后最多等这么久：{@value #CLEAN_WAIT_TRIES} 次 × {@value #CLEAN_WAIT_MS} ms。 */
    private static final int CLEAN_WAIT_TRIES = 4;

    private static final long CLEAN_WAIT_MS = 500L;

    /** 第一次 transfer 回「已转存」但那份不存在时，等 300 ms 再转一次（删除可能刚好落定）。 */
    private static final long DUP_RETRY_DELAY_MS = 300L;

    /**
     * 等 {@link #SAVE_DIR} 真的空：{@code api/filemanager?opera=delete&async=2} 只保证删除**已受理**，
     * 返回 {@code errno 0} 时文件可能还在（2026-11-27 实测），此时转存会撞上「已转存」并把正在删的
     * 副本当现成的用。最多 {@value #CLEAN_WAIT_TRIES} 次、每次 {@value #CLEAN_WAIT_MS} ms；超时照常
     * 转存，只把结果写进日志。
     *
     * @return true 表示轮询到目录已无文件
     */
    private static boolean waitSaveDirEmpty(BaiduLogin.Credential credential, String bdstoken) {
        for (int tries = 1; tries <= CLEAN_WAIT_TRIES; tries++) {
            DirListing listing = listDir(credential, bdstoken, SAVE_DIR);
            if (listing.ok() && !hasFileRows(listing)) {
                AppLog.w(TAG, "clean wait ok tries=" + tries);
                return true;
            }
            if (tries < CLEAN_WAIT_TRIES) sleepQuietly(CLEAN_WAIT_MS);
        }
        AppLog.w(TAG, "clean wait timed out tries=" + CLEAN_WAIT_TRIES);
        return false;
    }

    /** 目录列举里还有没有**文件**（子目录不算）。 */
    private static boolean hasFileRows(DirListing listing) {
        if (listing == null) return false;
        for (JsonObject row : listing.entries) {
            if (NetdiskShareHttp.optInt(row, "isdir", 0) == 0) return true;
        }
        return false;
    }

    /**
     * {@code fs_id} 现在还在不在 {@link #SAVE_DIR} 里；在就返回那一行（用来复用，也顺手拿路径）。
     *
     * <p>列目录失败按「不在」处理：宁可多转存一次，也不把一份可能正在被删的副本交给 filemetas。
     */
    private static JsonObject fileInSaveDir(
            BaiduLogin.Credential credential, String bdstoken, String fsId) {
        String wanted = text(fsId).trim();
        if (wanted.isEmpty()) return null;
        DirListing listing = listDir(credential, bdstoken, SAVE_DIR);
        if (!listing.ok()) {
            AppLog.w(TAG, "dup check skipped: list failed " + listing.error);
            return null;
        }
        for (JsonObject row : listing.entries) {
            if (NetdiskShareHttp.optInt(row, "isdir", 0) != 0) continue;
            if (wanted.equals(NetdiskShareHttp.optString(row, "fs_id").trim())) return row;
        }
        return null;
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 播前清理：把 {@link #SAVE_DIR} 里的文件全删掉（{@code POST /api/filemanager?opera=delete&async=2}，
     * 表单 {@code filelist=["/MarcoMovie/x.mp4", …]}，父会话已在盒子上实测 {@code errno 0}）。
     *
     * <p>为什么整个清空而不是只删同名：这个目录是本应用转存的临时区（与夸克清 {@code MarcoPlayer}
     * 同一套口径），每次播放只留这一集，才不会堆出 {@code xxx(1).mp4}、{@code xxx(2).mp4}；而且转存前
     * 我们还不知道文件叫什么（名字只在转存返回里才有）。子目录不动。删除失败只记日志 —— 转存与播放
     * 不依赖它。
     *
     * @return 删掉的文件数（0 表示本来就没有，或列目录 / 删除失败）
     */
    private static int clearSaveDir(BaiduLogin.Credential credential, String bdstoken) {
        DirListing listing = listDir(credential, bdstoken, SAVE_DIR);
        if (!listing.ok()) {
            AppLog.w(TAG, "delete old copy skipped: list failed " + listing.error);
            return 0;
        }
        List<String> paths = new ArrayList<>();
        for (JsonObject row : listing.entries) {
            if (NetdiskShareHttp.optInt(row, "isdir", 0) != 0) continue;
            String path = NetdiskShareHttp.optString(row, "path").trim();
            if (path.isEmpty()) {
                String name = NetdiskShareHttp.optString(row, "server_filename").trim();
                if (!name.isEmpty()) path = SAVE_DIR + "/" + name;
            }
            if (!path.isEmpty() && !paths.contains(path)) paths.add(path);
        }
        if (paths.isEmpty()) {
            AppLog.w(TAG, "delete old copy none in " + SAVE_DIR);
            return 0;
        }
        JsonArray filelist = new JsonArray();
        for (String path : paths) filelist.add(path);
        String url =
                "https://pan.baidu.com/api/filemanager?opera=delete&bdstoken="
                        + encodePart(bdstoken)
                        + "&async=2&channel=chunlei&web=1&app_id=250528&clienttype=0";
        String form = "filelist=" + encodePart(filelist.toString());
        try {
            Response answer =
                    fetch(
                            "POST",
                            url,
                            form,
                            "application/x-www-form-urlencoded",
                            UA_SHARE,
                            REFERER,
                            accountCookie(credential, ""));
            JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
            int errno = body == null ? -1 : NetdiskShareHttp.optInt(body, "errno", -1);
            AppLog.w(TAG, "delete old copy n=" + paths.size() + " errno=" + errno);
            return errno == 0 ? paths.size() : 0;
        } catch (IOException error) {
            AppLog.w(TAG, "delete old copy failed: " + NetdiskShareHttp.describe(error));
            return 0;
        }
    }

    /** 一次转存的结果：成功时 {@code toFsId} 非空、{@code path} 是它在盘里的路径。 */
    private static final class TransferResult {
        String toFsId = "";
        /** errno 4 时百度说「已经有一份」的 fs_id；只有验活通过才会升成 {@link #toFsId}。 */
        String duplicateFsId = "";
        String path = "";
        String error = "";
        String show = "";
        int errno;

        boolean ok() {
            return !toFsId.isEmpty();
        }

        String describe() {
            if (!error.isEmpty()) return error;
            String said = text(show).trim();
            return "errno=" + errno + (said.isEmpty() ? "" : "，" + said);
        }
    }

    /**
     * 转存本集到 {@link #SAVE_DIR}：{@code POST /share/transfer?shareid&from&sekey&bdstoken}，表单
     * {@code fsidlist=[<fsid>]&path=/MarcoMovie}。2026-11-26 深夜复测：{@code errno 0} 时返回体里
     * **没有** {@code to_fs_id}（只有 {@code extra.list[].to}），所以下面那条「列目录取最新」不是兜底
     * 而是主路径；同一份再转一次会回 {@code errno 4}（已转存），带 {@code duplicated.list[].fs_id}。
     *
     * <p>{@code to_fs_id} 从顶层 / {@code extra.list[]} / {@code list[]} 三处找；返回里一个都没有时再
     * 列一次目录，按时间取最新的那个文件（刚转存进来的就是它）。
     */
    private static TransferResult transfer(
            BaiduLogin.Credential credential, String bdstoken, Ident ident) {
        TransferResult first = transferOnce(credential, bdstoken, ident);
        if (first.errno != ERRNO_ALREADY_SAVED) return first;
        // 「已转存」不一定是好事：上一轮的删除可能还没落定，duplicated.list[0].fs_id 指的就是那份正在
        // 被删的副本（随后 filemetas 的 dlink 404）。先确认它还在，不在就重试一次 transfer。
        JsonObject aliveRow = fileInSaveDir(credential, bdstoken, first.duplicateFsId);
        if (aliveRow != null) {
            AppLog.w(
                    TAG,
                    "dup fs_id="
                            + first.duplicateFsId
                            + " alive=yes retry=no");
            first.toFsId = first.duplicateFsId;
            first.path = pathOf(aliveRow);
            first.error = "";
            AppLog.w(
                    TAG,
                    "transfer says already saved, reuse fs_id="
                            + first.toFsId
                            + " path="
                            + first.path);
            return first;
        }
        AppLog.w(
                TAG,
                "dup fs_id=" + first.duplicateFsId + " alive=no retry=yes");
        sleepQuietly(DUP_RETRY_DELAY_MS);
        TransferResult retry = transferOnce(credential, bdstoken, ident);
        if (retry.errno == ERRNO_ALREADY_SAVED) {
            JsonObject retryRow =
                    fileInSaveDir(credential, bdstoken, retry.duplicateFsId);
            boolean alive = retryRow != null;
            AppLog.w(
                    TAG,
                    "dup retry fs_id="
                            + retry.duplicateFsId
                            + " alive="
                            + (alive ? "yes" : "no")
                            + " reuse="
                            + (alive ? "yes" : "no"));
            if (alive) {
                retry.toFsId = retry.duplicateFsId;
                retry.path = pathOf(retryRow);
                retry.error = "";
            }
        }
        return retry;
    }

    /**
     * 一次 transfer 请求。{@code errno 4}（已转存）只把百度回的 {@code duplicated.list[].fs_id} 记进
     * {@link TransferResult#duplicateFsId}，是否复用它由 {@link #transfer} 验活后决定。
     */
    private static TransferResult transferOnce(
            BaiduLogin.Credential credential, String bdstoken, Ident ident) {
        TransferResult out = new TransferResult();
        String url =
                "https://pan.baidu.com/share/transfer?shareid="
                        + encodePart(ident.shareId)
                        + "&from="
                        + encodePart(ident.uk)
                        + "&sekey="
                        + encodePart(percentDecode(ident.sekey))
                        + "&bdstoken="
                        + encodePart(bdstoken)
                        + "&channel=chunlei&web=1&app_id=250528&clienttype=0";
        String form =
                "fsidlist="
                        + encodePart("[" + ident.fsId + "]")
                        + "&path="
                        + encodePart(SAVE_DIR);
        String cookie = accountCookie(credential, ident.sekey);
        AppLog.w(
                TAG,
                "transfer request share="
                        + ident.shareId
                        + " fid="
                        + ident.fsId
                        + " to="
                        + SAVE_DIR
                        + " sekey="
                        + (ident.sekey.isEmpty() ? "no" : "yes"));
        Response answer;
        try {
            answer =
                    fetch(
                            "POST",
                            url,
                            form,
                            "application/x-www-form-urlencoded",
                            UA_SHARE,
                            REFERER,
                            cookie);
        } catch (IOException error) {
            out.error = "访问失败（" + NetdiskShareHttp.describe(error) + "）";
            return out;
        }
        out.errno = 0;
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        AppLog.w(TAG, "transfer answer http=" + answer.http + " body=" + maskBody(answer.body));
        if (body == null) {
            out.error = "没有返回可用数据（HTTP " + answer.http + "）";
            return out;
        }
        out.errno = NetdiskShareHttp.optInt(body, "errno", 0);
        out.show = baiduMessage(body);
        if (out.errno == ERRNO_ALREADY_SAVED) {
            // 目录里已经有同一份（上一次没清干净，或两个解析撞在一起）。百度把现成的 fs_id 放在
            // duplicated.list[] 里，但它可能正在被异步删除（2026-11-27 实测）：这里只记下来，
            // 由 transfer() 列目录验活后再决定复用还是重转。
            out.duplicateFsId = duplicatedFsId(body);
            return out;
        }
        if (out.errno != 0) return out;
        out.toFsId = transferFsId(body);
        out.path = transferPath(body);
        if (out.toFsId.isEmpty()) {
            // 有的部署 errno=0 但不回 to_fs_id：列一次目录，按时间取最新的那个文件。
            DirListing listing = listDir(credential, bdstoken, SAVE_DIR);
            JsonObject newest = newestFile(listing);
            if (newest != null) {
                out.toFsId = NetdiskShareHttp.optString(newest, "fs_id").trim();
                if (out.path.isEmpty()) out.path = pathOf(newest);
            }
            AppLog.w(
                    TAG,
                    "transfer returned no to_fs_id, newest in "
                            + SAVE_DIR
                            + " fs_id="
                            + (out.toFsId.isEmpty() ? "-" : out.toFsId));
        }
        if (!out.toFsId.isEmpty()) {
            AppLog.w(TAG, "transfer ok to_fs_id=" + out.toFsId + " path=" + out.path);
        } else {
            out.error = "errno=0 但没找到转存后的文件（to_fs_id 与目录里都没有）";
        }
        return out;
    }

    /**
     * {@code errno=4} 时百度回的现成 fs_id：{@code duplicated.list[].fs_id}（实测形状，2026-11-26
     * 同一条分享连转两次复现）。取不到返回空串，调用方按普通失败处理。
     */
    private static String duplicatedFsId(JsonObject body) {
        if (body == null) return "";
        JsonElement dup = body.get("duplicated");
        if (dup == null || !dup.isJsonObject()) return "";
        JsonElement list = dup.getAsJsonObject().get("list");
        if (list == null || !list.isJsonArray()) return "";
        for (JsonElement item : list.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) continue;
            String id = NetdiskShareHttp.optString(item.getAsJsonObject(), "fs_id").trim();
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    /** 转存返回里所有可能带 {@code to_fs_id} 的行：{@code extra.list[]} 优先，其次顶层 {@code list[]}。 */
    private static List<JsonObject> transferRows(JsonObject body) {
        List<JsonObject> out = new ArrayList<>();
        if (body == null) return out;
        JsonElement extra = body.get("extra");
        if (extra != null && extra.isJsonObject()) {
            JsonElement list = extra.getAsJsonObject().get("list");
            if (list != null && list.isJsonArray()) {
                for (JsonElement item : list.getAsJsonArray()) {
                    if (item != null && item.isJsonObject()) out.add(item.getAsJsonObject());
                }
            }
        }
        JsonElement list = body.get("list");
        if (list != null && list.isJsonArray()) {
            for (JsonElement item : list.getAsJsonArray()) {
                if (item != null && item.isJsonObject()) out.add(item.getAsJsonObject());
            }
        }
        return out;
    }

    /** 转存后新文件的 {@code fs_id}：顶层 {@code to_fs_id} 优先，其次各行的 {@code to_fs_id}。 */
    private static String transferFsId(JsonObject body) {
        if (body == null) return "";
        String direct = NetdiskShareHttp.optString(body, "to_fs_id").trim();
        if (!direct.isEmpty()) return direct;
        for (JsonObject row : transferRows(body)) {
            String id = NetdiskShareHttp.optString(row, "to_fs_id").trim();
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    /** 转存后新文件的路径（{@code extra.list[].to}）：用来打日志，也是复用时给人看的名字。 */
    private static String transferPath(JsonObject body) {
        if (body == null) return "";
        for (JsonObject row : transferRows(body)) {
            String path = NetdiskShareHttp.optString(row, "to").trim();
            if (!path.isEmpty()) return path;
        }
        return "";
    }

    /** 目录列举里 {@code server_mtime} 最大的那个文件（刚转存进来的就是它）；没有文件返回 null。 */
    private static JsonObject newestFile(DirListing listing) {
        if (listing == null || !listing.ok()) return null;
        JsonObject newest = null;
        long best = Long.MIN_VALUE;
        for (JsonObject row : listing.entries) {
            if (NetdiskShareHttp.optInt(row, "isdir", 0) != 0) continue;
            long time = NetdiskShareHttp.optLong(row, "server_mtime", 0L);
            if (newest == null || time > best) {
                newest = row;
                best = time;
            }
        }
        return newest;
    }

    /** 目录里一行的路径：{@code path} 优先，没有就用目录名 + {@code server_filename} 拼。 */
    private static String pathOf(JsonObject row) {
        if (row == null) return "";
        String path = NetdiskShareHttp.optString(row, "path").trim();
        if (!path.isEmpty()) return path;
        String name = NetdiskShareHttp.optString(row, "server_filename").trim();
        return name.isEmpty() ? "" : SAVE_DIR + "/" + name;
    }

    /**
     * 取自己盘里一个文件的直链：{@code GET /api/filemetas?dlink=1&fsids=[<fsid>]}，只要账号 Cookie。
     *
     * <p>2026-11-26 深夜在 PC 上用 curl 逐条对过（同样几组参数在盒子上也复现过，记录见
     * {@code 14-master/records/2026-11-26-网盘支持矩阵.md}）：
     *
     * <table>
     *   <caption>实测：什么参数是必须的</caption>
     *   <tr><th>写法</th><th>结果</th></tr>
     *   <tr><td>{@code fsids=[…]} 或干脆只留这一项</td><td>{@code errno 0}，给出 dlink</td></tr>
     *   <tr><td>写成 {@code target=[…]}（百度当年那个参数名）</td><td>{@code errno 12}，{@code info[0].errno=31021}</td></tr>
     *   <tr><td>不带 {@code bdstoken} / 不带 {@code PANWEB=1}</td><td>{@code errno 0}（都不是必须）</td></tr>
     *   <tr><td>{@code /rest/2.0/xpan/multimedia?method=filemetas}（要 {@code access_token}）</td>
     *       <td>没令牌回 {@code 9019 need verify}</td></tr>
     * </table>
     *
     * <p>拿到的 {@code dlink} 指向 {@code d.pcs.baidu.com}，会再 302 到 {@code *.baidupcs.com}
     * 落地域名；下载时**必须**用 {@link #UA_DLINK} 当 UA 并带上同一份账号 Cookie，否则落地域名回
     * {@code 31362 sign error}（UA 不对）或 {@code 31045 user not exists}（缺 Cookie）。这里先把
     * 302 跟掉，把落地 URL 直接交给播放器 —— 播放器跟不跟跳转、跳转后还带不带 Cookie 都不再赌。
     */
    private static Attempt filemetas(BaiduLogin.Credential credential, Ident ident, String fsId) {
        return filemetas(credential, ident == null ? "" : ident.sekey, fsId);
    }

    /**
     * 同上，但直接给 {@code sekey}：网盘中心浏览自己的盘没有分享坐标，只需要账号 Cookie
     * （{@code sekey} 为空）。
     */
    private static Attempt filemetas(
            BaiduLogin.Credential credential, String sekey, String fsId) {
        Attempt out = new Attempt();
        out.attempted = true;
        out.ua = UA_DLINK;
        out.cookie = accountCookie(credential, sekey);
        String url =
                "https://pan.baidu.com/api/filemetas?dlink=1&fsids="
                        + encodePart("[" + fsId + "]")
                        + "&thumb=0&channel=chunlei&web=1&app_id=250528&clienttype=0";
        AppLog.w(TAG, "filemetas request fs_id=" + fsId);
        Response answer;
        try {
            answer = fetch("GET", url, null, null, UA_MEDIA, REFERER, out.cookie);
        } catch (IOException error) {
            out.error = "访问失败（" + NetdiskShareHttp.describe(error) + "）";
            return out;
        }
        out.http = answer.http;
        AppLog.w(TAG, "filemetas answer http=" + answer.http + " body=" + maskBody(answer.body));
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            out.error = "没有返回可用数据（HTTP " + answer.http + "）";
            return out;
        }
        out.errno = NetdiskShareHttp.optInt(body, "errno", 0);
        out.show = baiduMessage(body);
        if (out.errno != 0) {
            // errno 在顶层，具体原因常在 info[0].errno（例如 31021）。
            JsonArray info = body.has("info") && body.get("info").isJsonArray()
                    ? body.getAsJsonArray("info")
                    : null;
            if (info != null && info.size() > 0 && info.get(0).isJsonObject()) {
                int inner = NetdiskShareHttp.optInt(info.get(0).getAsJsonObject(), "errno", 0);
                if (inner != 0 && inner != out.errno) {
                    out.error = "返回错误（errno=" + out.errno + "，info.errno=" + inner + "）";
                }
            }
            return out;
        }
        String raw = mediaUrl(dlinkOf(body, fsId));
        if (raw.isEmpty()) {
            out.error = "errno=0 但没有给出 dlink";
            return out;
        }
        out.dlink = followDlink(raw, out.cookie);
        AppLog.w(
                TAG,
                "filemetas ok fs_id="
                        + fsId
                        + " dlink host="
                        + hostOf(out.dlink)
                        + (out.dlink.equals(raw) ? "" : "（已跟掉 302）"));
        return out;
    }

    /**
     * 把 {@code d.pcs.baidu.com} 的 302 跟到落地域名，返回最终可播 URL；任何异常都原样返回入参
     * （跟不动也不能把播放挡死，交给播放器自己跟）。
     *
     * <p>只带 {@code Range: bytes=0-0} 探一跳，不读正文（那是整部电影）；Cookie 只发给百度的域名
     * （{@code *.baidu.com} / {@code *.baidupcs.com}），别把账号凭据递给跳出去的第三方。
     */
    private static String followDlink(String dlink, String cookie) {
        String current = text(dlink).trim();
        if (current.isEmpty()) return current;
        for (int hop = 0; hop < 3; hop++) {
            if (!isBaiduDownloadHost(hostOf(current))) return current;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", UA_DLINK);
                conn.setRequestProperty("Range", "bytes=0-0");
                if (!text(cookie).isEmpty()) conn.setRequestProperty("Cookie", cookie.trim());
                int http = conn.getResponseCode();
                String location = text(conn.getHeaderField("Location")).trim();
                if (http >= 300 && http < 400 && !location.isEmpty()) {
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                // 200/206/403/… 都算「落到头了」：403 交给播放器去失败，原因由它记。
                return current;
            } catch (IOException | RuntimeException error) {
                AppLog.w(TAG, "follow dlink hop failed: " + NetdiskShareHttp.describe(error));
                return current;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return current;
    }

    /** 这个主机名是不是百度自己的下载域名（决定能不能把 Cookie 发过去）。 */
    private static boolean isBaiduDownloadHost(String host) {
        String value = text(host).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        return value.equals("baidu.com")
                || value.endsWith(".baidu.com")
                || value.equals("baidupcs.com")
                || value.endsWith(".baidupcs.com");
    }

    // -------------------------------------------------------------- 网页接口（第二条）

    /**
     * 网页端 sharedownload：先用 id 里带的 {@code sign}/{@code timestamp} 打一次（解析分享时抓的，
     * 通常还在有效期内）；失败（常见是 sign 过期或没带）再取一份新的 —— 先按 HTTP 重开分享页（快，
     * 但百度对没有 JS 的客户端只回壳页面），HTTP 取不到签名时再用应用自己的 WebView（真浏览器）把页面
     * 跑一遍取 {@code window.yunData}；新 sign 与已经打过的不同才再打一次。
     *
     * <p>两条取签名的路都没给出可用签名时，失败原因里补一句「WebView 也没拿到 yunData（分享页可能要求
     * 验证）」（见 {@link Attempt#hint}），不让上层只看到一句笼统的「缺少 sign」。
     */
    private static Attempt webAttempt(BaiduLogin.Credential credential, Ident ident) {
        ShareTicket stored = ident.ticket;
        Attempt last = null;
        List<String> tried = new ArrayList<>();
        if (stored.hasSignature()) {
            tried.add(stored.sign);
            last = sharedownload(credential, ident, stored);
            if (last.ok()) return last;
            AppLog.w(
                    TAG,
                    "sharedownload with id ticket failed: "
                            + last.describe()
                            + "（errno="
                            + last.errno
                            + "）");
        }
        if (!stored.surl.isEmpty()) {
            // 现取一份：先走 HTTP（快）。HTTP 拿到签名就按它打 —— 那已经是百度的明确答案，不为一次
            // 确定的失败再多等 WebView 的十几秒；HTTP 拿不到签名才借 WebView 当一回真浏览器
            // （百度对没有 JS 的 HTTP 客户端只回壳页面，页面跑起来 window.yunData 才有 sign/timestamp）。
            ShareTicket http = sharePageTicket(ident);
            boolean httpSigned = http != null && http.hasSignature();
            if (httpSigned && !tried.contains(http.sign)) {
                tried.add(http.sign);
                AppLog.w(TAG, "retry sharedownload with fresh sign from share page");
                last = sharedownload(credential, ident, http);
                if (last.ok()) return last;
                AppLog.w(
                        TAG,
                        "sharedownload with fresh sign failed: "
                                + last.describe()
                                + "（errno="
                                + last.errno
                                + "）");
            }
            if (!httpSigned) {
                ShareTicket webview = webViewTicket(ident);
                if (webview == null || !webview.hasSignature()) {
                    AppLog.w(
                            TAG,
                            "webview ticket unusable for share="
                                    + ident.shareId
                                    + "（"
                                    + WEBVIEW_NO_YUN_DATA
                                    + "）");
                    if (last == null) {
                        last = new Attempt();
                        last.error =
                                "缺少 sign/timestamp（分享页与 id 里都没取到；"
                                        + WEBVIEW_NO_YUN_DATA
                                        + "）";
                    } else {
                        last.hint = WEBVIEW_NO_YUN_DATA;
                    }
                    return last;
                }
                if (tried.contains(webview.sign)) {
                    AppLog.w(TAG, "webview sign already tried, skip sharedownload");
                } else {
                    tried.add(webview.sign);
                    AppLog.w(TAG, "retry sharedownload with fresh sign from webview");
                    last = sharedownload(credential, ident, webview);
                    if (last.ok()) return last;
                    AppLog.w(
                            TAG,
                            "sharedownload with webview sign failed: "
                                    + last.describe()
                                    + "（errno="
                                    + last.errno
                                    + "）");
                }
            }
        }
        if (last == null) {
            last = new Attempt();
            last.error = "缺少 sign/timestamp（分享页与 id 里都没取到）";
        }
        return last;
    }

    /**
     * 打一次 {@code POST /api/sharedownload}。不抛异常：网络失败、errno、没有 dlink 都记进
     * {@link Attempt}，由调用方决定要不要换一份 sign 再打。
     */
    private static Attempt sharedownload(
            BaiduLogin.Credential credential, Ident ident, ShareTicket ticket) {
        Attempt out = new Attempt();
        String url = sharedownloadUrl(ticket.sign, ticket.timestamp);
        String form = sharedownloadForm(ident.uk, ident.shareId, ident.fsId, ident.sekey);
        out.cookie = accountCookie(credential, ident.sekey);
        AppLog.w(
                TAG,
                "sharedownload request sign=***("
                        + ticket.sign.length()
                        + ") timestamp="
                        + ticket.timestamp
                        + " share="
                        + ident.shareId
                        + " uk="
                        + maskId(ident.uk)
                        + " fid="
                        + ident.fsId
                        + " sekey="
                        + (ident.sekey.isEmpty() ? "no" : "yes"));
        Response answer;
        try {
            answer =
                    fetch(
                            "POST",
                            url,
                            form,
                            "application/x-www-form-urlencoded",
                            UA_MEDIA,
                            REFERER,
                            out.cookie);
        } catch (IOException error) {
            out.error = "访问失败（" + NetdiskShareHttp.describe(error) + "）";
            AppLog.w(TAG, "sharedownload failed: " + NetdiskShareHttp.describe(error));
            return out;
        }
        out.http = answer.http;
        // 响应体原样（截断 + dlink/sign 打码）：errno 与 show_msg 全靠它。
        AppLog.w(
                TAG, "sharedownload answer http=" + answer.http + " body=" + maskBody(answer.body));
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            out.error = "没有返回可用数据（HTTP " + answer.http + "）";
            return out;
        }
        out.errno = NetdiskShareHttp.optInt(body, "errno", 0);
        out.show = baiduMessage(body);
        if (out.errno != 0) return out;
        out.dlink = mediaUrl(dlinkOf(body, ident.fsId));
        if (out.dlink.isEmpty()) {
            out.error = "errno=0 但没有给出 dlink";
            return out;
        }
        AppLog.w(TAG, "sharedownload ok dlink host=" + hostOf(out.dlink));
        return out;
    }

    /**
     * 带着 {@code BDCLND} 重开一次分享页，取新的 {@code sign}/{@code timestamp}。失败返回 null（只记
     * 日志）：{@link #webAttempt} 会把 id 里那份当最后一次机会。
     */
    private static ShareTicket sharePageTicket(Ident ident) {
        String surl = ident.ticket.surl;
        if (surl.isEmpty()) return null;
        Jar jar = new Jar();
        if (!ident.sekey.isEmpty()) jar.put("BDCLND", ident.sekey);
        String url = "https://pan.baidu.com/s/" + encodePart(surl);
        try {
            Response answer =
                    request("GET", url, null, null, UA_SHARE, REFERER, jar, MAX_REDIRECTS);
            ShareTicket ticket = sharePageTicket(answer.body, surl);
            AppLog.w(
                    TAG,
                    "share page http="
                            + answer.http
                            + " sign="
                            + (ticket.hasSignature() ? "yes" : "no"));
            return ticket;
        } catch (IOException error) {
            AppLog.w(TAG, "share page failed: " + NetdiskShareHttp.describe(error));
            return null;
        }
    }

    /**
     * 用应用自己的 WebView（真浏览器）重开一次分享页取 {@code sign}/{@code timestamp}：百度对没有 JS 的
     * HTTP 客户端只回 3512 字节的壳页面（没有 {@code yunData}），页面在 WebView 里跑起来之后才有。
     *
     * <p>Cookie 只带 {@code BDCLND}（提取码验过的那份，与 {@link #sharePageTicket(Ident)} 的 HTTP 版同一
     * 口径）；不带账号 Cookie —— 取签名是页面自己的事，不需要登录态。
     *
     * @return 探测到的 ticket（可能没有签名）；分享页开不起来 / 页面里没有 {@code yunData} 时返回 null
     */
    private static ShareTicket webViewTicket(Ident ident) {
        String surl = ident.ticket.surl;
        if (surl.isEmpty()) return null;
        // 带上账号 Cookie：只给 BDCLND 时百度把 WebView 当访客，分享页回的是"需要验证"的壳页面；
        // 有登录态才跟浏览器一致（Boss 的账号就是网页扫码登的）。
        StringBuilder cookie = new StringBuilder();
        BaiduLogin.Credential credential = BaiduLogin.loadSaved();
        if (credential != null && !text(credential.cookie).trim().isEmpty()) {
            cookie.append(text(credential.cookie).trim());
        }
        if (!ident.sekey.isEmpty()) {
            if (cookie.length() > 0) cookie.append("; ");
            cookie.append("BDCLND=").append(ident.sekey);
        }
        return webViewTicket(surl, cookie.toString());
    }

    /**
     * 同上，但由调用方给 {@code surl} 与 Cookie 头（解析分享那边是访客 Cookie 罐，播放这边是 {@code
     * BDCLND}）。
     *
     * <p>{@code surl} 这里是**分享页 URL 里那个 id**（含前导 {@code 1}），因为它用来拼 {@code /s/<id>}；
     * 与列表接口的 {@code shorturl} 差一个前导 {@code 1}，两者别混。
     */
    static ShareTicket webViewTicket(String surl, String cookieHeader) {
        String id = text(surl).trim();
        if (id.isEmpty()) return null;
        String url = "https://pan.baidu.com/s/" + encodePart(id);
        long started = System.currentTimeMillis();
        String raw = WebPageProbe.evaluate(url, cookieHeader, SHARE_PAGE_JS);
        if (raw == null) {
            AppLog.w(
                    TAG,
                    "webview probe failed share="
                            + id
                            + " cost_ms="
                            + (System.currentTimeMillis() - started));
            return null;
        }
        ShareTicket ticket = yunDataTicket(raw, id);
        AppLog.w(
                TAG,
                "webview probe share="
                        + id
                        + " cost_ms="
                        + (System.currentTimeMillis() - started));
        return ticket;
    }

    /**
     * 从 WebView 取回来的 {@code yunData}（{@link #SHARE_PAGE_JS} 的返回值，JSON 文本）里取 {@code sign} /
     * {@code timestamp}。取不到就是空 ticket。
     *
     * <p>日志固定一行、便于上机对账：{@code webview ticket sign=<yes/no> len=<返回长度> ts=<timestamp>}；
     * {@code sign} 只报有没有，不落明文。
     */
    static ShareTicket yunDataTicket(String json, String surl) {
        String raw = text(json).trim();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        String block = start >= 0 && end > start ? raw.substring(start, end + 1) : "";
        String sign = block.isEmpty() ? "" : fieldValue(block, "sign");
        String timestamp = block.isEmpty() ? "" : fieldValue(block, "timestamp");
        ShareTicket ticket = new ShareTicket(surl, sign, timestamp);
        AppLog.w(
                TAG,
                "webview ticket sign="
                        + (ticket.hasSignature() ? "yes" : "no")
                        + " len="
                        + raw.length()
                        + " ts="
                        + (ticket.timestamp.isEmpty() ? "no" : ticket.timestamp));
        return ticket;
    }

    /**
     * 带签名取一次直链：{@code GET /share/list?shareid&uk&fid&sekey&origin=dlna&devuid&…&time&rand}。
     *
     * <p>不抛异常：网络失败记进 {@link Attempt#error}，百度说 errno 就记 errno 与它自己那句话，由调用方
     * 决定要不要换个 uid 再签一次。请求地址与响应体各打一行日志（{@code sekey} / {@code rand} /
     * {@code dlink} 打码，其余参数与百度原话照原样），一次上机就能定案。
     *
     * @param uid 用来签名的**账号** uid（不是分享所有者的 uk）
     * @param skewMs 本机时钟与服务端的偏差（毫秒）；签名里的 {@code time} 用校正后的值
     */
    private static Attempt attempt(
            BaiduLogin.Credential credential, Ident ident, String uid, long skewMs) {
        Attempt out = new Attempt();
        // 签名里的 time 取「服务端此刻」：盒子/电视的时钟偏几分钟，rand 就会被判过期。
        String time = String.valueOf(System.currentTimeMillis() + skewMs);
        String rand =
                sha1Hex(sha1Hex(credential.bduss) + uid + RAND_KEY + time + DEVICE + RAND_TAIL);
        String url = shareListUrl(ident.shareId, ident.uk, ident.fsId, ident.sekey, time, rand);
        out.cookie = accountCookie(credential, ident.sekey);
        AppLog.w(TAG, "list request " + maskUrl(url));
        Response answer;
        try {
            answer = fetch("GET", url, null, null, UA_MEDIA, REFERER, out.cookie);
        } catch (IOException error) {
            out.error =
                    "百度分享解析失败：百度网盘访问失败（"
                            + NetdiskShareHttp.describe(error)
                            + "），请换一条线路";
            AppLog.w(TAG, "list failed: " + NetdiskShareHttp.describe(error));
            return out;
        }
        out.http = answer.http;
        // 响应体原样（截断 + dlink 打码）：errno 之外的那句 show_msg/errmsg 全靠它。
        AppLog.w(TAG, "list answer http=" + answer.http + " body=" + maskBody(answer.body));
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            out.error =
                    "百度分享解析失败：百度网盘没有返回可用数据（HTTP "
                            + answer.http
                            + "），请换一条线路";
            return out;
        }
        out.errno = NetdiskShareHttp.optInt(body, "errno", 0);
        out.show = baiduMessage(body);
        if (out.errno != 0) return out;
        out.dlink = mediaUrl(dlinkOf(body, ident.fsId));
        return out;
    }

    /**
     * 这个 episode id 是不是百度分享坐标（五或六段、第一段 {@code bd}、中间三段数字）。只读判据、不动
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

    // ------------------------------------------------------- 分享页凭据（网页接口）

    /**
     * 分享页里与网页下载接口有关的三样东西：{@code surl}（分享页短 id）、{@code sign}、
     * {@code timestamp}。
     *
     * <p>为什么放进 id：{@code /api/sharedownload} 要的就是这几项，而播放时手上只有 episode id；解析
     * 分享（列目录）那一刻页面正开着，顺手取下来最省一次网络。{@code sign} 会过期，所以播放时失败还会
     * 带着 {@code BDCLND} 再开一次分享页现取（{@link #sharePageTicket(Ident)}）；HTTP 拿不到时再用
     * WebView 现取（{@link #webViewTicket(Ident)}）。
     *
     * <p>只有 {@code surl} 也算一份凭据（{@code sign}/{@code timestamp} 为空）：解析分享时两条取签名的
     * 路都失败的话，播放时还能带着 {@code surl} 再试一次 WebView —— 否则连「去哪一页取」都不知道。
     *
     * <p>id 第 6 段放的是 {@code surl|sign|timestamp} 再百分号编码一次：{@code sign} 里可能有
     * {@code +} / {@code /} / {@code =}，不编码就会撞上 {@code ++} 分隔符；没有签名时是
     * {@code surl||}。
     */
    static final class ShareTicket {
        final String surl;
        final String sign;
        final String timestamp;

        ShareTicket(String surl, String sign, String timestamp) {
            this.surl = text(surl).trim();
            this.sign = text(sign).trim();
            this.timestamp = text(timestamp).trim();
        }

        boolean hasSignature() {
            return !sign.isEmpty() && !timestamp.isEmpty();
        }

        /** id 第 6 段的明文形态（调用方再 {@link #encodePart} 一次）；没有 {@code surl} 时是空串。 */
        String encode() {
            if (surl.isEmpty()) return "";
            return hasSignature() ? surl + "|" + sign + "|" + timestamp : surl + "||";
        }

        /** 解开第 6 段；形状不对返回 null（老 id 没有这一段，调用方按「没有网页凭据」处理）。 */
        static ShareTicket decode(String raw) {
            String value = text(raw).trim();
            if (value.isEmpty()) return null;
            String[] parts = value.split("\\|", 3);
            if (parts.length != 3) return null;
            String surl = parts[0].trim();
            String sign = parts[1].trim();
            String timestamp = parts[2].trim();
            if (!isSurl(surl)) return null;
            // 只有 surl：解析分享时没取到签名，播放时还要靠它再开一次分享页（WebView）。
            if (sign.isEmpty() && timestamp.isEmpty()) return new ShareTicket(surl, "", "");
            if (!isSafeSign(sign) || !isDigits(timestamp, 1, 20)) return null;
            return new ShareTicket(surl, sign, timestamp);
        }
    }

    /**
     * 从分享页 HTML 里取 {@code sign} / {@code timestamp}（顺带把 {@code share_id} / {@code uk} 记成
     * 日志便于对账）。页面里两种形态都认：{@code window.yunData = {...}} 与
     * {@code locals.mset({...})}。
     *
     * @param surl 分享页短 id（原样带进 ticket，以后重开这一页要用）
     */
    static ShareTicket sharePageTicket(String html, String surl) {
        String page = text(html);
        String block = objectBlock(page, "window.yunData");
        if (block.isEmpty()) block = objectBlock(page, "locals.mset");
        if (block.isEmpty()) {
            AppLog.w(TAG, "share page has no yunData/locals.mset, len=" + page.length());
            return new ShareTicket(surl, "", "");
        }
        String sign = fieldValue(block, "sign");
        String timestamp = fieldValue(block, "timestamp");
        String shareId = fieldValue(block, "shareid");
        if (shareId.isEmpty()) shareId = fieldValue(block, "share_id");
        String uk = fieldValue(block, "uk");
        AppLog.w(
                TAG,
                "share page fields sign="
                        + (sign.isEmpty() ? "no" : "yes(" + sign.length() + ")")
                        + " timestamp="
                        + (timestamp.isEmpty() ? "no" : timestamp)
                        + " share_id="
                        + (shareId.isEmpty() ? "no" : "yes")
                        + " uk="
                        + (uk.isEmpty() ? "no" : "yes"));
        return new ShareTicket(surl, sign, timestamp);
    }

    /**
     * 找 {@code marker} 后面那个对象字面量并原样切出来。{@code window.yunData = {...}} 与
     * {@code locals.mset({...})} 两种写法都能过：marker 与 <code>{</code> 之间只允许 {@code =} /
     * {@code (} 与空白（隔着几十个字符还找不到就不认，免得命中别处的同名片段）。
     */
    private static String objectBlock(String html, String marker) {
        int at = html.indexOf(marker);
        if (at < 0) return "";
        int from = at + marker.length();
        int brace = html.indexOf('{', from);
        if (brace < 0 || brace - from > 64) return "";
        String gap = html.substring(from, brace).trim();
        if (!gap.isEmpty() && !"=".equals(gap) && !"(".equals(gap)) return "";
        return balancedObject(html, brace);
    }

    /** 从 {@code start} 那个左花括号起做括号配对（跳过字符串里的括号与转义），返回整段或 ""。 */
    private static String balancedObject(String html, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return html.substring(start, i + 1);
            }
        }
        return "";
    }

    /**
     * 从一个 JS 对象字面量里取标量字段：键的双引号与单引号写法都认；值可以是带引号的字符串（两种引号
     * 都认，按需反转义），也可以是不带引号的数字/布尔。找不到返回 ""。
     */
    private static String fieldValue(String block, String name) {
        if (block.isEmpty() || name.isEmpty()) return "";
        for (char quote : new char[] {'"', '\''}) {
            String needle = quote + name + quote;
            int at = block.indexOf(needle);
            if (at < 0) continue;
            int i = at + needle.length();
            while (i < block.length() && Character.isWhitespace(block.charAt(i))) i++;
            if (i >= block.length()) continue;
            char sep = block.charAt(i);
            if (sep != ':' && sep != '=') continue;
            i++;
            while (i < block.length() && Character.isWhitespace(block.charAt(i))) i++;
            if (i >= block.length()) return "";
            char first = block.charAt(i);
            if (first == '"' || first == '\'') {
                StringBuilder sb = new StringBuilder();
                for (i = i + 1; i < block.length(); i++) {
                    char c = block.charAt(i);
                    if (c == '\\' && i + 1 < block.length()) {
                        char next = block.charAt(++i);
                        if (next == 'n') sb.append('\n');
                        else if (next == 't') sb.append('\t');
                        else sb.append(next);
                        continue;
                    }
                    if (c == first) return sb.toString().trim();
                    sb.append(c);
                }
                return "";
            }
            int start = i;
            while (i < block.length()) {
                char c = block.charAt(i);
                if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
                i++;
            }
            return block.substring(start, i).trim();
        }
        return "";
    }

    // ------------------------------------------------------------------ id 形状

    /** 五 / 六段 {@code ++} 分享坐标。{@code sekey}、{@code ticket} 都已按 id 编码还原。 */
    private static final class Ident {
        final String shareId;
        final String uk;
        final String fsId;
        final String sekey;
        /** 第 6 段（网页凭据）；老 id 没有这一段时是空 ticket。 */
        final ShareTicket ticket;

        Ident(String shareId, String uk, String fsId, String sekey, ShareTicket ticket) {
            this.shareId = shareId;
            this.uk = uk;
            this.fsId = fsId;
            this.sekey = sekey;
            this.ticket = ticket == null ? new ShareTicket("", "", "") : ticket;
        }
    }

    /**
     * @return null 当且仅当不是五段或六段、第一段不是 {@code bd}、或某一段形状与量级对不上
     */
    private static Ident parseIdent(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return null;
        // limit=6：末段（网页凭据）里就算出现 "++" 也整段收在第 6 段，不会被切碎。
        String[] segments = value.split("\\+\\+", 6);
        if (segments.length != 5 && segments.length != 6) return null;
        if (!"bd".equals(segments[0])) return null;
        boolean shaped =
                isDigits(segments[1], 1, 32)
                        && isDigits(segments[2], 1, 32)
                        && isDigits(segments[3], 1, 32)
                        && isSafeIdPart(segments[4])
                        && (segments.length == 5 || isSafeIdPart(segments[5]));
        if (!shaped) {
            // 形状不对：记长度就够定位，不记明文（后几段是分享凭据）。
            AppLog.w(
                    TAG,
                    "skip: "
                            + segments.length
                            + " segments but shapes do not look like a baidu share id, len="
                            + segments[1].length()
                            + "/"
                            + segments[2].length()
                            + "/"
                            + segments[3].length()
                            + "/"
                            + segments[4].length()
                            + (segments.length == 6 ? "/" + segments[5].length() : ""));
            return null;
        }
        // 第 5 段在 id 里是编码形态；这里先还原成 raw randsk（可能与 Flutter 端的 session 值一致）。
        ShareTicket ticket =
                segments.length == 6 ? ShareTicket.decode(percentDecode(segments[5])) : null;
        return new Ident(segments[1], segments[2], segments[3], percentDecode(segments[4]), ticket);
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

    /** 分享页短 id：字母数字与 {@code -} / {@code _}，4–80 位（与 provider 的判据同一套）。 */
    private static boolean isSurl(String value) {
        if (value == null) return false;
        if (value.length() < 4 || value.length() > 80) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum && c != '-' && c != '_') return false;
        }
        return true;
    }

    /** 网页下载接口的 {@code sign}：不透明串，可打印 ASCII、不含分隔符 {@code |}、4–1024 位。 */
    private static boolean isSafeSign(String value) {
        if (value == null) return false;
        if (value.length() < 4 || value.length() > 1024) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '|') return false;
        }
        return true;
    }

    // -------------------------------------------------- 百度接口（主路：网页 sharedownload）

    /**
     * 网页下载接口的地址（包内可见的纯函数，桌面上可以直接核对 query 字节）。参数全部来自分享页
     * {@code yunData}，这里一个都不自己造。
     */
    static String sharedownloadUrl(String sign, String timestamp) {
        StringBuilder url = new StringBuilder("https://pan.baidu.com/api/sharedownload");
        url.append("?sign=").append(encodePart(text(sign).trim()));
        url.append("&timestamp=").append(encodePart(text(timestamp).trim()));
        url.append("&channel=chunlei&web=1&app_id=250528&clienttype=0");
        return url.toString();
    }

    /**
     * 网页下载接口的表单（包内可见的纯函数）。{@code extra.sekey} 用**解过一次码**的 randsk：这是
     * 服务端实际拿去校验的值（query 里的 {@code sekey} 也是这个口径，见 {@link #shareListUrl}）。
     */
    static String sharedownloadForm(String uk, String shareId, String fsId, String rawSekey) {
        String extra = "{\"sekey\":\"" + jsonEscape(percentDecode(rawSekey)) + "\"}";
        StringBuilder form = new StringBuilder();
        form.append("encrypt=0&product=share");
        form.append("&uk=").append(encodePart(uk));
        form.append("&primaryid=").append(encodePart(shareId));
        form.append("&fid_list=").append(encodePart("[" + text(fsId).trim() + "]"));
        form.append("&extra=").append(encodePart(extra));
        return form.toString();
    }

    /** JSON 字符串里的两个必须转义的字符（sekey 是 base64，正常不会中，转义只是兜底）。 */
    private static String jsonEscape(String value) {
        String text = text(value);
        if (text.indexOf('\\') < 0 && text.indexOf('"') < 0) return text;
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------------------------------------------------- 百度接口（兜底：客户端签名 + dlna）

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
        // PANWEB=1 是网页端写/列操作的门票：少了它 api/list 会回 errno -9（2026-11-26 实测）。
        if (!base.isEmpty() && !base.contains("PANWEB=")) base = base + "; PANWEB=1";
        String raw = text(sekey).trim();
        if (raw.isEmpty()) return base;
        return base.isEmpty() ? "BDCLND=" + raw : base + "; BDCLND=" + raw;
    }

    /** 取 uid 的第一入口（盒子上实测能通）：{@code pan.baidu.com/api/gettemplatevariable}。 */
    private static final String UID_TEMPLATE_URL =
            "https://pan.baidu.com/api/gettemplatevariable?clienttype=0&app_id=250528&web=1&fields=";

    /** 第二入口：{@code pan.baidu.com/rest/2.0/xpan/nas?method=uinfo}。 */
    private static final String UID_UINFO_URL =
            "https://pan.baidu.com/rest/2.0/xpan/nas?method=uinfo&clienttype=0&app_id=250528&web=1";

    /** 最后兜底：{@code mbd.baidu.com/userx/v1/info/get}（盒子上连不上，只当兜底）。 */
    private static final String UID_MBD_URL =
            "https://mbd.baidu.com/userx/v1/info/get?appname=baiduboxapp&fields=";

    /** mbd 那个入口要的字段串（实测值，一个字节都别动）。 */
    private static final String UID_MBD_FIELDS =
            "[\"bg_image\",\"member\",\"uid\",\"avatar\",\"avatar_member\"]"
                    + "&client=&clientfrom=&lang=zh-cn&tpl=&ttt=";

    /**
     * 取账号 uid（**兜底**那条客户端签名要的是账号 uid；网页接口不需要它，所以只在兜底时才走这里）。
     * 与分享坐标里的 uk 不同也无所谓，签名口径不变。
     *
     * <p>三入口按顺序试：{@code pan.baidu.com/api/gettemplatevariable} →
     * {@code pan.baidu.com/rest/2.0/xpan/nas?method=uinfo} → {@code mbd.baidu.com/userx/v1/info/get}。
     * 盒子（Android 11）上实测 {@code mbd.baidu.com} 8 秒超时，先试它会让整条百度线路解析失败；pan 域
     * 下这两个入口走的是已经打通的链路。三个都失败时，失败原因里把每个入口各自怎么了写清（{@link
     * UidProbe#describe()}），不只说一句"访问失败"。
     */
    private static UidResult uid(BaiduLogin.Credential credential) {
        String cookie = NetdiskShareHttp.text(credential == null ? null : credential.cookie).trim();
        UidProbe probe = new UidProbe();
        UidResult result =
                uidFromJson(
                        "pan.baidu.com gettemplatevariable",
                        UID_TEMPLATE_URL + encodePart("[\"uk\"]"),
                        UA_SHARE,
                        REFERER,
                        cookie,
                        probe);
        if (result == null) {
            result =
                    uidFromJson(
                            "pan.baidu.com xpan uinfo",
                            UID_UINFO_URL,
                            UA_SHARE,
                            REFERER,
                            cookie,
                            probe);
        }
        if (result == null) {
            result =
                    uidFromJson(
                            "mbd.baidu.com info",
                            UID_MBD_URL + encodePart(UID_MBD_FIELDS),
                            UA_MEDIA_NO_TAIL,
                            "",
                            cookie,
                            probe);
        }
        if (result != null) {
            AppLog.w(TAG, "uid ok via " + result.source);
            return result;
        }
        String detail = probe.describe();
        String reason =
                probe.answered == 0
                        ? "百度账号信息接口都连不上（" + detail + "）"
                        : "百度账号信息接口都没给出 uid（"
                                + detail
                                + "），可能登录已失效，请在网盘中心重新粘贴 Cookie";
        AppLog.w(TAG, "uid failed: " + reason);
        return UidResult.failed(reason);
    }

    /**
     * 一个入口：请求 → 认 {@code errno} → 找 uid。失败只记进 {@code probe} 并返回 null，由
     * {@link #uid} 继续试下一个入口。
     */
    private static UidResult uidFromJson(
            String label,
            String url,
            String userAgent,
            String referer,
            String cookie,
            UidProbe probe) {
        return uidFromJson(
                label,
                url,
                userAgent,
                referer,
                cookie,
                probe,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS);
    }

    /** 同上，自带超时：8001 之后补问 mbd 的那次用短超时，别为诊断拖住失败提示。 */
    private static UidResult uidFromJson(
            String label,
            String url,
            String userAgent,
            String referer,
            String cookie,
            UidProbe probe,
            int connectMs,
            int readMs) {
        Response answer;
        try {
            answer = fetch("GET", url, null, null, userAgent, referer, cookie, connectMs, readMs);
        } catch (IOException error) {
            probe.fail(label + " 连不上（" + NetdiskShareHttp.describe(error) + "）");
            return null;
        }
        // 接口真的回了话（哪怕是错误页）就不再算"连不上"。
        probe.answered++;
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        if (body == null) {
            probe.fail(label + " 返回的不是 JSON（HTTP " + answer.http + "）");
            return null;
        }
        // 正常说 errno=0；没有 errno 的部署也认，只要真拿到 uid。
        if (body.has("errno") && NetdiskShareHttp.optInt(body, "errno", -1) != 0) {
            probe.fail(label + " errno=" + NetdiskShareHttp.optInt(body, "errno", -1));
            return null;
        }
        String uid = uidOfAny(body);
        if (uid.isEmpty()) {
            probe.fail(label + " 没给出 uid");
            return null;
        }
        return UidResult.ok(uid, label, answer.skewMs());
    }

    /**
     * 从各家返回里找账号 uid：顶层 / {@code data} 里 / {@code data.fields} 里 / {@code result[]} 的某
     * 一行里，键叫 {@code uid} 或 {@code uk} 都认，全都不中就返回 ""。
     *
     * <p>两个容易踩的点，都按"签名要什么"定：
     * <ul>
     *   <li>{@code uid} 优先于 {@code uk}。签名要的是**账号 uid**；{@code uk} 是网盘侧的 id，两者通常
     *       同值，但不同值时错的那个一定签不过；
     *   <li>{@code result[]} 里见过 {@code {"key":"uk","value":"…"}} 这种键值对形状（
     *       {@code /api/gettemplatevariable} 就是）。只认 {@code uk}/{@code uid} 这两个 key，别的行
     *       （模板变量、头像等）一律跳过；形状不认识就返回 ""，由调用方继续试下一个入口。
     * </ul>
     */
    private static String uidOfAny(JsonObject body) {
        if (body == null) return "";
        String direct = valueOf(body, "uid");
        if (direct.isEmpty()) direct = valueOf(body, "uk");
        if (!direct.isEmpty()) return direct;
        JsonElement data = body.get("data");
        if (data != null && data.isJsonObject()) {
            JsonObject inner = data.getAsJsonObject();
            String nested = valueOf(inner, "uid");
            if (nested.isEmpty()) nested = valueOf(inner, "uk");
            if (!nested.isEmpty()) return nested;
            JsonElement fields = inner.get("fields");
            if (fields != null && fields.isJsonObject()) {
                String field = valueOf(fields.getAsJsonObject(), "uid");
                if (field.isEmpty()) field = valueOf(fields.getAsJsonObject(), "uk");
                if (!field.isEmpty()) return field;
            }
        }
        JsonElement result = body.get("result");
        if (result != null && result.isJsonObject()) {
            JsonObject inner = result.getAsJsonObject();
            String nested = valueOf(inner, "uid");
            if (nested.isEmpty()) nested = valueOf(inner, "uk");
            if (!nested.isEmpty()) return nested;
        }
        if (result != null && result.isJsonArray()) {
            for (JsonElement item : result.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject row = item.getAsJsonObject();
                String direct2 = valueOf(row, "uid");
                if (direct2.isEmpty()) direct2 = valueOf(row, "uk");
                if (!direct2.isEmpty()) return direct2;
                // {"key":"uk","value":"…"} 这类键值对行：只认 uk / uid 两个 key。
                String key = NetdiskShareHttp.optString(row, "key").trim().toLowerCase(Locale.ROOT);
                if (!"uk".equals(key) && !"uid".equals(key)) continue;
                String value = NetdiskShareHttp.optString(row, "value").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    /** 一个对象上的标量字符串值：数字与字符串都认（百度有的部署把 id 写成数字）。 */
    private static String valueOf(JsonObject obj, String key) {
        if (obj == null) return "";
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        return element.getAsString().trim();
    }

    /** 三个入口的探查记录：{@code answered} 数"接口真的回了话"的个数，失败原因按试的顺序记。 */
    private static final class UidProbe {
        int answered;
        private final List<String> failures = new ArrayList<>();

        void fail(String reason) {
            AppLog.w(TAG, "uid entry failed: " + reason);
            failures.add(reason);
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            for (String failure : failures) {
                if (sb.length() > 0) sb.append("；");
                sb.append(failure);
            }
            return sb.toString();
        }
    }

    /** 取 uid 的结果：成功时 {@code uid} 非空、{@code source} 是入口名；失败时 {@code reason} 已写清三个入口各自怎么了。 */
    private static final class UidResult {
        final String uid;
        final String source;
        final String reason;
        /** 这一个入口的响应头 {@code Date} 与本机时钟的差（毫秒）；拿不到就是 0。 */
        final long skewMs;

        private UidResult(String uid, String source, String reason, long skewMs) {
            this.uid = uid;
            this.source = source;
            this.reason = reason;
            this.skewMs = skewMs;
        }

        static UidResult ok(String uid, String source, long skewMs) {
            return new UidResult(uid, source, "", skewMs);
        }

        static UidResult failed(String reason) {
            return new UidResult("", "", reason, 0L);
        }
    }

    /**
     * 只走 {@code mbd.baidu.com/userx/v1/info/get} 这一个入口（Flutter 端签名用的就是它的 uid）。
     *
     * <p>只在 {@code /share/list} 回 8001 之后才调：盒子上这个域 8 秒超时，平时先试它会白白拖慢解析。
     * 拿不到 uid 不算错误，调用方按"没有第二个 uid 可比"处理。
     */
    private static UidResult uidFromMbd(BaiduLogin.Credential credential) {
        String cookie = NetdiskShareHttp.text(credential == null ? null : credential.cookie).trim();
        UidProbe probe = new UidProbe();
        UidResult result =
                uidFromJson(
                        "mbd.baidu.com info",
                        UID_MBD_URL + encodePart(UID_MBD_FIELDS),
                        UA_MEDIA_NO_TAIL,
                        "",
                        cookie,
                        probe,
                        MBD_PROBE_TIMEOUT_MS,
                        MBD_PROBE_TIMEOUT_MS);
        if (result != null) return result;
        String detail = probe.describe();
        return UidResult.failed(detail.isEmpty() ? "mbd 入口没有回话" : detail);
    }

    /**
     * 三条都失败时给用户看的那句话：把「转存到自己网盘」（走了才写）、网页接口、兜底各自的原因一起写出来
     * （各含 errno 与百度原话），一眼能分清没授权、风控、登录失效还是分享没了。分享失效码优先（那时
     * errno 的翻译比链路描述有用）。
     */
    private static String combinedReason(Attempt save, Attempt web, Attempt legacy) {
        if (isShareGoneErrno(save.errno)
                || isShareGoneErrno(web.errno)
                || isShareGoneErrno(legacy.errno)) {
            return SHARE_GONE;
        }
        StringBuilder sb = new StringBuilder("百度分享解析失败：");
        if (save != null && save.attempted) {
            sb.append("转存到自己网盘").append(save.describe()).append("；");
        }
        sb.append("网页接口").append(web.describe());
        sb.append("；旧接口（dlna）").append(legacy.describe());
        sb.append("，请换一条线路");
        return sb.toString();
    }

    /** 分享已取消 / 已过期 / 被删的 errno（与 Flutter 端 {@code _checkBaidu} 同一组）。 */
    private static boolean isShareGoneErrno(int errno) {
        return errno == -7 || errno == -9 || errno == -21 || errno == -144 || errno == -145;
    }

    /** 常见「登录态不对」的 errno：-6 未登录、110 登录态失效；这两种多给一句重新登录的提示。 */
    private static boolean isAuthErrno(int errno) {
        return errno == -6 || errno == 110;
    }

    /** 百度响应里它自己那句人话：{@code show_msg} 优先，其次 {@code errmsg}；都没有返回 ""。 */
    private static String baiduMessage(JsonObject body) {
        if (body == null) return "";
        String show = NetdiskShareHttp.optString(body, "show_msg").trim();
        if (show.isEmpty()) show = NetdiskShareHttp.optString(body, "errmsg").trim();
        if (show.isEmpty()) return "";
        String oneLine = show.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) : oneLine;
    }

    /** 打码一个 id：只留前三后二，够在日志里对比两次是不是同一个，不落整串账号 id。 */
    private static String maskId(String value) {
        String text = text(value).trim();
        if (text.length() <= 6) return "***";
        return text.substring(0, 3) + "***" + text.substring(text.length() - 2);
    }

    /** 打码请求地址里的两项凭据：{@code sekey}（提取码换来的 randsk）与 {@code rand}（签名）。 */
    private static String maskUrl(String url) {
        return maskQueryValue(maskQueryValue(url, "sekey"), "rand");
    }

    private static String maskQueryValue(String url, String name) {
        String value = text(url);
        int at = value.indexOf("?" + name + "=");
        if (at < 0) at = value.indexOf("&" + name + "=");
        if (at < 0) return value;
        int start = at + name.length() + 2;
        int end = value.indexOf('&', start);
        if (end < 0) end = value.length();
        return value.substring(0, start)
                + "***("
                + (end - start)
                + ")"
                + value.substring(end);
    }

    /**
     * 响应体打码：{@code dlink} 是签名直链、{@code sign} 是网页下载接口的签名，两个都换成
     * {@code ***}；其余原样（截断到 400 字）。
     */
    private static String maskBody(String body) {
        String value = text(body).replace('\n', ' ').replace('\r', ' ');
        value = value.replaceAll("\"dlink\"\\s*:\\s*\"[^\"]*\"", "\"dlink\":\"***\"");
        value = value.replaceAll("\"sign\"\\s*:\\s*\"[^\"]*\"", "\"sign\":\"***\"");
        return value.length() > 400 ? value.substring(0, 400) + "…" : value;
    }

    /** {@code list[]} 里 {@code fs_id} 对得上的那条的 {@code dlink}；对不上返回 ""。 */
    /**
     * 从响应里挑这一集的 {@code dlink}。
     *
     * <p>两个键都认：{@code /api/filemetas} 回的是 {@code info[]}，{@code share/list} 与
     * {@code sharedownload} 回的是 {@code list[]}（2026-11-26 盒子实测：只认 {@code list} 时，
     * {@code filemetas} 明明 {@code errno 0} 且有 dlink，却被判成「没有给出 dlink」）。
     */
    private static String dlinkOf(JsonObject body, String fsId) {
        String fromInfo = dlinkIn(body, "info", fsId);
        if (!fromInfo.isEmpty()) return fromInfo;
        return dlinkIn(body, "list", fsId);
    }

    /** 从指定的那个数组键里挑 {@code dlink}：{@code fs_id} 对得上，或唯一一行没带 {@code fs_id}。 */
    private static String dlinkIn(JsonObject body, String key, String fsId) {
        JsonElement list = body == null ? null : body.get(key);
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
     *
     * <p>UA 由调用方给：网页接口 / dlna 那两条用 {@link #UA_MEDIA}（实测值），开放平台
     * {@code xpan} 的直链用 {@link #UA_DLINK}（百度文档对 {@code dlink} 的要求）。
     */
    private static Cms.Episode episode(String lineName, String url, String cookie, String userAgent) {
        Cms.Episode episode = new Cms.Episode();
        String name = text(lineName).trim();
        if (name.isEmpty()) name = "百度分享";
        episode.name = name;
        episode.url = url;
        episode.headers.put("Cookie", cookie);
        episode.headers.put("User-Agent", text(userAgent).trim().isEmpty() ? UA_MEDIA : userAgent);
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
        /** 服务端 {@code Date} 头原样，用来校正签名里的 {@code time}；拿不到是 ""。 */
        final String date;

        Response(int http, String body, List<String> setCookies, String location, String date) {
            this.http = http;
            this.body = body;
            this.setCookies = setCookies;
            this.location = location;
            this.date = date;
        }

        boolean isRedirect() {
            return http >= 300 && http < 400 && http != 304 && !location.isEmpty();
        }

        /** 服务端时钟与本机的差（毫秒）；没有 {@code Date} 头或解析不了就是 0。 */
        long skewMs() {
            long server = serverTimeMillis(date);
            return server <= 0 ? 0L : server - System.currentTimeMillis();
        }
    }

    /**
     * 解析 HTTP {@code Date} 头（RFC 1123，如 {@code Wed, 26 Nov 2026 12:00:00 GMT}）成毫秒。
     *
     * <p>为什么需要它：签名里的 {@code time} 要和百度那边对得上，而电视盒子的系统时钟经常偏几十秒到
     * 几分钟（能过 TLS 却过不了签名）。用服务端给的时刻签名，盒子时钟不准也不会把 8001 招来。
     */
    static long serverTimeMillis(String date) {
        String value = text(date).trim();
        if (value.isEmpty()) return 0L;
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            java.util.Date parsed = format.parse(value);
            return parsed == null ? 0L : parsed.getTime();
        } catch (java.text.ParseException | RuntimeException ignored) {
            return 0L;
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
        return fetch(
                method,
                url,
                body,
                contentType,
                userAgent,
                referer,
                cookie,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS);
    }

    /** 同上，但自带超时；诊断性的那几次网络用短超时，别把失败提示拖到十秒。 */
    static Response fetch(
            String method,
            String url,
            String body,
            String contentType,
            String userAgent,
            String referer,
            String cookie,
            int connectMs,
            int readMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
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
                    http,
                    reply,
                    setCookies(conn),
                    text(conn.getHeaderField("Location")).trim(),
                    text(conn.getHeaderField("Date")).trim());
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
