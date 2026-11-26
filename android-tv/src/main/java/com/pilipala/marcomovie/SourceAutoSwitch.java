package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "默认源没线路时，换一个能播的"的执行侧：候选收集、60 秒记忆、并发探测、换站与提示。
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
 * <p>只跳一次：换站走 {@link MainActivity#openListing}，目标站会重建页面；探过的站记在
 * {@link #AUTO_SWITCH_TRIED} 里 {@link #AUTO_SWITCH_RETRY_MS} 毫秒，同一部片不会因为反复进详情
 * 变成无休止的换站接力。一个能播的都没有时什么都不做，调用方保持它自己的"暂无线路"状态。
 *
 * <p>探测直接走防腐层（{@link FongMiGateway#detailRaw} + {@link FongMiGateway#parseDetail}），
 * 不走 {@code SourceController.loadDetail}：那条路会往 UI 投递、会写详情缓存，探测不能有副作用。
 */
final class SourceAutoSwitch {
    /** How many ledger-known sites one automatic switch may probe in the background. */
    private static final int AUTO_SWITCH_PROBE_LIMIT = 6;

    /** A site probed by the automatic switch is not probed again for this long. */
    private static final long AUTO_SWITCH_RETRY_MS = 60000L;

    /**
     * Sites already probed for a title by the automatic switch: {@code normalized title|site key}
     * → the clock of that probe. Only touched from the UI thread, and pruned as it is read, so a
     * title whose probes all failed cannot turn one open into an endless relay of switches.
     */
    private static final Map<String, Long> AUTO_SWITCH_TRIED = new HashMap<>();

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
     * <p>One hop only: the switch goes through {@link MainActivity#openListing}, which rebuilds the
     * page on the target site, and the probed sites are remembered for
     * {@link #AUTO_SWITCH_RETRY_MS} so the same probe cannot repeat in a loop. When no candidate
     * plays, nothing happens at all: the caller keeps its own "no playable lines" state.
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
        return probeAndSwitch(a, site, video, valid, known, "没有可播放线路", onExhausted);
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
     * {@link #AUTO_SWITCH_TRIED} 60 秒记忆、同一套直链优先与只跳一次。差别只有三处：这里的延迟与
     * "详情还没落地"这道闸门，以及换站提示说的是"加载慢"而不是"没有可播放线路"——默认源这次并没有
     * 失败，只是还没回来。
     *
     * <p>记忆仍在真正派发探测时写，所以默认源稍后真的失败、走到 {@link #switchIfDefaultHasNoLines} 时，
     * 不会把同一批站再探一遍；那时候选为空就什么都不做，由它自己的"暂无线路"状态兜底。
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
                    probeAndSwitch(a, site, video, valid, known, "加载慢", onExhausted);
                },
                TvDetailHost.PROBE_AHEAD_DELAY_MS);
        return true;
    }

    /**
     * 探测与换站的唯一实现：{@link #switchIfDefaultHasNoLines} 与 {@link #probeAhead} 都走这里，
     * 区别只有进门的闸门和 {@code why} 那句提示词。
     *
     * <p>{@code onExhausted} 是"一轮探完、一个能播的都没有"的收尾通知：调用方在派发探测时往往已经把
     * 页面切成"正在尝试其他来源…"，探完没命中必须让它收回去，否则那句过渡态就成了新的死路。所以这里
     * 只在 {@link SourcePlan#pick} 仍为空、这一轮从没换过站（{@code claimed} 一直 false）时回调一次。
     * 成功换站不回调。
     */
    private static boolean probeAndSwitch(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            TvDetailHost.Valid valid,
            SourceLedger.Sources known,
            String why,
            Runnable onExhausted) {
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

        final String fromName = sourceName(site);
        final String title = video.title;
        final FongMiGateway gateway = new FongMiGateway(a);
        // 探测这一趟属于哪个引擎世代：换代之后这次取到的原文不能再按新键写进缓存。
        final int probeEpoch = a.engineEpoch;
        final Map<String, SourcePlan.Capability> answers = new HashMap<>();
        final Object lock = new Object();
        final AtomicBoolean claimed = new AtomicBoolean();
        // "探完真没命中"只通知一次：几个探测线程可能同时看到 settled 为真，收尾文案只能覆盖一次。
        final AtomicBoolean exhausted = new AtomicBoolean();
        for (int i = 0; i < candidates.size(); i++) {
            // 换站已经定了就不再派发后面的候选：这些线程要留给换站后新页面的 loadDetail。
            if (claimed.get()) break;
            final Cms.Video listing = listings.get(i);
            final SiteRef siteRef = probed.get(i);
            RequestScope.submit(
                    a.details,
                    () -> {
                        // 同一句短路放在任务体最前面：已经排进池子的探测，一旦换站定了就不再打网络，
                        // 让出线程给新页面的详情请求（否则缓存命中也得排在它们后面）。
                        if (claimed.get()) return;
                        SourcePlan.Capability answer = SourcePlan.Capability.NONE;
                        String raw = null;
                        try {
                            raw = gateway.detailRaw(siteRef, listing.id, null);
                            MediaDetail detail = gateway.parseDetail(raw);
                            boolean playable = detail != null && detail.playable();
                            if (playable) {
                                answer =
                                        hasDirectEpisode(detail)
                                                ? SourcePlan.Capability.DIRECT
                                                : SourcePlan.Capability.SHARE;
                                // 这趟网络代价已经付过了：把原文喂给详情缓存，换站后新页面按同一个
                                // id 取详情时直接命中，不必再等第二趟（写前 rememberDetail 再判可播）。
                                if (raw != null && probeEpoch == a.engineEpoch) {
                                    SourceController.rememberDetail(
                                            a, siteByKey.get(siteRef.key), listing.id, raw);
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
                        SiteRef winner = null;
                        boolean direct = false;
                        boolean decided;
                        synchronized (lock) {
                            answers.put(siteRef.key, answer);
                            // 已经跳过一次就不再判；否则等领域层说"这个答案不会被后面的站推翻"。
                            // 出现直链就立刻定，剩下的站还在飞也不等（不拿最慢的站拖住起播）。
                            decided =
                                    !claimed.get()
                                            && SourcePlan.settled(probed, answers);
                            if (decided) {
                                winner = SourcePlan.pick(probed, answers);
                                direct =
                                        winner != null
                                                && answers.get(winner.key)
                                                        == SourcePlan.Capability.DIRECT;
                            }
                        }
                        if (!decided) return;
                        if (winner == null) {
                            AppLog.w("AutoSwitch", "no playable candidate for " + title);
                            // 全部候选都答完、一个能播的都没有，而且这一轮从没换过站：调用方那片
                            // "正在尝试其他来源…"必须收回去，否则那句过渡态就是新的死路。
                            // 只回调一次（同一轮），成功换站时走不到这里。
                            if (claimed.get() || !exhausted.compareAndSet(false, true)) return;
                            if (onExhausted != null) {
                                a.ui.post(
                                        () -> {
                                            if (!valid.get()) return;
                                            onExhausted.run();
                                        });
                            }
                            return;
                        }
                        if (!claimed.compareAndSet(false, true)) return;
                        final Cms.Site target = siteByKey.get(winner.key);
                        final Cms.Video targetListing = listingByKey.get(winner.key);
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
                                    // Same entry point the 换站观看 picker uses: the page is
                                    // rebuilt on that site and the ledger keeps its own order.
                                    a.openListing(target, targetListing);
                                    // 轻提示，不打断：换站本来就不该让用户每跳一次按一下「知道了」。
                                    a.toast(
                                            "「"
                                                    + fromName
                                                    + "」"
                                                    + why
                                                    + "，已换到「"
                                                    + sourceName(target)
                                                    + "」");
                                });
                    });
        }
        return true;
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

    /**
     * True when the probed detail plays from a plain {@code http(s)} address — the m3u8/mp4 a
     * collection site serves. A share-style id ({@code fid++…++stoken++size}) is not one: resolving
     * it needs the netdisk path, so that class is only a fallback.
     */
    private static boolean hasDirectEpisode(MediaDetail detail) {
        if (detail == null || detail.lines == null) return false;
        for (LineRef line : detail.lines) {
            if (line == null || line.episodes == null) continue;
            for (EpisodeRef episode : line.episodes) {
                if (episode == null || episode.url == null) continue;
                String url = episode.url.trim();
                if (url.regionMatches(true, 0, "http://", 0, 7)
                        || url.regionMatches(true, 0, "https://", 0, 8)) {
                    return true;
                }
            }
        }
        return false;
    }
}
