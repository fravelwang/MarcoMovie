package com.pilipala.marcomovie;

import android.app.Dialog;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
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
import java.util.Locale;

/**
 * 网盘网页扫码登录：打开该家的网页登录页，Boss 用手机 App 扫电视上的二维码，登录成功后应用自动
 * 把凭据从 WebView 里取出来落盘，然后关掉页面、刷新牌面。
 *
 * <p>为什么这么做：夸克有 {@link QuarkQrLogin} 那套自己实现协议的扫码；其余三家（UC / 百度 / 阿里）
 * 和 115 的登录各挂在自己的风控链路上，自己编协议风险高。这里不碰协议，只借用它们**网页版**已有的
 * 扫码登录：网页怎么登，应用就怎么拿——Cookie 走 {@link CookieManager}，阿里的 token 走
 * {@code localStorage.getItem('token')}。本类只管「怎么拿到凭据」，解析与落盘交给三家现成的
 * {@link UcLogin#saveRawCookie} / {@link BaiduLogin#saveRawCookie} / {@link AliLogin#saveRawCookie}，
 * 所以凭据文件结构、播放侧读取口径一个字都没变。
 *
 * <p>四家的口径都在下面 {@code PROFILES} 一张表里（本卡第 2 节），改判据只改那一处：
 *
 * <pre>
 * provider  登录页                          成功判据                              取什么
 * uc        https://drive.uc.cn/            Cookie 有 __pus 或 __puus             整段 Cookie
 * baidu     https://pan.baidu.com/          Cookie 有 BDUSS                       整段 Cookie
 * ali       https://www.alipan.com/drive    localStorage.token 里有 refresh_token  token JSON
 * 115       https://115.com/                Cookie 有 UID 且 CID                 整段 Cookie
 * </pre>
 *
 * <p>盒子是 Android 11（API 30），本类只用 API 21+ 的接口（{@code setAcceptThirdPartyCookies} 是
 * 21，{@code evaluateJavascript} 是 19），{@code onRenderProcessGone} 是 26+，按版本分开装，API 30
 * 以下不会去碰它。JS、DOM storage、第三方 Cookie 全开，UA 用桌面 Chrome 串——各家网页版看到
 * Android UA 会直接往 App 里跳，电视上就看不到二维码。
 *
 * <p>凭据只写本机文件，日志里最多记「provider=uc keys=[__pus,__puus]」这一级，绝不打印 Cookie 值。
 */
final class NetdiskWebLogin {

    /** 115 还没有自己的 *Login 类（本卡只存不接播放），provider 键先放这里。 */
    static final String PROVIDER_115 = "115";

    /** 每 1.5 秒看一次判据：够快，又不至于让 WebView 一直忙。 */
    private static final long POLL_INTERVAL_MS = 1500L;

    /** 最多等 5 分钟，超时把人往「刷新 / 手工粘贴」上引。 */
    private static final long LOGIN_TIMEOUT_MS = 5 * 60 * 1000L;

    /** 成功后停留一下再关，让 Boss 看到「已登录」。 */
    private static final long SUCCESS_DISMISS_MS = 1200L;

    /**
     * 「只给二维码」最多等这么久：页面加载完之后 15 秒还没找到二维码，就退回整页显示（即原来的样子）。
     * 页面一直不出来的话，从打开登录页算起最多白等两倍时间，之后同样退回整页——绝不让人对着白屏干等。
     */
    private static final long QR_FALLBACK_MS = 15_000L;

    /**
     * 桌面 Chrome 的 UA。各家网页版对 Android UA 会强制跳 App 或给一个不能扫码的简化页，桌面串才
     * 会给出正常的网页登录页与二维码。
     */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    private static final int MAX_COOKIE_CHARS = 40 * 1024;

    private static final String[] NO_COOKIES = new String[0];

    /**
     * 阿里的 token 在 localStorage 里，且键名随版本变过（{@code token} / {@code aliyundrive_token}）。
     * 只返回**确实是 token JSON 且带 refresh_token** 的那一段原文，拿不到就回空串继续等。
     */
    private static final String ALI_TOKEN_JS =
            "(function(){try{"
                    + "var keys=['token','aliyundrive_token','alipan_token'];"
                    + "for(var i=0;i<keys.length;i++){"
                    + "var v=window.localStorage.getItem(keys[i]);if(!v)continue;"
                    + "try{var o=JSON.parse(v);"
                    + "if(o&&(o.refresh_token||o.refreshToken))return v;}catch(e){}}"
                    + "return '';}catch(e){return '';}})()";

