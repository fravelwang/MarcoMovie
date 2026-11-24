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
    static final int PER_SITE_LIMIT = 12;
    static final int MAX_CONCURRENCY = 6;
    // A shorter per-site timeout lets one 15 s budget reach ~18 sites instead of ~11; sites that
    // need longer than this are usually dead or engine-backed anyway.
    static final long SITE_TIMEOUT_MS = 5000L;

    /** Wave 1 only looks at this many sites per round; a cursor rotates through the rest. */
    static final int MAX_WAVE1_SITES = 36;

    /** Prefs key holding the rotation cursor over never-tried sites. */
    static final String CURSOR_KEY = "library_agg_cursor";

    /** Prefs key holding the rotation cursor over previously failing sites (wave 2). */
    static final String RETRY_CURSOR_KEY = "library_agg_retry_cursor";
    static final long TOTAL_TIMEOUT_MS = 15000L;
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
                AppLog.w(TAG, site.key + " -> fail");
                continue;
            }
            result.okSites++;
            SiteHealth.markOk(a, site);
            if (outcome.videos.isEmpty()) result.emptySites++;
            AppLog.w(TAG, site.key + " -> ok");
            for (Cms.Video video : outcome.videos) {
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

    /** Whitespace-insensitive title key; Chinese titles often differ only by spaces. */
    static String normalizeTitle(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[\\s\\u00a0\\u3000]+", "");
    }

    /** First home page, then the home video feed, then the first category, mirroring HomeScreen. */
    private static List<Cms.Video> fetch(MainActivity a, Cms.Site site) throws Exception {
        SourceEngine engine = a.engine(site);
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
        if (listing.videos.isEmpty() && !categories.isEmpty()) {
            listing = Cms.listing(engine.category(categories.get(0).id, 1));
            HomeScreen.clean(listing.videos);
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
        final long elapsedMs;

        SiteOutcome(int index, List<Cms.Video> videos, long elapsedMs) {
            this.index = index;
            this.videos = videos;
            this.elapsedMs = elapsedMs;
        }

        /** A listing that arrived inside the per-site budget counts as healthy, empty or not. */
        boolean usable() {
            return videos != null && elapsedMs <= SITE_TIMEOUT_MS;
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
            FutureTask<SiteOutcome> self = task;
            ScheduledFuture<?> watchdog = null;
            if (self != null) {
                try {
                    watchdog =
                            timer.schedule(
                                    new Runnable() {
                                        public void run() {
                                            FutureTask<SiteOutcome> running = task;
                                            if (running != null) running.cancel(true);
                                        }
                                    },
                                    SITE_TIMEOUT_MS,
                                    TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                    // The round already ended; the fetch is discarded with the pool.
                }
            }
            SiteOutcome outcome;
            try {
                outcome =
                        new SiteOutcome(
                                index,
                                fetch(activity, site),
                                android.os.SystemClock.elapsedRealtime() - started);
            } catch (Throwable failure) {
                outcome =
                        new SiteOutcome(
                                index, null, android.os.SystemClock.elapsedRealtime() - started);
            } finally {
                if (watchdog != null) watchdog.cancel(false);
            }
            sink.offer(outcome);
            return outcome;
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
