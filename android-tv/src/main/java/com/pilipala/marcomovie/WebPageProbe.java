package com.pilipala.marcomovie;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONTokener;

/**
 * 借应用自己的 WebView 当一次「真浏览器」：打开一个页面、让页面的 JS 跑完，取一个表达式的结果，然后
 * 把这个 WebView 拆掉。
 *
 * <p>为什么需要它：百度分享页对没有 JS 的 HTTP 客户端只回 3512 字节的壳页面（里面没有
 * {@code window.yunData}），{@code /api/sharedownload} 要的 {@code sign}/{@code timestamp} 因此拿不到；
 * 而这些字段是页面跑起来之后才填进 {@code window.yunData} 的。所以这里用 WebView 把页面跑一遍再取。
 *
 * <p>约定：
 *
 * <ul>
 *   <li>**必须在后台线程调用**：{@link #evaluate} 会阻塞等结果（最长 {@link #DEFAULT_TIMEOUT_MS}），
 *       所有 WebView 操作都投到主线程，不阻塞主线程事件循环；调用方在主线程时直接返回 null；
 *   <li>WebView 不可见：1×1、不聚焦、不响应点击，挂在当前页面的 {@code playbackLoading}（没有就
 *       {@code root}）上；不弹任何 UI。结束（取到 / 失败 / 超时）一律移出视图树并 {@code destroy()}；
 *   <li>页面装载完成（{@code onPageFinished}）后取一次表达式；结果为空再等 {@link #RETRY_DELAY_MS}
 *       重取一次（页面可能是异步填的），仍然为空就放弃；
 *   <li>拿不到 Activity（界面已退出）时不做探测，返回 null，只记日志。
 * </ul>
 *
 * <p>用的都是老接口（{@code WebView} / {@code CookieManager} / {@code evaluateJavascript} /
 * {@code flush}），Android 11（API 30）可用。
 */
final class WebPageProbe {

    private static final String TAG = "WebPageProbe";

    /** 一次探测的总上限：装载页面 + 跑 JS + 那次 1.5 秒重试都算在里面。 */
    static final long DEFAULT_TIMEOUT_MS = 12000L;

    /** 第一次取到空结果后，再等这么久重取一次（页面可能异步填数据）。 */
    private static final long RETRY_DELAY_MS = 1500L;

    /** 调用方等结果的余量：让主线程上的超时回调先把 WebView 清掉再返回。 */
    private static final long WAIT_SLACK_MS = 1500L;

    /**
     * 浏览器 UA：不用 {@link WebSettings} 的默认串（带 {@code wv} 标记，百度会按 WebView 处理），也不该
     * 用网盘客户端串（那样跑的不是网页版逻辑）。这串是同一代 Android 的 Chrome 移动版形态。
     */
    static final String UA_BROWSER =
            "Mozilla/5.0 (Linux; Android 11; V2118A) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Mobile Safari/537.36";

    private WebPageProbe() {}

    /**
     * 在后台线程打开 {@code url}，页面装载完成后取 {@code js} 的字符串结果。
     *
     * @param cookieHeader {@code name=value; name2=value2} 形态的 Cookie（可为空；逐个写进
     *     {@link CookieManager}）
     * @param js 要取值的表达式；结果按字符串处理（对象请自己在表达式里 {@code JSON.stringify}）
     * @return JS 结果；取不到 / 超时 / 没有 Activity / 在主线程调用时返回 null
     */
    static String evaluate(String url, String cookieHeader, String js) {
        return evaluate(url, cookieHeader, js, DEFAULT_TIMEOUT_MS);
    }

