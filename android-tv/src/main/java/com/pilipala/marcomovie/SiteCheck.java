package com.pilipala.marcomovie;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot health sweep over every site the library wall would use.
 *
 * <p>Why: the aggregation log only reports {@code ok} / {@code fail}, which cannot tell a dead
 * domain from an empty home page, a site that needs an account, or a spider whose {@code init}
 * throws. Deciding what to fix needs that split, so this sweep runs each site once with its own
 * budget and writes one line per site plus a summary to logcat under the {@code SiteCheck} tag.
 *
 * <p>Read-only with respect to user state: it builds engines through the same {@link
 * SourceController#engine} path the wall uses and only calls {@code home()}. It does not write
 * {@link SiteHealth}, so a slow sweep cannot poison the wall's health memory.
 */
final class SiteCheck {

    static final String TAG = "SiteCheck";

    /** One site gets this long in total (engine construction included) before it is cancelled. */
    static final long BUDGET_MS = 10000L;

    static final int CONCURRENCY = 4;

    private static final AtomicInteger RUNNING = new AtomicInteger();

    private SiteCheck() {}

    /** Must be called on the UI thread: target collection copies the config list. */
    static void run(MainActivity a) {
        if (a == null) return;
        if (RUNNING.getAndIncrement() > 0) {
            RUNNING.decrementAndGet();
            a.toast("站点体检正在进行");
            return;
        }
        final List<Cms.Site> targets = LibraryAggregator.targets(a);
        if (targets.isEmpty()) {
            RUNNING.decrementAndGet();
            a.toast("当前配置没有可体检的站点");
            return;
        }
        a.toast("站点体检开始（" + targets.size() + " 个）");
        Log.w(TAG, "SUMMARY-START total=" + targets.size());
        Thread worker =
                new Thread(
                        () -> {
                            List<String> failures = new ArrayList<>();
                            try {
                                failures = sweep(a, targets);
                            } catch (Throwable fatal) {
                                Log.w(TAG, "sweep aborted", fatal);
                            } finally {
                                RUNNING.decrementAndGet();
                            }
                            final List<String> top = failures;
                            a.ui.post(() -> report(a, targets.size(), top));
                        },
                        "movie-site-check");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs every site, logs one line each, returns the human-readable failure list. */
    private static List<String> sweep(MainActivity a, List<Cms.Site> targets) {
        final ScheduledExecutorService timer =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "movie-site-check-timer");
                            t.setDaemon(true);
                            return t;
                        });
        final ExecutorService pool =
                Executors.newFixedThreadPool(
                        Math.min(CONCURRENCY, Math.max(1, targets.size())),
                        r -> {
                            Thread t = new Thread(r, "movie-site-check-worker");
                            t.setDaemon(true);
                            return t;
                        });
        int emit = 0, empty = 0, timeout = 0, error = 0, unavailable = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (int i = 0; i < targets.size(); i++) {
                Cms.Site site = targets.get(i);
                String name = site == null ? "?" : site.name;
                if (site == null || !SourceController.available(a, site)) {
                    unavailable++;
                    Log.w(TAG, line(i, targets.size(), name, "UNAVAILABLE", -1, -1, 0, ""));
                    failures.add(name + " 不可用");
                    continue;
                }
                Outcome outcome = probe(a, site, pool, timer);
                switch (outcome.verdict) {
                    case "EMIT":
                        emit++;
                        break;
                    case "EMPTY":
                        empty++;
                        failures.add(name + " 空内容");
                        break;
                    case "TIMEOUT":
                        timeout++;
                        failures.add(name + " 超时");
                        break;
                    default:
                        error++;
                        failures.add(name + " " + outcome.verdict + (outcome.detail.isEmpty() ? "" : " " + outcome.detail));
                        break;
                }
                Log.w(
                        TAG,
                        line(
                                i,
                                targets.size(),
                                name,
                                outcome.verdict,
                                outcome.engineMs,
                                outcome.homeMs,
                                outcome.items,
                                outcome.detail));
            }
        } finally {
            pool.shutdownNow();
            timer.shutdownNow();
        }
        Log.w(
                TAG,
                "SUMMARY total="
                        + targets.size()
                        + " emit="
                        + emit
                        + " empty="
                        + empty
                        + " timeout="
                        + timeout
                        + " error="
                        + error
                        + " unavailable="
                        + unavailable);
        return failures;
    }

    private static final class Outcome {
        String verdict = "ERROR";
        long engineMs = -1;
        long homeMs = -1;
        int items;
        String detail = "";
    }

    private static Outcome probe(
            MainActivity a, Cms.Site site, ExecutorService pool, ScheduledExecutorService timer) {
        final Outcome outcome = new Outcome();
        final FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            long started = SystemClock.elapsedRealtime();
                            try {
                                SourceEngine engine = a.engine(site);
                                outcome.engineMs = SystemClock.elapsedRealtime() - started;
                                long homeStarted = SystemClock.elapsedRealtime();
                                String raw = engine.home();
                                outcome.homeMs = SystemClock.elapsedRealtime() - homeStarted;
                                Cms.Listing listing = HomeScreen.parseHomeListing(raw);
                                int videos = listing == null ? 0 : listing.videos.size();
                                int categories = 0;
                                try {
                                    categories = Cms.categories(raw).size();
                                } catch (Throwable ignored) {
                                }
                                final int categoryCount = categories;
                                // Mirror the wall: a site whose home page only lists channels is not
                                // empty until a few channels have been tried.
                                for (int probe = 0;
                                        videos == 0
                                                && probe < Math.min(3, categoryCount);
                                        probe++) {
                                    long probeStarted = SystemClock.elapsedRealtime();
                                    try {
                                        String channel =
                                                engine.category(
                                                        Cms.categories(raw).get(probe).id, 1);
                                        Cms.Listing listing2 = Cms.listing(channel);
                                        videos = listing2 == null ? 0 : listing2.videos.size();
                                        outcome.homeMs +=
                                                SystemClock.elapsedRealtime() - probeStarted;
                                    } catch (Throwable ignored) {
                                        // One dead channel does not end the probe loop.
                                    }
                                }
                                outcome.items = videos;
                                if (videos > 0) {
                                    outcome.verdict = "EMIT";
                                } else if (categories > 0) {
                                    outcome.verdict = "EMPTY";
                                    outcome.detail = "cats=" + categories;
                                } else {
                                    outcome.verdict = "EMPTY";
                                    outcome.detail = "no categories";
                                }
                            } catch (InterruptedException cancelled) {
                                outcome.verdict = "TIMEOUT";
                            } catch (Throwable failure) {
                                Throwable cause = unwrap(failure);
                                outcome.verdict = "ERROR:" + cause.getClass().getSimpleName();
                                outcome.detail = shortMessage(cause);
                            }
                            return null;
                        });
        ScheduledFuture<?> watchdog = null;
        try {
            watchdog = timer.schedule(() -> task.cancel(true), BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        pool.execute(task);
        try {
            task.get(BUDGET_MS + 2000L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            outcome.verdict = "TIMEOUT";
            task.cancel(true);
        } finally {
            if (watchdog != null) watchdog.cancel(false);
        }
        return outcome;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 6 && current.getCause() != null; depth++) {
            current = current.getCause();
        }
        return current;
    }

    private static String shortMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null) return "";
        message = message.replaceAll("(?i)https?://\\S+", "<url>").replace('\n', ' ').trim();
        return message.length() > 60 ? message.substring(0, 60) + "…" : message;
    }

    private static String line(
            int index, int total, String name, String verdict, long engineMs, long homeMs, int items, String detail) {
        return String.format(
                Locale.ROOT,
                "%d/%d %s | %s | engine=%s home=%s | items=%d | %s",
                index + 1,
                total,
                name,
                verdict,
                engineMs < 0 ? "-" : engineMs + "ms",
                homeMs < 0 ? "-" : homeMs + "ms",
                items,
                detail == null ? "" : detail);
    }

    private static void report(MainActivity a, int total, List<String> failures) {
        if (a == null || a.isFinishing()) return;
        StringBuilder text = new StringBuilder();
        text.append("体检站点数：").append(total).append('\n');
        text.append("结果明细已写入运行日志（标签 SiteCheck）。\n");
        if (!failures.isEmpty()) {
            text.append('\n').append("未通过的站点（前 20 条）：");
            int shown = 0;
            for (String failure : failures) {
                if (shown++ >= 20) break;
                text.append('\n').append("· ").append(failure);
            }
            if (failures.size() > 20) {
                text.append('\n').append("… 其余 ").append(failures.size() - 20).append(" 条见日志");
            }
        }
        new AppDialog.Builder(a)
                .setTitle("站点体检")
                .setMessage(text.toString())
                .setPositiveButton("关闭", null)
                .show();
    }
}