    /**
     * 「电视上只要一个二维码」的注入脚本（幂等，跑多少遍都只留一层）。
     *
     * <p>做法：在页面里挑一个像二维码的元素（优先 {@code canvas}，其次同源的 {@code img}），把它的像素
     * 或地址取出来，另造一层白底容器铺满视口、正中放那张图，页面其余内容（导航、营销图、页脚）全被
     * 盖住。这样 Boss 在电视上看到的只有二维码，官方网页整页不再铺出来。
     *
     * <p>判据在 {@code shown()} 里：宽高都在 120–600 px、宽高比 0.8–1.25、元素确实显示着（隐藏的、
     * 图标大小的、横幅大小的都不算）。{@code canvas} 用 {@code toDataURL('image/png')} 取像素；{@code
     * img} 用 {@code src}，空串、{@code blob:} 和跨域地址都跳过——跨域图拿不到干净数据就不冒这个险，
     * 让 Java 侧到点退回整页（不报错、不比现在差）。
     *
     * <p>页面跳转分两种：整页跳转会换掉 document，旧容器跟着消失；SPA 只换 URL 时脚本自己认出 URL
     * 变了，摘掉旧容器重新找一次（免得旧二维码一直盖着新页面）。同一个页面里页面自己刷新二维码（过期
     * 重取）时，也会同步更新我们这张图，不会让 Boss 扫到过期的那张。
     *
     * <p>返回值给 Java 侧看：{@code "1"} 已注入（或本来就在、顺手刷新了），{@code "0"} 这轮没找到。
     */
    private static final String QR_ONLY_JS =
            "(function(){try{"
                    + "var url=String(location.href||'');"
                    + "var old=window.__mmQrOnly||null;"
                    // 只认「显示着、尺寸像二维码、接近正方形」的元素
                    + "function shown(el){try{"
                    + "if(!el||!el.getBoundingClientRect)return false;"
                    + "var s=window.getComputedStyle?window.getComputedStyle(el):null;"
                    + "if(s&&(s.display==='none'||s.visibility==='hidden'||s.opacity==='0'))return false;"
                    + "var r=el.getBoundingClientRect();"
                    + "if(!(r.width>=120&&r.width<=600&&r.height>=120&&r.height<=600))return false;"
                    + "var q=r.width/r.height;"
                    + "return q>=0.8&&q<=1.25;"
                    + "}catch(e){return false;}}"
                    // canvas 取像素、img 取地址；取不到就回空串
                    + "function dataOf(el){try{"
                    + "if(el.tagName==='CANVAS'){"
                    + "var d=el.toDataURL('image/png');"
                    + "return d&&d.length>500?d:'';}"
                    + "var src=el.currentSrc||el.src||'';"
                    + "if(!src)return '';"
                    + "if(/^data:image\\//i.test(src)||/^blob:/i.test(src))return src;"
                    + "var a=document.createElement('a');a.href=src;"
                    + "var here=a.protocol+'//'+a.host;"
                    + "return here===location.protocol+'//'+location.host?src:'';"
                    + "}catch(e){return '';}}"
                    // 收集候选：canvas 优先，其次按面积从大到小
                    + "var nodes=document.querySelectorAll('canvas, img');"
                    + "var list=[];"
                    + "for(var i=0;i<nodes.length&&i<200;i++){"
                    + "var el=nodes[i];"
                    + "if(!shown(el))continue;"
                    + "var rr=el.getBoundingClientRect();"
                    + "list.push({el:el,pri:el.tagName==='CANVAS'?0:1,area:rr.width*rr.height});}"
                    + "list.sort(function(x,y){return x.pri!==y.pri?x.pri-y.pri:y.area-x.area;});"
                    + "var data='';"
                    + "for(var j=0;j<list.length&&!data;j++){data=dataOf(list[j].el);}"
                    // 这轮没找到
                    + "if(!data){"
                    + "if(old&&old.parentNode&&window.__mmQrUrl===url)return '1';"
                    + "if(old&&old.parentNode)old.parentNode.removeChild(old);"
                    + "window.__mmQrOnly=null;window.__mmQrUrl='';window.__mmQrData='';"
                    + "return '0';}"
                    // 同一个页面已经注入过：页面换了二维码就同步刷新那张图，别叠第二层
                    + "if(old&&old.parentNode&&window.__mmQrUrl===url){"
                    + "if(window.__mmQrData!==data){"
                    + "var pic=old.getElementsByTagName('img')[0];"
                    + "if(pic)pic.src=data;window.__mmQrData=data;}"
                    + "return '1';}"
                    + "if(old&&old.parentNode)old.parentNode.removeChild(old);"
                    + "var box=document.createElement('div');"
                    + "box.setAttribute('data-mm-qr-only','1');"
                    + "box.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;width:100%;height:100%;"
                    + "margin:0;padding:0;border:0;background:#fff;display:flex;flex-direction:column;"
                    + "align-items:center;justify-content:center;z-index:2147483647;overflow:hidden;';"
                    + "var img=document.createElement('img');"
                    + "img.src=data;img.alt='二维码';"
                    + "img.style.cssText='display:block;width:min(60vh,60vw);height:auto;"
                    + "max-width:60vw;max-height:60vh;background:#fff;';"
                    + "var tip=document.createElement('div');"
                    + "tip.textContent='用手机 App 扫上面的二维码';"
                    + "tip.style.cssText='margin:18px 0 0;font:400 22px/1.4 sans-serif;color:#333;text-align:center;';"
                    + "box.appendChild(img);box.appendChild(tip);"
                    + "(document.body||document.documentElement).appendChild(box);"
                    + "window.__mmQrOnly=box;window.__mmQrUrl=url;window.__mmQrData=data;"
                    + "return '1';"
                    + "}catch(e){return '0';}})()";