    /** 同上，自带上限（夹在 2–30 秒之间）。 */
    static String evaluate(String url, String cookieHeader, String js, long timeoutMs) {
        String target = text(url).trim();
        if (!target.startsWith("https://") && !target.startsWith("http://")) return null;
        String expression = text(js).trim();
        if (expression.isEmpty()) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程等自己会把事件循环锁死：这条路只能从后台线程走。
            AppLog.w(TAG, "probe skipped: called on main thread");
            return null;
        }
        MainActivity activity = MainActivity.activeActivity;
        if (activity == null || activity.isFinishing()) {
            AppLog.w(TAG, "probe skipped: no active activity");
            return null;
        }
        long budget = Math.min(Math.max(timeoutMs, 2000L), 30000L);
        return new Probe(activity, target, text(cookieHeader), expression, budget).run();
    }

    /** 一次探测的全部状态；一个实例只走一次，结束后不再复用。 */
    private static final class Probe {
        private final MainActivity activity;
        private final String url;
        private final String cookieHeader;
        private final String js;
        private final long timeoutMs;

        /** 主线程队列：重试、总超时、清理都从这里排。每个探测自己一个，清理时一起撤掉。 */
        private final Handler main = new Handler(Looper.getMainLooper());

        /** 等结果的闩：只在主线程 {@link #finishOnMain()} 里放。 */
        private final CountDownLatch done = new CountDownLatch(1);

        /** 结束只做一次（成功、超时、渲染进程退出可能同时来）。 */
        private final AtomicBoolean finished = new AtomicBoolean();

        private volatile String result;
        private WebView webView;
        private ViewGroup host;

        Probe(MainActivity activity, String url, String cookieHeader, String js, long timeoutMs) {
            this.activity = activity;
            this.url = url;
            this.cookieHeader = cookieHeader;
            this.js = js;
            this.timeoutMs = timeoutMs;
        }

        /** 后台线程上的入口：主线程开跑，阻塞等结果（超时就先返回 null，清理仍会做）。 */
        String run() {
            main.post(this::start);
            try {
                if (!done.await(timeoutMs + WAIT_SLACK_MS, TimeUnit.MILLISECONDS)) {
                    main.post(this::finishOnMain);
                    return null;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                main.post(this::finishOnMain);
                return null;
            }
            return result;
        }

        /** 主线程：建 WebView、写 Cookie、挂上去、开跑。任何一步出问题都走清理。 */
        private void start() {
            if (finished.get()) return;
            WebView view;
            try {
                view = new WebView(activity);
            } catch (Throwable error) {
                // 系统 WebView 缺失 / 被禁用时这里会抛，不能让解析整条挂掉。
                AppLog.w(TAG, "probe unavailable: " + error);
                finishOnMain();
                return;
            }
            webView = view;
            try {
                WebSettings settings = view.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setSupportMultipleWindows(false);
                settings.setAllowFileAccess(false);
                settings.setAllowContentAccess(false);
                settings.setUserAgentString(UA_BROWSER);
                if (Build.VERSION.SDK_INT >= 21) {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                }
                view.setBackgroundColor(0);
                view.setFocusable(false);
                view.setFocusableInTouchMode(false);
                view.setClickable(false);
                view.setVerticalScrollBarEnabled(false);
                view.setHorizontalScrollBarEnabled(false);
                view.setWebViewClient(createClient());
                applyCookies(view);
                attach(view);
                main.postDelayed(this::finishOnMain, timeoutMs);
                view.loadUrl(url);
            } catch (Throwable error) {
                AppLog.w(TAG, "probe failed to start: " + error);
                finishOnMain();
            }
        }

        /** 逐个写 Cookie：{@link CookieManager#setCookie} 一次只认一个（多条拼一起会把后半截当属性）。 */
        private void applyCookies(WebView view) {
            try {
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                if (Build.VERSION.SDK_INT >= 21) cookies.setAcceptThirdPartyCookies(view, true);
                for (Map.Entry<String, String> entry : parseCookies(cookieHeader).entrySet()) {
                    cookies.setCookie(url, entry.getKey() + "=" + entry.getValue() + "; path=/");
                }
                cookies.flush();
            } catch (Throwable error) {
                // Cookie 写得进写不进都不影响探测本身；记一行就够。
                AppLog.w(TAG, "probe cookie failed: " + error);
            }
        }

        /** 1×1 挂到当前页面：优先播放加载层，其次根容器；都拿不到就离屏跑（不挂树也就没有要摘的）。 */
        private void attach(WebView view) {
            ViewGroup target = null;
            if (activity.playbackLoading != null) target = activity.playbackLoading;
            else if (activity.root != null) target = activity.root;
            if (target == null) return;
            try {
                target.addView(view, new ViewGroup.LayoutParams(1, 1));
                host = target;
            } catch (Throwable error) {
                host = null;
            }
        }

        private WebViewClient createClient() {
            if (Build.VERSION.SDK_INT >= 26) return new Api26Client();
            return new BaseClient();
        }

        /** 一次取值；空就排一次重取，重取还空就结束。 */
        private void evaluate(int attempt) {
            if (finished.get()) return;
            WebView view = webView;
            if (view == null || Build.VERSION.SDK_INT < 19) {
                finishOnMain();
                return;
            }
            view.evaluateJavascript(
                    js,
                    value -> {
                        if (finished.get()) return;
                        String decoded = decode(value);
                        if (!decoded.isEmpty()) {
                            result = decoded;
                            finishOnMain();
                            return;
                        }
                        if (attempt >= 1) {
                            finishOnMain();
                            return;
                        }
                        main.postDelayed(() -> evaluate(1), RETRY_DELAY_MS);
                    });
        }

        /** 主线程上的唯一出口：撤掉排着的回调、拆视图、销毁 WebView、放闩。 */
        private void finishOnMain() {
            if (!finished.compareAndSet(false, true)) return;
            main.removeCallbacksAndMessages(null);
            WebView view = webView;
            webView = null;
            ViewGroup parent = host;
            host = null;
            if (view != null) {
                try {
                    view.stopLoading();
                } catch (Throwable ignored) {
                }
                try {
                    view.loadUrl("about:blank");
                } catch (Throwable ignored) {
                }
                if (parent != null) {
                    try {
                        parent.removeView(view);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    view.removeAllViews();
                } catch (Throwable ignored) {
                }
                try {
                    view.destroy();
                } catch (Throwable ignored) {
                }
            }
            AppLog.w(
                    TAG,
                    "probe done host="
                            + hostOf(url)
                            + " len="
                            + (result == null ? 0 : result.length()));
            done.countDown();
        }

        /** 页面装载完成就取一次；跳转链上可能来好几次，{@link #evaluate} 自己会挡住重复。 */
        private class BaseClient extends WebViewClient {
            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                if (finished.get()) return;
                evaluate(0);
            }
        }

        /**
         * API 26+ 的渲染进程守护：WebView 渲染进程被杀时默认返回 false 会让应用崩溃，这里按失败收尾。
         * 单独一个子类，避免老系统上加载到不认识的方法签名（与 {@link WebViewJsEngine} 同一套写法）。
         */
        private final class Api26Client extends BaseClient {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                AppLog.w(TAG, "probe render process gone");
                finishOnMain();
                return true;
            }
        }
    }

    /** {@code name=value; name2=value2} → 有序表；值里的 {@code =} 保留（randsk 这类 base64 会带）。 */
    static Map<String, String> parseCookies(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = text(header).trim();
        if (raw.isEmpty()) return out;
        for (String pair : raw.split(";")) {
            String item = pair.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            String name = item.substring(0, eq).trim();
            String value = item.substring(eq + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            out.put(name, value);
        }
        return out;
    }

    /** {@code evaluateJavascript} 回来的是 JSON：字符串要剥一层引号，{@code null} 当空。 */
    private static String decode(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty() || "null".equals(value)) return "";
        try {
            Object decoded = new JSONTokener(value).nextValue();
            if (decoded == null || decoded == org.json.JSONObject.NULL) return "";
            return text(String.valueOf(decoded)).trim();
        } catch (Exception error) {
            return "";
        }
    }

    private static String hostOf(String url) {
        try {
            String host = new java.net.URL(text(url).trim()).getHost();
            return host == null || host.isEmpty() ? "-" : host;
        } catch (Exception ignored) {
            return "-";
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
