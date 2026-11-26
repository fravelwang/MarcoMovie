package com.pilipala.marcomovie;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "默认源没线路时，换一个能播的"的执行侧：候选收集、60 秒记忆、并发探测、验流、换站与提示。
 *
 * <p>决策不在这里：该先探谁、该选谁、能不能现在就定，都由纯 Java 的 {@link SourcePlan} 回答。
 * 本类只做三件事：把账本里的站整理成 {@link SourcePlan.Candidate}（"名字里带不带网盘"这个判据
 * 在 {@link SourcePreferences#netdiskNamed} 算，领域层不猜站点命名）；把探测结果换算成
 * {@link SourcePlan.Capability}；按 {@link SourcePlan#settled} / {@link SourcePlan#pick} 的
 * 答案换站并提示。
 *
 * <p>账本序是播放偏好序，不保证这些站真的有集（网盘站可以把一部片绑上却零线路）。所以不是盲目
 * 跳到下一个站，而是并发探前 {@link #AUTO_SWITCH_PROBE_LIMIT} 个候选，取第一个真能播的。
 *
 * <p>"真能播"要过两道，缺一道就是"换过去播不了"（Boss 2026-11-26 报的：虎斑线路坏，自动切到
 * 天堂，天堂整站 403，于是播放失败）：
 *
 * <ol>
 *   <li>详情能解析、有线路、有集 —— 原来就有的那道，但线路要过 {@link LineHealth}：一条线路被标成
 *       坏（线路问题/解析问题/片源问题/设备问题）就不算，口径同 {@link TvDetailHost#hasUsableLine}；
 *   <li>第一集的 {@code http(s)} 直链真发一次 {@code Range: bytes=0-1} 的 GET —— 详情正常、
 *       流 403 的站（天堂 {@code vip.dytt-kan.com} 对我们出口 IP 整站 403）就是被这道挡住的。
 * </ol>
 *
 * <p>只跳一次：换站走 {@link MainActivity#openListing}，目标站会重建页面；探过的站记在
 * {@link #AUTO_SWITCH_TRIED} 里 {@link #AUTO_SWITCH_RETRY_MS} 毫秒，同一部片不会因为反复进详情
 * 变成无休止的换站接力。账本里的站全都播不了时，{@link #switchIfDefaultHasNoLines} 这条来路会
 * 回落到"按标题搜索"（{@link #crossSiteSearch}）：账本外的站（例如能播的 360资源）只有这条路
 * 走得到，而自动换源只在账本内挑。
 *
 * <p>探测直接走防腐层（{@link FongMiGateway#detailRaw} + {@link FongMiGateway#parseDetail}），
 * 不走 {@code SourceController.loadDetail}：那条路会往 UI 投递、会写详情缓存，探测不能有副作用。
 * 两道都在后台线程池里跑，UI 线程只做"换站/提示"这一步。
 */
final class SourceAutoSwitch {
    /** How many ledger-known sites one automatic switch may probe in the background. */
    private static final int AUTO_SWITCH_PROBE_LIMIT = 6;

    /** A site probed by the automatic switch is not probed again for this long. */
    private static final long AUTO_SWITCH_RETRY_MS = 60000L;

    /** 验流的并发上限：一条流一次请求，四条一起探，最慢的那条不拖住其余候选。 */
    private static final int STREAM_PROBE_THREADS = 4;

    /** 单个候选的验流超时（连接与读取各自封顶）；整轮还有预算时以预算剩余为准。 */
    private static final int STREAM_PROBE_TIMEOUT_MS = 3000;

    /** 一整轮验流的总预算，从这一轮第一次验流发出算起；到点按已拿到的结果决定。 */
    private static final long STREAM_PROBE_BUDGET_MS = 6000L;

    /** 验流的日志头：真机验收就看这个 tag 下每个候选的结论。 */
    private static final String STREAM_PROBE_TAG = "SourceProbe";

    /**
     * 验流专用线程池：最多 {@link #STREAM_PROBE_THREADS} 条并发，与详情池分开。
     *
     * <p>为什么不复用 {@code a.details}：详情池只有 4 条线程，验流一次要占住其中一条到 3 秒，
     * 换站后新页面的 loadDetail 就得排在它后面；而验流是可以独立排队的，混在一起等于让"探能不能
     * 播"拖住"真的要播"。
     */
    private static final ExecutorService STREAM_PROBE_POOL =
            Executors.newFixedThreadPool(
                    STREAM_PROBE_THREADS,
                    runnable -> {
                        Thread thread = new Thread(runnable, "movie-source-probe");
                        thread.setDaemon(true);
                        return thread;
                    });

    /**
     * Sites already probed for a title by the automatic switch: {@code normalized title|site key}
     * → the clock of that probe. Only touched from the UI thread, and pruned as it is read, so a
     * title whose probes all failed cannot turn one open into an endless relay of switches.
     */
    private static final Map<String, Long> AUTO_SWITCH_TRIED = new HashMap<>();

    /**
     * 刚刚被"整轮探光"的片：{@code normalized title} → 那一轮的时钟，存活时间同为
     * {@link #AUTO_SWITCH_RETRY_MS}，读一次就消费掉。
     *
     * <p>为什么要单独记一笔：网盘默认源先走 {@link #probeAhead}，它 15 秒就开始探，详情却可能 31 秒
     * 才报错；等失败分支 {@link #switchIfDefaultHasNoLines} 再来时，同一批站已经被
     * {@link #AUTO_SWITCH_TRIED} 的 60 秒记忆挡掉、一个候选都拿不到，跨站回落（搜索账本外的来源）
     * 就永远走不到。所以"整轮全灭"这件事按片记下来，让稍后那一次失败能接上它。
     *
     * <p>为什么读一次就删：它只负责把"上一轮已经探光了"这个结论递给紧随其后的那次失败，不是给同一
     * 部片挂一张长期通行证 —— 留在表里的话，用户从搜索页再点进这部片、再失败一次，就又被弹回搜索页，
     * 变成"搜索页 ↔ 失败详情"来回跳。
     */
    private static final Map<String, Long> AUTO_SWITCH_EXHAUSTED = new HashMap<>();

    private SourceAutoSwitch() {}

    /**
     * "默认源没线路时，换一个能播的": the detail answered with no playable episode, but the source
     * ledger knows other sites that carry this title. Probe the first few in the background and
     * open the first one that really plays; the order and the choice are {@link SourcePlan}'s.
     *
     * <p>"Playable" is not the same as "the right show": a share-link site can bind a title and
     * hand back another show's file names (a mislabelled share). So a candidate whose episodes
     * carry at least one plain {@code http(s)} stream (the collection sites' m3u8/mp4) outranks one
     * whose episodes are only share ids; {@link SourcePlan#pick} encodes that, and the ledger order
     * decides inside each class.
     *
     * <p>"Playable" is also not the same as "the stream answers": 天堂 hands back a perfectly normal
     * detail whose every episode lives on {@code vip.dytt-kan.com}, a host that 403s this box
     * outright. So a plain stream is only accepted after the first-byte probe in
     * {@link Round#verify} answers 200/206; a candidate the probe refutes is answered
     * {@link SourcePlan.Capability#NONE} and dropped.
     *
     * <p>One hop only: the switch goes through {@link MainActivity#openListing}, which rebuilds the
     * page on the target site, and the probed sites are remembered for
     * {@link #AUTO_SWITCH_RETRY_MS} so the same probe cannot repeat in a loop. When no candidate
     * plays (the usual cause on this box: the ledger's sites are all dead for that title), the
     * caller's exhausted state is restored first and then the title goes out to
     * {@link #crossSiteSearch}: the ledger only ever holds a handful of sites, and the ones that can
     * play are often outside it.
     *
     * @return true when a probe round was dispatched; the page may switch asynchronously later
     */
    static boolean switchIfDefaultHasNoLines(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            TvDetailHost.Valid valid,
            Runnable onExhausted) {
        if (a == null || site == null || video == null || valid == null) return false;
        // A local-drive listing is a file on disk, not a site: never hand it to a web source.
        if (LocalMediaLibrary.LOCAL_SITE_KEY.equals(site.key)) return false;
        SourceLedger.Sources known = SourceLedger.resolve(a, video);
        if (known == null || known.isEmpty()) return false;
        // 默认源已经判定失败，所以提示词说"没有可播放线路"。
        boolean dispatched =
                probeAndSwitch(
                        a,
                        site,
                        video,
                        valid,
                        known,
                        "没有可播放线路",
                        crossSiteSearch(a, video, onExhausted),
                        true);
        if (dispatched) return true;
        // 这一轮派不出去（账本里没有可探的候选），但这部片刚被上一轮探光过 —— 典型来路是网盘默认源：
        // probeAhead 15 秒就先探了一轮、全灭，详情到 31 秒才报错，此时 60 秒记忆让上面的探测一个候选
        // 都拿不到。跨站回落不能因为这段记忆丢掉，否则"账本里都不行"的结论就白下了。
        if (!consumeExhausted(video)) return false;
        a.ui.post(
                () -> {
                    if (!valid.get()) return;
                    crossSiteSearch(a, video, onExhausted).run();
                });
        return true;
    }

    /**
     * 默认源是网盘站时，如果它过了 {@link TvDetailHost#PROBE_AHEAD_DELAY_MS} 还没把详情拿回来，就在后台
     * 探其它候选；探到能播的来源就换过去，不等默认源那一次超时（网盘站的详情请求实测能到 31 秒）。
     *
     * <p>不是立刻探：网盘站常常是原盘/高码率（账本当初就是"网盘优先"），15 秒内答得出来的站不该因为
     * 别的站先答了个直链就被换掉。所以先挂一个延迟任务，到点再看两件事——页面还有效
     * （{@code valid.get()}）、这部片的详情还没落地（{@code settled[0]}）；详情已经落地就直接放弃，
     * 交给详情自己的分支（能播就已开播，不能播由失败分支的 {@link #switchIfDefaultHasNoLines} 兜底）。
     *
     * <p>规则与 {@link #switchIfDefaultHasNoLines} 完全一样：同一套候选、同样的顺序与上限、同一份
     * {@link #AUTO_SWITCH_TRIED} 60 秒记忆、同一套直链优先、同一条 {@link Round#verify} 验流、只跳
     * 一次。差别只有三处：这里的延迟与"详情还没落地"这道闸门、换站提示说的是"加载慢"而不是"没有
     * 可播放线路"，以及一轮全灭时**不去跨站搜索**——默认源这会儿只是慢，还没失败，把用户从这段
     * 等待里拽到搜索页是抢跑。
     *
     * <p>记忆仍在真正派发探测时写，所以默认源稍后真的失败、走到 {@link #switchIfDefaultHasNoLines} 时，
     * 不会把同一批站再探一遍。但"这一轮已经探光了"这件事会经 {@link #AUTO_SWITCH_EXHAUSTED} 递过去：
     * 那次失败照样能走到跨站回落，不至于因为 60 秒记忆而停在死路上。
     *
     * @param settled 详情是否已经落地（{@code TvDetailHost} 的 {@code detailSettled}）；null 当"还没落地"
     * @param onExhausted 一轮探完全部候选、一个能播的都没有时回调（UI 线程，只回调一次，可 null）
     * @return true 表示延迟检查已挂上；页面可能稍后异步换站
     */
    static boolean probeAhead(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            TvDetailHost.Valid valid,
            boolean[] settled,
            Runnable onExhausted) {
        if (a == null || site == null || video == null || valid == null) return false;
        if (LocalMediaLibrary.LOCAL_SITE_KEY.equals(site.key)) return false;
        // 只有网盘站的详情会慢到值得提前探；普通站慢是另一回事，不在这里赌。
        if (!SourcePreferences.netdiskNamed(site)) return false;
        final SourceLedger.Sources known = SourceLedger.resolve(a, video);
        // "第一次打开别再等两分钟"同一条判据：账本只认识一个站时没有候选可探。
        if (known == null || known.count() < 2) return false;
        if (!hasOrdinaryCandidate(known, site.key)) return false;
        a.ui.postDelayed(
                () -> {
                    if (!valid.get()) return;
                    // 详情已经回来了：能播就已开播，不能播也已经由失败分支接手；这里不再插一脚。
                    if (settled != null && settled[0]) return;
                    // 默认源这会儿只是在加载，还没失败。
                    probeAndSwitch(
                            a, site, video, valid, known, "加载慢", onExhausted, false);
                },
                TvDetailHost.PROBE_AHEAD_DELAY_MS);
        return true;
    }

    /**
     * 探测与换站的唯一实现：{@link #switchIfDefaultHasNoLines} 与 {@link #probeAhead} 都走这里，
     * 区别只有进门的闸门、{@code why} 那句提示词，以及一轮全灭时收不收尾到跨站搜索
     * （{@code crossSiteFallback}）。
     *
     * <p>{@code onExhausted} 是"一轮探完、一个能播的都没有"的收尾通知：调用方在派发探测时往往已经把
     * 页面切成"正在尝试其他来源…"，探完没命中必须让它收回去，否则那句过渡态就成了新的死路。所以这里
     * 只在 {@link SourcePlan#pick} 仍为空、这一轮从没换过站（{@code claimed} 一直 false）时回调一次。
     * 成功换站不回调。
     *
     * @param crossSiteFallback 这一轮全灭时由 {@code onExhausted} 自己收尾到跨站搜索：为真时不再留
     *     {@link #AUTO_SWITCH_EXHAUSTED} 那张便条，否则同一部片会被"整轮全灭"的结论拽两次搜索页
     */
    private static boolean probeAndSwitch(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            TvDetailHost.Valid valid,
            SourceLedger.Sources known,
            String why,
            Runnable onExhausted,
            boolean crossSiteFallback) {
        final long now = android.os.SystemClock.elapsedRealtime();
        final List<Cms.Site> candidates = new ArrayList<>();
        final List<Cms.Video> listings = new ArrayList<>();
        final List<SiteRef> probed = new ArrayList<>();
        final Map<String, Cms.Site> siteByKey = new HashMap<>();
        final Map<String, Cms.Video> listingByKey = new HashMap<>();
        synchronized (AUTO_SWITCH_TRIED) {
            // 60 秒记忆：先清过期项，再把"这部片这次已经探过的站"交给领域层跳过。
            for (Iterator<Long> it = AUTO_SWITCH_TRIED.values().iterator(); it.hasNext(); ) {
                if (now - it.next() > AUTO_SWITCH_RETRY_MS) it.remove();
            }
            final List<SourcePlan.Candidate> eligible = new ArrayList<>();
            final Set<String> skip = new HashSet<>();
            for (Cms.Site candidate : known.sites) {
                if (candidate == null || candidate.key == null) continue;
                Cms.Video listing = known.listingAt(candidate.key);
                if (listing == null || listing.id == null || listing.id.trim().isEmpty()) continue;
                if (AUTO_SWITCH_TRIED.containsKey(autoSwitchKey(video, candidate.key))) {
                    skip.add(candidate.key);
                }
                eligible.add(
                        new SourcePlan.Candidate(
                                new SiteRef(candidate.key, candidate.name),
                                SourcePreferences.netdiskNamed(candidate)));
                siteByKey.put(candidate.key, candidate);
                listingByKey.put(candidate.key, listing);
            }
            // 顺序（普通站优先、同档账本序）和上限都由领域层给；这里只记住"探了哪几个"。
            for (SourcePlan.Candidate chosen :
                    SourcePlan.probeOrder(
                            eligible, site.key, skip, AUTO_SWITCH_PROBE_LIMIT)) {
                String key = chosen.site.key;
                candidates.add(siteByKey.get(key));
                listings.add(listingByKey.get(key));
                probed.add(chosen.site);
                AUTO_SWITCH_TRIED.put(autoSwitchKey(video, key), now);
            }
        }
        if (candidates.isEmpty()) return false;

        final Round round =
                new Round(
                        a,
                        site,
                        video,
                        valid,
                        why,
                        onExhausted,
                        crossSiteFallback,
                        probed,
                        siteByKey,
                        listingByKey);
        final FongMiGateway gateway = new FongMiGateway(a);
        // 探测这一趟属于哪个引擎世代：换代之后这次取到的原文不能再按新键写进缓存。
        final int probeEpoch = a.engineEpoch;
        for (int i = 0; i < candidates.size(); i++) {
            // 换站已经定了就不再派发后面的候选：这些线程要留给换站后新页面的 loadDetail。
            if (round.closed.get()) break;
            final Cms.Site candidateSite = candidates.get(i);
            final Cms.Video listing = listings.get(i);
            final SiteRef siteRef = probed.get(i);
            RequestScope.submit(
                    a.details,
                    () -> {
                        // 同一句短路放在任务体最前面：已经排进池子的探测，一旦换站定了就不再打网络，
                        // 让出线程给新页面的详情请求（否则缓存命中也得排在它们后面）。
                        if (round.closed.get()) return;
                        DetailVerdict verdict = DetailVerdict.NONE;
                        try {
                            String raw = gateway.detailRaw(siteRef, listing.id, null);
                            MediaDetail detail = gateway.parseDetail(raw);
                            if (detail != null && detail.playable()) {
                                verdict = verdictFor(a, candidateSite, detail);
                                // 这趟网络代价已经付过了：把原文喂给详情缓存，换站后新页面按同一个
                                // id 取详情时直接命中，不必再等第二趟（写前 rememberDetail 再判可播）。
                                if (raw != null && probeEpoch == a.engineEpoch) {
                                    SourceController.rememberDetail(
                                            a, candidateSite, listing.id, raw);
                                }
                            }
                        } catch (GatewayException failure) {
                            AppLog.w(
                                    "AutoSwitch",
                                    siteRef.key
                                            + " probe: "
                                            + failure.kind
                                            + " "
                                            + failure.getMessage());
                        } catch (Throwable failure) {
                            AppLog.w("AutoSwitch", siteRef.key + " probe: " + failure);
                        }
                        if (verdict.target == null) {
                            round.answer(siteRef, verdict.capability);
                        } else {
                            // 详情这一关过了，还要这一集的流真答一次才敢换过去。
                            round.verify(siteRef, candidateSite, verdict.target);
                        }
                    });
        }
        return true;
    }

    /**
     * 账本里一个站都播不了时的跨站回落：按标题走既有的搜索入口，找账本外的来源。
     *
     * <p>为什么是搜索而不是新页面：账本外的站（这部片的 360资源 就是一例）只有搜索能找到，
     * {@code MainActivity.openSearch} 是现成的、电视与手机两条壳都认的入口，本卡不新造页面。
     *
     * <p>顺序是先收尾再切页：调用方的 {@code onExhausted} 负责把那句"正在尝试其他来源…"换回它
     * 自己的死路文案，页面随后就被搜索页替掉，不留半句过渡态。
     */
    private static Runnable crossSiteSearch(
            MainActivity a, Cms.Video video, Runnable onExhausted) {
        return () -> {
            if (onExhausted != null) onExhausted.run();
            String title = video == null || video.title == null ? "" : video.title.trim();
            if (title.isEmpty()) return;
            AppLog.w("AutoSwitch", "ledger exhausted, cross-site search for " + title);
            a.toast("账本里的站都播不了，已去搜索其他来源");
            a.openSearch(title);
        };
    }

    /**
     * 一轮换源探测的状态：候选、探测序、已答答案、验流预算，以及"只做一次"的两道闸门。
     *
     * <p>为什么单独成类：一个候选现在要过两道（详情能不能播、这一集的流答不答），两道可能落在不同
     * 线程上；而判"这轮定了没"、判"该换谁"、探完之后收不收尾，用的又是同一份答案表与同一把锁。
     * 验流预算到期时还要有人替没答完的站收尾，那段代码要跑的就是"某个站刚答完"那套判定，所以从
     * {@code probeAndSwitch} 里提出来，两边共用一份。
     */
    private static final class Round {
        final MainActivity a;
        final Cms.Site from;
        final Cms.Video video;
        final TvDetailHost.Valid valid;
        final String why;
        final Runnable onExhausted;
        /** 这一轮全灭时 {@code onExhausted} 自己会收尾到跨站搜索（见 {@link #exhaust} 的便条）。 */
        final boolean crossSiteFallback;
        final List<SiteRef> probed;
        final Map<String, Cms.Site> siteByKey;
        final Map<String, Cms.Video> listingByKey;
        /** 已答的站：site key → 能力；缺项按"还没答"处理（{@link SourcePlan#settled} 的输入）。 */
        final Map<String, SourcePlan.Capability> answers = new HashMap<>();

        final Object lock = new Object();
        /** 换站已经定了：后面的探测/验流不再打网络、也不再改判定。 */
        final AtomicBoolean claimed = new AtomicBoolean();
        /** "一轮全灭"的收尾只做一次：几个线程可能同时看到答案表满。 */
        final AtomicBoolean exhausted = new AtomicBoolean();
        /** 这一轮已经收口（换了站或收尾了）：迟到的答案一律丢掉，免得页面被改两次。 */
        final AtomicBoolean closed = new AtomicBoolean();
        /** 验流总预算的截止时刻（elapsedRealtime）；0 表示这一轮还没有人开始验流。 */
        final AtomicLong streamDeadline = new AtomicLong();

        Round(
                MainActivity a,
                Cms.Site from,
                Cms.Video video,
                TvDetailHost.Valid valid,
                String why,
                Runnable onExhausted,
                boolean crossSiteFallback,
                List<SiteRef> probed,
                Map<String, Cms.Site> siteByKey,
                Map<String, Cms.Video> listingByKey) {
            this.a = a;
            this.from = from;
            this.video = video;
            this.valid = valid;
            this.why = why;
            this.onExhausted = onExhausted;
            this.crossSiteFallback = crossSiteFallback;
            this.probed = probed;
            this.siteByKey = siteByKey;
            this.listingByKey = listingByKey;
        }

        /** 记一个候选的答复；答案表够判了就判，判完要么换站、要么收尾。 */
        void answer(SiteRef ref, SourcePlan.Capability capability) {
            if (ref == null) return;
            Cms.Site target = null;
            Cms.Video targetListing = null;
            boolean direct = false;
            synchronized (lock) {
                if (closed.get()) return;
                answers.put(ref.key, capability);
                // 已经跳过一次就不再判；否则等领域层说"这个答案不会被后面的站推翻"。
                // 出现直链就立刻定，剩下的站还在飞也不等（不拿最慢的站拖住起播）。
                if (!SourcePlan.settled(probed, answers)) return;
                SiteRef winner = SourcePlan.pick(probed, answers);
                if (winner == null) {
                    // 全部候选都答完、一个能播的都没有：收尾一次，这一轮到此为止。
                    if (claimed.get() || !exhausted.compareAndSet(false, true)) return;
                    closed.set(true);
                } else {
                    if (claimed.get()) return;
                    claimed.set(true);
                    closed.set(true);
                    target = siteByKey.get(winner.key);
                    targetListing = listingByKey.get(winner.key);
                    direct = answers.get(winner.key) == SourcePlan.Capability.DIRECT;
                }
            }
            if (target == null) exhaust();
            else switchTo(target, targetListing, direct);
        }

        /**
         * 真发一次流请求再定这个候选：详情和直链都可能"看着没问题"，只有流的第一个字节不会撒谎。
         *
         * <p>请求落在 {@link #STREAM_PROBE_POOL}（最多四条并发，不占详情池），单条超时
         * {@link #STREAM_PROBE_TIMEOUT_MS}，整轮从第一次验流算起 {@link #STREAM_PROBE_BUDGET_MS}；
         * 预算到点由 {@link #expire} 按已拿到的结果收尾，不把一个"换源"卡在两条慢流上。
         *
         * <p>判据只说状态码：{@code 200}/{@code 206} 算能播；{@code 403}/{@code 404}/{@code 410}/
         * {@code 5xx}/连不上算不可用（天堂就是这个 403）；其余状态码（例如 401、416、429）既不证明
         * 能播也不证明播不了 —— 按"没有反证"处理，保留这个候选并打日志。理由：本机只发一次
         * {@code Range} 请求，某些 CDN 对它回 416/429 而播放器能正常播，为这个判死一个真能播的站，
         * 比漏掉一个播不了的站更伤（漏掉的还有跨站搜索兜底，误杀的会让用户白等一轮）。
         */
        void verify(final SiteRef ref, final Cms.Site site, final DirectTarget target) {
            final long deadline = beginStreamRound();
            if (deadline <= 0L) {
                answer(ref, SourcePlan.Capability.NONE);
                return;
            }
            final String name = sourceName(site);
            RequestScope.submit(
                    STREAM_PROBE_POOL,
                    () -> {
                        if (closed.get()) return;
                        long left = deadline - android.os.SystemClock.elapsedRealtime();
                        if (left <= 0L) {
                            // 预算已经用完：这一轮按"验不出来"收口，让已知道的结果先落地。
                            AppLog.w(
                                    STREAM_PROBE_TAG,
                                    "site=" + name + " line=" + target.lineName + " -> skip");
                            answer(ref, SourcePlan.Capability.NONE);
                            return;
                        }
                        int timeout = (int) Math.min((long) STREAM_PROBE_TIMEOUT_MS, left);
                        int code = streamStatus(target, timeout);
                        if (code == 200 || code == 206) {
                            AppLog.w(
                                    STREAM_PROBE_TAG,
                                    "site="
                                            + name
                                            + " line="
                                            + target.lineName
                                            + " http="
                                            + code
                                            + " -> ok");
                            answer(ref, SourcePlan.Capability.DIRECT);
                        } else if (isDeadStatus(code)) {
                            AppLog.w(
                                    STREAM_PROBE_TAG,
                                    "site="
                                            + name
                                            + " line="
                                            + target.lineName
                                            + " http="
                                            + code
                                            + " -> skip");
                            answer(ref, SourcePlan.Capability.NONE);
                        } else {
                            AppLog.w(
                                    STREAM_PROBE_TAG,
                                    "site="
                                            + name
                                            + " line="
                                            + target.lineName
                                            + " http="
                                            + code
                                            + " -> unknown, keep");
                            answer(ref, SourcePlan.Capability.DIRECT);
                        }
                    });
        }

        /** 这一轮验流的总截止时刻；第一个进来验流的候选负责定它并挂上预算到点的收尾。 */
        private long beginStreamRound() {
            long known = streamDeadline.get();
            if (known != 0L) return known;
            final long deadline =
                    android.os.SystemClock.elapsedRealtime() + STREAM_PROBE_BUDGET_MS;
            if (!streamDeadline.compareAndSet(0L, deadline)) return streamDeadline.get();
            // 到点收尾必须由一个人来催：验流自己只会在"轮到自己"时看预算，排在队列尾的候选可能
            // 一直轮不到，那样整轮就没有人判"定不定"。
            a.ui.postDelayed(this::expire, STREAM_PROBE_BUDGET_MS);
            return deadline;
        }

        /**
         * 验流预算到期：还没答的站按"验不出来"记 NONE，然后照常判定一次。
         *
         * <p>为什么必须有人替它们答：{@link SourcePlan#settled} 要求探测序的前缀全都答过，少一个
         * 没答的站，整轮就永远"定不了"——页面停在"正在尝试其他来源…"，既没换站也没收尾。
         */
        private void expire() {
            if (closed.get()) return;
            if (!valid.get()) {
                closed.set(true);
                return;
            }
            List<SiteRef> pending = new ArrayList<>();
            synchronized (lock) {
                if (closed.get()) return;
                for (SiteRef ref : probed) {
                    if (ref != null && !answers.containsKey(ref.key)) pending.add(ref);
                }
            }
            if (pending.isEmpty()) return;
            AppLog.w(STREAM_PROBE_TAG, "stream budget spent, undecided=" + pending.size());
            for (SiteRef ref : pending) answer(ref, SourcePlan.Capability.NONE);
        }

        /** 换站：与以前同一套动作，只是判据多过了验流这一道。 */
        private void switchTo(Cms.Site target, Cms.Video targetListing, boolean direct) {
            final String title = title();
            final String fromName = sourceName(from);
            AppLog.w(
                    "AutoSwitch",
                    "switch to "
                            + target.key
                            + (direct ? " (direct)" : " (share only)")
                            + " for "
                            + title);
            a.ui.post(
                    () -> {
                        if (!valid.get()) return;
                        // 换站交接：目标页按目标站自己的线路与「第 N 集」重新落位。
                        // 不沿用上一站的线路名/下标 —— 虎斑两条 UC 只有 2 集、天堂有
                        // 14 集，下标跨站没有意义，线路名在目标站更可能压根不存在。
                        a.prepareSiteHandover(target, targetListing, video, null, null, null);
                        // Same entry point the 换站观看 picker uses: the page is
                        // rebuilt on that site and the ledger keeps its own order.
                        a.openListing(target, targetListing);
                        // 轻提示，不打断：换站本来就不该让用户每跳一次按一下「知道了」。
                        a.toast("「" + fromName + "」" + why + "，已换到「" + sourceName(target) + "」");
                    });
        }

        /**
         * 一轮探完、一个能播的都没有：把调用方那片过渡态还回去。
         *
         * <p>顺手给"这部片被探光了"留张便条，但只在这一轮自己不搜索时留（{@code crossSiteFallback}
         * 为假，也就是 {@link #probeAhead} 那条来路）：它的结论要交给紧随其后的那次失败去执行。
         * 已经搜过的那一轮不留，否则同一部片再失败一次就又被弹回搜索页。
         */
        private void exhaust() {
            AppLog.w("AutoSwitch", "no playable candidate for " + title());
            if (!crossSiteFallback) markExhausted(video);
            final Runnable callback = onExhausted;
            if (callback == null) return;
            a.ui.post(
                    () -> {
                        if (!valid.get()) return;
                        callback.run();
                    });
        }

        private String title() {
            return video == null || video.title == null ? "" : video.title;
        }
    }

    /**
     * "第一次打开别再等两分钟": the ledger knows two or more sources for this title, so the detail's
     * failure branches have a real candidate to probe ({@link #switchIfDefaultHasNoLines}) and this
     * round must not pay for the cross-site matching page — that two-minute search is only for a
     * title whose other sources the ledger really does not know.
     *
     * <p>Read by {@link MainActivity#openCuratedFallback}, which is the one place that could
     * otherwise take the page over before the probe runs: it is called from the detail-load error
     * path, i.e. before this page's own {@code onDetailError} can dispatch the probe.
     */
    static boolean prefersProbeOverCurated(MainActivity a, Cms.Video video) {
        if (a == null || video == null) return false;
        SourceLedger.Sources known = SourceLedger.resolve(a, video);
        return known != null && known.count() >= 2;
    }

    /**
     * 提前探测的闸门：账本里至少有一个"名字不带网盘、且带着自己的 listing"的站可探。全是网盘站时
     * 提前探也快不了，不如把预算留给默认源自己。判据与 {@link #probeAndSwitch} 认候选时一致。
     */
    private static boolean hasOrdinaryCandidate(SourceLedger.Sources known, String currentKey) {
        if (known == null) return false;
        for (Cms.Site candidate : known.sites) {
            if (candidate == null || candidate.key == null || candidate.key.isEmpty()) continue;
            if (currentKey != null && currentKey.equals(candidate.key)) continue;
            if (SourcePreferences.netdiskNamed(candidate)) continue;
            Cms.Video listing = known.listingAt(candidate.key);
            if (listing == null || listing.id == null || listing.id.trim().isEmpty()) continue;
            return true;
        }
        return false;
    }

    /** Site name for a user-facing sentence; falls back to the key when the config has no name. */
    private static String sourceName(Cms.Site site) {
        if (site == null) return "";
        String name = site.name == null ? "" : site.name.trim();
        if (!name.isEmpty()) return name;
        return site.key == null ? "" : site.key;
    }

    private static String autoSwitchKey(Cms.Video video, String siteKey) {
        String title = video == null ? "" : LibraryAggregator.normalizeTitle(video.title);
        return title + "|" + (siteKey == null ? "" : siteKey);
    }

    /** 记下"这部片这一轮探光了"，等紧随其后的那次失败来取（见 {@link #AUTO_SWITCH_EXHAUSTED}）。 */
    private static void markExhausted(Cms.Video video) {
        String key = exhaustedKey(video);
        if (key.isEmpty()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (AUTO_SWITCH_EXHAUSTED) {
            for (Iterator<Long> it = AUTO_SWITCH_EXHAUSTED.values().iterator(); it.hasNext(); ) {
                if (now - it.next() > AUTO_SWITCH_RETRY_MS) it.remove();
            }
            AUTO_SWITCH_EXHAUSTED.put(key, now);
        }
    }

    /**
     * {@link #AUTO_SWITCH_RETRY_MS} 内这部片被探光过吗 —— 取一次就消费掉，同一部片不会被同一条结论
     * 反复拽去搜索页。
     */
    private static boolean consumeExhausted(Cms.Video video) {
        String key = exhaustedKey(video);
        if (key.isEmpty()) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (AUTO_SWITCH_EXHAUSTED) {
            Long at = AUTO_SWITCH_EXHAUSTED.remove(key);
            return at != null && now - at <= AUTO_SWITCH_RETRY_MS;
        }
    }

    private static String exhaustedKey(Cms.Video video) {
        if (video == null || video.title == null) return "";
        return LibraryAggregator.normalizeTitle(video.title);
    }

    /**
     * 一个候选的详情查出了什么：{@link #target} 非空表示"有直链，但还得验一次流"，空表示这一关
     * 已经有答案（{@link #capability}）。
     */
    private static final class DetailVerdict {
        static final DetailVerdict NONE = new DetailVerdict(SourcePlan.Capability.NONE, null);
        static final DetailVerdict SHARE = new DetailVerdict(SourcePlan.Capability.SHARE, null);

        static DetailVerdict direct(DirectTarget target) {
            return new DetailVerdict(SourcePlan.Capability.DIRECT, target);
        }

        final SourcePlan.Capability capability;
        final DirectTarget target;

        private DetailVerdict(SourcePlan.Capability capability, DirectTarget target) {
            this.capability = capability;
            this.target = target;
        }
    }

    /** 验流要用的一条流：哪条线路、哪个地址、带什么头。 */
    private static final class DirectTarget {
        final String lineName;
        final String url;
        final Map<String, String> headers;

        DirectTarget(String lineName, String url, Map<String, String> headers) {
            this.lineName = lineName == null ? "" : lineName;
            this.url = url;
            this.headers = headers;
        }
    }

    /**
     * 详情的判据，与 {@link TvDetailHost#hasUsableLine} 同口径：有集、且这条线路没有被
     * {@link LineHealth} 标坏。
     *
     * <p>三种答案：
     *
     * <ul>
     *   <li>第一条可用线路上有 {@code http(s)} 直链 → {@link DetailVerdict#direct}（还要验流）；
     *   <li>没有直链，但有可用线路（分享式 id）→ {@link DetailVerdict#SHARE}，不验流：分享 id 要
     *       先转存换链才有地址，本机拿不到可以验的流；
     *   <li>一条可用线路都没有（全被标坏，或压根没有集）→ {@link DetailVerdict#NONE}，也不验流：
     *       换到一条已知播不了的线路上没有意义，不如把预算留给别的站。
     * </ul>
     */
    private static DetailVerdict verdictFor(MainActivity a, Cms.Site site, MediaDetail detail) {
        if (detail == null || detail.lines == null || detail.lines.isEmpty()) {
            return DetailVerdict.NONE;
        }
        final String siteKey = site == null ? null : site.key;
        final String name = sourceName(site);
        boolean usableLine = false;
        for (LineRef line : detail.lines) {
            if (line == null || line.episodes == null || line.episodes.isEmpty()) continue;
            String badge = LineHealth.badge(a, siteKey, line.name);
            // 空徽标 = 没有探测记录（未知），算可用；同一个字面量口径见 TvDetailHost.hasUsableLine。
            if (!badge.isEmpty() && !"可用".equals(badge)) {
                AppLog.w(
                        STREAM_PROBE_TAG,
                        "site=" + name + " line=" + line.name + " health=" + badge + " -> skip line");
                continue;
            }
            usableLine = true;
            for (EpisodeRef episode : line.episodes) {
                if (episode == null || episode.url == null) continue;
                String url = episode.url.trim();
                if (!isDirectUrl(url)) continue;
                return DetailVerdict.direct(new DirectTarget(line.name, url, headersFor(site)));
            }
        }
        if (!usableLine) {
            AppLog.w(STREAM_PROBE_TAG, "site=" + name + " -> no usable line, skip");
            return DetailVerdict.NONE;
        }
        return DetailVerdict.SHARE;
    }

    /**
     * True when this address is a plain {@code http(s)} stream — the m3u8/mp4 a collection site
     * serves. A share-style id ({@code fid++…++stoken++size}) is not one: resolving it needs the
     * netdisk path, so that class is only a fallback.
     */
    private static boolean isDirectUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        return url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * 验流带的头：站点自己的头（配置给的就照发，多半是 User-Agent/Referer/Cookie），加上
     * {@code Range: bytes=0-1} 只要两个字节。
     *
     * <p>{@link EpisodeRef} 不带单集头（只有名字与地址），所以这里只有站点级的；真要单集头才播得
     * 了的那种站，验流可能被判死 —— 这条代价记在"unknown 保留"那一档的注释里，用"没有反证就不判
     * 死"来兜。
     */
    private static Map<String, String> headersFor(Cms.Site site) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (site == null || site.headers == null) return headers;
        for (Map.Entry<String, String> entry : site.headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            if (key.trim().isEmpty() || value.isEmpty()) continue;
            headers.put(key, value);
        }
        return headers;
    }

    /** {@code 403/404/410/5xx} 与"连不上"（0）是明确的播不了；其余状态码不算反证。 */
    private static boolean isDeadStatus(int code) {
        return code == 0
                || code == 403
                || code == 404
                || code == 410
                || (code >= 500 && code <= 599);
    }

    /**
     * 一集直链的状态码：一次 {@code Range: bytes=0-1} 的 GET，只取响应码、不读正文，超时用
     * {@code timeoutMs} 封顶。返回 0 表示连不上（DNS/连接/超时/地址解析失败）。
     *
     * <p>为什么不复用 {@link MediaContentProbe#probe}：那个函数回答的是"这是什么格式"，把"403"和
     * "内容不是媒体"一起吞成 null；这里必须把状态码分出来 —— 既为了真机验收能看
     * {@code http=403 -> skip} 这一行，也为了区分"明确播不了"和"没有反证"。
     *
     * <p>连接走 {@link ProxyAddress#openHttpConnection}，与播放器/其它探测同一套代理设置；
     * 重定向按 HttpURLConnection 的默认行为跟随（与 {@link LineProbe} 一致）。
     */
    private static int streamStatus(DirectTarget target, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(target.url);
            connection = ProxyAddress.openHttpConnection(url);
            RequestScope.attach(connection);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            for (Map.Entry<String, String> header : target.headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setRequestProperty("Range", "bytes=0-1");
            return connection.getResponseCode();
        } catch (Throwable failure) {
            AppLog.w(STREAM_PROBE_TAG, "stream " + target.url + ": " + failure);
            return 0;
        } finally {
            if (connection != null) {
                RequestScope.detach(connection);
                connection.disconnect();
            }
        }
    }
}
