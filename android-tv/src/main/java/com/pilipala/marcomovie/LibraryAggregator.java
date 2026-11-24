package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 */
final class LibraryAggregator {
    static final String TAG = "LibraryAgg";
    static final int PER_SITE_LIMIT = 12;
    static final int MAX_CONCURRENCY = 6;
    static final long SITE_TIMEOUT_MS = 8000L;
    static final long TOTAL_TIMEOUT_MS = 15000L;
    static final int MAX_ITEMS = 200;

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
        final BlockingQueue<SiteOutcome> finished = new LinkedBlockingQueue<>();
        final ScheduledExecutorService timer =
                Executors.newSingleThreadScheduledExecutor(daemonFactory("movie-library-timer"));
        final ExecutorService pool =
                Executors.newFixedThreadPool(
                        Math.min(MAX_CONCURRENCY, count), daemonFactory("movie-library-fetch"));
        final List<FutureTask<SiteOutcome>> tasks = new ArrayList<>(count);
        final long deadline = android.os.SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS;

        try {
            for (int i = 0; i < count; i++) {
                SiteTask callable = new SiteTask(a, result.targets.get(i), i, timer, finished);
                FutureTask<SiteOutcome> task = new FutureTask<SiteOutcome>(callable);
                callable.task = task;
                tasks.add(task);
                pool.execute(task);
            }
            int received = 0;
            while (received < count) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    result.timedOut = true;
                    break;
                }
                SiteOutcome outcome = finished.poll(remaining, TimeUnit.MILLISECONDS);
                if (outcome == null) {
                    result.timedOut = true;
                    break;
                }
                received++;
                if (outcomes[outcome.index] == null) outcomes[outcome.index] = outcome;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.timedOut = true;
        } catch (Throwable ignored) {
            result.timedOut = true;
        } finally {
            for (FutureTask<SiteOutcome> task : tasks) {
                if (!task.isDone()) task.cancel(true);
            }
            pool.shutdownNow();
            timer.shutdownNow();
        }

        // Merge in config order so the first source of a card is stable across runs.
        Map<String, Item> merged = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Cms.Site site = result.targets.get(i);
            SiteOutcome outcome = outcomes[i];
            if (outcome == null || outcome.videos == null || outcome.elapsedMs > SITE_TIMEOUT_MS) {
                result.failedSites++;
                AppLog.w(TAG, site.key + " -> fail");
                continue;
            }
            result.okSites++;
            if (outcome.videos.isEmpty()) result.emptySites++;
            AppLog.w(TAG, site.key + " -> ok");
            for (Cms.Video video : outcome.videos) {
                String key = normalizeTitle(video.title);
                if (key.isEmpty()) continue;
                Item item = merged.get(key);
                if (item == null) {
                    item = new Item(video);
                    merged.put(key, item);
                }
                if (!item.videosBySite.containsKey(site.key)) {
                    item.sites.add(site);
                    item.videosBySite.put(site.key, video);
                }
            }
        }

        result.items.addAll(merged.values());
        Collections.sort(result.items, WALL_ORDER);
        while (result.items.size() > MAX_ITEMS) {
            result.items.remove(result.items.size() - 1);
        }
        result.loadedAt = System.currentTimeMillis();
        return result;
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