    private NetdiskWebLogin() {}

    // ------------------------------------------------------------------ 四家配置表

    /**
     * 一家网盘的网页登录口径。{@code cookies} + {@code requireAllCookies} 是 Cookie 判据；
     * {@code tokenInLocalStorage} 为真时改看 localStorage（阿里）；{@code loginFlowMarkers} 是「还停在
     * 登录流程上」的 URL 片段，命中就先不保存（防止游客态或半登录态被当成成功）。
     */
    static final class Profile {
        final String key;
        final String label;
        /** 扫码要用哪个 App，写在等待提示里。 */
        final String appHint;

        final String loginUrl;
        /** 取 Cookie 的主机（CookieManager 按 URL 取，域名 Cookie 与 host-only Cookie 都会带上）。 */
        final String cookieHost;

        final String[] cookies;
        final boolean requireAllCookies;
        final boolean tokenInLocalStorage;
        final String[] loginFlowMarkers;

        Profile(
                String key,
                String label,
                String appHint,
                String loginUrl,
                String cookieHost,
                String[] cookies,
                boolean requireAllCookies,
                boolean tokenInLocalStorage,
                String[] loginFlowMarkers) {
            this.key = key;
            this.label = label;
            this.appHint = appHint;
            this.loginUrl = loginUrl;
            this.cookieHost = cookieHost;
            this.cookies = cookies == null ? NO_COOKIES : cookies;
            this.requireAllCookies = requireAllCookies;
            this.tokenInLocalStorage = tokenInLocalStorage;
            this.loginFlowMarkers = loginFlowMarkers == null ? new String[0] : loginFlowMarkers;
        }
    }

    /** 本卡第 2 节那张表。顺序与网盘中心牌面一致。 */
    private static final Profile[] PROFILES = {
        // provider, 牌子, 扫码用哪个 App, 登录页, 取 Cookie 的主机,
        // Cookie 判据, 是否要求全中, 是否看 localStorage, 「还在登录流程」的 URL 片段
        new Profile(
                UcLogin.PROVIDER,
                "UC 网盘",
                "手机 UC 浏览器 / UC 网盘 App",
                "https://drive.uc.cn/",
                "drive.uc.cn",
                new String[] {"__pus", "__puus"},
                false,
                false,
                new String[] {"login", "sso"}),
        new Profile(
                BaiduLogin.PROVIDER,
                "百度网盘",
                "手机百度网盘 App",
                "https://passport.baidu.com/v2/?login&tpl=netdisk&u=https%3A%2F%2Fpan.baidu.com%2Fdisk%2Fhome",
                "pan.baidu.com",
                new String[] {"BDUSS"},
                false,
                false,
                new String[] {"passport", "login"}),
        new Profile(
                AliLogin.PROVIDER,
                "阿里云盘",
                "手机阿里云盘 App",
                "https://www.alipan.com/drive",
                "www.alipan.com",
                NO_COOKIES,
                false,
                true,
                new String[] {"passport", "login"}),
        new Profile(
                PROVIDER_115,
                "115 网盘",
                "手机 115 App",
                "https://115.com/",
                "115.com",
                new String[] {"UID", "CID"},
                true,
                false,
                new String[] {"login"}),
    };

