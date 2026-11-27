package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 网盘站的详情页里，那些挂在「影片下载」区的分享本身就是线路。
 *
 * <p>背景：虎斑这类 csp_MacCMS 站，某些剧的详情页没有在线播放节点，只在下载区挂几条网盘分享
 * （百度 / 夸克 / UC / 阿里）。爬虫按 {@code vod_play_url} 解析，于是本应用要么「没有线路」，要么拿到
 * 一条四段 UC 分享 id 的线路。这几类分享都能变成本应用可播的线路，只是协议不同。
 *
 * <p>所以本类只做「公共那一半」，每家的协议在各自 provider 里（{@link NetdiskShareProvider}）：
 * <ol>
 *   <li>{@link #shouldTry}：这次详情值不值得翻页面；
 *   <li>{@link #sharesIn}：从页面 HTML 里抓分享链接（夸克 / UC / 百度 / 阿里各一条正则），按 host 分派给
 *       provider；
 *   <li>{@link #plan}：把 provider 给的线路排好名（{@code 夸克分享} / {@code UC分享} /
 *       {@code 百度分享} / {@code 阿里分享}，同一家多条追加序号）并按 {@link #CACHE} 缓存 10 分钟；
 *   <li>{@link #append}：并进当前详情，并告诉调用方哪一条最值得设为默认（集数最多的那条）。
 * </ol>
 *
 * <p>只有名字里带「网盘」的站才会进来（{@link SourcePreferences#netdiskNamed}），普通站一点开销
 * 都不加。
 *
 * <p>线程：{@link #build} 会打网络（一个详情页 + 每条分享两三次接口），**必须在后台线程调用**；
 * {@link #buildAsync} 就是给 UI 线程用的壳，结果回投到主线程，失败静默（只记日志）。
 *
 * <p>缓存：{@link #CACHE} 键 {@code 站key|详情id|夸克登录态}，10 分钟。命中直接返回，避免每次
 * 进详情都去翻页面、打接口。缓存里放的是「线路计划」（名字 + 每集的 id），命中时重新
 * {@code new Cms.Line/Episode}，不把同一批对象塞给第二部片 —— 播放器会往 episode 上写状态
 * （{@code media}），共用对象会串台。
 */
final class NetdiskShareLines {

    private static final String TAG = NetdiskShareHttp.TAG;

    /**
     * 所有已知的盘。顺序决定同一条详情里线路的先后（夸克在前：它要登录、更稳，也是 Boss 要的那条；
     * 百度在最后：它的线路播放还要账号 Cookie，但列目录本身免登录）。
     * 加一家盘 = 加一个 provider + 一条正则，这里一行。
     */
    private static final List<NetdiskShareProvider> PROVIDERS =
            Arrays.asList(
                    new QuarkShareProvider(),
                    new UcShareProvider(),
                    new BaiduShareProvider(),
                    new AliShareProvider());

    /**
     * 页面里的夸克分享：{@code https://pan.quark.cn/s/<id>}，可带 {@code ?pwd=xxxx}。
     * 页面上同一个链接会出现两三次（复制按钮的 data-clipboard-text、下载按钮的 href、标题里的正文），
     * 所以 {@link #sharesIn} 按分享 id 去重。
     */
    private static final Pattern QUARK_SHARE_LINK =
            Pattern.compile("https?://pan\\.quark\\.cn/s/[A-Za-z0-9]+(?:\\?[^\\s\"'<>]*)?");

    /** 页面里的 UC 分享：{@code https://drive.uc.cn/s/<id>}，{@code fast.uc.cn} 是同盘入口。 */
    private static final Pattern UC_SHARE_LINK =
            Pattern.compile("https?://(?:drive|fast)\\.uc\\.cn/s/[A-Za-z0-9]+(?:\\?[^\\s\"'<>]*)?");

    /**
     * 页面里的百度分享：{@code https://pan.baidu.com/s/<id>}，可带 {@code ?pwd=xxxx}。百度 id 允许
     * {@code -} 与 {@code _}（与 {@link BaiduShareProvider#matches} 同一套字符集）。
     */
    private static final Pattern BAIDU_SHARE_LINK =
            Pattern.compile(
                    "https?://pan\\.baidu\\.com/s/[A-Za-z0-9_-]+(?:\\?[^\\s\"'<>]*)?");

    /**
     * 页面里的阿里分享：{@code https://www.alipan.com/s/<id>}（新域名）与
     * {@code https://www.aliyundrive.com/s/<id>}（老域名），可带 {@code ?pwd=xxxx}。子目录形态
     * {@code /s/<id>/folder/<fid>} 也收（provider 从分享根开始列）。
     */
    private static final Pattern ALI_SHARE_LINK =
            Pattern.compile(
                    "https?://(?:www\\.)?(?:aliyundrive|alipan)\\.com/s/[A-Za-z0-9]+"
                            + "(?:/folder/[A-Za-z0-9]+)?(?:\\?[^\\s\"'<>]*)?");

    /** 抓链接的正则，一家一条；抓到之后仍按 host 交给 provider 认领（谁认谁处理）。 */
    private static final Pattern[] SHARE_LINKS = {
        QUARK_SHARE_LINK, UC_SHARE_LINK, BAIDU_SHARE_LINK, ALI_SHARE_LINK
    };

    /** 停在链接末尾的标点：有的站把链接直接写在正文里，后面跟着句号或右括号。 */
    private static final String TRAILING_PUNCTUATION = "。，、；：！？）】》,;:!?";

    /**
     * 一次最多处理几条分享：页面通常两三条（夸克 + UC + 百度 + 阿里），四条是一页的上限（四家各挂一条
     * 就正好占满；同一家挂两条时另一家可能挤不进来，这与加阿里之前的行为一致）。
     */
    private static final int MAX_SHARES = 4;

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 空结果只记 60 秒：刚转存/刚登录时的一次空结果不该让线路消失十分钟。 */
    private static final long EMPTY_CACHE_TTL_MS = 60 * 1000L;
    private static final int CACHE_MAX_ENTRIES = 64;

    /** 静态缓存，只为「同一部片反复进详情」省网络；过期或超量就先清。 */
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private NetdiskShareLines() {}

    // ------------------------------------------------------------------ 对外入口

    /**
     * 这次绑定要不要去页面里捞分享。
     *
     * <p>判据两条：这是网盘站的详情（名字带「网盘」，与自动换源用的是同一个判据），并且当前详情里
     * 没有一条 episode 长得像夸克分享 id。前者保证普通站一分钱开销都不多花。
     *
     * <p>为什么 UC 四段 id 不算「已经有了、不用补」：UC 四段 id 现在确实能播（{@link UcShareResolver}），
     * 但那是爬虫从页面抄来的一条，集数与清晰度都不保证 —— 盒子上实测《两京十五日》虎斑就是
     * 「两条 UC、各 2 集」，同一个页面里的分享才是全的。所以只要缺夸克 id 就翻一次页面；页面里没有
     * 任何分享时只花一次详情页请求，并记进缓存（10 分钟），下次进详情不再打。
     */
    static boolean shouldTry(Cms.Site site, Cms.Video video) {
        if (site == null || video == null) return false;
        if (!SourcePreferences.netdiskNamed(site)) return false;
        return !hasQuarkShareId(video);
    }

    /**
     * 后台捞一趟，成功把新线路回投到主线程。返回值只说「派没派出去」，也就是「这次详情值不值得
     * 去看页面」；后台真有没有捞到，由 {@code done} / {@code onNothing} 回答。
     *
     * <p>为什么不是同步返回 {@code false} 让调用方接着换源：这里的工作量是一个详情页 + 每条分享两三次
     * 接口，同步做会把 UI 卡住。所以失败时回调 {@code onNothing}，调用方在那里继续它原来的
     * 「换源」路径，语义和同步判断一样，只是晚几秒。
     *
     * @param done 拿到了线路（主线程）；调用方负责把它并进当前详情并就地重绑
     * @param onNothing 没拿到（没链接 / 全失效 / 没登录夸克 / 网络失败），主线程，可 null
     * @return true 表示已经派发；false 表示这次不该试（普通站 / 已有夸克线路 / 没有详情 id）
     */
    static boolean buildAsync(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            TvDetailHost.Valid valid,
            MainActivity.Done<List<Cms.Line>> done,
            Runnable onNothing) {
        if (a == null || site == null || video == null) return false;
        if (!shouldTry(site, video)) return false;
        final String detailId = text(video.id).trim();
        if (detailId.isEmpty()) return false;
        AppLog.w(TAG, "try " + text(site.key) + " " + detailId);
        // 走通用 worker 而不是 details：details 池要留给「换页/换站」的详情请求，这里只是顺手捞一趟。
        RequestScope.submit(
                a.worker,
                () -> {
                    List<Cms.Line> lines = null;
                    try {
                        lines = build(a, site, detailId);
                    } catch (Throwable error) {
                        AppLog.w(TAG, "build failed", error);
                    }
                    final List<Cms.Line> ready = lines;
                    a.ui.post(
                            () -> {
                                if (valid != null && !valid.get()) return;
                                if (ready == null || ready.isEmpty()) {
                                    if (onNothing != null) onNothing.run();
                                    return;
                                }
                                if (done == null) return;
                                try {
                                    done.accept(ready);
                                } catch (Exception error) {
                                    AppLog.w(TAG, "callback failed", error);
                                }
                            });
                });
        return true;
    }

    /**
     * 把捞到的线路并进当前详情，返回「最值得设为默认」的那条分享线下标，一条都没并进去时返回 -1。
     *
     * <p>为什么不是「第一条」：一部片的页面上可能挂着好几家的分享，集数差得很远 —— 盒子上实测
     * 《两京十五日》虎斑就是「UC分享 2 集 + 百度分享 14 集」，先并进去的是 UC，按下标取第一条就把
     * 只有 2 集的那家选成了默认。所以按 {@code episodes.size()} 取多的那条；集数一样时取先并进来的
     * （provider 顺序固定，结果稳定可预期）。
     *
     * <p>同名线路不重复加：本类只在「一条夸克 id 都没有」时才被调用，重复一般来自缓存命中后又一次
     * 绑定，跳过就能保证详情里同一家最多一条同名分享线。已经存在的同名线路也参与比较 —— 它就是这次
     * 那一家，集数以详情里的为准。
     */

    /**
     * 分享里常混进封面、字幕、主题曲：虎斑那条夸克分享的第一集就是 {@code E01.mp3}（1 分钟），
     * 它会被当成第 1 集播出来，看起来就是"点了播放只有 1 分钟、还没声音"。这里只留视频文件；
     * 一个视频都没有时保持原样（宁可给它原列表，也不要凭空少一条线路）。
     */
    private static void dropNonVideoEpisodes(Cms.Line line) {
        if (line == null || line.episodes == null || line.episodes.size() <= 1) return;
        List<Cms.Episode> dropped = new java.util.ArrayList<>();
        int videos = 0;
        for (Cms.Episode episode : line.episodes) {
            if (episode == null) continue;
            if (looksLikeVideoName(episode.name)) videos++;
            else dropped.add(episode);
        }
        // 全是非视频时保持原样：宁可给它原列表，也不要凭空少一条线路。
        if (videos > 0 && !dropped.isEmpty()) line.episodes.removeAll(dropped);
    }

    private static boolean looksLikeVideoName(String name) {
        String value = text(name).trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) return true;
        int dot = value.lastIndexOf('.');
        if (dot < 0) return true;
        String ext = value.substring(dot + 1);
        switch (ext) {
            case "mp3":
            case "m4a":
            case "flac":
            case "wav":
            case "aac":
            case "jpg":
            case "jpeg":
            case "png":
            case "webp":
            case "srt":
            case "ass":
            case "ssa":
            case "txt":
            case "nfo":
                return false;
            default:
                return true;
        }
    }

    static int append(Cms.Video video, List<Cms.Line> lines) {
        if (video == null || lines == null || lines.isEmpty()) return -1;
        int best = -1;
        int bestEpisodes = -1;
        for (Cms.Line incoming : lines) {
            if (incoming == null || incoming.episodes.isEmpty()) continue;
            dropNonVideoEpisodes(incoming);
            int index = indexOf(video, incoming.name);
            if (index < 0) {
                video.lines.add(incoming);
                index = video.lines.size() - 1;
            }
            Cms.Line merged = video.lines.get(index);
            int episodes = merged == null || merged.episodes == null ? 0 : merged.episodes.size();
            // 严格大于才换：集数相同时保住先并进来的那条。
            if (episodes > bestEpisodes) {
                bestEpisodes = episodes;
                best = index;
            }
        }
        return best;
    }

    /**
     * 同步版本：抓详情页 → 找分享 → 交给 provider 换线路。**必须在后台线程调用。**
     *
     * <p>{@code a} 是卡里定下的入口形状，留给「以后要按账号/网络设置取东西」；现在这条路只用静态
     * 依赖，所以除了判空不碰它。
     *
     * @return 新造的线路；没分享 / 全失效 / 网络失败都返回 null（不抛）
     */
    static List<Cms.Line> build(MainActivity a, Cms.Site site, String detailId) {
        if (a == null || site == null) return null;
        String id = text(detailId).trim();
        if (id.isEmpty()) return null;
        final long now = System.currentTimeMillis();
        final String key = cacheKey(site, id);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            long ttl = cached.lines.isEmpty() ? EMPTY_CACHE_TTL_MS : CACHE_TTL_MS;
            if (now - cached.at <= ttl) {
                AppLog.w(TAG, "cache hit lines=" + cached.lines.size() + " " + key);
                return materialize(cached.lines);
            }
            CACHE.remove(key);
        }
        Plan plan;
        try {
            plan = plan(site, id);
        } catch (RuntimeException error) {
            AppLog.w(TAG, "plan crashed", error);
            return null;
        }
        if (plan == null) return null;
        if (plan.cacheable) remember(key, plan.lines, now);
        return materialize(plan.lines);
    }

    // ------------------------------------------------------------------ 抓页面、找链接、分派

    /**
     * 一次完整的计划：页面 → 分享 → 各 provider 的线路。返回 null 表示这次不该记进缓存（网络失败等），
     * {@link Plan#lines} 为空表示「看过了，没有可用的」。
     */
    private static Plan plan(Cms.Site site, String detailId) {
        String pageUrl = detailUrl(site, detailId);
        if (pageUrl.isEmpty()) {
            AppLog.w(TAG, "skip: no site root in ext/api of " + text(site.key));
            return null;
        }
        String html;
        try {
            html = Cms.fetch(pageUrl, site.headers);
        } catch (Exception error) {
            AppLog.w(TAG, "detail page failed " + pageUrl + ": " + describe(error));
            return null;
        }
        List<PageShare> shares = sharesIn(html);
        if (shares.isEmpty()) {
            AppLog.w(TAG, "no share link on " + pageUrl);
            return new Plan(new ArrayList<>(), true);
        }
        AppLog.w(TAG, "shares=" + shares.size() + " on " + pageUrl);
        List<PlannedLine> lines = new ArrayList<>();
        // 同一家盘在页面上可能挂多条分享：第 N 条起线路名追加序号（线性可读，也让 append 的去重按名字生效）。
        Map<String, Integer> perProvider = new LinkedHashMap<>();
        for (PageShare share : shares) {
            NetdiskShareProvider provider = providerFor(share.url);
            if (provider == null) {
                AppLog.w(TAG, "no provider for share=" + share.url);
                continue;
            }
            List<Cms.Line> built;
            try {
                built = provider.lines(share.url, share.passcode);
            } catch (Exception error) {
                AppLog.w(
                        TAG,
                        "provider="
                                + provider.id()
                                + " failed share="
                                + share.pwdId
                                + ": "
                                + describe(error));
                continue;
            }
            if (built == null || built.isEmpty()) continue;
            for (Cms.Line line : built) {
                List<FilePlan> files = filesOf(line);
                if (files.isEmpty()) continue;
                // 序号只按"真的并进去的线路"递进：一次分享全失效时不该把下一条挤成「夸克分享 2」。
                int seq = perProvider.containsKey(provider.id()) ? perProvider.get(provider.id()) + 1 : 1;
                String name = lineName(line.name, seq);
                perProvider.put(provider.id(), seq);
                lines.add(new PlannedLine(name, files));
                AppLog.w(
                        TAG,
                        "provider="
                                + provider.id()
                                + " line="
                                + name
                                + " files="
                                + files.size()
                                + " share="
                                + share.pwdId);
            }
        }
        if (lines.isEmpty()) {
            // 有链接但一条都没换成文件（全失效 / 要提取码 / 口子变了）：不写缓存，下次进详情再试。
            AppLog.w(TAG, "shares found but none usable");
            return new Plan(new ArrayList<>(), false);
        }
        return new Plan(lines, true);
    }

    /**
     * 缓存键：把「本机有没有夸克登录态」也算进去。
     *
     * <p>没登录夸克时页面里的夸克分享捞不到（UC 那边不需要登录，照样能捞到），这时若只用
     * {@code 站|详情} 当键，扫码登录后的 10 分钟内会一直吃这份只有 UC 的旧结果，看起来像"登录没用"。
     * 加上这一位，登录后立刻换键、立刻重试。
     */
    private static String cacheKey(Cms.Site site, String detailId) {
        return text(site.key) + "|" + detailId + "|qk=" + (QuarkShareProvider.credentialReady() ? "1" : "0");
    }

    /** 按 host 找认领这条链接的 provider；都认不了返回 null。 */
    private static NetdiskShareProvider providerFor(String url) {
        for (NetdiskShareProvider provider : PROVIDERS) {
            if (provider.matches(url)) return provider;
        }
        return null;
    }

    /**
     * 线路名：provider 给的基础名（{@code 夸克分享} / {@code UC分享}），同一家第 N 条追加
     * {@code " N"}，与本次重构前夸克那套写法一致（第一条不带序号）。
     */
    private static String lineName(String base, int seq) {
        String name = text(base).trim();
        if (name.isEmpty()) name = "网盘分享";
        return seq <= 1 ? name : name + " " + seq;
    }

    /** provider 给的线路 → 缓存用的计划（名字 + 每集 id）。空 episode 的线路丢掉。 */
    private static List<FilePlan> filesOf(Cms.Line line) {
        List<FilePlan> out = new ArrayList<>();
        if (line == null || line.episodes == null) return out;
        for (Cms.Episode episode : line.episodes) {
            if (episode == null) continue;
            String id = text(episode.url).trim();
            if (id.isEmpty()) continue;
            out.add(new FilePlan(text(episode.name), id));
        }
        return out;
    }

    /** 详情页地址：站根（{@code ext.site[0]}）+ 详情 id。两者缺一就返回 ""。 */
    private static String detailUrl(Cms.Site site, String detailId) {
        String id = text(detailId).trim();
        if (id.isEmpty()) return "";
        if (isHttp(id)) return id;
        String root = siteRoot(site);
        if (root.isEmpty()) return "";
        return root + (id.startsWith("/") ? id : "/" + id);
    }

    /**
     * 站根地址。网盘站的 {@code ext} 是 JSON，{@code site} 字段是地址数组（盒子上实测
     * {@code {"site":["http://38.76.197.172:16969"]}}），数组里取第一个非空项。
     *
     * <p>其它写法按顺序兜底：{@code ext.site} 是单个字符串、{@code ext.url/base/host}、{@code ext}
     * 本身就是地址字符串、最后是 {@code api} 是 http 时取它的 origin（普通 CMS 站就是这样）。
     * 都拿不到就返回 ""，调用方当作「这个站没有可抓的详情页」。
     */
    private static String siteRoot(Cms.Site site) {
        if (site == null) return "";
        String root = "";
        Object ext = site.ext;
        if (ext instanceof JSONObject) {
            JSONObject obj = (JSONObject) ext;
            JSONArray sites = obj.optJSONArray("site");
            if (sites != null) {
                for (int i = 0; i < sites.length() && root.isEmpty(); i++) {
                    Object item = sites.opt(i);
                    if (item instanceof String) root = ((String) item).trim();
                }
            }
            if (root.isEmpty()) {
                Object single = obj.opt("site");
                if (single instanceof String) root = ((String) single).trim();
            }
            if (root.isEmpty()) {
                for (String field : new String[] {"url", "base", "host"}) {
                    String value = obj.optString(field, "").trim();
                    if (!value.isEmpty()) {
                        root = value;
                        break;
                    }
                }
            }
        } else if (ext instanceof String) {
            String value = ((String) ext).trim();
            if (isHttp(value)) root = value;
        }
        if (root.isEmpty() && isHttp(site.api)) root = origin(site.api);
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root;
    }

    /**
     * 页面里的分享链接，按分享 id 去重，最多 {@link #MAX_SHARES} 条。
     *
     * <p>两家各一条正则（只抓自己域名下的链接），抓到之后仍要过 {@code providerFor}：正则与
     * provider 的 {@code matches} 是同一套域名口径，两边漂了会在日志里留一行
     * {@code no provider for share=…}，而不是悄悄少一条线路。
     */
    private static List<PageShare> sharesIn(String html) {
        List<PageShare> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String body = text(html);
        for (Pattern pattern : SHARE_LINKS) {
            Matcher matcher = pattern.matcher(body);
            while (matcher.find() && out.size() < MAX_SHARES) {
                PageShare share = shareOf(trimLink(matcher.group()));
                if (share == null || !seen.add(share.pwdId)) continue;
                out.add(share);
            }
        }
        return out;
    }

    /** 一条链接 → 「原链接 + 分享 id + 提取码」；形状不对返回 null。 */
    private static PageShare shareOf(String url) {
        NetdiskShareHttp.ShareRef parsed = NetdiskShareHttp.parseShare(url);
        if (parsed == null) return null;
        return new PageShare(url, parsed.pwdId, parsed.passcode);
    }

    /** 去掉链接尾巴上的标点，并把 HTML 实体写法的 {@code &amp;} 还原成 {@code &}。 */
    private static String trimLink(String raw) {
        String value = text(raw).trim().replace("&amp;", "&");
        int cut = value.length();
        while (cut > 0 && TRAILING_PUNCTUATION.indexOf(value.charAt(cut - 1)) >= 0) cut--;
        return value.substring(0, cut);
    }

    // ------------------------------------------------------------------ 缓存与线路物化

    private static void remember(String key, List<PlannedLine> lines, long now) {
        if (CACHE.size() >= CACHE_MAX_ENTRIES) {
            for (Map.Entry<String, CacheEntry> entry : CACHE.entrySet()) {
                if (now - entry.getValue().at > CACHE_TTL_MS) CACHE.remove(entry.getKey());
            }
            if (CACHE.size() >= CACHE_MAX_ENTRIES) CACHE.clear();
        }
        List<PlannedLine> copy = new ArrayList<>(lines);
        CACHE.put(key, new CacheEntry(now, copy));
    }

    /** 计划 → 真正的 {@link Cms.Line}/{@link Cms.Episode}，每次都新建对象（不复用）。 */
    private static List<Cms.Line> materialize(List<PlannedLine> planned) {
        if (planned == null || planned.isEmpty()) return null;
        List<Cms.Line> out = new ArrayList<>(planned.size());
        for (PlannedLine plan : planned) {
            Cms.Line line = new Cms.Line();
            line.name = plan.name;
            for (FilePlan file : plan.files) {
                Cms.Episode episode = new Cms.Episode();
                episode.name = file.name;
                episode.url = file.id;
                line.episodes.add(episode);
            }
            if (!line.episodes.isEmpty()) out.add(line);
        }
        return out.isEmpty() ? null : out;
    }

    // ------------------------------------------------------------------ 判据小工具

    /**
     * 详情里是否已经有一条「夸克分享 id」线路。判据与 {@link QuarkShareResolver} 的解析口径一致
     * （五段、fid 32 hex、pwd_id 12 hex、stoken 是 token、size 是数字），不看线路名 —— 名字是站给的，
     * 同一批 id 可能叫「夸克原画」「quark」甚至「原盘」。
     */
    private static boolean hasQuarkShareId(Cms.Video video) {
        if (video == null || video.lines == null) return false;
        for (Cms.Line line : video.lines) {
            if (line == null || line.episodes == null) continue;
            for (Cms.Episode episode : line.episodes) {
                if (episode != null && looksLikeQuarkShareId(episode.url)) return true;
            }
        }
        return false;
    }

    /** 五段 {@code ++} 分隔、各段形状符合夸克分享 id。与 {@link QuarkShareResolver} 的判据同形。 */
    private static boolean looksLikeQuarkShareId(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return false;
        String[] segments = value.split("\\+\\+", -1);
        if (segments.length != 5) return false;
        if (!NetdiskShareHttp.isHex(segments[0], 32)) return false;
        if (!NetdiskShareHttp.isHex(segments[2], 12)) return false;
        if (!NetdiskShareHttp.isToken(segments[3])) return false;
        return isDigits(segments[4]);
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty() || value.length() > 19) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static int indexOf(Cms.Video video, String lineName) {
        if (video == null || video.lines == null || lineName == null) return -1;
        for (int i = 0; i < video.lines.size(); i++) {
            Cms.Line line = video.lines.get(i);
            if (line != null && lineName.equals(line.name)) return i;
        }
        return -1;
    }

    private static boolean isHttp(String value) {
        String text = text(value).trim().toLowerCase(java.util.Locale.ROOT);
        return text.startsWith("http://") || text.startsWith("https://");
    }

    private static String origin(String url) {
        String value = text(url).trim();
        int scheme = value.indexOf("://");
        if (scheme < 0) return "";
        int slash = value.indexOf('/', scheme + 3);
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static String describe(Throwable error) {
        return NetdiskShareHttp.describe(error);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ 小数据结构

    /** 页面里的一条分享：原链接 + 分享 id + 提取码（{@code ?pwd=} 参数，通常没有）。 */
    private static final class PageShare {
        final String url;
        final String pwdId;
        final String passcode;

        PageShare(String url, String pwdId, String passcode) {
            this.url = url;
            this.pwdId = pwdId;
            this.passcode = passcode;
        }
    }

    /** 造好 id 的一集（缓存里放的就是它）。 */
    private static final class FilePlan {
        final String name;
        final String id;

        FilePlan(String name, String id) {
            this.name = name;
            this.id = id;
        }
    }

    /** 一条线路的计划（名字 + 每集的 id）。 */
    private static final class PlannedLine {
        final String name;
        final List<FilePlan> files;

        PlannedLine(String name, List<FilePlan> files) {
            this.name = name;
            this.files = files;
        }
    }

    /** {@link #plan} 的产物：要不要记进缓存 + 计划本身。 */
    private static final class Plan {
        final List<PlannedLine> lines;
        final boolean cacheable;

        Plan(List<PlannedLine> lines, boolean cacheable) {
            this.lines = lines;
            this.cacheable = cacheable;
        }
    }

    private static final class CacheEntry {
        final long at;
        final List<PlannedLine> lines;

        CacheEntry(long at, List<PlannedLine> lines) {
            this.at = at;
            this.lines = lines;
        }
    }
}
