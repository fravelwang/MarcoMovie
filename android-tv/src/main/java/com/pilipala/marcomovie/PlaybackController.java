package com.pilipala.marcomovie;

import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.pilipala.marcomovie.hud.EpisodeHoldSwitchOverlay;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Playback resolution (direct, plugin/JS player, web resolver, magnet stream), player page
 * lifecycle, mini-player transitions, remote seek gesture, control overlay auto-hide, and watch
 * history bookmark persistence extracted from {@link MainActivity}.
 */
final class PlaybackController {
    private static final ExecutorService PLAYER_RESOLVE =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "player-resolve");
                        t.setDaemon(true);
                        return t;
                    });
    private static final ExecutorService PLAYER_INIT =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "player-init");
                        t.setDaemon(true);
                        return t;
                    });
    static final ExecutorService STORAGE_EXECUTOR = WatchHistory.STORAGE_EXECUTOR;
    private static final long DISK_SAVE_INTERVAL_MS = 15000L;

    private PlaybackController() {}

    static WatchHistory.Entry cloneBookmark(WatchHistory.Entry src) {
        return WatchHistory.cloneEntry(src);
    }

    static void updateInMemoryBookmark(MainActivity a) {
        if (a.player == null || !a.prepared || a.playingBookmark == null) return;
        try {
            int rawPos = a.player.getCurrentPosition();
            int position =
                    a.playbackControls != null
                            ? a.playbackControls.displayPosition(rawPos)
                            : rawPos;
            if (a.playbackControls != null
                    && a.playbackControls.pendingResumeMs > 0
                    && position < 2000) {
                position = a.playbackControls.pendingResumeMs;
            }
            if (position < 1000 && a.playingBookmark.position >= 1000) {
                position = a.playingBookmark.position;
            }
            a.playingBookmark.position = Math.max(0, position);
            int dur = a.player.getDuration();
            if (dur > 0) {
                a.playingBookmark.duration = dur;
            }
            a.playingBookmark.updated = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    static String formatTime(int ms) {
        int sec = Math.max(0, ms / 1000);
        return sec >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
                : String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
    }

    static void stopMagnet(MainActivity a) {
        if (a.magnetStream != null) {
            a.magnetStream.close();
            a.magnetStream = null;
        }
    }

    static void stopWeb(MainActivity a) {
        if (a.webResolver != null) {
            a.webResolver.stop();
            a.webResolver = null;
        }
    }

    static void resolveWebPage(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, String prefix) {
        String address = (prefix == null ? "" : prefix) + ep.url;
        startWebResolver(a, s, v, ep, address, null, null);
    }

    /**
     * Opens one WebView resolver. With a null {@code chain} this keeps the original single-page
     * behaviour (live channels, direct web pages, {@code webview://} episodes). With a chain it
     * reports the sniff result back to the chain so the next parse interface can be tried without
     * showing a failure to the user.
     */
    private static void startWebResolver(
            MainActivity a,
            Cms.Site s,
            Cms.Video v,
            Cms.Episode ep,
            String address,
            ParseChain chain,
            ParseChainResolver.Candidate candidate) {
        final boolean chained = chain != null;
        if (chained) {
            if (chain.ticket != a.generation
                    || chain.serial != a.playbackResolveSerial
                    || a.isFinishing()) return;
            chain.webAttempts++;
        }
        if (!Cms.isHttp(address)) {
            if (chained) {
                ParseChainResolver.logAttempt(
                        candidate == null ? "原页面" : candidate.name, false, "页面地址无效");
                startParseChain(a, s, v, ep, chain);
            } else {
                a.changingMiniEpisode = false;
                clearPlaybackLoading(a);
                failInPlaceSwitch(a, s, PlaybackFailure.Attribution.LINE, "此剧集的页面地址无效，建议换一条线路");
            }
            return;
        }
        final int ticket = a.generation;
        final int serial = a.playbackResolveSerial;
        try {
            List<Cms.SniffRule> sniffRules =
                    a.config != null ? a.config.rules : java.util.Collections.emptyList();
            List<String> adHosts =
                    a.config != null ? a.config.ads : java.util.Collections.emptyList();
            String clickJs = ep.media != null ? ep.media.optString("click", "") : "";
            Map<String, String> webHeaders = new LinkedHashMap<>(ep.headers);
            if (candidate != null) webHeaders.putAll(candidate.headers);
            final WebResolver resolver =
                    new WebResolver(
                            a,
                            address,
                            webHeaders,
                            sniffRules,
                            adHosts,
                            clickJs,
                            new WebResolver.Listener() {
                                public void media(String url, Map<String, String> headers) {
                                    if (ticket != a.generation || serial != a.playbackResolveSerial)
                                        return;
                                    a.webResolver = null;
                                    if (chained && candidate != null) {
                                        ParseChainResolver.logAttempt(
                                                candidate.name, true, "网页嗅探到播放地址");
                                        if (candidate.fromConfig) {
                                            ParseChainResolver.remember(a, candidate.name);
                                        }
                                    }
                                    clearPlaybackLoading(a);
                                    Cms.Episode resolved = new Cms.Episode();
                                    resolved.name = ep.name;
                                    resolved.url = url;
                                    resolved.headers.putAll(headers);
                                    resolved.media = ep.media;
                                    openPlayer(a, s, v, resolved);
                                }

                                public void failed(
                                        String reason, PlaybackFailure.Attribution kind) {
                                    if (ticket != a.generation || serial != a.playbackResolveSerial)
                                        return;
                                    if (chained) {
                                        stopWeb(a);
                                        ParseChainResolver.logAttempt(
                                                candidate == null ? "原页面" : candidate.name,
                                                false,
                                                reason);
                                        startParseChain(a, s, v, ep, chain);
                                        return;
                                    }
                                    a.changingMiniEpisode = false;
                                    clearPlaybackLoading(a);
                                    stopWeb(a);
                                    failInPlaceSwitch(a, s, kind, reason);
                                }
                            });
            a.webResolver = resolver;
            if (resolver.view() != null) {
                a.playbackLoading.addView(resolver.view(), new FrameLayout.LayoutParams(1, 1));
            }
            if (chained) {
                // WebResolver's built-in timeout is 30s; the chain needs its own shorter budget so
                // one silent page cannot stall the remaining candidates. The HTTP probe already
                // spent part of this interface's 8s slot, so only the remainder is available.
                final long remaining = chain.deadline - SystemClock.elapsedRealtime();
                final long perCandidate =
                        chain.candidateStarted <= 0
                                ? ParseChainResolver.PER_ATTEMPT_TIMEOUT_MS
                                : ParseChainResolver.PER_ATTEMPT_TIMEOUT_MS
                                        - Math.max(
                                                0L,
                                                SystemClock.elapsedRealtime()
                                                        - chain.candidateStarted);
                final long wait = Math.max(1L, Math.min(perCandidate, remaining));
                a.ui.postDelayed(
                        () -> {
                            if (a.webResolver != resolver) return;
                            if (ticket != a.generation
                                    || serial != a.playbackResolveSerial
                                    || a.isFinishing()) return;
                            stopWeb(a);
                            ParseChainResolver.logAttempt(
                                    candidate == null ? "原页面" : candidate.name, false, "网页嗅探超时");
                            startParseChain(a, s, v, ep, chain);
                        },
                        wait);
            }
        } catch (Exception error) {
            AppLog.w("PlaybackController", "WebResolver init failed", error);
            if (chained) {
                stopWeb(a);
                ParseChainResolver.logAttempt(
                        candidate == null ? "原页面" : candidate.name, false, "网页组件不可用");
                startParseChain(a, s, v, ep, chain);
            } else {
                a.changingMiniEpisode = false;
                clearPlaybackLoading(a);
                failInPlaceSwitch(
                        a, s, PlaybackFailure.Attribution.DEVICE, "这台设备无法运行网页播放组件，建议换一条线路或升级系统");
            }
        }
    }

    /**
     * Single exit for every playback failure shown in place: logs one attribution line, then shows
     * the same prefixed text in the status line and as a toast. {@code detail} is the sentence after
     * the 【...】 prefix; an empty detail falls back to the class advice.
     */
    static void failInPlaceSwitch(
            MainActivity a, Cms.Site s, PlaybackFailure.Attribution kind, String detail) {
        PlaybackFailure.Attribution attributed =
                kind == null ? PlaybackFailure.Attribution.UNKNOWN : kind;
        logAttribution(a, s, attributed, 0, 0);
        // A line-level failure gets one automatic hop first; the failed text is held back until the
        // hop budget is exhausted so the user sees one outcome instead of a stack of toasts.
        // 用户自己点的线路：switchToNextLine 一定返回 false，这里补一句"没有自动换源"，免得他以为
        // 应用又擅自换了一家（2026-11-26 Boss 现场：选了百度分享，失败后被换到 UC 直接播）。
        final boolean userPicked = isUserPickedLine(a == null ? null : a.activeEpisode);
        if (switchToNextLine(a, s, attributed)) return;
        String message =
                attributed.text(userPicked ? withUserPickedHint(detail) : detail);
        if (a.playerChrome != null) a.playerChrome.setLoading(false);
        if (a.current != null && a.current.player) {
            if (a.playStatus != null) a.playStatus.setText(message);
            showControls(a, true);
        }
        a.toast(message);
    }

    /**
     * One playback attempt's automatic line-switch budget: the lines that already failed, how many
     * automatic switches were made, and what the picker should announce once the switch plays.
     * Only touched on the UI thread.
     */
    private static final class LineFailover {
        private Cms.Video video;
        private final Set<String> tried = new HashSet<>();
        private int switches;
        private boolean selfRequested;
        private String pendingLabel;
        private String pendingTarget;
        /** Re-resolutions of the same episode after the CDN rejected a signed direct URL. */
        private int freshResolves;
        /** Set while our own re-resolve runs, so the counters survive the restart. */
        private boolean freshResolveRequested;

        void reset(Cms.Video next) {
            video = next;
            tried.clear();
            switches = 0;
            selfRequested = false;
            pendingLabel = null;
            pendingTarget = null;
            freshResolves = 0;
            freshResolveRequested = false;
        }
    }

    /**
     * True when the failure came from a signed netdisk address that a fresh resolve can replace:
     * the CDN expires these URLs and answers 403/404/412 for the stale ticket.
     */
    /** Host only: a signed URL's query is a credential, not diagnostic material. */
    private static String hostOf(String url) {
        if (url == null) return "-";
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            String host = uri.getHost();
            return host == null ? "-" : host;
        } catch (Throwable ignored) {
            return "-";
        }
    }

    private static boolean hasHeaderName(Cms.Episode ep, String name) {
        if (ep == null || ep.headers == null || name == null) return false;
        for (String key : ep.headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean isExpiredDirectUrl(Cms.Episode ep, int httpStatus) {
        if (ep == null) return false;
        if (httpStatus != 403 && httpStatus != 404 && httpStatus != 412) return false;
        boolean netdisk =
                ep.media.optBoolean("_netdiskHls", false)
                        || ep.media.optBoolean("_forceMpv", false)
                        || ep.media.optBoolean("_noCache", false);
        return netdisk && ep.origin != null && ep.origin.url != null && !ep.origin.url.trim().isEmpty();
    }

    /** Two hops show the feature without turning playback into a line roulette. */
    private static final int MAX_AUTO_LINE_SWITCHES = 2;

    /**
     * 用户在选择器里明确点过这条线路时，打在这一集 media 上的标记。只对本次播放的那一集有效：下一集是
     * 另一个 {@link Cms.Episode} 对象，自动连播因此不受影响；详情页重新加载会造新对象，标记自然消失。
     * 键名带 {@code _} 前缀，与其它内部标记（{@code _forceMpv} 等）同一套口径，不会漏进媒体参数。
     */
    static final String USER_PICKED_LINE = "_userPickedLine";

    /** 手动选线的失败句子后面补这一句：说清"没有自动换源"，并指到现有的「换源」入口。 */
    static final String USER_PICKED_LINE_HINT = "（手动选的线路，不会自动换源；可点「换源」或回选集换其他线路）";

    private static String withUserPickedHint(String detail) {
        String body = detail == null ? "" : detail.trim();
        return body.isEmpty() ? USER_PICKED_LINE_HINT : body + USER_PICKED_LINE_HINT;
    }

    /**
     * 这一集是不是"Boss 自己点的线路"播的。命中时失败只说明原因，不做线路自动切换、也不做跨站自动
     * 换源 —— 他选的是百度，就不能在他没同意的情况下换成 UC 直接播。
     */
    static boolean isUserPickedLine(Cms.Episode ep) {
        return ep != null && ep.media != null && ep.media.optBoolean(USER_PICKED_LINE, false);
    }

    /**
     * 播放入口的唯一打标点：{@code resolvePlayer} 之前先看这一页的选择器有没有"用户点过的线路"
     * （{@link EpisodeSelection#pickedLine}），且这次要播的就是那条线路，才给这一集打标。
     *
     * <p>为什么放在这里而不是每个按钮上：TV 的播放、选集面板、换站交接、继续播放最后都收敛到这一个
     * 入口；按钮上各写一遍会漏一条路径，而漏一条就等于换源又偷偷回来了。
     */
    private static void markUserPickedLine(MainActivity a, Cms.Line line, Cms.Episode ep) {
        if (a == null || ep == null || line == null || a.current == null) return;
        if (!a.current.selection.isPicked(line.name)) return;
        markPickedByUser(ep);
    }

    /**
     * 给"Boss 自己点的线路播的这一集"打标。播放入口（{@link #resolvePlayer} 由详情页选择器决定的那条）
     * 与播放器里的「换源」都收敛到这一个方法。
     */
    static void markPickedByUser(Cms.Episode ep) {
        if (ep == null) return;
        try {
            ep.media.put(USER_PICKED_LINE, true);
        } catch (Exception ignored) {
            // org.json 的受检异常；标记只是体验开关，写不进去就按旧行为换源。
        }
    }

    private static final LineFailover lineFailover = new LineFailover();

    /**
     * After a LINE/PARSE/SOURCE failure, plays the same episode from the next line of the same site
     * in picker order (wrapping once past the last line), skipping the lines this attempt already
     * tried. Device failures are not retried here: another line feeds the same decoder. At most
     * {@link #MAX_AUTO_LINE_SWITCHES} hops, so a site whose lines all fail still ends on the normal
     * failure text. Netdisk lines are included, their native resolution is already in place.
     *
     * <p>例外：这一集要是 Boss 在选择器里点的那条线路播的（{@link #isUserPickedLine}），这里一律
     * 返回 false —— 他选了哪家就看哪家，失败了说原因，不替他换。
     */
    private static boolean switchToNextLine(
            MainActivity a, Cms.Site s, PlaybackFailure.Attribution kind) {
        if (a == null || s == null || s.key == null || "live".equals(s.key)) return false;
        if (kind != PlaybackFailure.Attribution.LINE
                && kind != PlaybackFailure.Attribution.PARSE
                && kind != PlaybackFailure.Attribution.SOURCE) {
            return false;
        }
        // 这条线路是 Boss 在选择器里自己点的：失败就停在这一集，把原因说出来，不再替他换线路。
        // 默认线路/自动挑的线路走下面的自动换源，行为一个字节都不变。
        if (isUserPickedLine(a.activeEpisode)) return false;
        Cms.Video video = lineFailover.video;
        Cms.Line from = a.activeLine;
        if (video == null
                || video.lines == null
                || video.lines.size() < 2
                || from == null
                || from.name == null) {
            return false;
        }
        lineFailover.tried.add(from.name);
        if (lineFailover.switches >= MAX_AUTO_LINE_SWITCHES) return false;
        int index = video.lines.indexOf(from);
        if (index < 0) return false;
        for (int offset = 1; offset < video.lines.size(); offset++) {
            Cms.Line candidate = video.lines.get((index + offset) % video.lines.size());
            if (candidate == null
                    || candidate.name == null
                    || candidate.name.trim().isEmpty()
                    || lineFailover.tried.contains(candidate.name)
                    || candidate.episodes == null
                    || candidate.episodes.isEmpty()) {
                continue;
            }
            // Only a line that has the same episode can replace the current one.
            Cms.Episode match = findMatchingEpisode(candidate, from, a.activeEpisode);
            if (match == null) continue;
            lineFailover.switches++;
            lineFailover.selfRequested = true;
            lineFailover.pendingLabel = kind.label;
            lineFailover.pendingTarget = candidate.name;
            final Cms.Episode target = match;
            final Cms.Video source = video;
            final int ticket = a.generation;
            if (a.playStatus != null) {
                a.playStatus.setText("正在切换至 " + candidate.name + "…");
            }
            a.ui.post(
                    () -> {
                        if (ticket != a.generation) return;
                        resolvePlayer(a, s, source, candidate, target);
                    });
            return true;
        }
        return false;
    }

    /**
     * Marks the line healthy once it actually plays, and reports the automatic switch at that
     * moment: "switched" is only true when the new line reached the prepared state.
     */
    private static void onLinePrepared(MainActivity a, Cms.Site s) {
        if (a == null || s == null || "live".equals(s.key)) return;
        LineHealth.markOk(a, s, a.activeLine);
        if (lineFailover.pendingTarget == null) return;
        String label = lineFailover.pendingLabel == null ? "" : lineFailover.pendingLabel;
        a.toast("上一条线路【" + label + "】，已换到 " + lineFailover.pendingTarget);
        lineFailover.reset(lineFailover.video);
    }

    /** One PlayAttribution line per failure; only config labels, never URLs or credentials. */
    private static void logAttribution(
            MainActivity a, Cms.Site s, PlaybackFailure.Attribution kind, int code, int http) {
        AppLog.w(
                "PlayAttribution",
                PlaybackFailure.logLine(
                        kind,
                        code,
                        http,
                        a != null && a.activeLine != null ? a.activeLine.name : null,
                        s != null ? s.key : null));
        // Single attribution choke point for both failure exits (failInPlaceSwitch and the in-player
        // error handler), so the line picker memory is written here.
        if (a != null && s != null && !"live".equals(s.key)) {
            LineHealth.markFailed(a, s, a.activeLine, kind);
        }
    }

    private static boolean tryHistoryCrossSourceFailover(
            MainActivity a, Cms.Site failedSite, int currentPositionMs, String reason) {
        if (a == null
                || failedSite == null
                || "live".equals(failedSite.key)
                || a.historyFallbackInFlight) {
            return false;
        }
        WatchHistory.Entry bm = a.historyFallbackBookmark;
        if (bm == null || bm.title == null || bm.title.trim().isEmpty()) {
            return false;
        }
        if (!CatalogScreen.hasAlternativeHistorySources(a, failedSite.key)) {
            return false;
        }
        WatchHistory.Entry updated = WatchHistory.cloneEntry(bm);
        if (currentPositionMs > updated.position) {
            updated.position = currentPositionMs;
        }
        a.historyFallbackBookmark = null;
        CatalogScreen.resumeHistoryFromAlternativeSources(
                a, updated, failedSite.key, reason, false);
        return true;
    }

    static WatchHistory.Entry lookupSavedBookmark(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
        if (a == null
                || s == null
                || s.key == null
                || "live".equals(s.key)
                || v == null
                || v.id == null
                || ep == null) {
            return null;
        }
        WatchHistory.Entry found = matchBookmark(a.watchHistory(), s.key, v.id, line, ep);
        if (found != null) return found;
        // 网盘中心 native playback used the bare file id before 2026-11-27; ids now carry the drive
        // key (quark:/baidu:). Read the old row so existing progress is not lost, then rewrite its
        // key once so the same file does not end up with two rows.
        String legacyId = NetdiskScreen.legacyHistoryId(v.id);
        if (!legacyId.isEmpty()) {
            WatchHistory.Entry legacy = matchBookmark(a.watchHistory(), s.key, legacyId, line, ep);
            if (legacy != null) {
                migrateBookmarkKey(a, s.key, legacy, v.id);
                return legacy;
            }
        }
        return null;
    }

    /** The one entry for {@code siteKey + id} whose episode still matches this line/episode. */
    private static WatchHistory.Entry matchBookmark(
            List<WatchHistory.Entry> history,
            String siteKey,
            String bookmarkId,
            Cms.Line line,
            Cms.Episode ep) {
        if (history == null || siteKey == null || bookmarkId == null) return null;
        int epIdx = line != null ? line.episodes.indexOf(ep) : -1;
        for (WatchHistory.Entry entry : history) {
            if (!siteKey.equals(entry.site) || !bookmarkId.equals(entry.id)) continue;
            boolean sameName = ep.name != null && ep.name.equals(entry.episode);
            boolean sameIndex =
                    line != null
                            && line.name != null
                            && line.name.equals(entry.line)
                            && epIdx >= 0
                            && epIdx == entry.index;
            return sameName || sameIndex ? entry : null;
        }
        return null;
    }

    /**
     * Rewrites one legacy native-netdisk history key to the drive-prefixed one. The single storage
     * executor runs the upsert before the remove, so the intermediate state has both rows and the
     * final state exactly one; nothing else in the history is touched.
     */
    private static void migrateBookmarkKey(
            MainActivity a, String siteKey, WatchHistory.Entry legacy, String newId) {
        if (a == null || legacy == null || newId == null || newId.isEmpty()) return;
        final String oldKey = legacy.site + "\n" + legacy.id;
        WatchHistory.Entry migrated = WatchHistory.cloneEntry(legacy);
        migrated.id = newId;
        final WatchHistory.Persistence persistence = a.historyPersistence();
        WatchHistory.enqueueUpsert(persistence, migrated);
        WatchHistory.enqueueRemove(persistence, oldKey, null);
        AppLog.w(
                "PlaybackController",
                "history key migrated site=" + siteKey + " old_id=" + legacy.id + " new_id=" + newId);
    }

    private static Cms.Site findPushSite(MainActivity a) {
        if (a == null || a.config == null) return null;
        for (Cms.Site site : a.config.sites) {
            if (site.api != null
                    && site.api.toLowerCase(Locale.ROOT).contains("push")
                    && a.available(site)) {
                return site;
            }
        }
        return null;
    }

    private static boolean looksLikeNetdiskLine(Cms.Site s, Cms.Line line, String url) {
        String combined =
                ((s != null && s.name != null ? s.name : "")
                                + " "
                                + (line != null && line.name != null ? line.name : "")
                                + " "
                                + (url != null ? url : ""))
                        .toLowerCase(Locale.ROOT);
        return combined.contains("原画")
                || combined.contains("原畫")
                || combined.contains("网盘")
                || combined.contains("百度")
                || combined.contains("摆渡")
                || combined.contains("夸克")
                || combined.contains("夸父")
                || combined.contains("阿里")
                || combined.contains("天翼")
                || combined.contains("移动")
                || combined.contains("115")
                || combined.contains("123")
                || combined.contains("uc")
                || combined.contains("pan")
                || combined.contains("quark")
                || combined.contains("baidu");
    }

    private static Cms.Episode findMatchingEpisode(
            Cms.Line targetLine, Cms.Line fromLine, Cms.Episode ep) {
        return TvSeriesScraper.findMatchingEpisodeAcrossLines("", targetLine, fromLine, ep, null);
    }

    static void resolvePlayer(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
        markUserPickedLine(a, line, ep);
        if (lineFailover.freshResolveRequested) {
            // Our own re-resolve: same episode, same line, so the attempt budgets must survive.
            lineFailover.freshResolveRequested = false;
        } else if (lineFailover.selfRequested) {
            // Our own automatic line switch: keep the attempt budget and the tried set.
            lineFailover.selfRequested = false;
        } else {
            lineFailover.reset(v);
        }
        if (a.miniMode) {
            a.changingMiniEpisode = true;
            showEpisodeDetails(a, s, v);
        }
        a.activeLine = line;
        a.activeEpisode = ep;
        if (a.resumeAt == 0) {
            WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, line, ep);
            if (saved != null) {
                a.resumeAt = saved.resume();
            }
        }
        if (s.supported()) {
            String rewrittenEpUrl = LocalProxy.rewrite(ep.url);
            String cleanPath =
                    rewrittenEpUrl == null
                            ? ""
                            : rewrittenEpUrl.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
            boolean looksLikeWebPage =
                    cleanPath.endsWith(".html")
                            || cleanPath.endsWith(".htm")
                            || cleanPath.endsWith(".shtml")
                            || cleanPath.contains("/share/");
            if (Cms.isDirectStreamUrl(rewrittenEpUrl)
                    || (!s.requiresParser && Cms.isMedia(rewrittenEpUrl) && !looksLikeWebPage)) {
                openPlayer(a, s, v, ep);
                return;
            }
            if (v != null && v.lines != null) {
                for (Cms.Line sibling : v.lines) {
                    if (sibling == line) continue;
                    Cms.Episode match = findMatchingEpisode(sibling, line, ep);
                    if (match != null && Cms.isDirectStreamUrl(LocalProxy.rewrite(match.url))) {
                        a.activeLine = sibling;
                        a.activeEpisode = match;
                        rememberEpisodeOnBackStack(a, sibling, match);
                        openPlayer(a, s, v, match);
                        return;
                    }
                }
            }
            resolveCmsWithParser(a, s, v, ep, s.playUrl);
            return;
        }
        if (a.playbackLoading != null) return;
        showPlaybackLoading(a);
        PluginDialogStyler.watch(a);
        final int serial = a.playbackResolveSerial, ticket = a.generation;
        Future<?> request =
                RequestScope.submit(
                        PLAYER_RESOLVE,
                        () -> {
                            try {
                                Cms.Site targetSite = s;
                                Cms.Line chosenLine = line;
                                Cms.Episode chosenEp = ep;
                                String targetFlag = line != null ? line.name : "";
                                String targetId = ep.url != null ? ep.url : "";
                                if (targetId.regionMatches(true, 0, "push://", 0, 7)) {
                                    Cms.Site pushSite = findPushSite(a);
                                    if (pushSite != null) {
                                        targetSite = pushSite;
                                        String pushTarget = targetId.substring(7);
                                        Cms.Listing pushList =
                                                Cms.listing(a.engine(pushSite).detail(pushTarget));
                                        if (!pushList.videos.isEmpty()
                                                && !pushList.videos.get(0).lines.isEmpty()
                                                && !pushList.videos
                                                        .get(0)
                                                        .lines
                                                        .get(0)
                                                        .episodes
                                                        .isEmpty()) {
                                            Cms.Line pLine = pushList.videos.get(0).lines.get(0);
                                            targetFlag = pLine.name;
                                            targetId = pLine.episodes.get(0).url;
                                        }
                                    }
                                }
                                JSONObject result = null;
                                Cms.Episode resolvedCandidate = null;
                                Exception primaryError = null;
                                // Netdisk lines are resolved by us FIRST. The config-center
                                // plugin has no 夸克 credential of its own, so letting it answer
                                // first only produces its "cookie invalid, please log in again"
                                // popup and a five second wait before our own resolution wins.
                                // Recognising the id shape is the whole condition; when it does
                                // not match, nothing changes and the plugin answers as before.
                                final Cms.Episode nativeFirst =
                                        nativeNetdiskCandidate(
                                                targetSite, targetFlag, chosenEp, null, null);
                                if (nativeFirst != null) {
                                    final int nativeSerial = serial;
                                    final int nativeTicket = ticket;
                                    a.ui.post(
                                            () -> {
                                                if (nativeSerial != a.playbackResolveSerial
                                                        || nativeTicket != a.generation
                                                        || a.isFinishing()) return;
                                                clearPlaybackLoading(a);
                                                openPlayer(a, s, v, nativeFirst);
                                            });
                                    return;
                                }
                                AppLog.w(
                                        "SpiderAsk",
                                        "site="
                                                + targetSite.key
                                                + " flag="
                                                + targetFlag
                                                + " id=["
                                                + targetId
                                                + "]");
                                try {
                                    result =
                                            new JSONObject(
                                                    a.engine(targetSite)
                                                            .player(targetFlag, targetId));
                                    resolvedCandidate =
                                            MediaOptions.episode(targetSite, chosenEp, result);
                                    if (resolvedCandidate.url.regionMatches(
                                            true, 0, "push://", 0, 7)) {
                                        Cms.Site pushSite = findPushSite(a);
                                        if (pushSite != null) {
                                            String pushTarget = resolvedCandidate.url.substring(7);
                                            Cms.Listing pushList =
                                                    Cms.listing(
                                                            a.engine(pushSite).detail(pushTarget));
                                            if (!pushList.videos.isEmpty()
                                                    && !pushList.videos.get(0).lines.isEmpty()
                                                    && !pushList.videos
                                                            .get(0)
                                                            .lines
                                                            .get(0)
                                                            .episodes
                                                            .isEmpty()) {
                                                Cms.Line pLine =
                                                        pushList.videos.get(0).lines.get(0);
                                                JSONObject pushRes =
                                                        new JSONObject(
                                                                a.engine(pushSite)
                                                                        .player(
                                                                                pLine.name,
                                                                                pLine.episodes.get(
                                                                                                0)
                                                                                        .url));
                                                resolvedCandidate =
                                                        MediaOptions.episode(
                                                                pushSite, chosenEp, pushRes);
                                                result = pushRes;
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    primaryError = e;
                                }

                                boolean primaryPlayable =
                                        resolvedCandidate != null
                                                && resolvedCandidate.url != null
                                                && !resolvedCandidate.url.trim().isEmpty()
                                                && (Cms.isMedia(resolvedCandidate.url.trim())
                                                        || resolvedCandidate
                                                                .url
                                                                .trim()
                                                                .startsWith("magnet:?")
                                                        || (result != null
                                                                && (result.optInt("parse", 0) != 0
                                                                        || result.optInt("jx", 0)
                                                                                != 0)));
                                if (!primaryPlayable
                                        && v != null
                                        && v.lines != null
                                        && v.lines.size() > 1
                                        && !looksLikeNetdiskLine(s, line, targetId)) {
                                    for (Cms.Line altLine : v.lines) {
                                        if (altLine == null
                                                || altLine == line
                                                || altLine.episodes.isEmpty()) {
                                            continue;
                                        }
                                        Cms.Episode altEp = findMatchingEpisode(altLine, line, ep);
                                        if (altEp == null || altEp.url == null) continue;
                                        if (looksLikeNetdiskLine(s, altLine, altEp.url)) continue;
                                        try {
                                            JSONObject altRes =
                                                    new JSONObject(
                                                            a.engine(s)
                                                                    .player(
                                                                            altLine.name,
                                                                            altEp.url));
                                            Cms.Episode altResolved =
                                                    MediaOptions.episode(s, altEp, altRes);
                                            String altUrl =
                                                    altResolved.url == null
                                                            ? ""
                                                            : altResolved.url.trim();
                                            if (!altUrl.isEmpty()
                                                    && (Cms.isMedia(altUrl)
                                                            || altUrl.startsWith("magnet:?")
                                                            || altRes.optInt("parse", 0) != 0
                                                            || altRes.optInt("jx", 0) != 0)) {
                                                result = altRes;
                                                resolvedCandidate = altResolved;
                                                chosenLine = altLine;
                                                chosenEp = altEp;
                                                primaryError = null;
                                                break;
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                                if (primaryError != null && resolvedCandidate == null) {
                                    throw primaryError;
                                }
                                final JSONObject finalResult =
                                        result != null ? result : new JSONObject();
                                final Cms.Episode resolved =
                                        resolvedCandidate != null
                                                ? resolvedCandidate
                                                : MediaOptions.episode(
                                                        targetSite, chosenEp, finalResult);
                                final Cms.Line finalLine = chosenLine;
                                final Cms.Episode finalEp = chosenEp;
                                final boolean switchedLine =
                                        chosenLine != line && chosenLine != null;
                                // Netdisk safety net: the config-center plugin has no 夸克
                                // credentials, so a 夸克 line answers {"list":[],"jx":0,"parse":0}
                                // and no address ever reaches the player. Ask our own logged-in
                                // client for one instead. Null keeps the original failure path.
                                final Cms.Episode nativeEp =
                                        nativeNetdiskCandidate(
                                                targetSite,
                                                targetFlag,
                                                finalEp,
                                                finalResult,
                                                resolved);
                                a.ui.post(
                                        () -> {
                                            if (serial != a.playbackResolveSerial
                                                    || ticket != a.generation
                                                    || a.isFinishing()) {
                                                JSONObject unused =
                                                        resolved.media.optJSONObject("embySession");
                                                if (unused != null
                                                        && s != null
                                                        && s.api != null
                                                        && s.api.startsWith(
                                                                com.pilipala.emby.EmbyLibrary
                                                                        .PREFIX))
                                                    try {
                                                        com.pilipala.emby.EmbyAccounts.open(
                                                                        a, s.api)
                                                                .track(unused)
                                                                .stop(0);
                                                    } catch (Exception ignored) {
                                                    }
                                                return;
                                            }
                                            try {
                                                if (switchedLine) {
                                                    a.activeLine = finalLine;
                                                    a.activeEpisode = finalEp;
                                                    a.toast("当前线路响应异常，已自动切换至 " + finalLine.name);
                                                }
                                                String url =
                                                        resolved.url == null
                                                                ? ""
                                                                : resolved.url.trim();
                                                if (url.isEmpty() && finalEp.url != null) {
                                                    String fallbackEpUrl =
                                                            LocalProxy.rewrite(finalEp.url.trim());
                                                    if (Cms.isMedia(fallbackEpUrl)
                                                            || fallbackEpUrl.startsWith(
                                                                    "magnet:?")) {
                                                        url = fallbackEpUrl;
                                                        resolved.url = fallbackEpUrl;
                                                    }
                                                }
                                                if (finalResult.optInt("parse", 0) != 0
                                                        || finalResult.optInt("jx", 0) != 0) {
                                                    String playPrefix =
                                                            finalResult.optString(
                                                                    "playUrl", s.playUrl);
                                                    boolean hasParses =
                                                            a.config != null
                                                                    && !a.config.parses.isEmpty();
                                                    if (hasParses) {
                                                        // Parse interfaces are available: run the
                                                        // ordered chain, starting with the source's
                                                        // own prefix when it has one.
                                                        clearPlaybackLoading(a);
                                                        resolveCmsWithParser(
                                                                a,
                                                                s,
                                                                v,
                                                                resolved,
                                                                playPrefix == null
                                                                        ? ""
                                                                        : playPrefix);
                                                        return;
                                                    }
                                                    resolveWebPage(a, s, v, resolved, playPrefix);
                                                    return;
                                                }
                                                // Native netdisk hook: only when the plugin came
                                                // back empty, exactly before the "no address"
                                                // fallback below, so every existing failure message
                                                // still shows when this returns nothing.
                                                if (url.isEmpty() && nativeEp != null) {
                                                    clearPlaybackLoading(a);
                                                    openPlayer(a, s, v, nativeEp);
                                                    return;
                                                }
                                                clearPlaybackLoading(a);
                                                if (url.isEmpty()) {
                                                    a.changingMiniEpisode = false;
                                                    // 用户自己点的线路失败时不做跨站自动换源（那条路也是"换到
                                                    // 别家直接播"），只把原因说清楚。
                                                    if (!isUserPickedLine(a.activeEpisode)
                                                            && tryHistoryCrossSourceFailover(
                                                                    a,
                                                                    s,
                                                                    a.resumeAt,
                                                                    "原片源未返回有效播放地址")) {
                                                        return;
                                                    }
                                                    String spiderMsg =
                                                            finalResult
                                                                    .optString(
                                                                            "msg",
                                                                            finalResult.optString(
                                                                                    "message",
                                                                                    finalResult
                                                                                            .optString(
                                                                                                    "tip",
                                                                                                    "")))
                                                                    .trim();
                                                    // 网盘线路的失败原因是我们自己解析出来的（阿里：分享失效 /
                                                    // 需要提取码 / 需要登录 / 登录失效 / 无直链；UC：分享失效 /
                                                    // 需要提取码 / 需要登录 UC；百度：需要登录 / 登录失效 /
                                                    // 分享失效），比插件那句笼统的「PC端未登录或Cookie失效」
                                                    // 准确：有就先说它。夸克与普通线路仍走下面原来的顺序，
                                                    // 一个字节都不变。
                                                    String netdiskRefusal =
                                                            netdiskRefusalReason(finalEp);
                                                    if (!netdiskRefusal.isEmpty()) {
                                                        failInPlaceSwitch(
                                                                a,
                                                                s,
                                                                PlaybackFailure.Attribution.SOURCE,
                                                                netdiskRefusal);
                                                    } else if (!spiderMsg.isEmpty()) {
                                                        failInPlaceSwitch(
                                                                a,
                                                                s,
                                                                PlaybackFailure.Attribution.LINE,
                                                                spiderMsg);
                                                    } else if (looksLikeNetdiskLine(
                                                            s, finalLine, finalEp.url)) {
                                                        String refusal =
                                                                QuarkShareResolver
                                                                        .lastFailureReason();
                                                        failInPlaceSwitch(
                                                                a,
                                                                s,
                                                                PlaybackFailure.Attribution.SOURCE,
                                                                refusal.isEmpty()
                                                                        ? "该网盘线路未返回播放地址，请先在顶部「网盘」里扫码登录对应网盘，或点击右上角 ⇄ 切换其他线路"
                                                                        : refusal);
                                                    } else {
                                                        failInPlaceSwitch(
                                                                a,
                                                                s,
                                                                PlaybackFailure.Attribution.LINE,
                                                                "当前线路未返回有效播放地址，请点击右上角 ⇄ 切换其他线路");
                                                    }
                                                    return;
                                                }
                                                if (!Cms.isMedia(url)
                                                        && !url.startsWith("magnet:?")) {
                                                    a.changingMiniEpisode = false;
                                                    if (looksLikeNetdiskLine(s, finalLine, url)) {
                                                        failInPlaceSwitch(
                                                                a,
                                                                s,
                                                                PlaybackFailure.Attribution.SOURCE,
                                                                "该网盘线路需先在顶部「网盘」里登录授权，或点击右上角 ⇄ 切换其他线路");
                                                    } else {
                                                        failInPlaceSwitch(
                                                                a,
                                                                s,
                                                                PlaybackFailure.Attribution.SOURCE,
                                                                "来源返回的播放协议暂不支持，建议换一条线路");
                                                    }
                                                    return;
                                                }
                                                openPlayer(a, s, v, resolved);
                                            } catch (Exception error) {
                                                AppLog.w(
                                                        "PlaybackController",
                                                        "parse player response failed",
                                                        error);
                                                a.changingMiniEpisode = false;
                                                clearPlaybackLoading(a);
                                                failInPlaceSwitch(
                                                        a,
                                                        s,
                                                        PlaybackFailure.Attribution.UNKNOWN,
                                                        "播放信息无法读取，已记录，建议重试或换一条线路");
                                            }
                                        });
                            } catch (Exception error) {
                                AppLog.w("PlaybackController", "engine.player failed", error);
                                a.ui.post(
                                        () -> {
                                            if (serial != a.playbackResolveSerial
                                                    || ticket != a.generation) return;
                                            a.changingMiniEpisode = false;
                                            clearPlaybackLoading(a);
                                            if (tryHistoryCrossSourceFailover(
                                                    a, s, a.resumeAt, "原片源解析失败")) {
                                                return;
                                            }
                                            failInPlaceSwitch(
                                                    a,
                                                    s,
                                                    PlaybackFailure.Attribution.LINE,
                                                    a.message(error));
                                        });
                            }
                        });
        a.pageRequests.add(request);
    }

    /**
     * Native netdisk fallback for a line the config-center plugin could not resolve: returns the
     * episode built by our own native resolver (阿里 when the id starts with {@code al}, 百度 when
     * it starts with {@code bd}, UC when it has four segments, 夸克 otherwise), or null so the
     * caller keeps its original "no address" handling (same toast, same cross-source failover).
     *
     * <p>Deliberately narrow: only when the plugin answered empty, did not ask for the web parser,
     * the episode url is not already a plain media link, and the id is a netdisk share id. Runs on
     * the player-resolve worker thread.
     */
    private static Cms.Episode nativeNetdiskCandidate(
            Cms.Site site,
            String lineName,
            Cms.Episode ep,
            JSONObject result,
            Cms.Episode resolved) {
        if (ep == null || ep.url == null) return null;
        String rawEp = ep.url.trim();
        if (rawEp.isEmpty() || Cms.isMedia(rawEp) || rawEp.startsWith("magnet:?")) return null;
        if (result != null && (result.optInt("parse", 0) != 0 || result.optInt("jx", 0) != 0)) {
            return null;
        }
        String pluginUrl = resolved == null || resolved.url == null ? "" : resolved.url.trim();
        if (!pluginUrl.isEmpty()) {
            AppLog.w("QuarkShare", "skip: plugin returned url=[" + pluginUrl + "]");
            return null;
        }
        // 按 id 形状分派：第一段 al 的阿里四段、第一段 bd 的百度五段、四段是 UC 分享，其余（五段
        // 夸克分享等）仍走原来那条，夸克那边一行不改。判据只看形状，不看站名/线路名：虎斑的
        // 「UC原画13」这种线路名和四段 id 形状已经够用。
        final boolean aliShare = AliShareResolver.looksLikeShareId(rawEp);
        final boolean baiduShare = !aliShare && BaiduShareResolver.looksLikeShareId(rawEp);
        final boolean ucShare =
                !aliShare && !baiduShare && UcShareResolver.looksLikeShareId(rawEp);
        final String logTag =
                aliShare
                        ? "AliShare"
                        : baiduShare ? "BaiduShare" : ucShare ? "UcShare" : "QuarkShare";
        try {
            Cms.Episode nativeEp =
                    aliShare
                            ? AliShareResolver.resolve(
                                    site == null ? "" : site.api, lineName, rawEp)
                            : baiduShare
                                    ? BaiduShareResolver.resolve(
                                            site == null ? "" : site.api, lineName, rawEp)
                                    : ucShare
                                            ? UcShareResolver.resolve(
                                                    site == null ? "" : site.api, lineName, rawEp)
                                            : QuarkShareResolver.resolve(
                                                    site == null ? "" : site.api, lineName, rawEp);
            if (nativeEp != null && nativeEp.url != null && !nativeEp.url.trim().isEmpty()) {
                // The synthesized episode carries only the media address. Keep the share-id
                // episode as its origin so auto-next advances through the real episode list and
                // resolves the next episode natively again.
                nativeEp.origin = ep;
        // Auto-next needs the next file inside the same share; carry it on both objects because
        // playback state keeps the original episode while the address lives on the synthesized one.
        String nextShareUrl = nativeEp.media.optString("_nextShareUrl", "");
        if (!nextShareUrl.isEmpty()) {
            try {
                ep.media.put("_nextShareUrl", nextShareUrl);
                String nextShareName = nativeEp.media.optString("_nextShareName", "");
                if (!nextShareName.isEmpty()) ep.media.put("_nextShareName", nextShareName);
            } catch (org.json.JSONException ignored) {
                // Auto-next simply falls back to the episode list when this copy fails.
            }
        }
                AppLog.w(logTag, "native episode ready name=" + nativeEp.name);
                return nativeEp;
            }
        } catch (RuntimeException error) {
            AppLog.w(logTag, "native resolution crashed", error);
        }
        return null;
    }

    /**
     * 网盘线路失败时给用户看的原因（阿里：分享失效 / 需要提取码 / 需要登录 / 登录失效 / 无直链；
     * UC：分享失效 / 需要提取码 / 需要登录 UC；百度：需要登录 / 登录失效 / 分享失效）。只有
     * episode id 真是对应那家的形状才去读它的 {@code lastFailureReason()}，免得把上一次解析留下的
     * 旧原因错挂到别的线路上。不是网盘线路返回 ""，调用方按原来的顺序处理。
     */
    private static String netdiskRefusalReason(Cms.Episode ep) {
        if (ep == null || ep.url == null) return "";
        if (AliShareResolver.looksLikeShareId(ep.url)) return AliShareResolver.lastFailureReason();
        if (UcShareResolver.looksLikeShareId(ep.url)) return UcShareResolver.lastFailureReason();
        if (BaiduShareResolver.looksLikeShareId(ep.url)) {
            return BaiduShareResolver.lastFailureReason();
        }
        return "";
    }

    static void resolveCmsWithParser(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, String preferredPrefix) {
        if (a.playbackLoading != null) return;
        showPlaybackLoading(a);
        PluginDialogStyler.watch(a);
        final int serial = a.playbackResolveSerial, ticket = a.generation;
        Future<?> request =
                RequestScope.submit(
                        PLAYER_RESOLVE,
                        () -> {
                            // 0. If ep.url is an HTML share wrapper with an embedded .m3u8/.mp4,
                            // extract it directly before falling back to external parsers.
                            if (ep.url != null && Cms.isHttp(ep.url)) {
                                try {
                                    String html = Cms.fetch(ep.url, s.headers);
                                    String embedded =
                                            ParseChainResolver.extractEmbeddedStreamUrl(
                                                    ep.url, html);
                                    if (Cms.isDirectStreamUrl(embedded)) {
                                        final Cms.Episode directEp = new Cms.Episode();
                                        directEp.name = ep.name;
                                        directEp.url = embedded;
                                        directEp.headers.putAll(
                                                Cms.inheritedHeaders(s.api, embedded, s.headers));
                                        a.ui.post(
                                                () -> {
                                                    if (serial != a.playbackResolveSerial
                                                            || ticket != a.generation
                                                            || a.isFinishing()) return;
                                                    clearPlaybackLoading(a);
                                                    openPlayer(a, s, v, directEp);
                                                });
                                        return;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                            // 1. Prepare the ordered parse chain off the UI thread. Every later
                            // step hops back to the UI thread because WebView work requires it.
                            final List<ParseChainResolver.Candidate> candidates =
                                    ParseChainResolver.candidates(a, s, preferredPrefix);
                            a.ui.post(
                                    () -> {
                                        if (serial != a.playbackResolveSerial
                                                || ticket != a.generation
                                                || a.isFinishing()) return;
                                        startParseChain(
                                                a,
                                                s,
                                                v,
                                                ep,
                                                new ParseChain(candidates, ticket, serial));
                                    });
                        });
        a.pageRequests.add(request);
    }

    /**
     * Advances the parse chain by one interface. Runs on the UI thread. Type-0 interfaces are
     * player pages and are verified by WebView sniffing; probing them over HTTP would burn the
     * budget without proving a JS-driven player works. JSON-style types are probed over HTTP first
     * and only fall back to the WebView when the response looks like a page.
     */
    private static void startParseChain(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, ParseChain chain) {
        if (a == null || chain == null) return;
        if (chain.ticket != a.generation
                || chain.serial != a.playbackResolveSerial
                || a.isFinishing()) return;
        long remaining = chain.deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0 || chain.next >= chain.candidates.size()) {
            finishParseChain(a, s, v, ep, chain);
            return;
        }
        final ParseChainResolver.Candidate candidate = chain.candidates.get(chain.next++);
        chain.attempted++;
        chain.candidateStarted = SystemClock.elapsedRealtime();
        if (candidate.type == 0) {
            startWebResolver(
                    a, s, v, ep, ParseChainResolver.targetUrl(candidate, ep.url), chain, candidate);
            return;
        }
        final long probeCap =
                candidate.fromConfig
                        ? ParseChainResolver.PROBE_PHASE_TIMEOUT_MS
                        : ParseChainResolver.PREFERRED_PROBE_TIMEOUT_MS;
        final long budget =
                Math.min(probeCap, Math.min(ParseChainResolver.PER_ATTEMPT_TIMEOUT_MS, remaining));
        ParseChainResolver.probeAsync(
                s,
                ep,
                candidate,
                budget,
                probe -> {
                    a.ui.post(
                            () -> {
                                if (chain.ticket != a.generation
                                        || chain.serial != a.playbackResolveSerial
                                        || a.isFinishing()) return;
                                if (probe != null && probe.ok && probe.episode != null) {
                                    ParseChainResolver.logAttempt(
                                            candidate.name, true, probe.reason);
                                    if (candidate.fromConfig) {
                                        ParseChainResolver.remember(a, candidate.name);
                                    }
                                    clearPlaybackLoading(a);
                                    openPlayer(a, s, v, probe.episode);
                                    return;
                                }
                                String reason = probe == null ? "请求失败" : probe.reason;
                                if (probe != null && (probe.pageLike || !candidate.fromConfig)) {
                                    // The interface answered with a player page (or this is the
                                    // source's own prefix): let the WebView sniff it before
                                    // moving on, matching the old page-based behaviour.
                                    startWebResolver(
                                            a,
                                            s,
                                            v,
                                            ep,
                                            ParseChainResolver.targetUrl(candidate, ep.url),
                                            chain,
                                            candidate);
                                    return;
                                }
                                ParseChainResolver.logAttempt(candidate.name, false, reason);
                                startParseChain(a, s, v, ep, chain);
                            });
                });
    }

    /** Emits the terminal state of the chain; only here does the user see a parse failure. */
    private static void finishParseChain(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, ParseChain chain) {
        if (chain.ticket != a.generation
                || chain.serial != a.playbackResolveSerial
                || a.isFinishing()) return;
        if (Cms.isDirectStreamUrl(ep.url)) {
            clearPlaybackLoading(a);
            openPlayer(a, s, v, ep);
            return;
        }
        long remaining = chain.deadline - SystemClock.elapsedRealtime();
        if (chain.webAttempts == 0 && !chain.rawPageTried && remaining > 0 && Cms.isHttp(ep.url)) {
            // JSON-only configurations used to sniff the episode page itself when no page-style
            // interface existed; keep that one last attempt.
            chain.rawPageTried = true;
            chain.candidateStarted = SystemClock.elapsedRealtime();
            startWebResolver(a, s, v, ep, ep.url, chain, null);
            return;
        }
        a.changingMiniEpisode = false;
        clearPlaybackLoading(a);
        failInPlaceSwitch(
                a,
                s,
                PlaybackFailure.Attribution.PARSE,
                PlaybackFailure.parseFailure(chain.attempted));
    }

    /** Mutable state of one parse-chain run; only touched on the UI thread. */
    private static final class ParseChain {
        final List<ParseChainResolver.Candidate> candidates;
        final long deadline;
        final int ticket;
        final int serial;
        int next;
        int attempted;
        int webAttempts;
        long candidateStarted;
        boolean rawPageTried;

        ParseChain(List<ParseChainResolver.Candidate> candidates, int ticket, int serial) {
            this.candidates = candidates;
            this.deadline = SystemClock.elapsedRealtime() + ParseChainResolver.TOTAL_TIMEOUT_MS;
            this.ticket = ticket;
            this.serial = serial;
        }
    }

    static void showPlaybackLoading(MainActivity a) {
        a.playbackLoadingFocus = a.getCurrentFocus();
        if (a.markerHost != null) a.markerHost.abort();
        a.playbackLoading = new PlaybackLoading(a);
        a.root.addView(a.playbackLoading, new FrameLayout.LayoutParams(-1, -1));
        if (a.playerChrome != null) a.playerChrome.update();
        a.playbackLoading.requestFocus();
    }

    static void clearPlaybackLoading(MainActivity a) {
        a.playbackResolveSerial++;
        if (a.playbackLoading != null) {
            if (a.playbackLoading.getParent() instanceof ViewGroup)
                ((ViewGroup) a.playbackLoading.getParent()).removeView(a.playbackLoading);
            a.playbackLoading = null;
            if (a.playerChrome != null) a.playerChrome.update();
            if (a.playbackLoadingFocus != null && UiCompat.isAttached(a.playbackLoadingFocus))
                a.playbackLoadingFocus.requestFocus();
            a.playbackLoadingFocus = null;
        }
    }

    /** Engine label shared by the PlayKernel and HlsRoute log lines. */
    private static String kernelName(MoviePlayer player) {
        if (player instanceof MpvPlayer) return "mpv";
        if (player instanceof SystemPlayer) return "system";
        return "media3";
    }

    /** True when the map carries a Cookie header, whatever its case. */
    private static boolean hasCookieHeader(Map<String, String> headers) {
        if (headers == null) return false;
        for (String key : headers.keySet()) {
            if ("Cookie".equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    /**
     * True when a player error looks like the first fetch being refused: an HTTP 4xx/5xx answer or
     * one of the Media3 IO error codes (2000..2999). Used to tell an unreachable HLS proxy apart
     * from decode or container problems. Such a failure downgrades the episode from the loopback
     * proxy to the original playlist URL once (_hlsDirectFallback guards the repeat).
     */
    static boolean isHlsProxyFetchFailure(int what, int httpStatus) {
        return httpStatus >= 400 || (what >= 2000 && what < 3000);
    }

    static void openPlayer(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode e) {
        // A natively resolved episode is only the media address; the playing context must stay on
        // the episode the user picked so auto-next, history and markers keep the episode list.
        if (e.origin != null) a.activeEpisode = e.origin;
        if (e.url != null) {
            e.url = e.url.trim();
            if (e.url.startsWith("//")) {
                e.url = "https:" + e.url;
            }
            if (e.url.regionMatches(true, 0, "webview://", 0, 10)) {
                e.url = e.url.substring(10).trim();
                resolveWebPage(a, s, v, e, "");
                return;
            }
            if (a.config != null
                    && (e.url.regionMatches(true, 0, "proxy://", 0, 8)
                            || e.url.contains("/proxy"))) {
                try {
                    a.ensureProxy(a.config);
                } catch (Exception ignored) {
                }
            }
            e.url = LocalProxy.rewrite(e.url);
        }
        if (e.url != null && e.url.startsWith("magnet:?")) {
            magnetPlayer(a, s, v, e);
            return;
        }
        if (!Cms.isMedia(e.url)) {
            if (a.playerChrome != null) a.playerChrome.setLoading(false);
            a.notice("这条剧集需要解析或使用了特殊协议，基础版尚未支持。");
            return;
        }
        MainActivity.Screen screen = new MainActivity.Screen(() -> playerPage(a, s, v, e));
        screen.player = true;
        a.open(screen, a.current == null || !a.current.player);
    }

    static void magnetPlayer(MainActivity a, Cms.Site site, Cms.Video video, Cms.Episode episode) {
        a.open(
                new MainActivity.Screen(
                        () -> {
                            a.renderPage(video.title, "MAGNET PLAYBACK");
                            TextView state = a.text("正在获取磁力文件列表…", 17, MainActivity.MUTED);
                            a.body.addView(state);
                            final int ticket = a.generation;
                            try {
                                a.magnetStream =
                                        MagnetStream.start(
                                                a,
                                                episode.url,
                                                new MagnetStream.Listener() {
                                                    public void progress(String message) {
                                                        if (ticket == a.generation)
                                                            state.setText(message);
                                                    }

                                                    public void files(
                                                            List<MagnetStream.MediaFile> files,
                                                            int suggested,
                                                            MagnetStream.Selection selection) {
                                                        if (ticket != a.generation) return;
                                                        String[] names = new String[files.size()];
                                                        for (int i = 0; i < names.length; i++)
                                                            names[i] = files.get(i).name;
                                                        new AppDialog.Builder(a)
                                                                .setTitle("选择要播放的视频")
                                                                .setSingleChoiceItems(
                                                                        names,
                                                                        suggested,
                                                                        (dialog, index) -> {
                                                                            dialog.dismiss();
                                                                            selection.select(index);
                                                                        })
                                                                .setNegativeButton(
                                                                        "取消",
                                                                        (dialog, index) ->
                                                                                a.onBackPressed())
                                                                .setOnCancelListener(
                                                                        dialog -> a.onBackPressed())
                                                                .show();
                                                    }

                                                    public void ready(
                                                            String url, String mime, String name) {
                                                        if (ticket != a.generation) return;
                                                        Cms.Episode resolved = new Cms.Episode();
                                                        resolved.name = name;
                                                        resolved.url = url;
                                                        resolved.headers.putAll(episode.headers);
                                                        try {
                                                            resolved.media =
                                                                    new JSONObject(
                                                                                    episode.media
                                                                                            .toString())
                                                                            .put("mimeType", mime);
                                                        } catch (Exception ignored) {
                                                        }
                                                        if (!a.back.isEmpty())
                                                            a.current = a.back.pop();
                                                        openPlayer(a, site, video, resolved);
                                                    }

                                                    public void error(String message) {
                                                        if (ticket == a.generation) {
                                                            state.setText(message);
                                                            a.addButton(
                                                                    a.body,
                                                                    a.button(
                                                                            "重试",
                                                                            "primary",
                                                                            a::refresh));
                                                        }
                                                    }
                                                });
                            } catch (Exception error) {
                                AppLog.w("PlaybackController", "MagnetStream start failed", error);
                                state.setText("磁力播放初始化失败：" + error.getClass().getSimpleName());
                            }
                        }),
                true);
    }

    static void changeEpisode(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            Cms.Line line,
            Cms.Episode episode,
            int position) {
        if (episode != null && episode.media != null && episode != a.activeEpisode) {
            episode.media.remove("_eofReconnectCount");
            episode.media.remove("_ioReconnectCount");
            episode.media.remove("_hwRetry");
        }
        a.changingMiniEpisode = a.miniMode;
        a.resumeAt = position;
        if (a.miniMode) {
            showEpisodeDetails(a, site, video);
        } else {
            rememberEpisodeOnBackStack(a, line, episode);
            if (a.current != null && a.current.player) {
                prepareInPlaceSwitch(a, line, episode);
            }
        }
        resolvePlayer(a, site, video, line, episode);
    }

    static void rememberEpisodeOnBackStack(MainActivity a, Cms.Line line, Cms.Episode episode) {
        if (a.back.isEmpty() || line == null || episode == null) return;
        MainActivity.Screen prev = a.back.peek();
        if (prev == null) return;
        int epIdx = line.episodes.indexOf(episode);
        if (epIdx >= 0) {
            boolean desc = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
            int page = EpisodeSelection.pageFor(epIdx, line.episodes.size(), desc);
            prev.selection.select(line.name, page, line.episodes.size());
            prev.focus = "episode:" + line.name + ":" + epIdx;
            prev.episodeFocus = epIdx;
            prev.episodeFocusLine = line.name;
        }
    }

    static void prepareInPlaceSwitch(MainActivity a, Cms.Line line, Cms.Episode episode) {
        finishSeek(a, false);
        savePlaybackHistory(a, true);
        stopWeb(a);
        stopMagnet(a);
        if (a.player != null) {
            try {
                a.player.release();
            } catch (Exception error) {
                AppLog.w("PlaybackController", "player.release failed", error);
            }
            a.player = null;
        }
        a.prepared = false;
        a.playing = false;
        if (a.playerChrome != null) {
            a.playerChrome.switchEpisode(episode, line);
        }
    }

    static void showEpisodeDetails(MainActivity a, Cms.Site site, Cms.Video video) {
        releasePlayer(a);
        if (!a.isTvDevice()) {
            a.applyMobileSystemBars(false);
            a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
            if (a.usableWidth > a.usableHeight && a.usableHeight > 0) {
                int tmp = a.usableWidth;
                a.usableWidth = a.usableHeight;
                a.usableHeight = tmp;
            }
        }
        if (a.current != null && a.current.player)
            a.current =
                    a.back.isEmpty()
                            ? new MainActivity.Screen(() -> a.detail(site, video))
                            : a.back.pop();
        if (a.current != null) {
            a.detail(site, video, video);
            a.restore(a.current);
        }
    }

    static void leavePlayback(MainActivity a) {
        a.playerLocked = false;
        if (a.hideLockButton != null) {
            a.ui.removeCallbacks(a.hideLockButton);
            a.hideLockButton = null;
        }
        if (!a.isTvDevice()) {
            a.applyMobileSystemBars(false);
            a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
            if (a.usableWidth > a.usableHeight && a.usableHeight > 0) {
                int tmp = a.usableWidth;
                a.usableWidth = a.usableHeight;
                a.usableHeight = tmp;
            }
        }
        if (!a.back.isEmpty()) {
            releasePlayer(a);
            a.current = a.back.pop();
            a.renderReturn(a.current);
            a.restore(a.current);
        } else a.onBackPressed();
    }

    static void minimizePlayback(MainActivity a) {
        if (a.playbackControls == null || a.miniMode) return;
        if (a.playerLocked) {
            setPlayerLocked(a, false);
        }
        a.miniMode = true;
        showControls(a, false);
        if (a.playerChrome != null) a.playerChrome.syncClockViews();
        a.playbackControls.subtitle.setVisibility(View.GONE);
        a.playbackControls.danmaku.setVisibility(View.GONE);
        a.miniDetails = new MiniDetails(a);
    }

    static void expandPlayback(MainActivity a) {
        if (!a.miniMode) return;
        a.miniMode = false;
        if (a.miniDetails != null) {
            a.miniDetails.remove();
            a.miniDetails = null;
        }
        UiCompat.setClipToOutline(a.playbackLayer, false);
        a.playbackLayer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        a.playbackControls.subtitle.setVisibility(View.VISIBLE);
        a.playbackControls.danmaku.setVisibility(View.VISIBLE);
        if (a.playerChrome != null) a.playerChrome.syncClockViews();
        a.playbackLayer.post(
                () -> {
                    if (a.playbackControls != null) {
                        a.playbackControls.resize(
                                a.playbackControls.videoWidth, a.playbackControls.videoHeight);
                        showControls(a, true);
                        a.playbackControls.timeline.requestFocus();
                    }
                });
    }

    static void playerPage(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep) {
        ep.url = LocalProxy.rewrite(ep.url);
        try {
            String dm = ep.media.optString("danmaku");
            if (dm != null && !dm.isEmpty()) {
                ep.media.put("danmaku", LocalProxy.rewrite(dm));
            } else {
                String recentDm = CspEngine.consumeRecentPluginDanmaku(25000L);
                if (!recentDm.isEmpty()) {
                    ep.media.put("danmaku", LocalProxy.rewrite(recentDm));
                }
            }
            JSONArray subtitles = ep.media.optJSONArray("subtitles");
            if (subtitles != null)
                for (int i = 0; i < subtitles.length(); i++) {
                    JSONObject sub = subtitles.optJSONObject(i);
                    if (sub != null) sub.put("url", LocalProxy.rewrite(sub.optString("url")));
                }
        } catch (Exception ignored) {
        }
        final boolean keepLocked =
                !a.isTvDevice()
                        && a.current != null
                        && a.current.player
                        && !a.miniMode
                        && a.playerLocked;
        JSONObject embySession = ep.media.optJSONObject("embySession");
        com.pilipala.emby.EmbyLibrary.Reporter retained = a.embyReporter;
        if (retained != null && retained.matches(embySession)) a.embyReporter = null;
        else retained = null;
        releasePlayer(a);
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager)
                            a.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am != null
                    && ((android.os.Build.VERSION.SDK_INT >= 19 && am.isLowRamDevice())
                            || am.getMemoryClass() <= 128)) {
                a.images.evictAll();
                ImmersivePoster.trimMemory(
                        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
            }
        } catch (Throwable ignored) {
        }
        a.embyReporter = retained;
        a.playingBookmark = null;
        if (s != null
                && s.api != null
                && s.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)
                && embySession != null) {
            try {
                if (a.embyReporter == null)
                    a.embyReporter =
                            com.pilipala.emby.EmbyAccounts.open(a, s.api).track(embySession);
            } catch (Exception ignored) {
                // Check-in setup must not prevent an already resolved video from playing.
            }
        }
        String bookmarkId =
                v != null && v.id != null && !v.id.trim().isEmpty()
                        ? v.id.trim()
                        : (a.activeEpisode != null
                                        && a.activeEpisode.url != null
                                        && !a.activeEpisode.url.trim().isEmpty()
                                ? a.activeEpisode.url.trim()
                                : (v != null && v.title != null ? v.title.trim() : ""));
        if (v != null && !bookmarkId.isEmpty() && (v.id == null || v.id.trim().isEmpty())) {
            v.id = bookmarkId;
        }
        if (s.key != null
                && !s.key.equals("live")
                && !bookmarkId.isEmpty()
                && a.activeLine != null
                && a.activeEpisode != null) {
            WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, a.activeLine, a.activeEpisode);
            int initialResume =
                    a.resumeAt > 0
                            ? a.resumeAt
                            : (a.resumeAt < 0 ? 0 : (saved != null ? saved.resume() : 0));
            a.playingBookmark = new WatchHistory.Entry();
            a.playingBookmark.site = s.key;
            a.playingBookmark.id = bookmarkId;
            a.playingBookmark.title = v.title;
            a.playingBookmark.poster = v.poster;
            a.playingBookmark.line = a.activeLine.name;
            a.playingBookmark.episode = a.activeEpisode.name;
            a.playingBookmark.index = a.activeLine.episodes.indexOf(a.activeEpisode);
            a.playingBookmark.position = Math.max(0, initialResume);
            a.playingBookmark.duration = saved != null ? Math.max(0, saved.duration) : 0;
            a.playingBookmark.updated = System.currentTimeMillis();
            a.lastBookmarkSave = a.playingBookmark.updated;
            final WatchHistory.Entry snapshot = cloneBookmark(a.playingBookmark);
            if (a.prefs != null && snapshot != null) {
                WatchHistory.enqueueUpsert(a.historyPersistence(), snapshot);
            }
            CloudSyncDialog.scheduleAutoPush(a, false);
        }
        a.generation++;
        final int ticket = a.generation;
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!a.isTvDevice()) {
            a.applyMobileSystemBars(true);
            if (!keepLocked) {
                a.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else if (android.os.Build.VERSION.SDK_INT >= 18) {
                a.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            }
        }
        a.root = new FrameLayout(a);
        // Disable Android API 26+ defaultFocusHighlight and attach an explicit state_focused
        // pure-black background so a.root.requestFocus() when hiding TV controls never paints a
        // full-screen translucent gray highlight over letterbox bars or the video SurfaceView.
        UiCompat.setBackground(a.root, UiCompat.createFocusSafeColorBackground(Color.BLACK));
        UiCompat.setDefaultFocusHighlightEnabled(a.root, false);
        a.root.setFocusable(true);
        a.root.setFocusableInTouchMode(true);
        a.root.setTag("player-surface");
        a.scroll = null;
        boolean softwareVideoActive =
                a.prefs.getInt(Prefs.DECODER_MODE, 0) == 2
                        || (ep.media.optBoolean("_softwareFallback")
                                && ep.media.optInt("_fallbackMode", 2) == 2);
        boolean useCompatibleOutput =
                a.prefs.getInt(Prefs.VIDEO_OUTPUT, 0) == 1 || softwareVideoActive;
        VideoOutput surface = new VideoOutput(a, useCompatibleOutput);
        VideoOutput.setWindowHdrMode(a, false);
        a.playbackLayer = new FrameLayout(a);
        UiCompat.setDefaultFocusHighlightEnabled(a.playbackLayer, false);
        a.playbackLayer.setTag("playback-layer");
        a.root.addView(a.playbackLayer, new FrameLayout.LayoutParams(-1, -1));
        a.playbackLayer.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        a.playerChrome = new PlayerChrome(a, v, ep, "live".equals(s.key));
        if (!a.isTvDevice()) {
            final PlayerChrome chrome = a.playerChrome;
            a.root.setOnApplyWindowInsetsListener(
                    (view, insets) -> {
                        int left = insets.getSystemWindowInsetLeft();
                        int top = insets.getSystemWindowInsetTop();
                        int right = insets.getSystemWindowInsetRight();
                        int bottom = insets.getSystemWindowInsetBottom();
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            android.graphics.Insets safe =
                                    insets.getInsets(
                                            android.view.WindowInsets.Type.systemBars()
                                                    | android.view.WindowInsets.Type.displayCutout()
                                                    | android.view.WindowInsets.Type
                                                            .systemGestures());
                            left = safe.left;
                            top = safe.top;
                            right = safe.right;
                            android.graphics.Insets navigation =
                                    insets.getInsetsIgnoringVisibility(
                                            android.view.WindowInsets.Type.navigationBars());
                            left = Math.max(left, navigation.left);
                            right = Math.max(right, navigation.right);
                            bottom = Math.max(safe.bottom, navigation.bottom);
                        } else if (android.os.Build.VERSION.SDK_INT >= 28
                                && insets.getDisplayCutout() != null) {
                            android.view.DisplayCutout cutout = insets.getDisplayCutout();
                            left = Math.max(left, cutout.getSafeInsetLeft());
                            top = Math.max(top, cutout.getSafeInsetTop());
                            right = Math.max(right, cutout.getSafeInsetRight());
                            bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                        }
                        chrome.safeInsets(left, top, right, bottom);
                        return insets;
                    });
            a.root.requestApplyInsets();
        }
        a.playbackControls =
                new PlaybackControls(
                        a, a.playerChrome.dock, a.playerChrome.progress, surface, s, v, ep);
        TouchPlayerGesture.attach(a, surface);
        final PlaybackControls controls = a.playbackControls;
        a.root.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> {
                    int w = r - l, h = b - t;
                    if (w > 0 && h > 0) {
                        if (w != a.usableWidth || h != a.usableHeight) {
                            a.usableWidth = w;
                            a.usableHeight = h;
                            view.requestApplyInsets();
                            view.post(
                                    () -> {
                                        if (ticket != a.generation || a.isFinishing()) return;
                                        if (a.playerChrome != null) a.playerChrome.relayout();
                                        if (a.playbackControls != null) {
                                            a.playbackControls.resize(
                                                    a.playbackControls.videoWidth,
                                                    a.playbackControls.videoHeight);
                                        }
                                    });
                        }
                    }
                });
        a.setContentView(a.root);
        if (!a.isTvDevice()) a.root.requestApplyInsets();
        final boolean useSystemBackend =
                ep.media.optBoolean("_systemFallback")
                        || a.prefs.getInt(Prefs.PLAYER_ENGINE, 0) == 1;
        final Surface[] activeSurface = new Surface[1];
        final boolean[] initDispatched = new boolean[1];
        final Runnable launchPlayerInit =
                () -> {
                    if (initDispatched[0] || ticket != a.generation) return;
                    initDispatched[0] = true;
                    PLAYER_INIT.execute(
                            () -> {
                                MoviePlayer candidate = null;
                                try {
                                    Map<String, String> mediaHeaders =
                                            Cms.inheritedHeaders(s.api, ep.url, s.headers);
                                    Cms.mergeHeaders(mediaHeaders, ep.headers);
                                    int engPref = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
                                    JSONObject drmObj = ep.media.optJSONObject("drm");
                                    String drmScheme =
                                            drmObj == null
                                                    ? ep.media.optString("drmScheme", "")
                                                    : drmObj.optString(
                                                            "scheme", drmObj.optString("type", ""));
                                    boolean useMpv =
                                            !ep.media.optBoolean("_systemFallback")
                                                    && !ep.media.optBoolean("_softwareFallback")
                                                    && !ep.media.optBoolean("_mpvFallback")
                                                    && drmScheme.isEmpty()
                                                    && (engPref == 3
                                                            || ep.media.optBoolean(
                                                                    "_forceMpv", false))
                                                    && MpvPlayer.isAvailable();
                                    boolean preferIjk =
                                            engPref == 2
                                                    || (engPref == 0
                                                            && (ep.media.optBoolean("live")
                                                                    || ep.media.optInt(
                                                                                    "playerType", 0)
                                                                            == 2));
                                    if (ep.media.optBoolean("_systemFallback") || engPref == 1) {
                                        candidate = new SystemPlayer(a);
                                    } else if (useMpv) {
                                        try {
                                            candidate = new MpvPlayer(a);
                                        } catch (Throwable mpvInitErr) {
                                            AppLog.w(
                                                    "PlaybackController",
                                                    "MPV init failed, falling back to Media3",
                                                    mpvInitErr);
                                            candidate = new MoviePlayer(a);
                                        }
                                    } else if (ep.media.optBoolean("_softwareFallback")) {
                                        candidate =
                                                new MoviePlayer(
                                                        a,
                                                        ep.media.optInt("_fallbackMode", 2),
                                                        preferIjk);
                                    } else if (preferIjk) {
                                        candidate = MoviePlayer.createIjk(a);
                                    } else {
                                        candidate = new MoviePlayer(a);
                                    }
                                    // Evidence for device tests: which kernel actually plays. The
                                    // netdisk quality guard only sees Media3 drop reports, so a
                                    // "mpv" line here explains why no downgrade happened.
                                    AppLog.w("PlayKernel", kernelName(candidate));
                                    String rawPlayUrl =
                                            Cms.isHttp(ep.url) ? Cms.normalizeHttp(ep.url) : ep.url;
                                    String playUrl = rawPlayUrl;
                                    boolean hlsProxyRoute = false;
                                    boolean isLiveStream = ep.media.optBoolean("live");
                                    String declaredMime = MediaOptions.mime(ep.media);
                                    boolean hlsLike =
                                            !isLiveStream
                                                    && HlsStreamProxy.looksLikeHls(
                                                            rawPlayUrl, declaredMime);
                                    // Native netdisk episodes (QuarkShareResolver and the 网盘 page)
                                    // need the saved Cookie on the playlist and on every segment;
                                    // quark answers 412 without it. The player already forwards the
                                    // episode headers on each request (MoviePlayer's
                                    // OkHttpDataSource default request properties, MpvPlayer's
                                    // http-header-fields), so keep those episodes on the direct URL
                                    // and skip the loopback HLS proxy. Ordinary lines and every
                                    // other
                                    // HLS keep the proxy below.
                                    boolean netdiskHeaders =
                                            ep.media.optBoolean("_noCache", false)
                                                    || ep.media.optBoolean("_forceMpv", false)
                                                    || ep.media.optBoolean("_netdiskHls", false);
                                    if (hlsLike && netdiskHeaders) {
                                        AppLog.w(
                                                "HlsRoute",
                                                "direct engine="
                                                        + kernelName(candidate)
                                                        + " headers="
                                                        + mediaHeaders.size()
                                                        + " cookie="
                                                        + (hasCookieHeader(mediaHeaders)
                                                                ? "yes"
                                                                : "no"));
                                    } else if (hlsLike
                                            && ep.media.optBoolean("_hlsDirectFallback", false)) {
                                        // The loopback proxy already failed once for this episode
                                        // (see the proxy->direct retry in the error listener), so
                                        // stay on the original playlist URL. The player sends the
                                        // episode headers on every request either way.
                                        AppLog.w(
                                                "HlsRoute",
                                                "direct (fallback) engine="
                                                        + kernelName(candidate)
                                                        + " headers="
                                                        + mediaHeaders.size()
                                                        + " cookie="
                                                        + (hasCookieHeader(mediaHeaders)
                                                                ? "yes"
                                                                : "no"));
                                    } else if (hlsLike
                                            && a.prefs.getBoolean(Prefs.HLS_PROXY_ENABLED, true)) {
                                        try {
                                            SourceController.ensureProxy(a, a.config);
                                            playUrl =
                                                    HlsStreamProxy.register(
                                                            rawPlayUrl,
                                                            mediaHeaders,
                                                            a.prefs.getBoolean(
                                                                    Prefs.HLS_AD_STRIP, false));
                                            hlsProxyRoute = true;
                                            AppLog.w(
                                                    "HlsRoute",
                                                    "proxy headers="
                                                            + mediaHeaders.size()
                                                            + " cookie="
                                                            + (hasCookieHeader(mediaHeaders)
                                                                    ? "yes"
                                                                    : "no"));
                                        } catch (Exception proxyErr) {
                                            AppLog.w(
                                                    "PlaybackController",
                                                    "HLS proxy fallback to direct URL",
                                                    proxyErr);
                                        }
                                    } else if (netdiskHeaders
                && !isLiveStream
                && a.prefs.getBoolean(Prefs.NETDISK_RANGE_PROXY, false)) {
                                        // Signed netdisk direct links (Quark/UC/Baidu/Ali 4K
                                        // remuxes) are read over one upstream connection, which
                                        // stutters on this box. Wrap them in the loopback
                                        // multi-connection Range proxy; every failure keeps
                                        // playUrl on the original direct URL.
                                        try {
                                            SourceController.ensureProxy(a, a.config);
                                            String wrapped =
                                                    NetdiskRangeProxy.wrap(ep, mediaHeaders);
                                            if (wrapped != null) {
                                                playUrl = wrapped;
                                                AppLog.w(
                                                        "RangeProxy",
                                                        "route headers="
                                                                + mediaHeaders.size()
                                                                + " cookie="
                                                                + (hasCookieHeader(mediaHeaders)
                                                                        ? "yes"
                                                                        : "no"));
                                            } else {
                                                AppLog.w(
                                                        "RangeProxy",
                                                        "route skipped, keep direct URL");
                                            }
                                        } catch (Exception rangeErr) {
                                            AppLog.w(
                                                    "RangeProxy",
                                                    "proxy unavailable, fallback to direct URL",
                                                    rangeErr);
                                        }
                                    } else if (!isLiveStream) {
                                        int cacheMb = a.prefs.getInt(Prefs.MEDIA_CACHE_MB, 256);
                                        // Cloud streams resolved by the native netdisk layer opt
                                        // out:
                                        // their signed URLs are short-lived and the cache proxy's
                                        // chunked ranges are refused upstream.
                                        if (cacheMb > 0
                                                && !ep.media.optBoolean("_noCache", false)
                                                && MediaDiskCache.isCacheableDirectUrl(
                                                        rawPlayUrl, declaredMime)) {
                                            try {
                                                SourceController.ensureProxy(a, a.config);
                                                playUrl =
                                                        MediaDiskCache.getInstance(
                                                                        a.getCacheDir(), cacheMb)
                                                                .register(rawPlayUrl, mediaHeaders);
                                            } catch (Exception cacheErr) {
                                                AppLog.w(
                                                        "PlaybackController",
                                                        "Disk cache proxy fallback to direct URL",
                                                        cacheErr);
                                            }
                                        }
                                    }
                                    final boolean usedHlsProxy = hlsProxyRoute;
                                    candidate.configureDefaultAudio();
                                    candidate.setDataSource(a, Uri.parse(playUrl), mediaHeaders);
                                    try {
                                        if (ep.name != null
                                                && !ep.name.isEmpty()
                                                && ep.media
                                                        .optString("_episodeName", "")
                                                        .isEmpty()) {
                                            ep.media.put("_episodeName", ep.name);
                                        }
                                        if (a.activeLine != null
                                                && a.activeLine.name != null
                                                && !a.activeLine.name.isEmpty()
                                                && ep.media.optString("_lineName", "").isEmpty()) {
                                            ep.media.put("_lineName", a.activeLine.name);
                                        }
                                    } catch (Exception ignored) {
                                    }
                                    candidate.setOptions(ep.media);
                                    final MoviePlayer ready = candidate;
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing()) {
                                                    ready.release();
                                                    return;
                                                }
                                                if (useSystemBackend
                                                        && (activeSurface[0] == null
                                                                || !activeSurface[0].isValid())) {
                                                    initDispatched[0] = false;
                                                    ready.release();
                                                    return;
                                                }
                                                a.player = ready;
                                                surface.setExternalNativeRenderer(
                                                        ready instanceof MpvPlayer);
                                                if (activeSurface[0] != null
                                                        && activeSurface[0].isValid()) {
                                                    ready.setSurface(activeSurface[0]);
                                                    if (surface.getWidth() > 0
                                                            && surface.getHeight() > 0) {
                                                        ready.setSurfaceSize(
                                                                surface.getWidth(),
                                                                surface.getHeight());
                                                    }
                                                }
                                                ready.bindSubtitles(
                                                        controls.subtitle,
                                                        text -> {
                                                            if (ticket == a.generation) {
                                                                controls.lastSubtitle =
                                                                        String.valueOf(text);
                                                            }
                                                        });
                                                ready.setOnTextTracksChangedListener(
                                                        () -> {
                                                            if (ticket == a.generation
                                                                    && a.playbackControls
                                                                            == controls) {
                                                                controls
                                                                        .onPlayerTextTracksChanged();
                                                            }
                                                        });
                                                ready.setOnAudioTracksChangedListener(
                                                        () -> {
                                                            if (ticket == a.generation
                                                                    && a.playbackControls
                                                                            == controls) {
                                                                controls
                                                                        .onPlayerAudioTracksChanged();
                                                            }
                                                        });
                                                ready.setOnHdrChangedListener(
                                                        hdr -> {
                                                            if (ticket != a.generation
                                                                    || a.isFinishing()) return;
                                                            boolean hdrSurface =
                                                                    hdr
                                                                            && surface
                                                                                    .usesSurfaceView();
                                                            surface.setHdrContent(hdr);
                                                            VideoOutput.setWindowHdrMode(
                                                                    a, hdrSurface);
                                                            if (a.playerChrome != null) {
                                                                a.playerChrome.setHdrSurfaceActive(
                                                                        hdrSurface);
                                                            }
                                                        });
                                                ready.setOnBufferingListener(
                                                        (buf, pct) -> {
                                                            if (ticket != a.generation
                                                                    || a.isFinishing()) return;
                                                            if (a.playerChrome != null) {
                                                                a.playerChrome.setBuffering(
                                                                        buf, pct);
                                                            }
                                                        });
                                                ready.setOnPreparedListener(
                                                        mp -> {
                                                            if (ticket != a.generation) return;
                                                            a.prepared = true;
                                                            onLinePrepared(a, s);
                                                            a.historyFallbackInFlight = false;
                                                            a.historyFallbackBookmark = null;
                                                            if (a.migratingHistoryOldKey != null) {
                                                                String oldKey =
                                                                        a.migratingHistoryOldKey;
                                                                a.migratingHistoryOldKey = null;
                                                                if (a.playingBookmark == null
                                                                        || !oldKey.equals(
                                                                                a.playingBookmark
                                                                                        .key())) {
                                                                    a.removeWatchHistory(
                                                                            oldKey, null);
                                                                }
                                                            }
                                                            boolean hdr = mp.isHdrVideo();
                                                            boolean hdrSurface =
                                                                    hdr
                                                                            && surface
                                                                                    .usesSurfaceView();
                                                            surface.setHdrContent(hdr);
                                                            VideoOutput.setWindowHdrMode(
                                                                    a, hdrSurface);
                                                            if (a.playerChrome != null) {
                                                                a.playerChrome.setHdrSurfaceActive(
                                                                        hdrSurface);
                                                                a.playerChrome.setLoading(false);
                                                            }
                                                            a.playing = a.foreground;
                                                            if (a.foreground) mp.start();
                                                            else mp.pause();
                                                            controls.prepared();
                                                            if (!"live".equals(s.key)
                                                                    && a.foreground
                                                                    && !ep.media.optBoolean(
                                                                            "_dvColorWarned")) {
                                                                String dvEpName =
                                                                        ep.media.optString(
                                                                                "_episodeName",
                                                                                ep.name != null
                                                                                        ? ep.name
                                                                                        : "");
                                                                String dvLineName =
                                                                        ep.media.optString(
                                                                                "_lineName",
                                                                                a.activeLine != null
                                                                                                && a.activeLine
                                                                                                                .name
                                                                                                        != null
                                                                                        ? a.activeLine
                                                                                                .name
                                                                                        : "");
                                                                if (MoviePlayer
                                                                        .looksLikeTranscodedDolbyVisionColorRisk(
                                                                                dvEpName,
                                                                                dvLineName,
                                                                                mp
                                                                                        .activeVideoMimeType())) {
                                                                    try {
                                                                        ep.media.put(
                                                                                "_dvColorWarned",
                                                                                true);
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                    a.toast(
                                                                            "纯杜比视界片源经转码易偏色，建议选 HDR10 / SDR / DV+HDR 片源");
                                                                }
                                                            }
                                                            savePlaybackHistoryOnPrepared(a);
                                                            a.playStatus.setText(
                                                                    a.foreground
                                                                            ? ("live".equals(s.key)
                                                                                    ? (a
                                                                                                    .isTvDevice()
                                                                                            ? "正在直播 · 上下换台 · 菜单键工具条"
                                                                                            : "正在直播 · 单击显示选台工具栏")
                                                                                    : (a
                                                                                                    .isTvDevice()
                                                                                            ? "正在播放 · 确认暂停 · 上下键显示工具条"
                                                                                            : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量"))
                                                                            : (a.isTvDevice()
                                                                                    ? "已暂停 · 按确认继续"
                                                                                    : "已暂停 · 双击继续"));
                                                            clock(a, ticket);
                                                            if (a.changingMiniEpisode) {
                                                                a.changingMiniEpisode = false;
                                                                minimizePlayback(a);
                                                            } else if (a.foreground) {
                                                                if (a.playerLocked) {
                                                                    showControls(a, false);
                                                                } else if ("live".equals(s.key)) {
                                                                    showControls(a, false);
                                                                    a.toast(
                                                                            "频道 "
                                                                                    + (a.liveSession
                                                                                                    == null
                                                                                            ? ""
                                                                                            : (a.liveSession
                                                                                                                    .index
                                                                                                            + 1)
                                                                                                    + " · ")
                                                                                    + v.title);
                                                                } else scheduleHide(a);
                                                            }
                                                        });
                                                ready.setOnVideoSizeChangedListener(
                                                        (mp, w, h) -> {
                                                            if (ticket == a.generation) {
                                                                if (w > 0
                                                                        && h > 0
                                                                        && mp.isHdrVideo()) {
                                                                    boolean hdrSurface =
                                                                            surface
                                                                                    .usesSurfaceView();
                                                                    surface.setHdrContent(true);
                                                                    VideoOutput.setWindowHdrMode(
                                                                            a, hdrSurface);
                                                                    if (a.playerChrome != null) {
                                                                        a.playerChrome
                                                                                .setHdrSurfaceActive(
                                                                                        hdrSurface);
                                                                    }
                                                                }
                                                                controls.resize(w, h);
                                                                if ("live".equals(s.key)
                                                                        && w > 0
                                                                        && h > 0
                                                                        && a.prefs != null) {
                                                                    String updated =
                                                                            LiveQualityPolicy
                                                                                    .recordStreamSuccess(
                                                                                            a.prefs
                                                                                                    .getString(
                                                                                                            Prefs
                                                                                                                    .LIVE_STREAM_METRICS,
                                                                                                            ""),
                                                                                            ep.url,
                                                                                            w,
                                                                                            h,
                                                                                            (int)
                                                                                                    mp
                                                                                                            .getFirstFrameMs(),
                                                                                            System
                                                                                                    .currentTimeMillis());
                                                                    a.prefs
                                                                            .edit()
                                                                            .putString(
                                                                                    Prefs
                                                                                            .LIVE_STREAM_METRICS,
                                                                                    updated)
                                                                            .apply();
                                                                }
                                                            }
                                                        });
                                                ready.setOnCompletionListener(
                                                        mp -> {
                                                            if (ticket != a.generation
                                                                    || a.player != ready) return;
                                                            if (a.markerHost != null)
                                                                a.markerHost.abort();
                                                            int curPos = ready.getCurrentPosition();
                                                            int curDur = ready.getDuration();
                                                            if (curDur <= 0
                                                                    && a.playingBookmark != null
                                                                    && a.playingBookmark.duration
                                                                            > 0) {
                                                                curDur = a.playingBookmark.duration;
                                                            }
                                                            int eofRetries =
                                                                    Math.max(
                                                                            ep.media.optInt(
                                                                                    "_eofReconnectCount",
                                                                                    0),
                                                                            a.activeEpisode != null
                                                                                            && a.activeEpisode
                                                                                                            .media
                                                                                                    != null
                                                                                    ? a
                                                                                            .activeEpisode
                                                                                            .media
                                                                                            .optInt(
                                                                                                    "_eofReconnectCount",
                                                                                                    0)
                                                                                    : 0);
                                                            if (MoviePlayer
                                                                            .isPrematureProgressiveEnd(
                                                                                    "live"
                                                                                            .equals(
                                                                                                    s.key),
                                                                                    curPos,
                                                                                    curDur)
                                                                    && eofRetries < 3
                                                                    && a.activeLine != null
                                                                    && a.activeEpisode != null) {
                                                                try {
                                                                    ep.media.put(
                                                                            "_eofReconnectCount",
                                                                            eofRetries + 1);
                                                                    if (a.activeEpisode.media
                                                                            != null) {
                                                                        a.activeEpisode.media.put(
                                                                                "_eofReconnectCount",
                                                                                eofRetries + 1);
                                                                    }
                                                                } catch (Exception ignored) {
                                                                }
                                                                a.resumeAt = curPos;
                                                                a.toast("网络波动，正在断点续连…");
                                                                a.playStatus.setText(
                                                                        "网络中断，正在断点续连…");
                                                                final Cms.Line retryLine =
                                                                        a.activeLine;
                                                                final Cms.Episode retryEp =
                                                                        a.activeEpisode;
                                                                a.ui.postDelayed(
                                                                        () -> {
                                                                            if (ticket
                                                                                            == a.generation
                                                                                    && !a
                                                                                            .isFinishing()) {
                                                                                resolvePlayer(
                                                                                        a, s, v,
                                                                                        retryLine,
                                                                                        retryEp);
                                                                            }
                                                                        },
                                                                        450L);
                                                                return;
                                                            }
                                                            if (a.playingBookmark != null) {
                                                                if (curDur > 0) {
                                                                    a.playingBookmark.duration =
                                                                            curDur;
                                                                    a.playingBookmark.position =
                                                                            Math.max(
                                                                                    a.playingBookmark
                                                                                            .position,
                                                                                    curDur);
                                                                } else if (a.playingBookmark
                                                                                .position
                                                                        <= 0) {
                                                                    a.playingBookmark.position =
                                                                            1000;
                                                                }
                                                            }
                                                            savePlaybackHistory(a, true);
                                                            a.playing = false;
                                                            stopEmbySession(a);
                                                            a.playStatus.setText("播放结束");
                                                            AppLog.w(
                                                                    "TransNext",
                                                                    "ended: activeEp="
                                                                            + (a.activeEpisode == null
                                                                                    ? "-"
                                                                                    : a.activeEpisode.name)
                                                                            + " origin="
                                                                            + (a.activeEpisode == null
                                                                                            || a.activeEpisode
                                                                                                            .origin
                                                                                                    == null
                                                                                                    ? "-"
                                                                                                    : a.activeEpisode
                                                                                                            .origin
                                                                                                            .name)
                                                                            + " autoNext="
                                                                            + a.prefs.getBoolean(
                                                                                    Prefs.AUTO_NEXT,
                                                                                    true));
                                                            controls.completed();
                                                        });
                                                ready.setOnErrorListener(
                                                        (mp, what, extra) -> {
                                                            if (ticket != a.generation
                                                                    || a.player != ready)
                                                                return true;
                                                            if (a.markerHost != null)
                                                                a.markerHost.abort();
                                                            int currentPos =
                                                                    mp.getCurrentPosition();
                                                            if (!"live".equals(s.key)
                                                                    && what >= 2000
                                                                    && what < 3000
                                                                    && currentPos > 0
                                                                    && a.activeLine != null
                                                                    && a.activeEpisode != null) {
                                                                int ioRetries =
                                                                        Math.max(
                                                                                ep.media.optInt(
                                                                                        "_ioReconnectCount",
                                                                                        0),
                                                                                a.activeEpisode
                                                                                                        .media
                                                                                                != null
                                                                                        ? a
                                                                                                .activeEpisode
                                                                                                .media
                                                                                                .optInt(
                                                                                                        "_ioReconnectCount",
                                                                                                        0)
                                                                                        : 0);
                                                                if (ioRetries < 2) {
                                                                    try {
                                                                        ep.media.put(
                                                                                "_ioReconnectCount",
                                                                                ioRetries + 1);
                                                                        if (a.activeEpisode.media
                                                                                != null) {
                                                                            a.activeEpisode.media
                                                                                    .put(
                                                                                            "_ioReconnectCount",
                                                                                            ioRetries
                                                                                                    + 1);
                                                                        }
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                    a.resumeAt = currentPos;
                                                                    a.toast("连接波动，正在刷新播放链接并续播…");
                                                                    a.playStatus.setText(
                                                                            "连接波动，正在刷新播放链接…");
                                                                    final Cms.Line retryLine =
                                                                            a.activeLine;
                                                                    final Cms.Episode retryEp =
                                                                            a.activeEpisode;
                                                                    a.ui.postDelayed(
                                                                            () -> {
                                                                                if (ticket
                                                                                                == a.generation
                                                                                        && !a
                                                                                                .isFinishing()) {
                                                                                    resolvePlayer(
                                                                                            a, s, v,
                                                                                            retryLine,
                                                                                            retryEp);
                                                                                }
                                                                            },
                                                                            500L);
                                                                    return true;
                                                                }
                                                            }
                                                            // Proxy port taken: retry direct.
                                                            if (usedHlsProxy
                                                                    && !ep.media.optBoolean(
                                                                            "_hlsDirectFallback",
                                                                            false)
                                                                    && isHlsProxyFetchFailure(
                                                                            what,
                                                                            mp
                                                                                    .getLastHttpStatus())) {
                                                                try {
                                                                    ep.media.put(
                                                                            "_hlsDirectFallback",
                                                                            true);
                                                                    if (a.activeEpisode != null
                                                                            && a.activeEpisode.media
                                                                                    != null) {
                                                                        a.activeEpisode.media.put(
                                                                                "_hlsDirectFallback",
                                                                                true);
                                                                    }
                                                                } catch (Exception ignored) {
                                                                }
                                                                int httpStatus =
                                                                        mp.getLastHttpStatus();
                                                                AppLog.w(
                                                                        "HlsFallback",
                                                                        "proxy->direct "
                                                                                + (httpStatus >= 400
                                                                                        ? "http="
                                                                                                + httpStatus
                                                                                        : "io="
                                                                                                + what));
                                                                // Leave the resume intent as is.
                                                                if (currentPos > 0)
                                                                    a.resumeAt = currentPos;
                                                                a.playStatus.setText(
                                                                        "本地代理取流失败，正在改直连重试…");
                                                                a.ui.post(
                                                                        () -> {
                                                                            if (ticket
                                                                                    == a.generation)
                                                                                playerPage(
                                                                                        a, s, v,
                                                                                        ep);
                                                                        });
                                                                return true;
                                                            }
                                                            if (mp instanceof MpvPlayer
                                                                    && !ep.media.optBoolean(
                                                                            "_mpvFallback")) {
                                                                try {
                                                                    ep.media.put(
                                                                            "_mpvFallback", true);
                                                                } catch (Exception ignored) {
                                                                }
                                                                a.resumeAt =
                                                                        mp.getCurrentPosition();
                                                                a.playStatus.setText(
                                                                        "MPV 播放异常，正在切换 Media3 内核…");
                                                                a.ui.post(
                                                                        () -> {
                                                                            if (ticket
                                                                                    == a.generation)
                                                                                playerPage(
                                                                                        a, s, v,
                                                                                        ep);
                                                                        });
                                                                return true;
                                                            }
                                                            if (PlaybackFailure.decoderFallback(
                                                                            what)
                                                                    && a.prefs.getInt(
                                                                                    Prefs
                                                                                            .DECODER_MODE,
                                                                                    0)
                                                                            == 0) {
                                                                if (currentPos >= 5000
                                                                        && !ep.media.optBoolean(
                                                                                "_hwRetry")
                                                                        && !ep.media.optBoolean(
                                                                                "_softwareFallback")
                                                                        && !ep.media.optBoolean(
                                                                                "_systemFallback")
                                                                        && !mp
                                                                                .isUnsupportedDolbyVisionProfile5()) {
                                                                    try {
                                                                        ep.media.put(
                                                                                "_hwRetry", true);
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                    a.resumeAt = currentPos;
                                                                    a.playStatus.setText(
                                                                            "解码器波动，正在重新初始化硬件解码…");
                                                                    a.ui.postDelayed(
                                                                            () -> {
                                                                                if (ticket
                                                                                                == a.generation
                                                                                        && !a
                                                                                                .isFinishing()) {
                                                                                    playerPage(
                                                                                            a, s, v,
                                                                                            ep);
                                                                                }
                                                                            },
                                                                            250L);
                                                                    return true;
                                                                }
                                                                if (!ep.media.optBoolean(
                                                                                "_softwareFallback")
                                                                        && !mp
                                                                                .isUnsupportedDolbyVisionProfile5()) {
                                                                    try {
                                                                        ep.media.put(
                                                                                "_softwareFallback",
                                                                                true);
                                                                        ep.media.put(
                                                                                "_fallbackMode",
                                                                                mp
                                                                                        .softFallbackMode());
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                    a.resumeAt =
                                                                            mp.getCurrentPosition();
                                                                    a.playStatus.setText(
                                                                            "正在尝试内置软解码…");
                                                                    a.ui.post(
                                                                            () -> {
                                                                                if (ticket
                                                                                        == a.generation)
                                                                                    playerPage(
                                                                                            a, s, v,
                                                                                            ep);
                                                                            });
                                                                    return true;
                                                                }
                                                                if (!ep.media.optBoolean(
                                                                                "_systemFallback")
                                                                        && a.prefs.getInt(
                                                                                        Prefs
                                                                                                .PLAYER_ENGINE,
                                                                                        0)
                                                                                != 1) {
                                                                    try {
                                                                        ep.media.put(
                                                                                "_systemFallback",
                                                                                true);
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                    a.resumeAt =
                                                                            mp.getCurrentPosition();
                                                                    a.playStatus.setText(
                                                                            "正在切换至系统硬件播放器…");
                                                                    a.ui.post(
                                                                            () -> {
                                                                                if (ticket
                                                                                        == a.generation)
                                                                                    playerPage(
                                                                                            a, s, v,
                                                                                            ep);
                                                                            });
                                                                    return true;
                                                                }
                                                            }
                                                            if (tryAdvanceQualityOrLiveStream(
                                                                    a, ticket, s, v, ep)) {
                                                                return true;
                                                            }
                                                            if (v != null
                                                                    && v.lines != null
                                                                    && v.lines.size() > 1
                                                                    && a.activeLine != null) {
                                                                int curLineIdx =
                                                                        v.lines.indexOf(
                                                                                a.activeLine);
                                                                int hopCount =
                                                                        ep.media.optInt(
                                                                                "_lineHopCount", 0);
                                                                if (curLineIdx >= 0
                                                                        && hopCount
                                                                                < Math.min(
                                                                                        3,
                                                                                        v.lines
                                                                                                        .size()
                                                                                                - 1)) {
                                                                    Cms.Line nextLine =
                                                                            v.lines.get(
                                                                                    (curLineIdx + 1)
                                                                                            % v
                                                                                                    .lines
                                                                                                    .size());
                                                                    Cms.Episode nextEp =
                                                                            findMatchingEpisode(
                                                                                    nextLine,
                                                                                    a.activeLine,
                                                                                    a.activeEpisode
                                                                                                    != null
                                                                                            ? a.activeEpisode
                                                                                            : ep);
                                                                    if (nextEp != null
                                                                            && nextLine
                                                                                    != a.activeLine) {
                                                                        try {
                                                                            nextEp.media.put(
                                                                                    "_lineHopCount",
                                                                                    hopCount + 1);
                                                                        } catch (
                                                                                Exception ignored) {
                                                                        }
                                                                        // Remember this line:
                                                                        // the hop shows its own
                                                                        // toast instead of the
                                                                        // attribution text.
                                                                        if (!"live"
                                                                                .equals(s.key)) {
                                                                            LineHealth.markFailed(
                                                                                    a,
                                                                                    s,
                                                                                    a.activeLine,
                                                                                    mp
                                                                                            .getFailureAttribution(
                                                                                                    what));
                                                                        }
                                                                        a.resumeAt =
                                                                                mp
                                                                                        .getCurrentPosition();
                                                                        a.playStatus.setText(
                                                                                "当前线路异常，正在切换至 "
                                                                                        + nextLine.name
                                                                                        + "…");
                                                                        a.ui.post(
                                                                                () -> {
                                                                                    if (ticket
                                                                                            == a.generation) {
                                                                                        rememberEpisodeOnBackStack(
                                                                                                a,
                                                                                                nextLine,
                                                                                                nextEp);
                                                                                        resolvePlayer(
                                                                                                a,
                                                                                                s,
                                                                                                v,
                                                                                                nextLine,
                                                                                                nextEp);
                                                                                    }
                                                                                });
                                                                        return true;
                                                                    }
                                                                }
                                                            }
                                                            if (tryHistoryCrossSourceFailover(
                                                                    a,
                                                                    s,
                                                                    Math.max(
                                                                            0,
                                                                            mp
                                                                                    .getCurrentPosition()),
                                                                    "原片源播放失败")) {
                                                                return true;
                                                            }
                                                            stopEmbySession(a);
                                                            a.prepared = false;
                                                            a.playing = false;
                                                            if (a.playerChrome != null)
                                                                a.playerChrome.setLoading(false);
                                                            PlaybackFailure.Attribution kind =
                                                                    mp.getFailureAttribution(what);
                                                            int httpStatus = mp.getLastHttpStatus();
                                                            logAttribution(
                                                                    a, s, kind, what, httpStatus);
                                                            if (kind
                                                                    == PlaybackFailure.Attribution
                                                                            .SOURCE) {
                                                                // Which host refused, and did we
                                                                // even send the headers such a host
                                                                // usually wants? Without this, a 400
                                                                // from a CDN says nothing actionable.
                                                                AppLog.w(
                                                                        "PlaybackController",
                                                                        "source refused host="
                                                                                + hostOf(ep == null ? null : ep.url)
                                                                                + " http="
                                                                                + httpStatus
                                                                                + " referer="
                                                                                + (hasHeaderName(ep, "Referer")
                                                                                        ? "yes"
                                                                                        : "no")
                                                                                + " cookie="
                                                                                + (hasHeaderName(ep, "Cookie")
                                                                                        ? "yes"
                                                                                        : "no")
                                                                                + " ua="
                                                                                + (hasHeaderName(ep, "User-Agent")
                                                                                        ? "yes"
                                                                                        : "no")
                                                                                + " media="
                                                                                + (ep == null
                                                                                        ? "-"
                                                                                        : ep.media.length()));
                                                            }
                                                            // A netdisk direct URL is signed and short
                                                            // lived: quark answers 412 (and 403/404)
                                                            // once the token went stale between the
                                                            // resolve and the first frame. Re-resolve
                                                            // the very same episode from its share id
                                                            // before showing "片源服务器返回错误".
                                                            if (kind
                                                                            == PlaybackFailure
                                                                                    .Attribution
                                                                                    .SOURCE
                                                                    && isExpiredDirectUrl(ep, httpStatus)
                                                                    && lineFailover.freshResolves < 1) {
                                                                lineFailover.freshResolves++;
                                                                lineFailover.freshResolveRequested = true;
                                                                final Cms.Episode again = ep.origin;
                                                                a.resumeAt = 0;
                                                                a.playStatus.setText("直链已过期，正在重新解析…");
                                                                if (a.playerChrome != null) {
                                                                    a.playerChrome.setLoading(true);
                                                                }
                                                                AppLog.w(
                                                                        "PlaybackController",
                                                                        "fresh resolve after http="
                                                                                + httpStatus
                                                                                + " site="
                                                                                + (s == null ? "" : s.key));
                                                                a.ui.post(
                                                                        () -> {
                                                                            if (ticket
                                                                                    != a.generation)
                                                                                return;
                                                                            resolvePlayer(
                                                                                    a,
                                                                                    s,
                                                                                    v,
                                                                                    a.activeLine,
                                                                                    again);
                                                                        });
                                                                return true;
                                                            }
                                                            a.playStatus.setText(
                                                                    mp.getFailureMessage(what));
                                                            showControls(a, true);
                                                            return true;
                                                        });
                                                a.playStatus.setText("正在加载播放…");
                                                ready.prepareAsync();
                                                if ("live".equals(s.key)) {
                                                    JSONArray liveQs =
                                                            ep.media.optJSONArray("qualities");
                                                    int curQIdx = ep.media.optInt("_qualityIdx", 0);
                                                    if (liveQs != null
                                                            && curQIdx + 1 < liveQs.length()) {
                                                        a.ui.postDelayed(
                                                                () -> {
                                                                    if (ticket == a.generation
                                                                            && !a.prepared
                                                                            && a.current != null
                                                                            && a.current.player) {
                                                                        tryAdvanceQualityOrLiveStream(
                                                                                a, ticket, s, v,
                                                                                ep);
                                                                    }
                                                                },
                                                                7500L);
                                                    }
                                                }
                                            });
                                } catch (Exception ex) {
                                    AppLog.w("PlaybackController", "player init failed", ex);
                                    if (candidate != null) candidate.release();
                                    a.ui.post(
                                            () -> {
                                                if (ticket == a.generation) {
                                                    if (a.playerChrome != null)
                                                        a.playerChrome.setLoading(false);
                                                    // A Java Exception here means our own adapter
                                                    // failed, not the source: report it as ours and
                                                    // make clear the case was recorded.
                                                    PlaybackFailure.Attribution kind =
                                                            PlaybackFailure.Attribution.UNKNOWN;
                                                    logAttribution(a, s, kind, 0, 0);
                                                    a.playStatus.setText(
                                                            kind.text("播放器启动失败，已记录，建议重试或换一条线路"));
                                                    showControls(a, true);
                                                }
                                            });
                                }
                            });
                };
        surface.listen(
                new VideoOutput.Listener() {
                    public void available(Surface value) {
                        activeSurface[0] = value;
                        if (ticket != a.generation) return;
                        if (a.player != null) {
                            a.player.setSurface(value);
                            if (surface.getWidth() > 0 && surface.getHeight() > 0) {
                                a.player.setSurfaceSize(surface.getWidth(), surface.getHeight());
                            }
                            return;
                        }
                        launchPlayerInit.run();
                    }

                    public void resized(int width, int height) {
                        if (ticket == a.generation && a.player != null) {
                            a.player.setSurfaceSize(width, height);
                        }
                    }

                    public void destroyed() {
                        activeSurface[0] = null;
                        if (ticket == a.generation && a.player != null) a.player.setSurface(null);
                    }
                });
        if (!useSystemBackend) {
            launchPlayerInit.run();
        }
        if (keepLocked) {
            a.playerLocked = true;
            a.controlsVisible = false;
            if (a.playbackControls != null) a.playbackControls.visible(false);
            if (a.playerOverlay != null) {
                a.playerOverlay.animate().cancel();
                a.playerOverlay.setAlpha(0f);
                a.playerOverlay.setVisibility(View.GONE);
            }
            if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
            setUnlockButtonVisible(a, false, false);
            a.root.requestFocus();
        } else {
            a.playbackControls.timeline.requestFocus();
            if ("live".equals(s.key)) showControls(a, false);
        }
    }

    static boolean isPlayerLocked(MainActivity a) {
        return a != null && !a.isTvDevice() && a.playerLocked;
    }

    static void setPlayerLocked(MainActivity a, boolean locked) {
        if (a == null || a.isTvDevice() || a.playerLocked == locked) return;
        if (locked) {
            finishSeek(a, false);
            if (a.touchPlayerGesture != null) {
                a.touchPlayerGesture.cancelActiveGesture();
            }
            if (a.playbackControls != null) {
                a.playbackControls.sliderSeeking = false;
                a.playbackControls.touchSeeking = false;
                if (a.playbackControls.activeDialog != null) {
                    a.playbackControls.activeDialog.dismiss();
                    a.playbackControls.activeDialog = null;
                    a.playbackControls.dialogOpen = false;
                }
            }
            showControls(a, false);
            a.playerLocked = true;
            if (android.os.Build.VERSION.SDK_INT >= 18) {
                a.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            }
            setUnlockButtonVisible(a, true, true);
            if (a.touchPlayerGesture != null) {
                a.touchPlayerGesture.showBriefNotice("已锁定播放界面 · 防误触开启");
            }
        } else {
            a.playerLocked = false;
            a.setRequestedOrientation(
                    a.isPortrait()
                            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            setUnlockButtonVisible(a, false, false);
            showControls(a, true);
            if (a.touchPlayerGesture != null) {
                a.touchPlayerGesture.showBriefNotice("已解锁播放界面");
            }
        }
    }

    static void setUnlockButtonVisible(MainActivity a, boolean visible, boolean autoHide) {
        if (a == null) return;
        if (a.hideLockButton != null) {
            a.ui.removeCallbacks(a.hideLockButton);
        }
        if (a.playerChrome != null && a.playerChrome.unlockPill != null) {
            View pill = a.playerChrome.unlockPill;
            pill.animate().cancel();
            pill.setAlpha(1f);
            pill.setVisibility(visible && a.playerLocked ? View.VISIBLE : View.GONE);
        }
        if (visible && autoHide && a.playerLocked) {
            a.hideLockButton =
                    () -> {
                        if (a.playerLocked) {
                            setUnlockButtonVisible(a, false, false);
                        }
                    };
            a.ui.postDelayed(a.hideLockButton, 3500L);
        }
    }

    static void toggleUnlockButton(MainActivity a) {
        if (a == null || !a.playerLocked) return;
        boolean currentlyVisible =
                a.playerChrome != null
                        && a.playerChrome.unlockPill != null
                        && a.playerChrome.unlockPill.getVisibility() == View.VISIBLE;
        setUnlockButtonVisible(a, !currentlyVisible, !currentlyVisible);
    }

    static void revealLockedHint(MainActivity a) {
        if (a == null || !a.playerLocked) return;
        setUnlockButtonVisible(a, true, true);
        if (a.touchPlayerGesture != null) {
            a.touchPlayerGesture.showBriefNotice("屏幕已锁定 · 点击解锁恢复操作");
        }
    }

    static void showControls(MainActivity a, boolean visible) {
        showControls(a, visible, false);
    }

    static void showControls(MainActivity a, boolean visible, boolean progressOnly) {
        if (!visible) finishSeek(a, false);
        if (visible && a.playerChrome != null) a.playerChrome.cancelEpisodeHoldSwitch();
        if (a.miniMode && visible) return;
        if (a.playerLocked && visible) {
            setUnlockButtonVisible(a, true, true);
            return;
        }
        boolean changed = a.controlsVisible != visible;
        a.controlsVisible = visible;
        if (visible && a.playClock != null && a.player != null && a.prepared) {
            try {
                int rawPos = a.player.getCurrentPosition();
                int displayPos =
                        a.playbackControls != null
                                ? a.playbackControls.displayPosition(rawPos)
                                : rawPos;
                int dur = a.player.getDuration();
                if (dur <= 0 && a.playingBookmark != null && a.playingBookmark.duration > 0) {
                    dur = a.playingBookmark.duration;
                }
                String clockText =
                        formatTime(displayPos)
                                + " / "
                                + (dur > 0 ? formatTime(dur) : (a.isLive() ? "直播" : "--:--"));
                if (!clockText.contentEquals(a.playClock.getText())) {
                    a.playClock.setText(clockText);
                }
            } catch (Exception ignored) {
            }
        }
        if (a.playerChrome != null) {
            a.playerChrome.setProgressOnlyMode(visible && progressOnly);
        }
        if (a.playbackControls != null) a.playbackControls.visible(visible);
        if (a.playerChrome != null) a.playerChrome.update();
        if (a.playerOverlay != null) {
            a.playerOverlay.animate().cancel();
            if (visible) {
                a.playerOverlay.setAlpha(1f);
                a.playerOverlay.setTranslationY(0f);
                a.playerOverlay.setVisibility(View.VISIBLE);
                if (changed && !a.reduced && !a.isTvDevice()) {
                    a.playerOverlay.setAlpha(0f);
                    a.playerOverlay.animate().alpha(1f).setDuration(150).start();
                }
            } else {
                if (a.reduced || a.isTvDevice()) {
                    a.playerOverlay.setAlpha(0f);
                    a.playerOverlay.setVisibility(View.GONE);
                } else {
                    long hideDur = 140;
                    UiCompat.startWithEndAction(
                            a.playerOverlay.animate().alpha(0f).setDuration(hideDur),
                            a.playerOverlay,
                            hideDur,
                            () -> {
                                if (!a.controlsVisible && a.playerOverlay != null)
                                    a.playerOverlay.setVisibility(View.GONE);
                            });
                }
                a.root.requestFocus();
            }
        }
        if (visible) {
            if (changed && a.playbackControls != null && a.isTvDevice()) {
                a.playbackControls.timeline.requestFocus();
            }
            scheduleHide(a);
        } else if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
    }

    static void scheduleHide(MainActivity a) {
        if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
        a.hideControls =
                () -> {
                    if (a.prepared
                            && a.playing
                            && (a.playbackControls == null || !a.playbackControls.dialogOpen))
                        showControls(a, false);
                };
        a.ui.postDelayed(a.hideControls, 5000);
    }

    static void clock(MainActivity a, int ticket) {
        if (ticket != a.generation || a.player == null || !a.prepared) return;
        updateInMemoryBookmark(a);
        savePlaybackHistory(a, false);
        try {
            if (a.embyReporter != null)
                a.embyReporter.update(a.player.getCurrentPosition(), !a.player.isPlaying());
            if (a.seekGesture.active) previewSeek(a);
            else if (a.playbackControls == null || !a.playbackControls.isUserSeeking()) {
                int rawPos = a.player.getCurrentPosition();
                int displayPos =
                        a.playbackControls != null
                                ? a.playbackControls.displayPosition(rawPos)
                                : rawPos;
                int dur = a.player.getDuration();
                if (dur <= 0 && a.playingBookmark != null && a.playingBookmark.duration > 0) {
                    dur = a.playingBookmark.duration;
                }
                if (a.playerChrome != null) {
                    a.playerChrome.updatePlaybackHud(displayPos, dur, a.isLive());
                }
                if (a.controlsVisible && a.playClock != null) {
                    String clockText =
                            formatTime(displayPos)
                                    + " / "
                                    + (dur > 0 ? formatTime(dur) : (a.isLive() ? "直播" : "--:--"));
                    if (!clockText.contentEquals(a.playClock.getText())) {
                        a.playClock.setText(clockText);
                    }
                }
                if (a.playbackControls != null) a.playbackControls.tick();
            }
            if (a.playerChrome != null) a.playerChrome.update();
        } catch (Exception ignored) {
        }
        a.ui.postDelayed(() -> clock(a, ticket), 1000);
    }

    static void togglePlayback(MainActivity a) {
        if (a.player == null || !a.prepared) return;
        if (a.embyReporter == null
                && a.playbackControls != null
                && a.playbackControls.site != null
                && a.playbackControls.site.api != null
                && a.playbackControls.site.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)) {
            a.resumeAt = -1;
            resolvePlayer(
                    a,
                    a.playbackControls.site,
                    a.playbackControls.video,
                    a.activeLine,
                    a.activeEpisode);
            return;
        }
        try {
            if (a.player.isPlaying()) {
                a.player.pause();
                a.playing = false;
                savePlaybackHistory(a, true);
                a.playStatus.setText(a.isTvDevice() ? "已暂停 · 按确认继续" : "已暂停 · 双击继续");
            } else {
                if (a.player.getDuration() > 0
                        && a.player.getCurrentPosition() >= a.player.getDuration() - 300)
                    a.player.seekTo(0);
                a.player.start();
                a.playing = true;
                a.playStatus.setText(
                        a.isTvDevice()
                                ? "正在播放 · 确认暂停 · 上下键显示工具条"
                                : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量");
            }
            if (a.playerChrome != null) a.playerChrome.update();
            if (a.embyReporter != null)
                a.embyReporter.update(a.player.getCurrentPosition(), !a.playing);
        } catch (Exception error) {
            AppLog.w("PlaybackController", "togglePlayback failed", error);
        }
    }

    static void beginSeek(MainActivity a, int direction) {
        if (a.player == null || !a.prepared || a.player.getDuration() <= 0) return;
        int position = a.seekGesture.active ? a.seekGesture.target : a.player.getCurrentPosition();
        if (!a.seekGesture.active) a.seekStatus = a.playStatus.getText().toString();
        a.seekGesture.begin(
                direction, position, a.player.getDuration(), SystemClock.uptimeMillis());
        showControls(a, true, a.isTvDevice());
        if (a.playbackControls != null && a.isTvDevice()) {
            a.playbackControls.timeline.requestFocus();
        }
        previewSeek(a);
        a.ui.removeCallbacks(a.seekPulse);
        a.ui.postDelayed(a.seekPulse, 100);
    }

    static void previewSeek(MainActivity a) {
        if (!a.seekGesture.active || a.playbackControls == null) return;
        a.playbackControls.timeline.setProgress(a.seekGesture.target);
        a.playClock.setText(
                formatTime(a.seekGesture.target) + " / " + formatTime(a.seekGesture.duration));
        long now = SystemClock.uptimeMillis();
        int seekSpeed = a.seekGesture.speed(now);
        boolean accel = a.seekGesture.accelerated(now);
        String msg =
                (a.seekGesture.direction > 0 ? "快进" : "快退")
                        + (seekSpeed > 0
                                ? (accel ? " · 加速 " : " · ") + seekSpeed + " 秒/秒"
                                : " · 15 秒")
                        + " · 松开跳转";
        if (a.playStatus != null) a.playStatus.setText(msg);
        if (a.playerChrome != null && a.playerChrome.seekHint != null) {
            a.playerChrome.seekHint.setText(msg);
        }
    }

    static void finishSeek(MainActivity a, boolean commit) {
        if (!a.seekGesture.active) return;
        a.seekGesture.advance(SystemClock.uptimeMillis());
        int target = a.seekGesture.target;
        a.seekGesture.cancel();
        a.ui.removeCallbacks(a.seekPulse);
        if (commit && a.player != null && a.prepared) {
            a.player.seekTo(target);
            if (a.playingBookmark != null) {
                a.playingBookmark.position = Math.max(0, target);
            }
            savePlaybackHistory(a, true);
        }
        if (a.playStatus != null) a.playStatus.setText(a.seekStatus);
        if (a.playerChrome != null && a.playerChrome.seekHint != null) {
            a.playerChrome.seekHint.setText("");
        }
        if (a.player != null && a.prepared && a.playbackControls != null) {
            int position = commit ? target : a.player.getCurrentPosition();
            int dur = a.player.getDuration();
            a.playbackControls.timeline.setProgress(position);
            a.playClock.setText(formatTime(position) + " / " + formatTime(dur));
            if (a.playerChrome != null) {
                a.playerChrome.updatePlaybackHud(
                        a.playbackControls.displayPosition(position), dur, a.isLive());
            }
        }
        if (commit) scheduleHide(a);
    }

    static boolean tryAdvanceQualityOrLiveStream(
            MainActivity a, int ticket, Cms.Site s, Cms.Video v, Cms.Episode ep) {
        if ("live".equals(s.key) && a.prefs != null && ep.url != null && !ep.url.isEmpty()) {
            String failedMetrics =
                    LiveQualityPolicy.recordStreamFailure(
                            a.prefs.getString(Prefs.LIVE_STREAM_METRICS, ""),
                            ep.url,
                            System.currentTimeMillis());
            a.prefs.edit().putString(Prefs.LIVE_STREAM_METRICS, failedMetrics).apply();
        }
        JSONArray qualities = ep.media.optJSONArray("qualities");
        int qIdx = ep.media.optInt("_qualityIdx", 0);
        if (qualities == null || qIdx + 1 >= qualities.length()) return false;
        JSONObject nextQ = qualities.optJSONObject(qIdx + 1);
        String nextUrl = nextQ != null ? nextQ.optString("url", "") : "";
        if (nextUrl.isEmpty()) return false;
        try {
            ep.media.put("_qualityIdx", qIdx + 1);
            ep.media.remove("_softwareFallback");
            ep.media.remove("_systemFallback");
            ep.media.remove("_mpvFallback");
        } catch (Exception ignored) {
        }
        applyCandidateHeaders(s, ep, nextQ);
        if (LiveQualityPolicy.isEmbyLiveUrl(nextUrl)) {
            a.ui.post(
                    () -> {
                        if (ticket == a.generation) {
                            LiveController.playEmbyLiveAsync(a, s, v, ep, nextUrl);
                        }
                    });
            return true;
        }
        ep.url = LocalProxy.rewrite(nextUrl);
        String qName = nextQ.optString("name", "备用线路");
        if (a.playStatus != null) {
            a.playStatus.setText("正在切换至 " + qName + "…");
        }
        if (a.playerChrome != null) {
            a.playerChrome.setLoadingStatus("正在切换至 " + qName + "…", null);
        }
        a.ui.post(
                () -> {
                    if (ticket == a.generation) playerPage(a, s, v, ep);
                });
        return true;
    }

    static void applyCandidateHeaders(Cms.Site s, Cms.Episode ep, JSONObject qObj) {
        if (ep == null || qObj == null) return;
        ep.headers.clear();
        if (s != null && !"live".equals(s.key)) {
            ep.headers.putAll(s.headers);
        }
        JSONObject hs = qObj.optJSONObject("headers");
        if (hs != null) {
            java.util.Iterator<String> keys = hs.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String val = hs.optString(k, "");
                if (!val.isEmpty()) ep.headers.put(k, val);
            }
        }
        if (s != null && "live".equals(s.key)) {
            s.headers.clear();
            s.headers.putAll(ep.headers);
            if (!LiveQualityPolicy.isEmbyLiveUrl(qObj.optString("url", ""))) {
                ep.media.remove("embySession");
                s.api = qObj.optString("url", "");
            }
        }
        String mime = qObj.optString("mimeType", "").trim();
        JSONObject drm = qObj.optJSONObject("drm");
        try {
            if (!mime.isEmpty()) ep.media.put("mimeType", mime);
            else ep.media.remove("mimeType");
            if (drm != null) ep.media.put("drm", new JSONObject(drm.toString()));
            else ep.media.remove("drm");
        } catch (Exception ignored) {
        }
    }

    static void savePlaybackHistoryOnPrepared(MainActivity a) {
        if (a.privateMode) return;
        if (a.player == null || !a.prepared || a.playingBookmark == null) return;
        updateInMemoryBookmark(a);
        a.lastBookmarkSave = a.playingBookmark.updated;
        final WatchHistory.Entry snapshot = cloneBookmark(a.playingBookmark);
        if (a.prefs != null && snapshot != null) {
            WatchHistory.enqueueUpsert(a.historyPersistence(), snapshot);
        }
        CloudSyncDialog.scheduleAutoPush(a, false);
    }

    static void savePlaybackHistory(MainActivity a, boolean force) {
        if (a.privateMode) return;
        if (a.player == null || !a.prepared || a.playingBookmark == null) return;
        updateInMemoryBookmark(a);
        if (a.playingBookmark.position < 1000 && a.playingBookmark.position == 0) return;
        long now = System.currentTimeMillis();
        if (!force && now - a.lastBookmarkSave < DISK_SAVE_INTERVAL_MS) return;
        a.lastBookmarkSave = now;
        final WatchHistory.Entry snapshot = cloneBookmark(a.playingBookmark);
        if (a.prefs != null && snapshot != null) {
            WatchHistory.enqueueUpsert(a.historyPersistence(), snapshot);
        }
        CloudSyncDialog.scheduleAutoPush(a, force);
    }

    static void stopEmbySession(MainActivity a) {
        if (a.embyReporter == null) return;
        int position = 0;
        try {
            if (a.player != null && a.prepared) position = a.player.getCurrentPosition();
        } catch (Exception ignored) {
        }
        a.embyReporter.stop(position);
        a.embyReporter = null;
    }

    static void releasePlayer(MainActivity a) {
        if (a.markerHost != null) a.markerHost.abort();
        if (a.playerChrome != null) a.playerChrome.cancelEpisodeHoldSwitch();
        finishSeek(a, false);
        if (a.hideLockButton != null) {
            a.ui.removeCallbacks(a.hideLockButton);
            a.hideLockButton = null;
        }
        a.playerLocked = false;
        a.touchPlayerGesture = null;
        a.clearLiveNumber();
        savePlaybackHistory(a, true);
        stopEmbySession(a);
        a.miniMode = false;
        a.miniDetails = null;
        if (a.playbackControls != null) a.playbackControls.close();
        a.playbackControls = null;
        a.prepared = false;
        a.playing = false;
        if (a.player != null) {
            try {
                a.player.release();
            } catch (Exception error) {
                AppLog.w("PlaybackController", "player.release failed", error);
            }
            a.player = null;
        }
        VideoOutput.setWindowHdrMode(a, false);
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Policy for HUD visibility on TV pause/resume toggle: clicking pause on TV keeps the frame
     * clean without popping up top/bottom toolbars or timeline HUD.
     */
    static boolean shouldShowHudOnPauseToggle(
            boolean tvDevice, boolean controlsAlreadyVisible, boolean nowPlaying) {
        if (!tvDevice) return true;
        return false;
    }

    static boolean dispatchPlaybackKey(MainActivity a, KeyEvent event) {
        if (a.current == null
                || !a.current.player
                || a.miniMode
                || (a.playbackControls != null && a.playbackControls.dialogOpen)) return false;
        int key = event.getKeyCode();
        EpisodeHoldSwitchOverlay holdOverlay =
                a.playerChrome != null ? a.playerChrome.episodeHoldOverlay : null;
        if (holdOverlay != null) {
            if (holdOverlay.isTracking() && !holdOverlay.isTrackingKey(key)) {
                holdOverlay.cancel();
            }
            if (EpisodeHoldSwitchOverlay.isEpisodeHoldKey(key)) {
                int action = event.getAction();
                boolean eligibleHold =
                        a.isTvDevice()
                                && !a.isLive()
                                && !a.isCast()
                                && a.prepared
                                && a.markerHost == null
                                && !a.seekGesture.active
                                && (!a.controlsVisible
                                        || (a.playerChrome != null
                                                && a.playerChrome.progressOnlyMode))
                                && (a.playbackControls == null
                                        || !a.playbackControls.isSkipCountdownActive());
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0 && eligibleHold) {
                        int dir = EpisodeHoldSwitchOverlay.Controller.directionForKeyCode(key);
                        boolean hasTarget =
                                a.playbackControls != null
                                        && a.playbackControls.hasAdjacentEpisode(dir);
                        String targetName =
                                a.playbackControls != null
                                        ? a.playbackControls.peekAdjacentEpisodeName(dir)
                                        : "";
                        if (holdOverlay.beginHold(
                                key, event.getDownTime(), hasTarget, targetName)) {
                            return true;
                        }
                    } else if (holdOverlay.isTrackingKey(key)) {
                        holdOverlay.onKeyRepeat(key);
                        return true;
                    }
                } else if (action == KeyEvent.ACTION_UP && holdOverlay.isTrackingKey(key)) {
                    int upResult = holdOverlay.handleKeyUp(key, event.isCanceled());
                    if (upResult == EpisodeHoldSwitchOverlay.Controller.UP_SHORT_PRESS) {
                        if (key == KeyEvent.KEYCODE_DPAD_UP && a.playbackControls != null) {
                            KeyEvent syntheticDown =
                                    new KeyEvent(
                                            event.getDownTime(),
                                            event.getEventTime(),
                                            KeyEvent.ACTION_DOWN,
                                            KeyEvent.KEYCODE_DPAD_UP,
                                            0);
                            if (a.playbackControls.markers().open(syntheticDown)) {
                                return true;
                            }
                        }
                        showControls(a, true, false);
                        if (a.playbackControls != null) a.playbackControls.focusPrimaryAction();
                    }
                    return true;
                }
            }
        }
        if (a.isTvDevice()
                && key == KeyEvent.KEYCODE_DPAD_UP
                && a.playbackControls != null
                && a.playbackControls.markers().open(event)) return true;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        View focus = a.getCurrentFocus();
        boolean horizontal =
                key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (a.seekGesture.active && !horizontal && down)
            finishSeek(a, key != KeyEvent.KEYCODE_BACK);
        boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (confirm
                && (!a.controlsVisible
                        || focus == a.root
                        || a.playbackControls != null && focus == a.playbackControls.timeline)) {
            if (down && event.getRepeatCount() == 0) {
                boolean wasVisible = a.controlsVisible;
                togglePlayback(a);
                if (shouldShowHudOnPauseToggle(a.isTvDevice(), wasVisible, a.playing)) {
                    showControls(a, true);
                } else {
                    if (wasVisible && !a.playing) {
                        showControls(a, false);
                    } else if (a.playerChrome != null) {
                        a.playerChrome.update();
                    }
                }
            }
            return true;
        }
        if ((!a.controlsVisible || (a.playerChrome != null && a.playerChrome.progressOnlyMode))
                && (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_MENU)) {
            if (down) {
                showControls(a, true, false);
                if (a.playbackControls != null) a.playbackControls.focusPrimaryAction();
            }
            return true;
        }
        if (a.controlsVisible && key == KeyEvent.KEYCODE_MENU) {
            if (down && event.getRepeatCount() == 0) {
                if (a.playbackControls != null && focus == a.playbackControls.timeline) {
                    a.playbackControls.focusPrimaryAction();
                } else {
                    showControls(a, false);
                }
            }
            return true;
        }
        if (horizontal
                && !a.isLive()
                && (a.seekGesture.active
                        || !a.controlsVisible
                        || focus == a.root
                        || a.playbackControls != null && focus == a.playbackControls.timeline)) {
            int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (down) {
                if (!a.seekGesture.active && event.getRepeatCount() == 0
                        || a.seekGesture.active
                                && a.seekGesture.direction != direction
                                && event.getRepeatCount() == 0) beginSeek(a, direction);
            } else if (event.getAction() == KeyEvent.ACTION_UP
                    && a.seekGesture.active
                    && a.seekGesture.direction == direction) finishSeek(a, !event.isCanceled());
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down && event.getRepeatCount() == 0) {
                boolean wasVisible = a.controlsVisible;
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || (key == KeyEvent.KEYCODE_MEDIA_PLAY && !a.playing)
                        || (key == KeyEvent.KEYCODE_MEDIA_PAUSE && a.playing)) togglePlayback(a);
                if (shouldShowHudOnPauseToggle(a.isTvDevice(), wasVisible, a.playing)) {
                    showControls(a, true);
                } else {
                    if (wasVisible && !a.playing) {
                        showControls(a, false);
                    } else if (a.playerChrome != null) {
                        a.playerChrome.update();
                    }
                }
            }
            return true;
        }
        if (a.controlsVisible && down) scheduleHide(a);
        return false;
    }
}