    /** 这家有没有网页扫码登录。 */
    static boolean supports(String provider) {
        return profile(provider) != null;
    }

    /** 牌面与对话框上写的中文名；不认识的 provider 原样返回。 */
    static String label(String provider) {
        Profile profile = profile(provider);
        return profile == null ? (provider == null ? "" : provider) : profile.label;
    }

    /** 这家有没有「手工粘贴」这条兜底路（115 暂时只有扫码）。 */
    static boolean supportsPaste(String provider) {
        Profile profile = profile(provider);
        return profile != null && !PROVIDER_115.equals(profile.key);
    }

    private static Profile profile(String provider) {
        if (provider == null) return null;
        String key = provider.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        for (Profile profile : PROFILES) {
            if (profile.key.equals(key)) return profile;
        }
        return null;
    }

    // ------------------------------------------------------------------ 网页登录

    /**
     * 打开该家的网页登录页，扫码成功后自动保存凭据并关闭页面。
     *
     * @param provider {@code uc} / {@code baidu} / {@code ali} / {@code 115}
     * @param done 保存成功后的回调（网盘中心用它刷新牌面），可 null
     */
    static void show(final MainActivity a, final String provider, final Runnable done) {
        if (a == null || a.isFinishing()) return;
        final Profile profile = profile(provider);
        if (profile == null) {
            a.toast("这个网盘还没有网页登录");
            return;
        }

        final Dialog dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setCanceledOnTouchOutside(false);

        final LinearLayout panel = a.vertical();
        panel.setBackgroundColor(0xff101013);

        LinearLayout bar = a.row();
        bar.setPadding(a.dp(24), a.dp(12), a.dp(24), a.dp(4));
        TextView title = a.text(profile.label + " · 网页扫码登录", 19, MainActivity.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        final Button retry = a.navButton("刷新", "netdisk-weblogin-retry", () -> {});
        final Button close = a.navButton("关闭", "netdisk-weblogin-close", dialog::dismiss);
        bar.addView(retry, new LinearLayout.LayoutParams(a.dp(92), a.dp(34)));
        bar.addView(close, new LinearLayout.LayoutParams(a.dp(92), a.dp(34)));

        TextView hint =
                a.text(
                        "用手机 App 扫屏幕上的二维码登录，登录成功后凭据自动保存（只存本机）。"
                                + "按返回键可随时关掉。",
                        13,
                        MainActivity.MUTED);
        hint.setMaxLines(2);
        hint.setLineSpacing(a.dp(2), 1f);
        hint.setPadding(a.dp(24), 0, a.dp(24), a.dp(2));

        final TextView status = a.text("正在打开登录页…", 13, 0xffe6e6ee);
        status.setMaxLines(2);
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
        final boolean[] firstLoad = {false};
        final long[] deadline = {0L};
        /** 待保存的裸凭据：Cookie 串，或阿里的 token JSON。 */
        final String[] pending = {""};
        /** 页面已经加载完（主框架 onPageFinished）。 */
        final boolean[] pageLoaded = {false};
        /** 页面已经换成「只有一个二维码」。 */
        final boolean[] qrOnly = {false};
        /** 兜底已触发：不再注入，页面照旧整页显示。 */
        final boolean[] qrGivenUp = {false};
        /** 白等的最晚时刻：页面一直不出来，到点也得把整页露出来。 */
        final long[] qrHardDeadline = {0L};

        final Runnable[] poll = new Runnable[1];
        final Runnable[] begin = new Runnable[1];

        /**
         * 盖在网页上的白底。等二维码那十几秒别把官网整页铺在电视上；注入成功或兜底到点就撤掉。
         * 撤的时候底下已经是白底二维码（JS 注入的容器也是白底），所以不会闪一下黑的。
         */
        final View qrCover = new View(a);
        qrCover.setBackgroundColor(0xffffffff);
        qrCover.setVisibility(View.VISIBLE);

        final Runnable stopPolling = () -> {
            if (poll[0] != null) a.ui.removeCallbacks(poll[0]);
        };

        /** 把网页露出来：注入成功、兜底到点、加载失败、崩溃都走这一条。 */
        final Runnable revealPage = () -> qrCover.setVisibility(View.GONE);

        /** 兜底计时器本体，见 {@link #QR_FALLBACK_MS}。 */
        final Runnable[] qrFallback = new Runnable[1];

        final Runnable stopQrFallback = () -> {
            if (qrFallback[0] != null) a.ui.removeCallbacks(qrFallback[0]);
        };

        final Runnable resetActions = () -> {
            retry.setEnabled(false);
            retry.setAlpha(.5f);
        };

        final Runnable save =
                () -> {
                    final String raw = pending[0];
                    pending[0] = "";
                    if (raw.isEmpty()) return;
                    finished[0] = true;
                    stopPolling.run();
                    String summary;
                    try {
                        summary = saveCredentials(a, profile.key, raw);
                    } catch (IOException | RuntimeException error) {
                        // 判据过了但落盘/解析不认：把原因原样说给 Boss 看，别装作成功。
                        String reason = error.getMessage();
                        status.setText(
                                reason == null || reason.isEmpty() ? "凭据保存失败" : reason);
                        retry.setEnabled(true);
                        retry.setAlpha(1f);
                        return;
                    }
                    status.setText(summary);
                    try {
                        CookieManager.getInstance().flush();
                    } catch (Throwable ignored) {
                        // 刷盘失败不影响已经写下的凭据文件
                    }
                    AccountPanel.markDriveLoggedIn(a, profile.key, true);
                    if (done != null) done.run();
                    a.ui.postDelayed(
                            () -> {
                                if (dialog.isShowing()) dialog.dismiss();
                            },
                            SUCCESS_DISMISS_MS);
                };

        /**
         * 让页面只留下二维码。幂等：跟着 1.5 秒的轮询一起跑，页面换了二维码也会同步刷新那张图。
         *
         * <p>放在凭据判据前面跑，是因为判据在「还在登录流程」的页面上会早退（百度的二维码正好在
         * passport 页上），而二维码该不该显示跟登没登录无关。注入失败（WebView 坏了 / JS 被关）只影响
         * 这条美化路，凭据检测照旧跑，到点自动退回整页。
         */
        final Runnable injectQr =
                () -> {
                    final WebView web = view[0];
                    if (web == null || finished[0] || qrGivenUp[0] || !dialog.isShowing()) return;
                    try {
                        web.evaluateJavascript(
                                QR_ONLY_JS,
                                value -> {
                                    if (finished[0] || !dialog.isShowing()) return;
                                    if (!"1".equals(jsonString(value))) return;
                                    if (qrOnly[0]) return;
                                    qrOnly[0] = true;
                                    revealPage.run();
                                    status.setText(
                                            "二维码已就位：用"
                                                    + profile.appHint
                                                    + "扫屏幕中间的大二维码");
                                });
                    } catch (Throwable error) {
                        AppLog.w("NetdiskWebLogin", "qr-only inject failed", error);
                    }
                };

        final Runnable check =
                () -> {
                    final WebView webView = view[0];
                    if (finished[0] || webView == null || !dialog.isShowing()) return;
                    String url = webView.getUrl();
                    if (url == null || url.isEmpty()) return;
                    // 还停在那家的登录 / passport 页上就先不认，免得游客态被当成登录成功。
                    if (!onProfileHost(profile, url) || stillInLoginFlow(profile, url)) return;
                    if (profile.tokenInLocalStorage) {
                        webView.evaluateJavascript(
                                ALI_TOKEN_JS,
                                value -> {
                                    if (finished[0] || !dialog.isShowing()) return;
                                    String raw = jsonString(value);
                                    if (raw.isEmpty()) return;
                                    pending[0] = raw;
                                    save.run();
                                });
                        return;
                    }
                    String cookie = cookieHeader(profile);
                    if (cookie.isEmpty() || !cookieReady(profile, cookie)) return;
                    pending[0] = cookie;
                    save.run();
                };

        poll[0] =
                () -> {
                    if (finished[0] || !dialog.isShowing() || a.isFinishing()) return;
                    // 先管二维码：这一步跟凭据判据互不影响，也不该被判据的早退挡掉。
                    injectQr.run();
                    if (System.currentTimeMillis() >= deadline[0]) {
                        stopPolling.run();
                        stopQrFallback.run();
                        revealPage.run();
                        status.setText(
                                "没检测到登录：可以点「刷新」重来，或用「手工粘贴」这条兜底路");
                        retry.setEnabled(true);
                        retry.setAlpha(1f);
                        return;
                    }
                    check.run();
                    if (!finished[0]) a.ui.postDelayed(poll[0], POLL_INTERVAL_MS);
                };

        qrFallback[0] =
                () -> {
                    if (finished[0] || !dialog.isShowing() || qrOnly[0] || qrGivenUp[0]) return;
                    // 页面还没出来就再等一轮（最多等到 qrHardDeadline），别在加载中途把整页顶出来。
                    if (!pageLoaded[0] && System.currentTimeMillis() < qrHardDeadline[0]) {
                        a.ui.postDelayed(qrFallback[0], QR_FALLBACK_MS);
                        return;
                    }
                    qrGivenUp[0] = true;
                    revealPage.run();
                    status.setText(
                            "没有找到二维码，已退回完整登录页：可以直接扫页面上的二维码，或点「刷新」重来");
                };

        final PageEvents events =
                new PageEvents() {
                    @Override
                    public void loaded() {
                        if (finished[0] || !dialog.isShowing()) return;
                        pageLoaded[0] = true;
                        // 页面加载完了再给足 15 秒找二维码：加载慢不该算在找二维码的时间里。
                        if (!qrOnly[0] && !qrGivenUp[0]) {
                            stopQrFallback.run();
                            a.ui.postDelayed(qrFallback[0], QR_FALLBACK_MS);
                        }
                        if (firstLoad[0]) return;
                        firstLoad[0] = true;
                        if (qrOnly[0] || qrGivenUp[0]) return;
                        status.setText(
                                "正在找二维码（最多 15 秒，找不到就退回完整登录页）");
                    }

                    @Override
                    public void failed(String reason) {
                        if (finished[0] || !dialog.isShowing()) return;
                        stopQrFallback.run();
                        qrGivenUp[0] = true;
                        revealPage.run();
                        String detail = reason == null ? "" : reason.trim();
                        status.setText(
                                "网页加载失败"
                                        + (detail.isEmpty() ? "" : "（" + detail + "）")
                                        + "，可以点「刷新」重试");
                        retry.setEnabled(true);
                        retry.setAlpha(1f);
                    }

                    @Override
                    public void crashed() {
                        if (finished[0]) return;
                        finished[0] = true;
                        stopPolling.run();
                        stopQrFallback.run();
                        revealPage.run();
                        status.setText("网页进程异常退出，请关闭后重试");
                        retry.setEnabled(true);
                        retry.setAlpha(1f);
                    }
                };

        WebView created = null;
        try {
            created = new WebView(a);
            WebSettings settings = created.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            // 桌面 UA：各家网页版看见 Android UA 会往 App 里跳，电视上就没有二维码可扫了。
            settings.setUserAgentString(DESKTOP_UA);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            // 登录页偶尔还有 http 的图片（二维码就可能是），被动内容允许加载、主动内容仍被拦。
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            // 第三方 Cookie 不开，登录页的跨域校验拿不到会话（setAcceptThirdPartyCookies 是 API 21，
            // 本应用 minSdk 23）。
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(created, true);
            created.setBackgroundColor(0xff101013);
            created.setFocusable(true);
            created.setWebViewClient(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? new LoginClient26(events)
                            : new LoginClient(events));
            view[0] = created;
            webHost.addView(created, new FrameLayout.LayoutParams(-1, -1));
            // 白底压在网页上面：二维码没就位之前，电视上看到的是一块干净白底，不是官网整页。
            webHost.addView(qrCover, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable error) {
            // 盒子上 WebView 被裁掉/版本坏掉时别把整个应用带走：这条兜底路还在。
            AppLog.w("NetdiskWebLogin", "webview unavailable", error);
            a.notice("这个盒子的网页组件用不了，请改用「手工粘贴凭据」");
            dialog.dismiss();
            return;
        }
        final WebView webView = created;

        begin[0] =
                () -> {
                    if (finished[0] || view[0] == null || !dialog.isShowing()) return;
                    stopPolling.run();
                    stopQrFallback.run();
                    firstLoad[0] = false;
                    pageLoaded[0] = false;
                    qrOnly[0] = false;
                    qrGivenUp[0] = false;
                    pending[0] = "";
                    deadline[0] = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
                    qrHardDeadline[0] = System.currentTimeMillis() + QR_FALLBACK_MS * 2;
                    resetActions.run();
                    qrCover.setVisibility(View.VISIBLE);
                    status.setText("正在打开登录页…");
                    a.ui.postDelayed(qrFallback[0], QR_FALLBACK_MS);
                    view[0].loadUrl(profile.loginUrl);
                    a.ui.postDelayed(poll[0], POLL_INTERVAL_MS);
                };
        retry.setOnClickListener(v -> begin[0].run());

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, -1));
        dialog.setOnDismissListener(
                ignored -> {
                    finished[0] = true;
                    stopPolling.run();
                    stopQrFallback.run();
                    final WebView closing = view[0];
                    view[0] = null;
                    if (closing != null) {
                        webHost.removeAllViews();
                        closing.stopLoading();
                        // 拆掉回调再销毁：销毁过程中的页面回调不该再碰到对话框。
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
        // 初始焦点给网页：遥控器上下左右/确定能直接点页面上的「扫码登录」页签。
        webView.post(webView::requestFocus);
        begin[0].run();
    }

    // ------------------------------------------------------------------ 判据

    /** 当前页面是不是还在该家的域里（passport 子域也算）。 */
    private static boolean onProfileHost(Profile profile, String url) {
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (RuntimeException error) {
            return false;
        }
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals(profile.cookieHost) || lower.endsWith("." + profile.cookieHost);
    }

    /** URL 里带着登录/passport 的痕迹：说明还在登录流程上，先不认。 */
    private static boolean stillInLoginFlow(Profile profile, String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String marker : profile.loginFlowMarkers) {
            if (!marker.isEmpty() && lower.contains(marker)) return true;
        }
        return false;
    }

    /** 该家 Cookie 判据：{@code requireAllCookies} 为真要求全中，否则有一个就算。 */
    private static boolean cookieReady(Profile profile, String cookie) {
        if (profile.cookies.length == 0) return false;
        for (String name : profile.cookies) {
            boolean present = !UcLogin.cookieValue(cookie, name).isEmpty();
            if (profile.requireAllCookies && !present) return false;
            if (!profile.requireAllCookies && present) return true;
        }
        return profile.requireAllCookies;
    }

    private static String cookieHeader(Profile profile) {
        try {
            String cookie = CookieManager.getInstance().getCookie(cookieUrl(profile));
            return cookie == null ? "" : cookie.trim();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static String cookieUrl(Profile profile) {
        return "https://" + profile.cookieHost + "/";
    }

    /** {@code evaluateJavascript} 的回调是 JSON 编码的字符串：拆成原文；裸串原样返回。 */
    private static String jsonString(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.isEmpty() || "null".equals(text)) return "";
        if (!text.startsWith("\"")) return text;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed != null && parsed.isJsonPrimitive() ? parsed.getAsString() : "";
        } catch (RuntimeException error) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 落盘（交给三家现成入口）

    /**
     * 把网页里取到的一段凭据交给该家现成的解析/落盘入口。失败抛 {@link IOException}，消息已经是给
     * 人看的一句话（例如「网页登录拿到的 Cookie 里没有 __pus」）。
     */
    private static String saveCredentials(MainActivity a, String provider, String raw)
            throws IOException {
        if (UcLogin.PROVIDER.equals(provider)) {
            UcLogin.Credential saved = UcLogin.saveRawCookie(raw);
            return "UC 网盘 · 已登录（" + UcLogin.describeShort(saved) + "）";
        }
        if (BaiduLogin.PROVIDER.equals(provider)) {
            BaiduLogin.Credential saved = BaiduLogin.saveRawCookie(raw);
            return "百度网盘 · 已登录（" + BaiduLogin.describeShort(saved) + "）";
        }
        if (AliLogin.PROVIDER.equals(provider)) {
            AliLogin.Credential saved = AliLogin.saveRawCookie(raw);
            // 落盘后再后台真换一次 access_token：当场告诉 Boss 这条 refresh_token 能不能用。
            AliLogin.verifySavedAsync(a);
            return "阿里云盘 · 已登录（" + AliLogin.describeShort(saved) + "），正在校验";
        }
        if (PROVIDER_115.equals(provider)) {
            Cookie115 saved = save115Cookie(a == null ? null : a.getFilesDir(), raw);
            return "115 网盘 · 已登录（" + describe115(saved) + "）";
        }
        throw new IOException("这个网盘还没有网页登录");
    }

    // ------------------------------------------------------------------ 115（只存不接播放）

    /**
     * 115 的登录凭据，结构照 {@code files/netdisk/uc.json}：{@code provider} / {@code version} /
     * {@code cookie} / {@code validatedAt}。
     *
     * <p>115 本卡只存不接播放，所以不单开一个 {@code XxxLogin} 类——免得让人以为播放侧已经接上。
     */
    static final class Cookie115 {
        static final int VERSION = 1;

        final String cookie;
        final String uid;
        final String cid;
        final String validatedAt;

        Cookie115(String cookie, String uid, String cid, String validatedAt) {
            this.cookie = cookie;
            this.uid = uid;
            this.cid = cid;
            this.validatedAt = validatedAt;
        }

        String toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("provider", PROVIDER_115);
            out.addProperty("version", VERSION);
            out.addProperty("cookie", cookie);
            out.addProperty("validatedAt", validatedAt);
            return out.toString();
        }

        /** 缺字段、来自别的 provider、或 Cookie 里没有 UID/CID 时返回 null。 */
        static Cookie115 parse(String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                JsonElement root = JsonParser.parseString(json);
                if (root == null || !root.isJsonObject()) return null;
                JsonObject obj = root.getAsJsonObject();
                if (!PROVIDER_115.equals(NetdiskShareHttp.optString(obj, "provider"))) return null;
                if (obj.has("version")
                        && NetdiskShareHttp.optInt(obj, "version", -1) != VERSION) {
                    return null;
                }
                String cookie = NetdiskShareHttp.optString(obj, "cookie").trim();
                if (cookie.isEmpty() || cookie.length() > MAX_COOKIE_CHARS) return null;
                String validatedAt = NetdiskShareHttp.optString(obj, "validatedAt").trim();
                if (validatedAt.isEmpty()) return null;
                String uid = UcLogin.cookieValue(cookie, "UID");
                String cid = UcLogin.cookieValue(cookie, "CID");
                if (uid.isEmpty() || cid.isEmpty()) return null;
                return new Cookie115(cookie, uid, cid, validatedAt);
            } catch (RuntimeException error) {
                return null;
            }
        }
    }

    static File credential115File(File filesDir) {
        if (filesDir == null) return null;
        return new File(new File(filesDir, "netdisk"), "115.json");
    }

    /** 读 {@code files/netdisk/115.json}；没有或读不出来返回 null。 */
    static Cookie115 load115(File filesDir) {
        File file = credential115File(filesDir);
        if (file == null || !file.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Cookie115.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
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

    /** 原子写（tmp + rename），半截文件不会被当成凭据读进来。 */
    static boolean save115(File filesDir, Cookie115 credential) {
        File file = credential115File(filesDir);
        if (file == null || credential == null) return false;
        File parent = file.getParentFile();
        if (parent == null) return false;
        if (!parent.isDirectory() && !parent.mkdirs()) return false;
        File tmp = new File(parent, file.getName() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(credential.toJson().getBytes(StandardCharsets.UTF_8));
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

    /** 网页里拿到的整段 Cookie → 115 凭据并落盘（只留 UID / CID，其余丢掉）。 */
    static Cookie115 save115Cookie(File filesDir, String raw) throws IOException {
        String cookie = raw == null ? "" : raw.trim();
        if (cookie.isEmpty()) throw new IOException("网页登录没有拿到 115 的 Cookie");
        String uid = UcLogin.cookieValue(cookie, "UID");
        String cid = UcLogin.cookieValue(cookie, "CID");
        if (uid.isEmpty() || cid.isEmpty()) {
            throw new IOException("网页登录拿到的 Cookie 里没有 UID / CID");
        }
        StringBuilder kept = new StringBuilder();
        appendCookie(kept, "UID", uid);
        appendCookie(kept, "CID", cid);
        Cookie115 saved = new Cookie115(kept.toString(), uid, cid, UcLogin.isoNow());
        if (!save115(filesDir, saved)) throw new IOException("凭据保存失败，请检查存储权限");
        return saved;
    }

    private static void appendCookie(StringBuilder out, String name, String value) {
        if (value == null || value.isEmpty()) return;
        if (out.length() > 0) out.append("; ");
        out.append(name).append('=').append(value);
    }

    /** UI 里的一行短状态。 */
    static String describe115(Cookie115 credential) {
        if (credential == null) return "未登录";
        return "UID 与 CID 已存 · 保存于 " + credential.validatedAt;
    }

    // ------------------------------------------------------------------ WebView 回调

    /** 页面事件：加载完成 / 主框架加载失败 / 渲染进程挂掉。 */
    private interface PageEvents {
        void loaded();

        void failed(String reason);

        void crashed();
    }

    private static class LoginClient extends WebViewClient {
        final PageEvents events;

        LoginClient(PageEvents events) {
            this.events = events;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            events.loaded();
        }

        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                events.failed(error == null ? "" : String.valueOf(error.getDescription()));
            }
        }
    }

    /** API 26+ 才有的渲染进程回收回调；按版本分开装，低版本不会加载这个类。 */
    private static final class LoginClient26 extends LoginClient {
        LoginClient26(PageEvents events) {
            super(events);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            events.crashed();
            return true;
        }
    }
}
