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
 * Builds the cross-site library wall: every searchable, playable site contributes the first page of
 * its home listing (or its first category), entries are merged by normalized title and sorted by
 * source count. All work is bounded so the wall can never stall the home screen.
 *
 * <p>Numbers are product decisions, not tuning knobs: 12 items per site, 6 concurrent fetches, 8 s
 * per site, 15 s for the whole aggregation, 200 cards at most.
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

    /** Channels tried when a site's home page carries no videos (see fetch). */
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

    /** Prefs key holding the rotation cursor over never-tried sites. */
    static final String CURSOR_KEY = "library_agg_cursor";

    /** Prefs key holding the rotation cursor over previously failing sites (wave 2). */
    static final String RETRY_CURSOR_KEY = "library_agg_retry_cursor";
    static final long TOTAL_TIMEOUT_MS = 18000L;
    static final int MAX_ITEMS = 200;

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

        Item(Cms.Video video) {
            this.video = video;
        }

        boolean hasSite(String key) {
            return key != null && videosBySite.containsKey(key);
        }
    }

    /** Immutable result of one aggregation round plus the counters the home screen reports. */
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

        /**
         * The card holding this listing. Wall cards resolve by object identity; search results,
         * curated recommendations and history entries resolve by the same normalized title the wall
         * merges on, so all three entry points share one source list.
         */
        Item itemFor(Cms.Video video) {
            if (video == null) return null;
            Item item = itemsByVideo.get(video);
            if (item != null) return item;
            return itemsByTitle.get(normalizeTitle(video.title));
        }

        /** Absorbs a source found later (on-demand per-title search from the detail page). */
        synchronized void addSource(Item item, Cms.Site site, Cms.Video video) {
            if (item == null || site == null || video == null) return;
            if (site.key == null || item.videosBySite.containsKey(site.key)) return;
            item.sites.add(site);
            item.videosBySite.put(site.key, video);
            itemsByVideo.put(video, item);
        }

        /** Every site carrying this title, in config order; empty when the wall never saw it. */
        List<Cms.Site> sourcesOf(Cms.Video video) {
            Item item = itemFor(video);
            return item == null ? Collections.emptyList() : Collections.unmodifiableList(item.sites);
        }

        /** That site's own listing entry, or null when the site did not carry the title. */
        Cms.Video videoAt(Cms.Video video, String siteKey) {
            Item item = itemFor(video);
            return item == null || siteKey == null ? null : item.videosBySite.get(siteKey);
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
            String state = SiteHealth.state(a, result.targets.get(i).key);
            if (SiteHealth.OK.equals(state)) knownOk.add(i);
            else if (SiteHealth.FAIL.equals(state)) retry.add(i);
            else untried.add(i);
        }
        final List<Integer> healthy = new ArrayList<>(MAX_WAVE1_SITES);
        healthy.addAll(knownOk.size() > MAX_WAVE1_SITES
                ? knownOk.subList(0, MAX_WAVE1_SITES)
                : knownOk);
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

        result.items.addAll(result.itemsByTitle.values());
        Collections.sort(result.items, WALL_ORDER);
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
                        + result.okSites);
        return result;
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

    static boolean hasShareSource(Item item) {
        if (item == null) return false;
        for (Cms.Site site : item.sites) {
            if (site == null) continue;
            String name = site.name == null ? "" : site.name;
            String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("网盘") || name.contains("盘搜") || api.contains("maccms")) return true;
        }
        return false;
    }

    /** The site a merged card should open with: the share source when there is one. */
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

    /** First home page, then the home video feed, then the first few categories, like HomeScreen. */
    private static List<Cms.Video> fetch(MainActivity a, Cms.Site site, SourceEngine engine)
            throws Exception {
        String raw = engine.home();
        List<Cms.Category> categories = Cms.categories(raw);
        Cms.Listing listing = HomeScreen.parseHomeListing(raw);
        HomeScreen.clean(listing.videos);
        if (listing.videos.isEmpty()) {
            try {
                listing = Cms.listing(engine.homeVideos());
                HomeScreen.clean(listing.videos);
            } catch (Throwable ignored) {
            }
        }
        // Sites whose home page only carries channel buttons (measured in the 2026-11-25 site check:
        // CNTV 69 categories, opera 68, Bilibili collection 42) have no home videos at all. Their
        // first channel is often empty too, so try a few in order and take the first that lists
        // something; one empty channel is not evidence that the site is dead.
        for (int index = 0;
                listing.videos.isEmpty() && index < Math.min(MAX_CATEGORY_PROBES, categories.size());
                index++) {
            try {
                listing = Cms.listing(engine.category(categories.get(index).id, 1));
                HomeScreen.clean(listing.videos);
            } catch (Throwable ignored) {
                // Keep probing: one bad channel should not end the fallback.
            }
        }
        List<Cms.Video> picked = new ArrayList<>(PER_SITE_LIMIT);
        for (Cms.Video video : listing.videos) {
            if (picked.size() >= PER_SITE_LIMIT) break;
            if (video == null || normalizeTitle(video.title).isEmpty()) continue;
            if (video.action != null && !video.action.trim().isEmpty()) continue;
            if (SourceController.isInternalActionCode(video.id, video.action)) continue;
            picked.add(video);
        }
        return picked;
    }

    private static final class SiteOutcome {
        final int index;
        final List<Cms.Video> videos;
        final long initMs;
        final long fetchMs;

        SiteOutcome(int index, List<Cms.Video> videos, long initMs, long fetchMs) {
            this.index = index;
            this.videos = videos;
            this.initMs = initMs;
            this.fetchMs = fetchMs;
        }

        /**
         * A listing that arrived inside the fetch budget counts as healthy, empty or not. Engine
         * construction is not charged here: it is a one-off, cached cost, and charging it made every
         * cold netdisk plugin look dead.
         */
        boolean usable() {
            return videos != null && fetchMs <= SITE_TIMEOUT_MS;
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
            try {
                engine = activity.engine(site);
            } catch (Throwable failure) {
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
            if (watchdog != null) watchdog.cancel(false);
            long initMs = android.os.SystemClock.elapsedRealtime() - started;
            long fetchStarted = android.os.SystemClock.elapsedRealtime();
            watchdog = arm(SITE_TIMEOUT_MS);
            List<Cms.Video> videos = null;
            try {
                videos = fetch(activity, site, engine);
            } catch (Throwable ignored) {
                // A site that throws is bounded by the watchdog and reported as not usable.
            } finally {
                if (watchdog != null) watchdog.cancel(false);
            }
            SiteOutcome outcome =
                    new SiteOutcome(
                            index,
                            videos,
                            initMs,
                            android.os.SystemClock.elapsedRealtime() - fetchStarted);
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
