package com.pilipala.marcomovie;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件（王二小系 jar）真正抓过的站内详情页地址。
 *
 * <p>背景：w2x 站的详情由插件自己去站点页取，插件内部用哪套分享 id、拼哪个站根，对我们是不透明的。
 * 《两京十五日》花卷 那次插件确实把 {@code https://www.hjzhencai.top/index.php/vod/detail/id/26137.html}
 * 取到了（HTTP 200，页面里挂着活的百度分享），但插件用了一个失效的夸克分享 id 回
 * {@code 41006}，整条详情直接回空列表 —— 页面就白抓了。站点配置里又没有站根（w2x 的 {@code api}
 * 是 {@code csp_…Guard}），我们没法自己拼出这个地址，只能从插件真实发出的请求里记。
 *
 * <p>所以 {@link com.github.catvod.net.ProxyRedirectInterceptor} 每看到一个 GET 的
 * {@code /index.php/vod/detail/id/<数字>.html}（2xx）就记一条：**按 host 只留最近一条**，
 * 总容量 {@link #CAPACITY}，超了淘汰最早记的。查找时优先拿「详情 id 和影片 id 相同」的那条，
 * 插件刚抓完就回详情，正常情况下就是它。
 *
 * <p>线程：记录发生在插件网络线程，读取发生在主线/后台，两边都走 {@link #PAGES} 的锁。
 * 这里不缓存页面内容，只记地址（几行字符串）。
 */
public final class DetailPageTrace {

    static final String TAG = "DetailPage";

    /** 最多记多少个 host。w2x 站一屏几十个，32 条足够覆盖一次浏览，也不会常驻太多字符串。 */
    static final int CAPACITY = 32;

    /** 按详情 id 找：10 分钟内有效（足够覆盖"插件抓页 → 回详情 → 我们兜底"这条链）。 */
    private static final long ID_MATCH_MAX_AGE_MS = 10 * 60 * 1000L;

    /** 按「最近一条」找：3 分钟内有效，再久就不敢认它还是这次这部片了。 */
    private static final long LATEST_MAX_AGE_MS = 3 * 60 * 1000L;

    /**
     * 站内详情页形状：{@code https://host/index.php/vod/detail/id/26137.html}，允许带查询串。
     * 只认这一种（与卡里给的花卷页一致），不扩面到别的路由 —— 记错页比不记更危险。
     */
    private static final Pattern DETAIL_PAGE =
            Pattern.compile(
                    "^https?://([^/]+)/index\\.php/vod/detail/id/(\\d+)\\.html(?:[?#].*)?$",
                    Pattern.CASE_INSENSITIVE);

    /** host → 最近一条。LinkedHashMap 按插入顺序，重记同一个 host 时先删再放，等于按时间排序。 */
    private static final Map<String, Entry> PAGES =
            new LinkedHashMap<String, Entry>(CAPACITY, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > CAPACITY;
                }
            };

    private DetailPageTrace() {}

    /**
     * 记一条详情页请求。只记「GET + 2xx + 形状匹配」的：插件可能会先 302 一次，落地页那次才算数。
     *
     * @param method HTTP 方法（大小写不敏感）
     * @param url    请求 URL
     * @param code   响应码
     */
    public static void record(String method, String url, int code) {
        if (method == null || !"GET".equalsIgnoreCase(method.trim())) return;
        if (code < 200 || code >= 300) return;
        String value = url == null ? "" : url.trim();
        Matcher matcher = DETAIL_PAGE.matcher(value);
        if (!matcher.matches()) return;
        String host = matcher.group(1);
        String id = matcher.group(2);
        synchronized (PAGES) {
            PAGES.remove(host);
            PAGES.put(host, new Entry(value, id, System.currentTimeMillis()));
        }
        AppLog.i(TAG, "detail page seen host=" + host + " url=" + value);
    }

    /**
     * 给这部片找它自己的详情页：详情 id 相同、且 10 分钟内记过的那条（多条取最新）。
     * 没有就返回空串 —— 不去猜别的页，免得把别的片的分享并进来。
     */
    static String forVideo(String videoId) {
        String id = videoId == null ? "" : videoId.trim();
        if (id.isEmpty()) return "";
        long now = System.currentTimeMillis();
        synchronized (PAGES) {
            String best = "";
            long bestAt = -1;
            for (Entry entry : PAGES.values()) {
                if (now - entry.at > ID_MATCH_MAX_AGE_MS) continue;
                if (!id.equals(entry.id) || entry.at < bestAt) continue;
                best = entry.url;
                bestAt = entry.at;
            }
            return best;
        }
    }

    /**
     * 最近一条详情页（3 分钟内）。只在 {@link #forVideo} 对不上时兜底用：插件刚抓完就回详情，
     * 这个窗口里通常只有它。
     */
    static String latest() {
        long now = System.currentTimeMillis();
        synchronized (PAGES) {
            Entry newest = null;
            for (Entry entry : PAGES.values()) {
                if (now - entry.at <= LATEST_MAX_AGE_MS) newest = entry;
            }
            return newest == null ? "" : newest.url;
        }
    }

    private static final class Entry {
        final String url;
        final String id;
        final long at;

        Entry(String url, String id, long at) {
            this.url = url;
            this.id = id;
            this.at = at;
        }
    }
}
