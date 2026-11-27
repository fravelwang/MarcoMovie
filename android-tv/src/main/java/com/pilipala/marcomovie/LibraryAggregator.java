package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Builds the cross-site library wall as a stable index: every searchable, playable site contributes
 * the first page of its home listing (or its first category), the recommendation catalog contributes
 * metadata-only cards, and both are merged by normalized title. All work is bounded so the wall can
 * never stall the home screen.
 *
 * <p>Why metadata cards: a site's home page is a 40-item sample, so a show can be on the wall one
 * round and gone the next (《两京十五日》 disappeared across two scans). The catalog adds the titles
 * Boss expects to see, as cards that open through the recommendation path until the ledger learns a
 * real source for them.
 *
 * <p>Numbers are product decisions, not tuning knobs: 12 items per site, 6 concurrent fetches, 8 s
 * per site, 15 s for the whole aggregation, 240 cards at most (200 sampled + ~40 catalog titles).
 *
 * <p>A round runs in two waves. Wave 1 only touches sites that worked last time or were never
 * tried; its outcome per site is written to {@link SiteHealth}. Only when wave 1 yields fewer than
 * {@link #MIN_OK_SITES} usable sites does wave 2 retry up to {@link #MAX_RETRY_SITES} previously
 * failing sites, oldest failure first, and only while at least {@link #MIN_RETRY_BUDGET_MS} of the
 * shared 15 s budget is left. Whatever has arrived when the budget ends is merged and rendered.
 *
 * <p>Both waves rotate. Wave 1 walks never-tried sites through {@link #CURSOR_KEY}; wave 2 walks
 * the failed set through {@link #RETRY_CURSOR_KEY}, so a long failure list is covered by
 * successive rounds instead of retrying the same oldest batch every time.
 */
final class LibraryAggregator {
    static final String TAG = "LibraryAgg";
    /**
     * Titles taken from one site's listing. Twelve only ever saw the first page of a netdisk site
     * that lists 200+ entries, so a title carried by five sites merged from two of them. Forty is
     * enough to cover a full recent page on every source while the wall's own item cap still holds.
     */
    static final int PER_SITE_LIMIT = 40;

    /**
     * Channels tried when a site's home page carries no videos. The probing itself moved to
     * {@link FongMiGateway#home}; the number stays here as the wall's single definition and is
     * referenced from there (compile-time constant, no second copy).
     */
    static final int MAX_CATEGORY_PROBES = 3;
    // Four workers leave headroom for the UI thread while the first wall is being laid out on a
    // four-core box (measured: load average 2.6-3.4 with six).
    static final int MAX_CONCURRENCY = 4;
    // A shorter per-site timeout lets one 15 s budget reach ~18 sites instead of ~11; sites that
    // need longer than this are usually dead or engine-backed anyway.
    static final long SITE_TIMEOUT_MS = 5000L;

    /**
     * Budget for building the engine once. Measured on the box (SiteCheck, 2026-11-25): the netdisk
     * spider plugins spend ~8.2 s in {@code init} before {@code home()} answers in 0.3-0.9 s. A single
     * 5 s watchdog killed every cold engine, so the wall never saw those sites at all. Engine
     * instances are cached per site key, so this cost is paid once per config epoch.
     */
    static final long ENGINE_TIMEOUT_MS = 12000L;

    /** Wave 1 only looks at this many sites per round; a cursor rotates through the rest. */
    static final int MAX_WAVE1_SITES = 36;

    /**
     * Guard 站同一轮里那次重试的预算。比首试短：首试已经用掉一整份预算（init 12 s / fetch 10 s），
     * 重试再不限量就会把整轮的 18 s 吃光；6 s 足够接住"抖一下"的那种偶发失败。
     */
    static final long GUARD_RETRY_BUDGET_MS = 6000L;

    /** Prefs key holding the rotation cursor over never-tried sites. */
    static final String CURSOR_KEY = "library_agg_cursor";

    /** Prefs key holding the rotation cursor over previously failing sites (wave 2). */
    static final String RETRY_CURSOR_KEY = "library_agg_retry_cursor";
    static final long TOTAL_TIMEOUT_MS = 18000L;
    /**
     * The wall's hard card cap. 200 sampled cards (the old limit) plus the recommendation catalog
     * (hero + up to 18 dramas + up to 18 movies, deduplicated) so metadata cards never push a
     * sampled card off the wall. This is the only place the number lives.
     */
    static final int MAX_ITEMS = 240;

    /** Wave 2 only starts when wave 1 produced fewer usable sites than this. */
    static final int MIN_OK_SITES = 8;

    /** Wave 2 is capped so a bad config cannot stretch a single round far beyond the budget. */
    static final int MAX_RETRY_SITES = 60;

    /** Wave 2 is skipped when the remaining budget cannot give a site a fair chance. */
    static final long MIN_RETRY_BUDGET_MS = 2000L;

    /** Slack on top of the retry slice so wave 1's reserved deadline cannot fail the 2 s check. */
    static final long RETRY_SLICE_SLACK_MS = 250L;

    /** Home screen reuses a finished wall for this long before aggregating again. */
    static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final Comparator<Item> WALL_ORDER =
            new Comparator<Item>() {
                public int compare(Item left, Item right) {
                    int bySources = Integer.compare(right.sites.size(), left.sites.size());
                    if (bySources != 0) return bySources;
                    String leftTitle = left.video.title == null ? "" : left.video.title;
                    String rightTitle = right.video.title == null ? "" : right.video.title;
                    int byTitle = leftTitle.compareTo(rightTitle);
                    if (byTitle != 0) return byTitle;
                    String leftSite = left.sites.isEmpty() ? "" : left.sites.get(0).key;
                    String rightSite = right.sites.isEmpty() ? "" : right.sites.get(0).key;
                    return leftSite.compareTo(rightSite);
                }
            };

    private LibraryAggregator() {}

    /** One poster card and every site that carries this title, in config order. */
    static final class Item {
        final Cms.Video video;
        final List<Cms.Site> sites = new ArrayList<>();
        final Map<String, Cms.Video> videosBySite = new LinkedHashMap<>();
        /**
         * True when only the recommendation catalog carries this title (no site sampled it). Its
         * poster and metadata come from Douban; the ledger decides whether any source can open it.
         */
        boolean curated;

        Item(Cms.Video video) {
            this.video = video;
        }
    }

    /**
     * The wall's own result for one aggregation round: the cards it produced and the sites each
     * card's listing sample happened to carry. It is deliberately <em>not</em> the source ledger —
     * {@link SourceLedger} owns "which sites carry which title" for the whole process, so a search
     * result or a detail match can never add a card the wall did not aggregate.
     */
    static final class Result {
        final List<Item> items = new ArrayList<>();
        final List<Cms.Site> targets = new ArrayList<>();
        /** Card index by exact listing object and by merge key, so any Cms.Video can ask. */
        private final Map<Cms.Video, Item> itemsByVideo = new IdentityHashMap<>();
        private final Map<String, Item> itemsByTitle = new LinkedHashMap<>();

        int okSites, emptySites, failedSites;
        boolean timedOut;
        long loadedAt;

        boolean fresh() {
            return loadedAt > 0 && System.currentTimeMillis() - loadedAt < CACHE_TTL_MS;
        }

        int totalSites() {
            return targets.size();
        }

        boolean hasContent() {
            return !items.isEmpty();
        }

        /** The wall card holding this listing, by object identity or by the merge key. */
        Item itemFor(Cms.Video video) {
            if (video == null) return null;
            Item item = itemsByVideo.get(video);
            if (item != null) return item;
            return itemsByTitle.get(normalizeTitle(video.title));
        }
    }

    /**
     * Sites eligible for the wall: searchable, openable, and not a config/netdisk utility entry.
     * Must be called on the UI thread so the config list is not mutated while it is copied.
     */
    static List<Cms.Site> targets(MainActivity a) {
        List<Cms.Site> out = new ArrayList<>();
        Cms.Config config = a == null ? null : a.config;
        if (config == null) return out;
        for (Cms.Site site : config.sites) {
            if (site == null || site.key == null || site.key.trim().isEmpty()) continue;
            if (!site.searchable) continue;
            if (SourceController.isConfigOrNetdiskSite(site)) continue;
            if (!SourceController.available(a, site)) continue;
            out.add(site);
        }
        return out;
    }

    /**
     * The wall's current card result, or null when a config change invalidated it. This answers
     * "which cards are on the wall", never "which sites carry this title" — that is
     * {@link SourceLedger}.
     */
    static Result wallCards(MainActivity a) {
        if (a == null) return null;
        synchronized (a) {
            Result result = a.libraryWall;
            return result != null && a.libraryWallEpoch == a.engineEpoch ? result : null;
        }
    }

    /**
     * Blocking aggregation. Call from a worker thread; returns a result even when every site times
     * out or throws. Failures are logged one line per site and never abort the wall.
     */
    static Result load(MainActivity a, List<Cms.Site> targets) {
        Result result = new Result();
        if (targets != null) result.targets.addAll(targets);
        if (a == null || result.targets.isEmpty()) {
            result.loadedAt = System.currentTimeMillis();
            return result;
        }

        final int count = result.targets.size();
        final SiteOutcome[] outcomes = new SiteOutcome[count];
        final ScheduledExecutorService timer =
                Executors.newSingleThreadScheduledExecutor(daemonFactory("movie-library-timer"));
        final ExecutorService pool =
                Executors.newFixedThreadPool(
                        Math.min(MAX_CONCURRENCY, count), daemonFactory("movie-library-fetch"));
        final List<FutureTask<SiteOutcome>> tasks = new ArrayList<>(count);
        final long deadline = android.os.SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS;

        // Wave 1 takes sites remembered as healthy or never tried; wave 2 is the failed retry batch.
        // Known-good sites always come first so the wall stays stable, then never-tried sites in a
        // rotating window: one round cannot try 400+ sites inside the budget, but successive rounds
        // walk through them and the good ones stick.
        final List<Integer> knownOk = new ArrayList<>(count);
        final List<Integer> untried = new ArrayList<>(count);
        final List<Integer> retry = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Cms.Site target = result.targets.get(i);
            String key = target == null ? null : target.key;
            if (SourceCooling.isCooled(key)) {
                // 冷却中的站（接口已关闭/异常）这一轮直接不探：十分钟内不花预算问它。
                AppLog.w(TAG, "source cooled skip key=" + key);
                continue;
            }
            String state = SiteHealth.state(a, key);
            if (SiteHealth.OK.equals(state)) knownOk.add(i);
            else if (SiteHealth.FAIL.equals(state)) retry.add(i);
            else untried.add(i);
        }
        final List<Integer> healthy = new ArrayList<>(MAX_WAVE1_SITES);
        // 站点偏好/置顶的站先排进 wave 1：用户把它设成默认源，就是在说「片库先取它的片」。
        // 之前这里只按 SiteHealth 排，一个慢站（w2x_ 的 Guard 首次 home 要 5 s 以上）第一次超时
        // 就会被塞进 retry，之后很多轮都轮不到，墙上永远没有它的卡。
        for (int index : preferenceFirst(a, result.targets)) {
            if (healthy.size() >= MAX_WAVE1_SITES) break;
            if (!healthy.contains(index)) healthy.add(index);
        }
        for (int index : knownOk) {
            if (healthy.size() >= MAX_WAVE1_SITES) break;
            if (!healthy.contains(index)) healthy.add(index);
        }
        int room = MAX_WAVE1_SITES - healthy.size();
        if (room > 0 && !untried.isEmpty()) {
            int cursor = Math.max(0, a.prefs == null ? 0 : a.prefs.getInt(CURSOR_KEY, 0));
            int start = cursor % untried.size();
            for (int taken = 0; taken < room && taken < untried.size(); taken++) {
                healthy.add(untried.get((start + taken) % untried.size()));
            }
            if (a.prefs != null) {
                a.prefs.edit().putInt(CURSOR_KEY, (start + room) % Math.max(1, untried.size())).apply();
            }
        }
        // Failed sites keep getting sampled inside wave 1. Health memory is a hint, not a sentence:
        // without this, once every site has been tried the wall only retries when the known-good set
        // drops below MIN_OK_SITES, so a site repaired by an app change could never come back.
        room = MAX_WAVE1_SITES - healthy.size();
        if (room > 0 && !retry.isEmpty()) {
            Collections.sort(
                    retry,
                    (left, right) ->
                            Long.compare(
                                    SiteHealth.failedAt(a, result.targets.get(left).key),
                                    SiteHealth.failedAt(a, result.targets.get(right).key)));
            int start = Math.max(0, a.prefs == null ? 0 : a.prefs.getInt(RETRY_CURSOR_KEY, 0))
                    % retry.size();
            for (int taken = 0; taken < room && taken < retry.size(); taken++) {
                healthy.add(retry.get((start + taken) % retry.size()));
            }
            if (a.prefs != null) {
                a.prefs.edit()
                        .putInt(RETRY_CURSOR_KEY, (start + room) % retry.size())
                        .apply();
            }
        }

        int wave1Ok = 0;
        int retried = 0;
        int retriedOk = 0;
        int retryStart = -1;
        try {
            // A site that ignores the per-site watchdog can keep wave 1 waiting until the shared
            // deadline. Reserve the retry slice up front so a failed site list still gets its
            // rotation instead of wave 2 being skipped every round.
            final long wave1Deadline =
                    retry.isEmpty()
                            ? deadline
                            : deadline - MIN_RETRY_BUDGET_MS - RETRY_SLICE_SLACK_MS;
            wave1Ok = fetchWave(a, result, healthy, outcomes, tasks, pool, timer, wave1Deadline);
            if (wave1Ok < MIN_OK_SITES
                    && !retry.isEmpty()
                    && deadline - android.os.SystemClock.elapsedRealtime() >= MIN_RETRY_BUDGET_MS) {
                // Oldest failure first keeps the order stable across rounds; the cursor then walks
                // that order so successive rounds sample the whole failed set instead of retrying
                // the same first batch forever.
                Collections.sort(
                        retry,
                        (left, right) ->
                                Long.compare(
                                        SiteHealth.failedAt(a, result.targets.get(left).key),
                                        SiteHealth.failedAt(a, result.targets.get(right).key)));
                final int start =
                        Math.max(0, a.prefs == null ? 0 : a.prefs.getInt(RETRY_CURSOR_KEY, 0))
                                % retry.size();
                retryStart = start;
                retried = Math.min(MAX_RETRY_SITES, retry.size());
                final List<Integer> batch = new ArrayList<>(retried);
                for (int taken = 0; taken < retried; taken++) {
                    batch.add(retry.get((start + taken) % retry.size()));
                }
                if (a.prefs != null) {
                    a.prefs.edit()
                            .putInt(RETRY_CURSOR_KEY, (start + retried) % retry.size())
                            .apply();
                }
                // Free the pool slots wave 1 left behind; a cancelled fetch reports no outcome, so
                // nothing is written to SiteHealth for it.
                for (FutureTask<SiteOutcome> task : tasks) {
                    if (!task.isDone()) task.cancel(true);
                }
                retriedOk =
                        fetchWave(a, result, batch, outcomes, tasks, pool, timer, deadline);
            }
        } catch (Throwable failure) {
            result.timedOut = true;
        } finally {
            for (FutureTask<SiteOutcome> task : tasks) {
                if (!task.isDone()) task.cancel(true);
            }
            pool.shutdownNow();
            timer.shutdownNow();
        }

        // Merge in config order so the first source of a card is stable across runs.
        for (int i = 0; i < count; i++) {
            Cms.Site site = result.targets.get(i);
            SiteOutcome outcome = outcomes[i];
            if (outcome == null) {
                // The round ended before this site started or answered: no evidence, no health write.
                result.failedSites++;
                AppLog.w(TAG, site.key + " -> fail");
                continue;
            }
            if (!outcome.usable()) {
                result.failedSites++;
                SiteHealth.markFailed(a, site);
                AppLog.w(
                        TAG,
                        site.key
                                + " -> fail (init="
                                + outcome.initMs
                                + "ms fetch="
                                + outcome.fetchMs
                                + "ms)");
                continue;
            }
            result.okSites++;
            SiteHealth.markOk(a, site);
            if (outcome.videos.isEmpty()) result.emptySites++;
            AppLog.w(
                    TAG,
                    site.key + " -> ok (init=" + outcome.initMs + "ms fetch=" + outcome.fetchMs + "ms)");
            mergeSite(result, site, outcome.videos);
        }

        // The stable index: sampled cards in wall order, then catalog-only cards in catalog order,
        // then one stable pass by source tier (multi → single → metadata-only).
        result.items.addAll(result.itemsByTitle.values());
        Collections.sort(result.items, WALL_ORDER);
        addCuratedItems(a, result);
        sortByIndexTier(a, result);
        while (result.items.size() > MAX_ITEMS) {
            result.items.remove(result.items.size() - 1);
        }
        result.loadedAt = System.currentTimeMillis();
        // Last line of the round so it survives the bounded in-memory log ring.
        AppLog.w(
                TAG,
                "round: wave1 "
                        + healthy.size()
                        + " -> "
                        + wave1Ok
                        + " ok, wave2 "
                        + retried
                        + " -> "
                        + retriedOk
                        + " ok, retry from "
                        + (retryStart < 0 ? "-" : String.valueOf(retryStart))
                        + ", ok sites "
                        + result.okSites
                        + ", cards "
                        + result.items.size()
                        + " (catalog-only "
                        + countCurated(result.items)
                        + ")");
        return result;
    }

    private static int countCurated(List<Item> items) {
        int count = 0;
        for (Item item : items) if (item != null && item.curated) count++;
        return count;
    }

    /**
     * Adds one metadata-only card per recommendation title the site sample did not already carry.
     * Order is hero → recent dramas → hot movies, deduplicated by normalized title, which is also
     * the order those cards keep inside the metadata tier. Rate/review data can be absent (offline
     * seeds); the card then falls back to the placeholder poster.
     */
    private static void addCuratedItems(MainActivity a, Result result) {
        if (a == null || a.privateMode) return;
        TvCuratedHome.Catalog catalog;
        try {
            catalog = TvCuratedHome.peekOrSeed();
        } catch (Throwable ignored) {
            return;
        }
        if (catalog == null) return;
        List<Cms.Video> all = new ArrayList<>();
        all.addAll(catalog.hero);
        all.addAll(catalog.hotDramas);
        all.addAll(catalog.hotMovies);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Cms.Video video : all) {
            if (video == null || video.title == null || video.title.trim().isEmpty()) continue;
            String key = normalizeTitle(video.title);
            if (key.isEmpty() || result.itemsByTitle.containsKey(key) || !seen.add(key)) continue;
            Item item = new Item(video);
            item.curated = true;
            result.itemsByTitle.put(key, item);
            result.itemsByVideo.put(video, item);
            // The catalog tier has to reach the rendered list: registering it in the title index
            // alone left every recommendation card invisible and the wall a pure site sample.
            result.items.add(item);
        }
    }

    /**
     * Re-merges the recommendation catalog into an already-built wall. The catalog is fetched
     * asynchronously and usually lands after the round, so without this the first wall of a session
     * is a pure site sample and the hot titles the home page shows are missing from 片库.
     */
    static void refreshCatalog(MainActivity a, Result result) {
        if (a == null || result == null || result.items == null) return;
        int before = result.items.size();
        try {
            addCuratedItems(a, result);
        } catch (Throwable ignored) {
            return;
        }
        if (result.items.size() == before) return;
        sortByIndexTier(a, result);
        while (result.items.size() > MAX_ITEMS) result.items.remove(result.items.size() - 1);
    }

    /**
     * Tier order for the stable index: a title with two or more known sources first, then exactly
     * one, then metadata-only cards nothing can open yet. The sort is stable, so each tier keeps
     * the order it arrived in (sampled by source count then title, catalog by catalog order).
     *
     * <p>Counts include the ledger on purpose: a card whose sites were sampled in an earlier round
     * must not slide down to the metadata tier just because this round's home listing missed it.
     */
    private static void sortByIndexTier(MainActivity a, Result result) {
        final java.util.Map<Item, Integer> tiers = new IdentityHashMap<>();
        for (Item item : result.items) {
            int count = item.sites.size();
            if (a != null) {
                for (Cms.Site site : a.sourceLedger.sourcesOf(item.video)) {
                    if (site == null || site.key == null) continue;
                    if (!item.videosBySite.containsKey(site.key)) count++;
                }
            }
            tiers.put(item, count >= 2 ? 2 : (count == 1 ? 1 : 0));
        }
        Collections.sort(result.items, (left, right) -> Integer.compare(tiers.get(right), tiers.get(left)));
    }

    /**
     * Fetches one batch of sites against the shared deadline. Outcomes land in {@code outcomes} by
     * config index; returns how many sites produced a usable listing.
     */
    private static int fetchWave(
            MainActivity a,
            Result result,
            List<Integer> indexes,
            SiteOutcome[] outcomes,
            List<FutureTask<SiteOutcome>> tasks,
            ExecutorService pool,
            ScheduledExecutorService timer,
            long deadline) {
        if (indexes.isEmpty()) return 0;
        final BlockingQueue<SiteOutcome> finished = new LinkedBlockingQueue<>();
        for (int index : indexes) {
            SiteTask callable = new SiteTask(a, result.targets.get(index), index, timer, finished);
            FutureTask<SiteOutcome> task = new FutureTask<SiteOutcome>(callable);
            callable.task = task;
            tasks.add(task);
            pool.execute(task);
        }
        int received = 0;
        while (received < indexes.size()) {
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                result.timedOut = true;
                break;
            }
            SiteOutcome outcome;
            try {
                outcome = finished.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                result.timedOut = true;
                break;
            }
            if (outcome == null) {
                result.timedOut = true;
                break;
            }
            received++;
            if (outcomes[outcome.index] == null) outcomes[outcome.index] = outcome;
        }
        int ok = 0;
        for (int index : indexes) {
            SiteOutcome outcome = outcomes[index];
            if (outcome != null && outcome.usable()) ok++;
        }
        return ok;
    }

    /**
     * Merge key for "the same show from different sites".
     *
     * <p>Every site decorates its titles: a bracketed tag, a resolution or language token, a
     * trailing year, a "全26集/更新至12集" suffix, or just stray spaces and punctuation. Comparing
     * the raw strings kept every copy apart, so the wall showed one card per site and search listed
     * the same show a dozen times. Strip the decorations, keep season markers (第一季 and 第二季 are
     * different shows) and keep the result only when enough of the title survives.
     */
    /** Merge one site's listings into {@code result}; shared by the wall and the search page. */
    static void mergeSite(Result result, Cms.Site site, List<Cms.Video> videos) {
        if (result == null || site == null || videos == null) return;
        for (Cms.Video video : videos) {
            if (video == null) continue;
            String key = normalizeTitle(video.title);
            if (key.isEmpty()) continue;
            Item item = result.itemsByTitle.get(key);
            if (item == null) {
                item = new Item(video);
                result.itemsByTitle.put(key, item);
            }
            result.itemsByVideo.put(video, item);
            if (!item.videosBySite.containsKey(site.key)) {
                item.sites.add(site);
                item.videosBySite.put(site.key, video);
            }
        }
    }

    /**
     * Fold already-fetched search pages into one merged result, without any network call. The
     * search page renders this instead of one card per source, so "两京十五日" shows up once with an
     * "N 源" badge and the detail page behind it can offer every source.
     */
    static Result mergeSearch(List<Cms.Site> sites, Map<String, List<Cms.Video>> bySite) {
        Result result = new Result();
        if (sites != null)
            for (Cms.Site site : sites) {
                if (site == null) continue;
                result.targets.add(site);
                List<Cms.Video> videos = bySite == null ? null : bySite.get(site.key);
                if (videos == null || videos.isEmpty()) continue;
                result.okSites++;
                mergeSite(result, site, videos);
            }
        result.items.addAll(result.itemsByTitle.values());
        Collections.sort(result.items, NETDISK_FIRST);
        result.loadedAt = System.currentTimeMillis();
        return result;
    }

    /** Titles carried by a cloud-drive source first: that source hands the player the original file. */
    static final Comparator<Item> NETDISK_FIRST =
            (left, right) -> {
                int l = bestRank(left);
                int r = bestRank(right);
                if (l != r) return Integer.compare(l, r);
                return Integer.compare(
                        right == null ? 0 : right.sites.size(),
                        left == null ? 0 : left.sites.size());
            };

    /** Lowest rank among the item's sources: 0 cloud drive, 1 ordinary, 2 magnet. */
    static int bestRank(Item item) {
        if (item == null || item.sites.isEmpty()) return 1;
        int best = 2;
        for (Cms.Site site : item.sites) {
            if (site == null) continue;
            String name = site.name == null ? "" : site.name;
            String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
            int rank;
            if (name.contains("网盘") || name.contains("盘搜") || api.contains("maccms")) rank = 0;
            else if (name.contains("磁力") || name.contains("P2P") || api.contains("magnet"))
                rank = 2;
            else rank = 1;
            if (rank < best) best = rank;
        }
        return best;
    }

    /**
     * Copies year/area/genre from a fetched detail onto the merged item's card video.
     *
     * <p>Home listings rarely carry these fields, but the detail does. Details are prefetched for
     * visible cards and fetched on open, so the merged wall's classification filters fill in while
     * the user browses instead of asking every site for a taxonomy it does not share.
     */
    static void enrichMetadata(MainActivity a, Cms.Video detail) {
        if (a == null || detail == null) return;
        Result wall = wallCards(a);
        if (wall == null) return;
        Item item = wall.itemFor(detail);
        if (item == null || item.video == null) return;
        Cms.Video card = item.video;
        if (isEmpty(card.year) && !isEmpty(detail.year)) card.year = detail.year;
        if (isEmpty(card.area) && !isEmpty(detail.area)) card.area = detail.area;
        if (isEmpty(card.genre) && !isEmpty(detail.genre)) card.genre = detail.genre;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    static Cms.Site preferredSite(Item item) {
        if (item == null) return null;
        Cms.Site best = null;
        int bestRank = 3;
        for (Cms.Site site : item.sites) {
            if (site == null) continue;
            String name = site.name == null ? "" : site.name;
            String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
            int rank;
            if (name.contains("网盘") || name.contains("盘搜") || api.contains("maccms")) rank = 0;
            else if (name.contains("磁力") || name.contains("P2P") || api.contains("magnet"))
                rank = 2;
            else rank = 1;
            if (rank < bestRank) {
                bestRank = rank;
                best = site;
            }
        }
        return best == null ? (item.sites.isEmpty() ? null : item.sites.get(0)) : best;
    }

    static String normalizeTitle(String raw) {
        if (raw == null) return "";
        String bare = raw.replaceAll("[\\s\\u00a0\\u3000]+", "");
        String key =
                bare.replaceAll("[\\[【(（〔<《].*?[\\]】)）〕>》]", "")
                        .replaceAll(
                                "(?i)(4K|8K|2160P|1080P|720P|HDR|WEB-?DL|BluRay|HDTV|HD|"
                                        + "国语|粤语|英语|日语|中字|双语|中文字幕|完结|已完结|"
                                        + "全集|全\\d+集|共\\d+集|更新至\\d+集?|\\d+集全)", "")
                        .replaceAll("[（(]?(19|20)\\d{2}[）)]?$", "")
                        .replaceAll("[\\p{Punct}，。、·—－_:：!！?？\"'“”‘’~～]+", "")
                        .trim();
        // "1921" or a title made only of decorations: never merge on a degenerate key.
        return key.length() >= 2 ? key : bare;
    }

    /**
     * One site's first page, fetched through the anti-corruption gateway (home page → video feed →
     * a few category probes, then filtering). This method only converts the gateway's refs back to
     * the base listing type; the fetch policy lives in {@link FongMiGateway#home}.
     *
     * <p>{@code engine} is the instance {@link SiteTask} already constructed for its init timing;
     * the gateway re-resolves it from the engine cache, so no second init and no extra request.
     */
    private static List<Cms.Video> fetch(MainActivity a, Cms.Site site, SourceEngine engine)
            throws Exception {
        FongMiGateway gateway = new FongMiGateway(a);
        List<MediaRef> refs = gateway.home(new SiteRef(site.key, site.name), PER_SITE_LIMIT);
        List<Cms.Video> picked = new ArrayList<>(refs.size());
        for (MediaRef ref : refs) {
            Cms.Video video = gateway.toListing(ref);
            if (video != null) picked.add(video);
        }
        return picked;
    }

    private static final class SiteOutcome {
        final int index;
        final List<Cms.Video> videos;
        final long initMs;
        final long fetchMs;
        /** The per-site budget this fetch was given; a slow Guard site gets more than the default. */
        final long budgetMs;

        SiteOutcome(int index, List<Cms.Video> videos, long initMs, long fetchMs) {
            this(index, videos, initMs, fetchMs, SITE_TIMEOUT_MS);
        }

        SiteOutcome(int index, List<Cms.Video> videos, long initMs, long fetchMs, long budgetMs) {
            this.index = index;
            this.videos = videos;
            this.initMs = initMs;
            this.fetchMs = fetchMs;
            this.budgetMs = budgetMs <= 0 ? SITE_TIMEOUT_MS : budgetMs;
        }

        /**
         * A listing that arrived inside the fetch budget counts as healthy, empty or not. Engine
         * construction is not charged here: it is a one-off, cached cost, and charging it made every
         * cold netdisk plugin look dead.
         */
        boolean usable() {
            return videos != null && fetchMs <= budgetMs;
        }
    }

    /** One bounded site fetch. A watchdog interrupts a site that exceeds the per-site budget. */
    private static final class SiteTask implements Callable<SiteOutcome> {
        final MainActivity activity;
        final Cms.Site site;
        final int index;
        final ScheduledExecutorService timer;
        final BlockingQueue<SiteOutcome> sink;
        volatile FutureTask<SiteOutcome> task;

        SiteTask(
                MainActivity activity,
                Cms.Site site,
                int index,
                ScheduledExecutorService timer,
                BlockingQueue<SiteOutcome> sink) {
            this.activity = activity;
            this.site = site;
            this.index = index;
            this.timer = timer;
            this.sink = sink;
        }

        public SiteOutcome call() {
            long started = android.os.SystemClock.elapsedRealtime();
            ScheduledFuture<?> watchdog = arm(ENGINE_TIMEOUT_MS);
            SourceEngine engine;
            final boolean guardSite =
                    site != null && site.key != null && site.key.startsWith("w2x_");
            // 每个站每轮最多一次重试：init 失败与 home 失败共用这个名额，避免叠加成循环。
            final boolean[] retried = {false};
            try {
                engine = activity.engine(site);
            } catch (Throwable failure) {
                if (watchdog != null) watchdog.cancel(false);
                // Guard 站的 init 会偶发超时（实测 init 打满 12 s 引擎预算，下一轮又好了）：
                // 同一轮里再建一次，有界（只一次）。失败仍按原样上报。
                if (guardSite && !retried[0]) {
                    // 首试多半是被预算看门狗打断的（init 打满 12 s）：清掉中断位，重试用更短的
                    // 预算再问一次。只重试一次，失败照旧上报。
                    retried[0] = true;
                    Thread.interrupted();
                    AppLog.w(TAG, "w2x home retry site=" + site.key + " try=2");
                    watchdog = arm(GUARD_RETRY_BUDGET_MS);
                    try {
                        engine = activity.engine(site);
                    } catch (Throwable second) {
                        if (watchdog != null) watchdog.cancel(false);
                        SiteOutcome outcome =
                                new SiteOutcome(
                                        index,
                                        null,
                                        android.os.SystemClock.elapsedRealtime() - started,
                                        0L);
                        sink.offer(outcome);
                        return outcome;
                    }
                } else {
                    SiteOutcome outcome =
                            new SiteOutcome(
                                    index,
                                    null,
                                    android.os.SystemClock.elapsedRealtime() - started,
                                    0L);
                    sink.offer(outcome);
                    return outcome;
                }
            }
            if (watchdog != null) watchdog.cancel(false);
            long initMs = android.os.SystemClock.elapsedRealtime() - started;
            long fetchStarted = android.os.SystemClock.elapsedRealtime();
            final long budget = fetchBudgetMs(site);
            watchdog = arm(budget);
            List<Cms.Video> videos = null;
            try {
                videos = fetch(activity, site, engine);
            } catch (Throwable ignored) {
                // A site that throws is bounded by the watchdog and reported as not usable.
            } finally {
                if (watchdog != null) watchdog.cancel(false);
            }
            // Guard 站的 home 会偶发失败（远程配置/网关抖一下，下一轮又好了）。同一轮里对它再试
            // 一次，有界：只重试一次、只在"第一次是抛异常"且线程没被预算看门狗打断时重试；重试
            // 的那次使用自己的预算，超时就不再试。
            if (guardSite
                    && !retried[0]
                    && (videos == null || videos.isEmpty())) {
                // 抛异常与"回空列表"都算这一次失败：同一个站每轮只重试一次（名额与 init 共用）。
                retried[0] = true;
                Thread.interrupted();
                AppLog.w(TAG, "w2x home retry site=" + site.key + " try=2");
                watchdog = arm(GUARD_RETRY_BUDGET_MS);
                try {
                    videos = fetch(activity, site, engine);
                } catch (Throwable ignored) {
                    // Give up after this one retry; the round goes on.
                } finally {
                    if (watchdog != null) watchdog.cancel(false);
                }
            }
            SiteOutcome outcome =
                    new SiteOutcome(
                            index,
                            videos,
                            initMs,
                            android.os.SystemClock.elapsedRealtime() - fetchStarted,
                            retried[0] ? budget * 2 : budget);
            sink.offer(outcome);
            return outcome;
        }

        /** Cancel this task's own future when the current phase outlives its budget. */
        private ScheduledFuture<?> arm(long budgetMs) {
            if (task == null) return null;
            try {
                return timer.schedule(
                        () -> {
                            FutureTask<SiteOutcome> running = task;
                            if (running != null) running.cancel(true);
                        },
                        budgetMs,
                        TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // The round already ended; the fetch is discarded with the pool.
                return null;
            }
        }
    }

    /**
     * 一次 fetch 允许占的时间。w2x_ 那批 Guard 站点的 {@code homeContent} 首调用要 5 s 以上（实测
     * 5.5 s init + 5 s 以上 home），用普通站的 5 s 会稳定判死；给它们多一倍，但整轮
     * {@link #TOTAL_TIMEOUT_MS} 不变，4 个 worker 里先跑偏好站，后面的站照旧排队。
     */
    private static long fetchBudgetMs(Cms.Site site) {
        String key = site == null || site.key == null ? "" : site.key;
        return key.startsWith("w2x_") ? SITE_TIMEOUT_MS * 2 : SITE_TIMEOUT_MS;
    }

    /**
     * Indices of the sites the user pointed at, in intent order: 默认偏好源 (PREFERRED_SITE) →
     * 当前浏览站点 (SELECTED_SITE) → 手排置顶顺序. Empty when none is set, so the wall keeps its
     * old ordering by default.
     *
     * <p>SELECTED_SITE 是「源 → 选择站点」切过去的那一站：Boss 切站点就是想看它的片，所以它必须
     * 进 wave 1，否则墙的取样和这个开关毫无关系（T-09 只接了 PREFERRED_SITE，切浏览站点等于没切）。
     * 放在显式偏好之后：默认偏好源是长期默认，浏览站点是这一次的意图，两者都存在时先尊重长期默认。
     */
    private static List<Integer> preferenceFirst(MainActivity a, List<Cms.Site> targets) {
        List<Integer> out = new ArrayList<>();
        if (a == null || targets == null || targets.isEmpty()) return out;
        for (int index = 0; index < targets.size(); index++) {
            Cms.Site site = targets.get(index);
            if (site != null && SourcePreferences.isPreferred(a, site) && !out.contains(index)) {
                out.add(index);
            }
        }
        String selected = a.prefs == null ? "" : a.prefs.getString(Prefs.SELECTED_SITE, "");
        if (selected != null && !selected.isEmpty()) {
            for (int index = 0; index < targets.size(); index++) {
                Cms.Site site = targets.get(index);
                if (site != null && selected.equals(site.key) && !out.contains(index)) {
                    out.add(index);
                }
            }
        }
        for (String key : SourcePreferences.order(a)) {
            for (int index = 0; index < targets.size(); index++) {
                Cms.Site site = targets.get(index);
                if (site != null && key.equals(site.key) && !out.contains(index)) out.add(index);
            }
        }
        if (!out.isEmpty()) {
            StringBuilder keys = new StringBuilder();
            for (int index : out) {
                if (keys.length() > 0) keys.append(',');
                keys.append(targets.get(index).key);
            }
            AppLog.w(TAG, "wave1 preference first: [" + keys + "]");
        }
        return out;
    }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
