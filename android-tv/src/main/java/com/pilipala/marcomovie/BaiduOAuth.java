package com.pilipala.marcomovie;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度开放平台 OAuth：拿 {@code access_token}，让「取自己网盘里那份副本的直链」不再需要网页签名。
 *
 * <p>为什么走这条路：{@code /api/filemetas} 要分享页 {@code yunData} 里的 {@code sign}（网页才给，
 * {@code gettemplatevariable} 不给），而开放平台的 {@code xpan/multimedia?method=filemetas&dlink=1}
 * 只认 {@code access_token}。转存与删除本来就已经通了（都吃账号 Cookie），缺的正是这一步签名，
 * 所以「先转存到 {@code /MarcoMovie}、再取自己文件的直链」这条主路只差一个令牌。
 *
 * <p>三步，一步都不自己编签名：
 * <ol>
 *   <li><b>授权码</b>：{@code GET https://openapi.baidu.com/oauth/2.0/authorize?response_type=code&
 *       client_id=266719&redirect_uri=oob&scope=basic,netdisk&display=tv}，用应用自己的 WebView（与
 *       {@link NetdiskWebLogin} 同一套：桌面 UA、JS、DOM storage、第三方 Cookie 全开）打开；账号已经
 *       在同一个 {@link CookieManager} 里登录过（网盘中心的「扫码登录」写的就是这份 Cookie），所以这一页
 *       通常只需要点一次「授权」。授权完百度把页面跳到 {@code oob?code=<code>}，{@code code} 从 URL 或
 *       页面上的「授权码」文字里取（{@link #parseCode(String)}）；
 *   <li><b>换令牌</b>：{@code POST https://openapi.baidu.com/oauth/2.0/token}（{@code grant_type=
 *       authorization_code&code=…&client_id=266719&redirect_uri=oob&client_secret=…}）；
 *       换到 {@code access_token} / {@code refresh_token} / {@code expires_in}；
 *   <li><b>续期</b>：{@code grant_type=refresh_token} 刷新，过期的令牌没人再发一次授权码。
 * </ol>
 *
 * <p>令牌落 {@code files/netdisk/baidu_token.json}（{@code provider} / {@code version} /
 * {@code access_token} / {@code refresh_token} / {@code expires_at} / {@code scope} /
 * {@code validatedAt}），原子写（tmp + rename），与 {@link BaiduLogin} 的凭据文件同一套习惯。
 *
 * <p>日志（tag {@code BaiduOAuth}）只记长度与 errno：{@code access_token} / {@code refresh_token} /
 * {@code code} 都不落明文，百度自己那句 {@code error} / {@code error_description} 原样记下来
 * （{@code invalid_client} 时就是靠它定案）。
 *
 * <p>线程：{@link #accessToken()} / {@link #refreshAccessToken()} / {@link #exchangeCode} 会打网络，
 * 必须在后台线程调用；{@link #showAuthorize} 必须在 UI 线程调用。盒子是 Android 11（API 30），
 * 本类只用 API 21+ 的接口，{@code shouldOverrideUrlLoading(WebView, WebResourceRequest)} 是 24+，
 * 按版本分开装（与 {@link NetdiskWebLogin} 的 {@code LoginClient26} 同一套写法）。
 */
final class BaiduOAuth {

    private static final String TAG = "BaiduOAuth";

    /** 应用（client）id，取自 Boss 现有的那个应用；换应用只改这里。 */
    static final String CLIENT_ID = "266719";

    /**
     * 应用控制台里那一份「公开发布的 secret」。任务卡给的第一个候选值；换应用时只改这一处，
     * 不往码里塞别的密钥。换不到令牌时百度原话（{@code invalid_client} 等）进日志与失败原因。
     */
    private static final String CLIENT_SECRET = "b00YPedC0xK9Q8vVrKzq0xAqX7r8FkZf";

    /** OOB（out-of-band）回调：百度把 code 直接回给授权页，不经过我们的服务器。 */
    private static final String REDIRECT_URI = "oob";

    /** 取直链只需要基础权限 + 网盘权限。 */
    private static final String SCOPE = "basic,netdisk";

    /** 授权页地址（{@code display=tv} 让百度给电视一个能遥控器点的页面）。 */
    static final String AUTHORIZE_URL =
            "https://openapi.baidu.com/oauth/2.0/authorize"
                    + "?response_type=code&client_id="
                    + CLIENT_ID
                    + "&redirect_uri="
                    + REDIRECT_URI
                    + "&scope="
                    + SCOPE
                    + "&display=tv";

    private static final String TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token";

    /** 换令牌 / 刷新都打 openapi，UA 用分享认的那串客户端名（实测值，与 {@link BaiduShareResolver} 同）。 */
    private static final String UA = BaiduShareResolver.UA_SHARE;

    /** 提前一分钟算过期：别把正好到点的令牌递出去。 */
    private static final long EXPIRY_SKEW_MS = 60_000L;

    /** 授权页最多等 4 分钟（登录 + 点授权 + 页面跳转都算在里面）。 */
    private static final long AUTHORIZE_TIMEOUT_MS = 4 * 60 * 1000L;

    /** 每 1.2 秒看一次页面：够快，又不至于让 WebView 一直忙。 */
    private static final long POLL_INTERVAL_MS = 1200L;

    /** 成功后停留一下再关，让 Boss 看到「已授权」。 */
    private static final long SUCCESS_DISMISS_MS = 1200L;

    /**
     * 桌面 Chrome 的 UA：与网盘中心网页登录同一串。百度对 WebView 的默认 UA 会走「打开 App」那一套，
     * 电视上就没有可点的授权按钮了。
     */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    /**
     * 授权页取值脚本：把当前地址与页面正文一起交回来，Java 侧用 {@link #parseCode(String)} 找 code。
     * 百度 OOB 那次跳转有时不改地址栏（页面直接显示「授权码」），所以正文也要看。
     */
    private static final String CODE_JS =
            "(function(){try{"
                    + "var u=String(location.href||'');"
                    + "var t=document.body?String(document.body.innerText||''):'';"
                    + "return u+' | '+t;"
                    + "}catch(e){return '';}})()";

    /** {@code ?code=…} / {@code &code=…} / {@code #code=…} / 页面里的 {@code code=…}。 */
    private static final Pattern CODE_IN_QUERY =
            Pattern.compile("[?&#\\s\"']code=([A-Za-z0-9._~-]{8,512})");

    /** 中文页面上的「授权码 xxxx」。 */
    private static final Pattern CODE_AFTER_LABEL =
            Pattern.compile("授权码[^A-Za-z0-9]{0,24}([A-Za-z0-9._~-]{8,512})");

    /** 英文页面上的 {@code code: xxxx} / {@code code=xxxx}。 */
    private static final Pattern CODE_AFTER_KEY =
            Pattern.compile("(?i)\\bcode\\s*[:：=]\\s*([A-Za-z0-9._~-]{8,512})");

    /** 换令牌 / 刷新令牌串成一条的活（一次只跑一个，别把授权页卡住）。 */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static final int MAX_BODY_CHARS = 400;

    private BaiduOAuth() {}

    // ------------------------------------------------------------------ 令牌

    /**
     * 一份令牌。{@code expiresAt} 是毫秒时间戳（本机时钟 + {@code expires_in}），不是百度原样的相对秒数：
     * 存盘之后跨进程、跨重启都还知道什么时候过期。
     */
    static final class Token {
        final String accessToken;
        final String refreshToken;
        final String scope;
        final long expiresAt;
        final String validatedAt;

        Token(
                String accessToken,
                String refreshToken,
                String scope,
                long expiresAt,
                String validatedAt) {
            this.accessToken = text(accessToken).trim();
            this.refreshToken = text(refreshToken).trim();
            this.scope = text(scope).trim();
            this.expiresAt = expiresAt;
            this.validatedAt = text(validatedAt).trim();
        }

        /** 还在有效期内（留一分钟余量）；过期了就先刷新。 */
        boolean valid() {
            return !accessToken.isEmpty()
                    && expiresAt - System.currentTimeMillis() > EXPIRY_SKEW_MS;
        }

        /** 能不能靠 refresh_token 续期（不复述 access_token）。 */
        boolean refreshable() {
            return !refreshToken.isEmpty();
        }

        String toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("provider", "baidu-openapi");
            out.addProperty("version", 1);
            out.addProperty("access_token", accessToken);
            out.addProperty("refresh_token", refreshToken);
            out.addProperty("scope", scope);
            out.addProperty("expires_at", expiresAt);
            out.addProperty("validatedAt", validatedAt);
            return out.toString();
        }

        /** 缺 access_token 或 provider 不对时返回 null。 */
        static Token parse(String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                JsonElement root = JsonParser.parseString(json);
                if (root == null || !root.isJsonObject()) return null;
                JsonObject obj = root.getAsJsonObject();
                if (!"baidu-openapi".equals(NetdiskShareHttp.optString(obj, "provider").trim())) {
                    return null;
                }
                String access = NetdiskShareHttp.optString(obj, "access_token").trim();
                if (access.isEmpty()) return null;
                return new Token(
                        access,
                        NetdiskShareHttp.optString(obj, "refresh_token"),
                        NetdiskShareHttp.optString(obj, "scope"),
                        NetdiskShareHttp.optLong(obj, "expires_at", 0L),
                        NetdiskShareHttp.optString(obj, "validatedAt"));
            } catch (RuntimeException error) {
                return null;
            }
        }
    }

    static File tokenFile(File filesDir) {
        if (filesDir == null) return null;
        return new File(new File(filesDir, "netdisk"), "baidu_token.json");
    }

    /** 读 {@code files/netdisk/baidu_token.json}；没有或读不出来返回 null。 */
    static Token load(File filesDir) {
        File file = tokenFile(filesDir);
        if (file == null || !file.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Token.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 没有可做的事
                }
            }
        }
    }

    /** 原子写（tmp + rename），半截文件不会被当成令牌读进来。 */
    static boolean save(File filesDir, Token token) {
        File file = tokenFile(filesDir);
        if (file == null || token == null) return false;
        File parent = file.getParentFile();
        if (parent == null) return false;
        if (!parent.isDirectory() && !parent.mkdirs()) return false;
        File tmp = new File(parent, file.getName() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(token.toJson().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return tmp.renameTo(file);
            }
            return true;
        } catch (IOException error) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 没有可做的事
                }
            }
            if (!tmp.equals(file) && tmp.exists() && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    static Token loadSaved() {
        return load(BaiduLogin.filesDir());
    }

    static boolean saveSaved(Token token) {
        return save(BaiduLogin.filesDir(), token);
    }

    /**
     * 给播放侧用的令牌串：有效就直接给，过期就先用 {@code refresh_token} 续一次；没有令牌、续不上
     * 返回 ""（调用方按「没有开放平台授权」处理，改走网页直链）。
     *
     * <p>打网络（最多一次刷新），必须在后台线程调用。
     */
    static String accessToken() {
        Token token = loadSaved();
        if (token == null) return "";
        if (token.valid()) return token.accessToken;
        if (!token.refreshable()) {
            AppLog.w(TAG, "token expired and not refreshable");
            return "";
        }
        return refreshAccessToken();
    }

    /**
     * 强制用存下来的 {@code refresh_token} 换一次新的 {@code access_token} 并落盘。
     *
     * @return 新的 access_token；没有 refresh_token、网络失败或百度拒绝时返回 ""（原因进日志）
     */
    static String refreshAccessToken() {
        Token token = loadSaved();
        if (token == null || !token.refreshable()) {
            AppLog.w(TAG, "refresh skipped: no refresh_token");
            return "";
        }
        try {
            Token fresh = refresh(token.refreshToken, token.scope);
            if (!saveSaved(fresh)) {
                AppLog.w(TAG, "refresh ok but save failed");
                return fresh.accessToken;
            }
            AppLog.w(TAG, "token refreshed expires_at=" + fresh.expiresAt);
            return fresh.accessToken;
        } catch (IOException error) {
            AppLog.w(TAG, "refresh failed: " + describe(error));
        }
        return "";
    }

    /** 用刷新令牌换一份新令牌。 */
    private static Token refresh(String refreshToken, String scope) throws IOException {
        String query =
                "grant_type=refresh_token&refresh_token="
                        + BaiduShareResolver.encodePart(refreshToken)
                        + "&client_id="
                        + CLIENT_ID
                        + "&client_secret="
                        + CLIENT_SECRET;
        return requestToken(query, scope);
    }

    /**
     * 授权码换令牌。
     *
     * @param code 授权页给的那串 code
     * @return 落盘后的令牌（调用方负责 {@link #saveSaved}，本方法只换不存）
     * @throws IOException 网络失败或百度拒绝；消息里带百度原话
     */
    static Token exchangeCode(String code) throws IOException {
        String value = text(code).trim();
        if (value.isEmpty()) throw new IOException("授权码为空");
        AppLog.w(TAG, "exchange code len=" + value.length());
        String query =
                "grant_type=authorization_code&code="
                        + BaiduShareResolver.encodePart(value)
                        + "&client_id="
                        + CLIENT_ID
                        + "&redirect_uri="
                        + REDIRECT_URI
                        + "&client_secret="
                        + CLIENT_SECRET;
        return requestToken(query, "");
    }

    /**
     * 打一次 {@code /oauth/2.0/token}。成功时把 {@code expires_at} 换算成本机毫秒时间戳。
     *
     * <p>失败把百度原话（{@code error} + {@code error_description}）记进日志再抛 —— {@code invalid_client}
     * 就是这个 client 的 secret 不对，一眼能定案，不需要猜。
     */
    private static Token requestToken(String query, String scope) throws IOException {
        String url = TOKEN_URL + "?" + query;
        BaiduShareResolver.Response answer;
        try {
            answer = BaiduShareResolver.fetch("POST", url, null, null, UA, "https://pan.baidu.com/", "");
        } catch (IOException error) {
            throw new IOException(
                    "连接百度开放平台失败（" + NetdiskShareHttp.describe(error) + "）", error);
        }
        JsonObject body = NetdiskShareHttp.parseObjectOrNull(answer.body);
        String access = body == null ? "" : NetdiskShareHttp.optString(body, "access_token").trim();
        if (access.isEmpty()) {
            String error = body == null ? "" : NetdiskShareHttp.optString(body, "error").trim();
            String detail =
                    body == null ? "" : NetdiskShareHttp.optString(body, "error_description").trim();
            AppLog.w(
                    TAG,
                    "token failed http="
                            + answer.http
                            + " error="
                            + (error.isEmpty() ? "-" : error)
                            + " desc="
                            + (detail.isEmpty() ? "-" : detail)
                            + " body="
                            + maskTokenBody(answer.body));
            StringBuilder reason = new StringBuilder("百度开放平台没给出 access_token（");
            if (error.isEmpty()) {
                reason.append("HTTP ").append(answer.http);
            } else {
                reason.append(error);
                if (!detail.isEmpty()) reason.append("：").append(detail);
            }
            reason.append("）");
            throw new IOException(reason.toString());
        }
        long expiresIn = NetdiskShareHttp.optLong(body, "expires_in", 0L);
        if (expiresIn <= 0) expiresIn = 3600L;
        String refreshToken = NetdiskShareHttp.optString(body, "refresh_token").trim();
        String grantedScope = NetdiskShareHttp.optString(body, "scope").trim();
        if (grantedScope.isEmpty()) grantedScope = text(scope).trim();
        Token token =
                new Token(
                        access,
                        refreshToken,
                        grantedScope,
                        System.currentTimeMillis() + expiresIn * 1000L,
                        BaiduLogin.isoNow());
        AppLog.w(
                TAG,
                "token ok expires_in="
                        + expiresIn
                        + " scope="
                        + (grantedScope.isEmpty() ? "-" : grantedScope)
                        + " refresh="
                        + (refreshToken.isEmpty() ? "no" : "yes"));
        return token;
    }

    // ------------------------------------------------------------------ 授权码

    /**
     * 从 URL 或页面文本里取授权码（纯函数，桌面上可以直接核对）。
     *
     * <p>认三种写法：{@code oob?code=<code>}（百度 OOB 跳转）、页面上的「授权码 <code>」、
     * 英文页面的 {@code code: <code>}。取不到返回 ""，调用方继续等下一次轮询。
     *
     * <p>{@code code=} 后面只收 {@code A-Za-z0-9._~-}：百度的 code 是长 hex 串，这样不会把后面
     * 跟着的中文/标点吞进来。
     */
    static String parseCode(String value) {
        String raw = text(value);
        if (raw.isEmpty()) return "";
        Matcher query = CODE_IN_QUERY.matcher(raw);
        if (query.find()) return query.group(1);
        Matcher labelled = CODE_AFTER_LABEL.matcher(raw);
        if (labelled.find()) return labelled.group(1);
        Matcher keyed = CODE_AFTER_KEY.matcher(raw);
        if (keyed.find()) return keyed.group(1);
        return "";
    }

    // ------------------------------------------------------------------ UI：授权页

    /**
     * 打开百度开放平台的授权页，拿到 code 后换令牌、落盘、回调 {@code done}（网盘中心用它刷新牌面）。
     *
     * <p>Cookie 不用另带：授权页与网盘中心网页登录用的是同一个 {@link CookieManager}，账号已经在那里
     * 登录过；百度看到登录态就直接问「是否授权」，不然会显示账号密码表单（电视上没法打字，所以先扫
     * 码登录百度再做这一步）。
     *
     * <p>code 的四个来源都认：{@code shouldOverrideUrlLoading}（{@code oob?code=…} 这种不认识的 scheme）、
     * 页面开始加载的地址、加载失败时的失败地址（有些 WebView 把 OOB 当成加载错误）、以及每 1.2 秒一次的
     * JS 取值（地址 + 正文）。
     */
    static void showAuthorize(final MainActivity a, final Runnable done) {
        if (a == null || a.isFinishing()) return;
        // 授权页认的是 CookieManager 里的登录态；没登录过百度网盘就先别开这一页（电视上没法打字）。
        if (BaiduLogin.loadSaved() == null) {
            a.toast("先「扫码登录」百度网盘，再做开放平台授权");
            return;
        }

        final Dialog dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setCanceledOnTouchOutside(false);

        final LinearLayout panel = a.vertical();
        panel.setBackgroundColor(0xff101013);

        LinearLayout bar = a.row();
        bar.setPadding(a.dp(24), a.dp(12), a.dp(24), a.dp(4));
        TextView title = a.text("百度网盘 · 开放平台授权", 19, MainActivity.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        final Button retry = a.navButton("刷新", "baidu-oauth-retry", () -> {});
        final Button close = a.navButton("关闭", "baidu-oauth-close", dialog::dismiss);
        bar.addView(retry, new LinearLayout.LayoutParams(a.dp(92), a.dp(34)));
        bar.addView(close, new LinearLayout.LayoutParams(a.dp(92), a.dp(34)));

        TextView hint =
                a.text(
                        "这是应用要一份「取直链」的权限：授权之后，百度分享会先转存到网盘的 /MarcoMovie，"
                                + "播前清理旧副本，再用自己的文件直链播放。授权只做这一次，令牌只存本机。",
                        13,
                        MainActivity.MUTED);
        hint.setMaxLines(3);
        hint.setLineSpacing(a.dp(2), 1f);
        hint.setPadding(a.dp(24), 0, a.dp(24), a.dp(2));

        final TextView status = a.text("正在打开授权页…", 13, 0xffe6e6ee);
        status.setMaxLines(3);
        status.setLineSpacing(a.dp(2), 1f);
        status.setPadding(a.dp(24), 0, a.dp(24), a.dp(8));

        final FrameLayout webHost = new FrameLayout(a);
        webHost.setBackgroundColor(0xff000000);

        panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(webHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        final WebView[] view = new WebView[1];
        final boolean[] finished = {false};
        final boolean[] exchanging = {false};
        final String[] pendingCode = {""};
        final long[] deadline = {0L};
        final Runnable[] poll = new Runnable[1];
        final Runnable[] begin = new Runnable[1];

        final Runnable stopPolling = () -> {
            if (poll[0] != null) a.ui.removeCallbacks(poll[0]);
        };

        /** 拿到 code 之后：后台换令牌、落盘、回调。重复调用只会跑一次。 */
        final Runnable consume =
                () -> {
                    final String code = pendingCode[0];
                    if (code.isEmpty() || finished[0] || exchanging[0]) return;
                    exchanging[0] = true;
                    status.setText("已拿到授权码（" + code.length() + " 位），正在换令牌…");
                    WORKER.execute(
                            () -> {
                                try {
                                    Token token = exchangeCode(code);
                                    final boolean saved = saveSaved(token);
                                    a.ui.post(
                                            () -> {
                                                if (finished[0]) return;
                                                if (!saved) {
                                                    exchanging[0] = false;
                                                    pendingCode[0] = "";
                                                    status.setText("令牌换到了但保存失败，请检查存储权限（可点「刷新」重来）");
                                                    return;
                                                }
                                                finished[0] = true;
                                                stopPolling.run();
                                                status.setText("已授权 · " + describe(token));
                                                try {
                                                    CookieManager.getInstance().flush();
                                                } catch (Throwable ignored) {
                                                    // 刷盘失败不影响已经写下的令牌文件
                                                }
                                                AccountPanel.markDriveLoggedIn(
                                                        a, BaiduLogin.PROVIDER, true);
                                                if (done != null) done.run();
                                                a.ui.postDelayed(
                                                        () -> {
                                                            if (dialog.isShowing()) {
                                                                dialog.dismiss();
                                                            }
                                                        },
                                                        SUCCESS_DISMISS_MS);
                                            });
                                } catch (IOException error) {
                                    a.ui.post(
                                            () -> {
                                                if (finished[0]) return;
                                                exchanging[0] = false;
                                                pendingCode[0] = "";
                                                status.setText(
                                                        "换令牌失败："
                                                                + describe(error)
                                                                + "（可点「刷新」重来）");
                                            });
                                }
                            });
                };

        final Runnable check =
                () -> {
                    final WebView web = view[0];
                    if (finished[0] || web == null || !dialog.isShowing()) return;
                    String url = web.getUrl();
                    String fromUrl = parseCode(url);
                    if (!fromUrl.isEmpty()) {
                        pendingCode[0] = fromUrl;
                        consume.run();
                        return;
                    }
                    try {
                        web.evaluateJavascript(
                                CODE_JS,
                                value -> {
                                    if (finished[0] || !dialog.isShowing()) return;
                                    String found = parseCode(jsonString(value));
                                    if (found.isEmpty()) return;
                                    pendingCode[0] = found;
                                    consume.run();
                                });
                    } catch (Throwable error) {
                        AppLog.w(TAG, "code probe failed", error);
                    }
                };

        /** 页面地址里带 code 就先收下（oob?code=… 这条最早出现在导航里）。 */
        final Runnable absorbUrl = () -> {
            final WebView web = view[0];
            if (finished[0] || web == null) return;
            String code = parseCode(web.getUrl());
            if (code.isEmpty()) return;
            pendingCode[0] = code;
            consume.run();
        };

        poll[0] =
                () -> {
                    if (finished[0] || !dialog.isShowing() || a.isFinishing()) return;
                    if (System.currentTimeMillis() >= deadline[0]) {
                        stopPolling.run();
                        status.setText(
                                "没等到授权码：可以点「刷新」重来；确认这个账号在电视上已经登录过百度网盘");
                        retry.setEnabled(true);
                        retry.setAlpha(1f);
                        return;
                    }
                    check.run();
                    if (!finished[0]) a.ui.postDelayed(poll[0], POLL_INTERVAL_MS);
                };

        WebView created = null;
        try {
            created = new WebView(a);
            WebSettings settings = created.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            // 桌面 UA：与网盘中心网页登录同一串，百度才会给正常的授权页（而不是「打开 App」）。
            settings.setUserAgentString(DESKTOP_UA);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(created, true);
            created.setBackgroundColor(0xff101013);
            created.setFocusable(true);
            created.setWebViewClient(
                    Build.VERSION.SDK_INT >= 26
                            ? new AuthorizeClient26(
                                    () -> absorbUrl.run(),
                                    status,
                                    retry,
                                    () -> exchanging[0] || finished[0])
                            : Build.VERSION.SDK_INT >= 24
                                    ? new AuthorizeClient24(
                                            () -> absorbUrl.run(),
                                            status,
                                            retry,
                                            () -> exchanging[0] || finished[0])
                                    : new AuthorizeClient(
                                            () -> absorbUrl.run(),
                                            status,
                                            retry,
                                            () -> exchanging[0] || finished[0]));
            view[0] = created;
            webHost.addView(created, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable error) {
            // 盒子上 WebView 被裁掉/版本坏掉时别把应用带走。
            AppLog.w(TAG, "webview unavailable", error);
            a.notice("这个盒子的网页组件用不了，开放平台授权暂时打不开");
            dialog.dismiss();
            return;
        }
        final WebView webView = created;

        begin[0] =
                () -> {
                    if (finished[0] || view[0] == null || !dialog.isShowing()) return;
                    stopPolling.run();
                    exchanging[0] = false;
                    pendingCode[0] = "";
                    deadline[0] = System.currentTimeMillis() + AUTHORIZE_TIMEOUT_MS;
                    retry.setEnabled(false);
                    retry.setAlpha(.5f);
                    status.setText("正在打开授权页…");
                    view[0].loadUrl(AUTHORIZE_URL);
                    a.ui.postDelayed(poll[0], POLL_INTERVAL_MS);
                };
        retry.setOnClickListener(v -> begin[0].run());

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, -1));
        dialog.setOnDismissListener(
                ignored -> {
                    finished[0] = true;
                    stopPolling.run();
                    final WebView closing = view[0];
                    view[0] = null;
                    if (closing != null) {
                        webHost.removeAllViews();
                        closing.stopLoading();
                        closing.setWebViewClient(new WebViewClient());
                        closing.destroy();
                    }
                });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setTag("app-dialog");
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
        }
        webView.post(webView::requestFocus);
        begin[0].run();
    }

    /** 「这一轮已经处理完 / 正在处理」的判据：在收错误页之前先问一句，别把 OOB 跳转当加载失败。 */
    private interface Busy {
        boolean now();
    }

    /** 页面事件：导航到不认识的地址 / 加载完成 / 加载失败。 */
    private static class AuthorizeClient extends WebViewClient {
        private final Runnable onUrl;
        private final TextView status;
        private final Button retry;
        private final Busy busy;

        AuthorizeClient(Runnable onUrl, TextView status, Button retry, Busy busy) {
            this.onUrl = onUrl;
            this.status = status;
            this.retry = retry;
            this.busy = busy;
        }

        /** {@code oob?code=…} 不是 WebView 认识的地址，这里拦下来收 code。 */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (!parseCode(url).isEmpty()) {
                onUrl.run();
                if (view != null) view.stopLoading();
                return true;
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!parseCode(url).isEmpty()) onUrl.run();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!parseCode(url).isEmpty()) onUrl.run();
        }

        @Override
        public void onReceivedError(
                WebView view, int errorCode, String description, String failingUrl) {
            // OOB 跳转在某些 WebView 上表现为「加载失败」，失败地址里就带着 code。
            if (!parseCode(failingUrl).isEmpty()) {
                onUrl.run();
                return;
            }
            if (busy.now()) return;
            status.setText(
                    "网页加载失败"
                            + (text(description).trim().isEmpty()
                                    ? ""
                                    : "（" + text(description).trim() + "）")
                            + "，可以点「刷新」重试");
            retry.setEnabled(true);
            retry.setAlpha(1f);
        }
    }

    /** API 24+ 的 {@code shouldOverrideUrlLoading} 变体；低版本不会加载这个类。 */
    private static class AuthorizeClient24 extends AuthorizeClient {
        AuthorizeClient24(Runnable onUrl, TextView status, Button retry, Busy busy) {
            super(onUrl, status, retry, busy);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url =
                    request == null || request.getUrl() == null
                            ? ""
                            : text(request.getUrl().toString());
            if (!parseCode(url).isEmpty()) {
                super.shouldOverrideUrlLoading(view, url);
                return true;
            }
            return false;
        }
    }

    /**
     * API 26+ 才有的渲染进程回收回调；单独一层，低版本不会加载这个类（与
     * {@link NetdiskWebLogin} 的 {@code LoginClient26} 同一套写法）。
     */
    private static final class AuthorizeClient26 extends AuthorizeClient24 {
        AuthorizeClient26(Runnable onUrl, TextView status, Button retry, Busy busy) {
            super(onUrl, status, retry, busy);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            AppLog.w(TAG, "authorize render process gone");
            return true;
        }
    }

    // ------------------------------------------------------------------ 摘要与杂项

    /** 网盘中心那一行短状态。 */
    static String describeShort() {
        Token token = loadSaved();
        if (token == null) return "未授权取直链";
        return token.valid() ? "已授权取直链" : "授权已过期（会自动续期）";
    }

    /** 授权结果的一句话摘要。 */
    static String describe(Token token) {
        if (token == null) return "未授权取直链";
        StringBuilder sb = new StringBuilder("取直链已授权");
        if (!token.scope.isEmpty()) sb.append("（").append(token.scope).append("）");
        sb.append(" · 令牌存到本机");
        return sb.toString();
    }

    /** {@code evaluateJavascript} 的回调是 JSON 编码的字符串：剥一层引号；裸串原样返回。 */
    private static String jsonString(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) return "";
        if (!trimmed.startsWith("\"")) return trimmed;
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            return parsed != null && parsed.isJsonPrimitive() ? parsed.getAsString() : "";
        } catch (RuntimeException error) {
            return "";
        }
    }

    /**
     * 令牌响应体打码：{@code access_token} / {@code refresh_token} / {@code session_secret} 换成
     * {@code ***}，其余（{@code error} / {@code error_description} / {@code expires_in} / {@code scope}）
     * 原样留着 —— 定案就靠它们。截断到 400 字。
     */
    private static String maskTokenBody(String body) {
        String value = text(body).replace('\n', ' ').replace('\r', ' ');
        value =
                value.replaceAll(
                        "\"(access_token|refresh_token|session_secret)\"\\s*:\\s*\"[^\"]*\"",
                        "\"$1\":\"***\"");
        return value.length() > MAX_BODY_CHARS ? value.substring(0, MAX_BODY_CHARS) + "…" : value;
    }

    private static String describe(IOException error) {
        if (error == null) return "";
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
