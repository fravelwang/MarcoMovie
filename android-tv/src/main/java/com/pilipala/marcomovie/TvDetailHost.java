package com.pilipala.marcomovie;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** TV-only bridge to detail loading, curated source resolution, favorites, and bookmark playback. */
final class TvDetailHost {
    static final int CURATED_MAX_SOURCES = 8;
    static final int CURATED_MAX_CONCURRENCY = 1;

    /** A detail that has not bound its lines after this long gets the slow-source hint. */
    static final long SLOW_SOURCE_HINT_MS = 6000L;

    /**
     * 墙卡换源的等待上限，按来源类型给：Guard/`w2x_` 站本来就慢（实测详情 4–12 s），给 12 s；
     * 其它站 8 s。之前统一 3 s，会把 4–6 s 就能落地的 w2x 站、甚至排队中的普通站一起切掉
     * （T-10 复盘中看到），所以这里只负责"等这么久还没回来任何数据才换"，已经落地的绝不切。
     */
    static final long CARD_FAILOVER_GUARD_MS = 12000L;
    static final long CARD_FAILOVER_NORMAL_MS = 8000L;

    /**
     * 墙轮（LibraryAggregator）在跑时，引擎队列会先伺候浏览请求，这一跳的详情实际是在排队；
     * 排队时间不该算成"这个站慢"，所以预算放宽到 1.5 倍。
     */
    static final double CARD_FAILOVER_BUSY_MULTIPLIER = 1.5d;

    /** Appended under the loading line; the loader itself keeps running. */
    static final String SLOW_SOURCE_HINT = "当前源较慢，可按「切换来源」换一个";

    /**
     * 网盘默认源过了这么久还没落地，才值得提前探其它候选：比 {@link #SLOW_SOURCE_HINT_MS} 宽得多，
     * 让 8–12 秒内答完的原盘/高码率网盘站不被换掉，只救那种几十秒的（实测 31 秒）。
     */
    static final long PROBE_AHEAD_DELAY_MS = 15000L;

    /**
     * 换站探测已经派发、结果还没回来时的状态行文案。这一刻还没定"没有线路"，
     * 写成死路文案就等于让用户先看到失败（实测那两秒的观感问题）。
     */
    static final String SWITCHING_SOURCE_STATUS = "正在尝试其他来源…";

    /** 同一时刻播放按钮的文案：按钮保持不可点，焦点留在「切换来源」上。 */
    static final String SWITCHING_SOURCE_PLAY_LABEL = "正在换源…";

    /** 真的没有可播放线路（探测没派发，或派发了但一轮全灭）时的状态行。 */
    static final String NO_PLAYABLE_LINES_STATUS = "该来源没有返回可播放线路";

    /** 整部片只有音频线路时的提示（站点把视频改名成音频，夸克 v2/play 没有音频档）。 */
    static final String AUDIO_ONLY_LINE_HINT = "这条线路是音频文件，可能播不出画面";

    /** 同一状态的播放按钮文案：按钮不可点。 */
    static final String NO_PLAYABLE_LINES_PLAY_LABEL = "该来源暂无线路";

    /**
     * 排空后那颗搜索按钮的文案。详情页不再自动跳搜索页（{@code SourceAutoSwitch} 的
     * {@code searchWhenExhausted=false}），用户点它才 {@link MainActivity#openSearch}。
     */
    static final String SEARCH_OTHER_SOURCES_LABEL = "🔍 全站搜其他源";

    /** 排空后的一句提示：不挡阅读，只说清按钮在哪。 */
    static final String EXHAUSTED_SEARCH_HINT = "账本里的站都播不了，可以点下面的『🔍 全站搜其他源』";

    /**
     * Hard budget for a curated cross-site match. Past this the page stops matching and says so:
     * a show the ledger does not know yet must not leave the viewer on a spinning line.
     */
    static final long CURATED_MATCH_BUDGET_MS = 20000L;

    /** Shown when the match budget ran out before any playable line bound. */
    static final String CURATED_MATCH_BUDGET_MESSAGE = "20 秒内没匹配到可播放线路，可按「🔍 全站搜索」查找更多来源";

    interface Valid {
        boolean get();
    }

    private static final class MatchedSource {
        final Cms.Site site;
        final Cms.Video listing;
        final int score;

        MatchedSource(Cms.Site site, Cms.Video listing, int score) {
            this.site = site;
            this.listing = listing;
            this.score = score;
        }
    }

    /**
     * One-shot phase log for a curated cross-site match: how many candidates shared the budget,
     * when the first one answered, when playable lines actually bound, how long the whole thing
     * took, and how it ended (bound / budget / nosource). Numbers and phase names only — never a
     * site address or a title, the same rule {@link DetailRequest} follows.
     */
    private static final class MatchTiming {
        private final long started;
        private final int candidates;
        private long firstMatch = -1L;
        private long firstPlayable = -1L;
        private boolean logged;

        MatchTiming(long started, int candidates) {
            this.started = started;
            this.candidates = candidates;
        }

        void firstMatch() {
            if (firstMatch < 0) firstMatch = android.os.SystemClock.elapsedRealtime();
        }

        void firstPlayable() {
            if (firstPlayable < 0) firstPlayable = android.os.SystemClock.elapsedRealtime();
        }

        void log(String outcome) {
            if (logged) return;
            logged = true;
            long total = android.os.SystemClock.elapsedRealtime() - started;
            android.util.Log.i(
                    "MovieMatchTiming",
                    "candidates="
                            + candidates
                            + " first_match_ms="
                            + (firstMatch < 0 ? -1L : firstMatch - started)
                            + " first_playable_ms="
                            + (firstPlayable < 0 ? -1L : firstPlayable - started)
                            + " total_ms="
                            + total
                            + " outcome="
                            + outcome);
        }
    }

    static int[] initialSelection(
            Cms.Video video,
            WatchHistory.Entry bookmark,
            String preferred,
            EpisodeSelection saved,
            boolean descending) {
        int line = 0, page = 0;
        if (video == null || video.lines == null || video.lines.isEmpty()) {
            return new int[] {0, 0};
        }
        for (int i = 0; i < video.lines.size(); i++) {
            Cms.Line cand = video.lines.get(i);
            if (cand != null && cand.name != null && cand.name.equals(preferred)) {
                line = i;
                break;
            }
        }
        if (bookmark != null) {
            for (int i = 0; i < video.lines.size(); i++) {
                Cms.Line candidate = video.lines.get(i);
                if (candidate == null
                        || candidate.name == null
                        || !candidate.name.equals(bookmark.line)) continue;
                line = i;
                if (candidate.episodes != null) {
                    int foundIdx = -1;
                    int bmEpNum = TvSeriesScraper.extractEpisodeNumber(bookmark.episode);
                    for (int n = 0; n < candidate.episodes.size(); n++) {
                        Cms.Episode ep = candidate.episodes.get(n);
                        if (ep != null && Objects.equals(ep.name, bookmark.episode)) {
                            foundIdx = n;
                            break;
                        }
                    }
                    if (foundIdx < 0 && bmEpNum > 0) {
                        for (int n = 0; n < candidate.episodes.size(); n++) {
                            Cms.Episode ep = candidate.episodes.get(n);
                            if (ep != null
                                    && TvSeriesScraper.extractEpisodeNumber(ep.name) == bmEpNum) {
                                foundIdx = n;
                                break;
                            }
                        }
                    }
                    if (foundIdx >= 0) {
                        page =
                                (descending ? candidate.episodes.size() - 1 - foundIdx : foundIdx)
                                        / 50;
                    }
                }
                break;
            }
        }
        if (saved != null && saved.selected)
            for (int i = 0; i < video.lines.size(); i++) {
                Cms.Line candidate = video.lines.get(i);
                if (candidate != null
                        && candidate.name != null
                        && candidate.name.equals(saved.line)) {
                    line = i;
                    int epCount = candidate.episodes == null ? 0 : candidate.episodes.size();
                    page = EpisodeSelection.boundPage(saved.page, epCount);
                    break;
                }
            }
        return new int[] {line, page};
    }

    static String formatHistoryCaption(
            MainActivity a, WatchHistory.Entry bookmark, String episodeFileCaption) {
        if (bookmark == null) return "";
        String progress =
                bookmark.complete()
                        ? "已看完"
                        : PlaybackController.formatTime(bookmark.position)
                                + (bookmark.duration > 0
                                        ? " / " + PlaybackController.formatTime(bookmark.duration)
                                        : "");
        String ep = bookmark.episode == null ? "" : bookmark.episode.trim();
        if (!ep.isEmpty() && episodeFileCaption != null && episodeFileCaption.contains(ep)) {
            return "上次观看进度 · " + progress;
        }
        return "上次观看 · " + a.historyLabel(bookmark);
    }

    /**
     * 书签落到这条线路上的那一集，对不上返回 null。
     *
     * <p>为什么不再在这里兜底「第 1 集」：换站之后要区分"书签真的管用"和"书签根本不属于这条
     * 线路"两件事 —— 前者接着看那一集，后者该看换站交接带来的第 N 集（{@link #handoverEpisode}）。
     * 兜底放在 {@link #resolveActiveEpisode} 里，语义才分得开。
     */
    private static Cms.Episode bookmarkedEpisode(Cms.Line line, WatchHistory.Entry bookmark) {
        if (line == null || line.episodes == null || line.episodes.isEmpty()) return null;
        if (bookmark != null
                && Objects.equals(line.name, bookmark.line)
                && bookmark.episode != null) {
            for (Cms.Episode ep : line.episodes) {
                if (ep != null && bookmark.episode.equals(ep.name)) return ep;
            }
            int bmNum = TvSeriesScraper.extractEpisodeNumber(bookmark.episode);
            if (bmNum > 0) {
                for (Cms.Episode ep : line.episodes) {
                    if (ep != null && TvSeriesScraper.extractEpisodeNumber(ep.name) == bmNum) {
                        return ep;
                    }
                }
            }
        }
        return null;
    }

    private static Cms.Episode firstEpisode(Cms.Line line) {
        if (line == null || line.episodes == null || line.episodes.isEmpty()) return null;
        return line.episodes.get(0);
    }

    private static Cms.Episode resolveActiveEpisode(Cms.Line line, WatchHistory.Entry bookmark) {
        Cms.Episode bookmarked = bookmarkedEpisode(line, bookmark);
        return bookmarked != null ? bookmarked : firstEpisode(line);
    }

    /**
     * 换站之后「该从哪一集开始」：书签优先（同一线路、同一集号，见 {@link #bookmarkedEpisode}），
     * 其次是换站交接带来的第 N 集（{@link SiteSwitchHandover#resolve}，按集号再按集名），都没有
     * 才从这条线路的第 1 集开始。
     *
     * <p>这一处就是"换站后选集状态以目标站为准"的落点：线路由 {@link #initialSelection} 按目标
     * 站自己的规则选好了，集号在这里对，两边集合大小不同（虎斑 2 集、天堂 14 集）也不会拿错下标。
     */
    private static Cms.Episode handoverEpisode(
            Cms.Line line, WatchHistory.Entry bookmark, SiteSwitchHandover.Want handover) {
        Cms.Episode bookmarked = bookmarkedEpisode(line, bookmark);
        if (bookmarked != null) return bookmarked;
        Cms.Episode carried = SiteSwitchHandover.resolve(line, handover);
        if (carried != null) return carried;
        return firstEpisode(line);
    }

    /**
     * 这部片在本站的观看记录。
     *
     * <p>判据是「同一个站 + 同一个影片 id + 同一部片」。片名这一道不是多余的：有些采集站的 id 会
     * 复用（下架后同一 id 换上别的片），跨片续播比不续播更糟；两边片名都拿得到却对不上时，宁可不
     * 认这条书签（任务卡 2026-11-26 第 2 节第 3 条）。
     */
    private static WatchHistory.Entry findBookmark(MainActivity a, Cms.Site site, Cms.Video video) {
        if (a == null || site == null || site.key == null || video == null || video.id == null)
            return null;
        for (WatchHistory.Entry entry : a.watchHistory()) {
            if (entry != null
                    && Objects.equals(site.key, entry.site)
                    && Objects.equals(video.id, entry.id)
                    && sameShow(entry, video)) {
                return entry;
            }
        }
        return null;
    }

    /** 记录与影片是不是同一部片；任一边片名为空时不再卡（旧记录没有片名也要能续播）。 */
    private static boolean sameShow(WatchHistory.Entry entry, Cms.Video video) {
        if (entry == null || video == null) return false;
        String entryTitle = entry.title == null ? "" : entry.title.trim();
        String videoTitle = video.title == null ? "" : video.title.trim();
        if (entryTitle.isEmpty() || videoTitle.isEmpty()) return true;
        return SiteSwitchHandover.sameTitle(entryTitle, videoTitle);
    }

    private static void mergeListingFallback(Cms.Video target, Cms.Video fallback) {
        if (target == null || fallback == null) return;
        if (target.id == null || target.id.trim().isEmpty()) target.id = fallback.id;
        if (target.title == null || target.title.isEmpty()) target.title = fallback.title;
        if (target.poster == null || target.poster.isEmpty()) target.poster = fallback.poster;
        if (target.year == null || target.year.isEmpty()) target.year = fallback.year;
        if (target.area == null || target.area.isEmpty()) target.area = fallback.area;
        if (target.tag == null || target.tag.isEmpty()) target.tag = fallback.tag;
        if (target.remarks == null || target.remarks.isEmpty()) target.remarks = fallback.remarks;
        if (target.doubanScore == null || target.doubanScore.isEmpty()) {
            target.doubanScore = fallback.doubanScore;
        }
        if (target.description == null || target.description.trim().isEmpty()) {
            target.description = fallback.description;
        }
        if (target.actor == null || target.actor.trim().isEmpty()) target.actor = fallback.actor;
        if (target.director == null || target.director.trim().isEmpty()) {
            target.director = fallback.director;
        }
        if (target.lines != null) {
            for (Iterator<Cms.Line> it = target.lines.iterator(); it.hasNext(); ) {
                Cms.Line line = it.next();
                if (line == null || line.episodes == null || line.episodes.isEmpty()) it.remove();
            }
        }
    }

    /** Package-private so the detail screen judges playability by the exact same rule. */
    static boolean hasPlayableLines(Cms.Video video) {
        if (video == null || video.lines == null) return false;
        for (Cms.Line line : video.lines) {
            if (line != null && line.episodes != null && !line.episodes.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 至少一条线路既有剧集、又没有被 {@link LineHealth} 判成坏线路。
     *
     * <p>「有剧集」沿用 {@link #hasPlayableLines} 的口径；「坏」用线路弹窗里那条判定
     * （{@link LineHealth#badge}，同一个站 + 同一个线路名）：只有弹窗会标出来的那几类
     * （线路问题 / 解析问题 / 片源问题 / 设备问题）才算坏，没有探测记录的一律算可用 ——
     * 未知不等于坏，否则每部新片第一次进详情都会被当成死路。
     *
     * <p>为什么要单独一个方法：一条线路全被标坏的站，对播放器来说等同于"这个站没有线路"，
     * 详情绑定处必须走同一条自动换源，不能只看"有没有集"。
     */
    static boolean hasUsableLine(MainActivity a, Cms.Site site, Cms.Video video) {
        if (!hasPlayableLines(video)) return false;
        for (Cms.Line line : video.lines) {
            if (line == null || line.episodes == null || line.episodes.isEmpty()) continue;
            String badge = LineHealth.badge(a, site, line);
            // 空徽标 = 没有探测记录（未知），算可用；同一个字面量口径见 SourcePreferences。
            if (badge.isEmpty() || "可用".equals(badge)) return true;
        }
        return false;
    }

    static String buildEpisodesButtonLabel(Cms.Video video, int lineIdx) {
        if (video == null || video.lines == null || video.lines.isEmpty()) return "☰ 选集";
        Cms.Line line = video.lines.get(Math.max(0, Math.min(lineIdx, video.lines.size() - 1)));
        int epCount = line == null || line.episodes == null ? 0 : line.episodes.size();
        int lineCount = video.lines.size();
        if (epCount <= 1 && lineCount > 1) return "☰ 选线路 (" + lineCount + ")";
        if (epCount > 1 && lineCount > 1) return "☰ 选集与线路 · " + epCount + " 集";
        if (epCount > 1) return "☰ 选集 · " + epCount + " 集";
        return "☰ 线路与片源";
    }

    static void showEpisodesDialog(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            int initialLine,
            int initialPage,
            Runnable onDismiss) {
        if (a == null || video == null || video.lines == null || video.lines.isEmpty()) {
            if (a != null) a.toast("该来源没有可播放线路");
            return;
        }
        int safeLine = Math.max(0, Math.min(initialLine, video.lines.size() - 1));
        Cms.Line targetLine = video.lines.get(safeLine);
        int epCount =
                targetLine == null || targetLine.episodes == null ? 0 : targetLine.episodes.size();
        int safePage = EpisodeSelection.boundPage(initialPage, epCount);

        LinearLayout host = a.vertical();
        host.setTag("detail-episodes");
        host.setClipChildren(false);
        host.setClipToPadding(false);
        host.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(10));
        if (epCount > 16) {
            host.setMinimumHeight(Math.max(a.dp(280), a.windowHeight() - a.dp(220)));
        }

        DetailScreen.renderEpisodes(a, site, video, safeLine, safePage, host, null);

        AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle("选线路与选集")
                        .setWide(true)
                        .setView(host)
                        .setNegativeButton("关闭", null)
                        .create();
        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }
        dialog.show();
        View firstEp = host.findViewWithTag("detail-line:" + safeLine);
        if (firstEp != null) firstEp.requestFocus();
    }

    private static void showEpisodesPopup(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            WatchHistory.Entry bookmark,
            MainActivity.Screen screen,
            TvV17DetailScreen ui,
            SiteSwitchHandover.Want handover) {
        if (!hasPlayableLines(video)) {
            a.toast("该来源没有可播放线路");
            return;
        }
        int[] selection =
                initialSelection(
                        video,
                        bookmark,
                        a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                        screen.selection,
                        a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false));
        // 选集面板开在"这一页真正会播的那一集"所在页：换站交接带来的第 N 集也算，否则从面板里
        // 看到的选中位置和「播放」要播的那一集对不上。
        int popupPage = selection[1];
        Cms.Line popupLine =
                selection[0] >= 0 && selection[0] < video.lines.size()
                        ? video.lines.get(selection[0])
                        : null;
        Cms.Episode popupEp = handoverEpisode(popupLine, bookmark, handover);
        if (popupLine != null && popupLine.episodes != null && popupEp != null) {
            int epIndex = popupLine.episodes.indexOf(popupEp);
            if (epIndex >= 0) {
                popupPage =
                        EpisodeSelection.pageFor(
                                epIndex,
                                popupLine.episodes.size(),
                                a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false));
            }
        }
        showEpisodesDialog(
                a,
                site,
                video,
                selection[0],
                popupPage,
                () -> {
                    if (!hasPlayableLines(video)) return;
                    int[] updated =
                            initialSelection(
                                    video,
                                    bookmark,
                                    a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                                    screen.selection,
                                    a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false));
                    Cms.Line activeLine = video.lines.get(updated[0]);
                    Cms.Episode activeEp = handoverEpisode(activeLine, bookmark, handover);
                    ui.updateEpisodeCaption(
                            TvV17DetailScreen.formatEpisodeFileCaption(activeLine, activeEp),
                            buildEpisodesButtonLabel(video, updated[0]));
                });
    }

    private static void bindSeasonPopupDiscovery(
            MainActivity a, Cms.Site site, Cms.Video video, TvV17DetailScreen ui) {
        if (a == null || site == null || video == null || ui == null) return;
        ui.setSeasonsAction(null, null);
        DetailScreen.discoverTvRelatedSeasons(
                a,
                site,
                video,
                ui.view,
                seasons -> {
                    ui.setDetectedSeasonCount(seasons.count());
                    ui.setSeasonsAction(
                            "☰ 选季 · 共 " + seasons.count() + " 季",
                            () -> DetailScreen.showTvSeasonsPopup(a, site, video, seasons, false));
                });
    }

    static void show(MainActivity a, Cms.Site site, Cms.Video listing, Cms.Video ready) {
        show(a, site, listing, ready, null);
    }

    /**
     * {@code cardSources} is the wall card's own source pool (every site its aggregation round
     * sampled for this title). It rides along into every automatic-switch call so the probe can ask
     * sites the ledger has not learned yet — the whole point of opening a card that says "10 源".
     */
    static void show(
            MainActivity a,
            Cms.Site site,
            Cms.Video listing,
            Cms.Video ready,
            SourceLedger.Sources cardSources) {
        a.renderPage(listing.title == null ? "详情" : listing.title, "");
        final MainActivity.Screen screen = a.current;
        final int ticket = a.generation;
        final TvSearchContext context = new TvSearchContext(a.config, a.privateMode, a.engineEpoch);
        final Valid valid =
                () ->
                        a.current == screen
                                && ticket == a.generation
                                && !a.isFinishing()
                                && context.matches(a.config, a.privateMode, a.engineEpoch);

        final Cms.Video initialVideo = ready != null ? ready : listing;
        // 音频线路打标记并排到最后（只动展示名/顺序，解析不变），整片只有音频时给一句提示。
        final boolean[] allAudio = {false};
        if (NetdiskLineLabels.apply(initialVideo)) allAudio[0] = true;
        mergeListingFallback(initialVideo, listing);
        SeriesScrapeStore.OverrideRecord savedOv =
                SeriesScrapeStore.load(
                        a, site != null ? site.key : "", initialVideo.id, initialVideo.title);
        if (savedOv != null) {
            TvSeriesScrapeDialog.applyOverrideToVideo(initialVideo, savedOv);
        }
        final Cms.Video[] activeVideo = {initialVideo};
        final WatchHistory.Entry[] activeBookmark = {findBookmark(a, site, initialVideo)};
        // Flips when the detail binds or fails, so the slow-source hint stops firing.
        final boolean[] detailSettled = {false};
        // probeAhead 那一轮排空了：失败分支据此把"没有可播放线路 + 用户自己点搜索"画完（不再跳页）。
        final boolean[] probeAheadExhausted = {false};
        final boolean initiallyPlayable = hasPlayableLines(initialVideo);

        int[] initSel =
                initiallyPlayable
                        ? initialSelection(
                                initialVideo,
                                activeBookmark[0],
                                a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                                screen.selection,
                                a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false))
                        : new int[] {0, 0};
        // 换站交接（若有）：这部片这次是换站进来的，取走凭据，绑定时按目标站自己的线路与集号落位。
        // 只在目标页用一次，普通打开（没有交接）时这里是 null，行为与以前一致。
        final SiteSwitchHandover.Want handover = a.siteHandover.take(site, initialVideo);
        Cms.Line initLine = initiallyPlayable ? initialVideo.lines.get(initSel[0]) : null;
        Cms.Episode initEp = handoverEpisode(initLine, activeBookmark[0], handover);
        String initFileCaption = TvV17DetailScreen.formatEpisodeFileCaption(initLine, initEp);
        String initHistoryCaption = formatHistoryCaption(a, activeBookmark[0], initFileCaption);
        String initContinueCaption =
                TvV17DetailScreen.formatContinuePlaySubtitle(
                        activeBookmark[0], initFileCaption, initHistoryCaption);
        String initPlayLabel =
                !initiallyPlayable
                        ? "正在获取线路…"
                        : (activeBookmark[0] == null && handover == null
                                ? "► 开始播放"
                                : "► 继续播放");

        final TvV17DetailScreen[] holder = new TvV17DetailScreen[1];
        TvV17DetailScreen ui =
                new TvV17DetailScreen(
                        a,
                        site,
                        initialVideo,
                        null,
                        Favorites.contains(
                                a.prefs.getString(Prefs.FAVORITES, "[]"),
                                site.key,
                                initialVideo.id),
                        initiallyPlayable,
                        initPlayLabel,
                        buildEpisodesButtonLabel(initialVideo, initSel[0]),
                        !initContinueCaption.isEmpty() ? initContinueCaption : initFileCaption,
                        !initContinueCaption.isEmpty() ? "" : initHistoryCaption,
                        new TvV17DetailScreen.Actions() {
                            public void back() {
                                if (valid.get()) a.onBackPressed();
                            }

                            public void play() {
                                Cms.Video v = activeVideo[0];
                                if (!valid.get()) return;
                                if (!hasPlayableLines(v)) {
                                    // The button is already disabled in this state; this only
                                    // fires on the edge path, and it must say why nothing plays.
                                    TvV17DetailScreen page = holder[0];
                                    a.notice(
                                            page != null && page.showKnownSourceSwitch()
                                                    ? "当前来源没有可播放线路，请按「切换来源」换一个"
                                                    : "当前来源没有可播放线路");
                                    return;
                                }
                                WatchHistory.Entry bm = activeBookmark[0];
                                int[] sel =
                                        initialSelection(
                                                v,
                                                bm,
                                                a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                                                screen.selection,
                                                a.prefs.getBoolean(
                                                        Prefs.EPISODES_DESCENDING, false));
                                Cms.Line playLine = v.lines.get(sel[0]);
                                // 换站进来的那一次：书签对不上这页的线路时，接着看换站交接的
                                // 第 N 集，而不是永远从第 1 集重开。
                                Cms.Episode playEp = handoverEpisode(playLine, bm, handover);
                                if (bm != null
                                        && (!screen.selection.selected
                                                || Objects.equals(
                                                        playLine == null ? null : playLine.name,
                                                        bm.line))) {
                                    a.playBookmark(site, v, bm);
                                } else if (playEp != null) {
                                    a.resolvePlayer(site, v, playLine, playEp);
                                }
                            }

                            public void episodes() {
                                if (!valid.get()) return;
                                showEpisodesPopup(
                                        a,
                                        site,
                                        activeVideo[0],
                                        activeBookmark[0],
                                        screen,
                                        holder[0],
                                        handover);
                            }

                            public void favorite() {
                                if (!valid.get()) return;
                                holder[0].setFavorite(
                                        DetailScreen.toggleFavorite(a, site, activeVideo[0]));
                            }

                            public void synopsis() {
                                if (valid.get())
                                    new AppDialog.Builder(a)
                                            .setTitle(activeVideo[0].title)
                                            .setMessage(
                                                    DetailScreen.buildFullSynopsisMessage(
                                                            activeVideo[0]))
                                            .setPositiveButton("关闭", null)
                                            .show();
                            }
                        });
        holder[0] = ui;
        if (activeBookmark[0] != null) ui.setBookmark(activeBookmark[0]);
        ui.setScrapeAction(
                () -> {
                    if (!valid.get()) return;
                    Cms.Video v = activeVideo[0];
                    int[] sel =
                            hasPlayableLines(v)
                                    ? initialSelection(
                                            v,
                                            activeBookmark[0],
                                            a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                                            screen.selection,
                                            a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false))
                                    : new int[] {0, 0};
                    Cms.Line curLine = hasPlayableLines(v) ? v.lines.get(sel[0]) : null;
                    TvSeriesScrapeDialog.show(
                            a,
                            site,
                            v,
                            curLine,
                            () -> {
                                if (!valid.get()) return;
                                SeriesScrapeStore.OverrideRecord ov =
                                        SeriesScrapeStore.load(
                                                a, site != null ? site.key : "", v.id, v.title);
                                if (ov != null) TvSeriesScrapeDialog.applyOverrideToVideo(v, ov);
                                Cms.Line updatedLine =
                                        hasPlayableLines(v) ? v.lines.get(sel[0]) : null;
                                Cms.Episode updatedEp =
                                        resolveActiveEpisode(updatedLine, activeBookmark[0]);
                                String fc =
                                        TvV17DetailScreen.formatEpisodeFileCaption(
                                                updatedLine, updatedEp);
                                ui.bindResolvedVideo(
                                        site,
                                        v,
                                        activeBookmark[0],
                                        Favorites.contains(
                                                a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                site != null ? site.key : "",
                                                v.id),
                                        activeBookmark[0] == null ? "► 开始播放" : "► 继续播放",
                                        buildEpisodesButtonLabel(v, sel[0]),
                                        fc,
                                        formatHistoryCaption(a, activeBookmark[0], fc));
                            });
                });

        // Mount immediately so entering detail is instant (zero blank "正在加载影片详情…" screen)
        a.root.removeView(a.scroll);
        a.root.addView(ui.view, new FrameLayout.LayoutParams(-1, -1));
        a.scroll = ui.scroll;
        a.body = ui.content;

        if (!initiallyPlayable) {
            // The ledger already knows the other sites: offer 切换来源 before lines arrive.
            ui.showKnownSourceSwitch();
            ui.setLoadingStatus("正在获取播放线路…");
            if (screen.focus == null || "detail-back".equals(screen.focus)) {
                screen.focus = "detail-favorite";
            }
        } else {
            bindSeasonPopupDiscovery(a, site, initialVideo, ui);
            if (screen.focus == null || "detail-back".equals(screen.focus)) {
                screen.focus = "detail-play";
            }
        }
        a.restore(screen);

        MainActivity.Done<Cms.Video> bindInPlace =
                resolved -> {
                    if (!valid.get() || resolved == null) return;
                    detailSettled[0] = true;
                    mergeListingFallback(resolved, listing);
                    SeriesScrapeStore.OverrideRecord ov =
                            SeriesScrapeStore.load(
                                    a, site != null ? site.key : "", resolved.id, resolved.title);
                    if (ov != null) TvSeriesScrapeDialog.applyOverrideToVideo(resolved, ov);
                    if (NetdiskLineLabels.apply(resolved)) allAudio[0] = true;
                    activeVideo[0] = resolved;
                    WatchHistory.Entry bm = findBookmark(a, site, resolved);
                    activeBookmark[0] = bm;
                    // 判定的是「有可用的线路」：全是已知坏线路时与"这个站没线路"同一条路 ——
                    // 先试同站网盘分享，再自动换源（SourceAutoSwitch 探到能播的就换过去）。
                    boolean playable = hasUsableLine(a, site, resolved);
                    int[] sel =
                            playable
                                    ? initialSelection(
                                            resolved,
                                            bm,
                                            a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                                            screen.selection,
                                            a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false))
                                    : new int[] {0, 0};
                    Cms.Line activeLine = playable ? resolved.lines.get(sel[0]) : null;
                    // 换站交接：这一页是换站进来的，就按"第 N 集"落到目标站自己的线路上（书签
                    // 对不上线路时也走它）；没有交接时与以前一致（书签 → 第 1 集）。
                    Cms.Episode activeEpisode = handoverEpisode(activeLine, bm, handover);
                    String fileCap =
                            TvV17DetailScreen.formatEpisodeFileCaption(activeLine, activeEpisode);
                    String histCap = formatHistoryCaption(a, bm, fileCap);
                    ui.bindResolvedVideo(
                            site,
                            resolved,
                            bm,
                            Favorites.contains(
                                    a.prefs.getString(Prefs.FAVORITES, "[]"),
                                    site.key,
                                    resolved.id),
                            bm == null && handover == null ? "► 开始播放" : "► 继续播放",
                            buildEpisodesButtonLabel(resolved, sel[0]),
                            fileCap,
                            histCap);
                    if (!playable) {
                        WatchHistory.Entry fallbackBm = bm != null ? bm : a.historyFallbackBookmark;
                        if (fallbackBm != null
                                && !a.historyFallbackInFlight
                                && fallbackBm.title != null
                                && !fallbackBm.title.trim().isEmpty()
                                && CatalogScreen.hasAlternativeHistorySources(a, site.key)) {
                            CatalogScreen.resumeHistoryFromAlternativeSources(
                                    a, fallbackBm, site.key, "原片源暂无可播放线路", true);
                            return;
                        }
                        // 同站自救：网盘站详情页的下载区里挂着的夸克分享，本身就是线路。派发得出去就先
                        // 等它（拿到就地重绑，见 rebindAfterShare）；拿不到（没链接 / 全失效 / 没登录
                        // 夸克）再走下面的换源。这个先后顺序就是卡里定的"先试分享、再试自动换源"。
                        // forceDefault 必须为 true：这个分支判成"没有可播线路"，但片子身上常常还挂着
                        // 几条被标坏的线路（盒子上实测《两京十五日》虎斑：两条 UC、各 2 集），
                        // initialSelection 会照样选中第一条；只有显式选分享线，「播放」才走它。
                        if (NetdiskShareLines.buildAsync(
                                a,
                                site,
                                resolved,
                                valid,
                                shareLines ->
                                        rebindAfterShare(
                                                a,
                                                site,
                                                resolved,
                                                bm,
                                                ui,
                                                valid,
                                                shareLines,
                                                true),
                                () -> recoverWithoutLines(a, site, resolved, ui, valid, cardSources))) {
                            return;
                        }
                        recoverWithoutLines(a, site, resolved, ui, valid, cardSources);
                    } else {
                        // 有线路，但一条都不是夸克可解析 id（例如全是 UC 四段 id，本应用没有 UC 原生
                        // 解析器，插件又报"PC端未登录或Cookie失效"）：页面里有夸克分享就补一条，并把
                        // 它设为默认选中，让「播放」直接走它。正常片不会走到这里，也就不多打夸克接口。
                        NetdiskShareLines.buildAsync(
                                a,
                                site,
                                resolved,
                                valid,
                                shareLines ->
                                        rebindAfterShare(
                                                a,
                                                site,
                                                resolved,
                                                bm,
                                                ui,
                                                valid,
                                                shareLines,
                                                true),
                                null);
                        ui.setLoadingStatus("");
                        if (allAudio[0]) {
                            // 整部片只有音频线路：把话说在前面，播放器黑屏时用户不会以为是我们的问题。
                            ui.setLoadingStatus(AUDIO_ONLY_LINE_HINT);
                            a.toast(AUDIO_ONLY_LINE_HINT);
                        }
                        bindSeasonPopupDiscovery(a, site, resolved, ui);
                        View curFocus = a.getCurrentFocus();
                        if (curFocus == null
                                || curFocus == ui.favorite
                                || curFocus == ui.back
                                || "detail-favorite".equals(screen.focus)
                                || "detail-play".equals(screen.focus)) {
                            screen.focus = "detail-play";
                            ui.play.requestFocus();
                        }
                    }
                };

        MainActivity.Done<Throwable> onDetailError =
                error -> {
                    if (!valid.get()) return;
                    detailSettled[0] = true;
                    WatchHistory.Entry fallbackBm =
                            activeBookmark[0] != null
                                    ? activeBookmark[0]
                                    : a.historyFallbackBookmark;
                    if (fallbackBm != null
                            && !a.historyFallbackInFlight
                            && fallbackBm.title != null
                            && !fallbackBm.title.trim().isEmpty()
                            && CatalogScreen.hasAlternativeHistorySources(a, site.key)) {
                        CatalogScreen.resumeHistoryFromAlternativeSources(
                                a, fallbackBm, site.key, "原片源加载失败", true);
                        return;
                    }
                    // 卡片自带的来源池：首选站失败就立刻问池子里的下一个，不等自动换源那一轮探测
                    // （那一轮要逐站问详情，用户看到的是"卡住"）。池子每跳一次就少一个站，跳完为止。
                    if (hopCardSource(a, site, listing, cardSources)) return;
                    // The source failed outright: same probe-and-switch fallback as the empty-lines
                    // path, dispatched before the error state. This is the branch that runs instead
                    // of the curated matching page when the ledger knows another source.
                    String fallbackTitle =
                            initialVideo.title != null ? initialVideo.title.trim() : "";
                    boolean switching =
                            SourceAutoSwitch.switchIfDefaultHasNoLines(
                                    a,
                                    site,
                                    activeVideo[0],
                                    valid,
                                    detailExhaustedHint(a, ui, valid, fallbackTitle),
                                    false,
                                    cardSources);
                    // 提前探测这一轮已经排空了（它先于这次失败结束）：失败分支不再派发，这里直接
                    // 落到「没有可播放线路 + 用户自己点的搜索按钮」，不再跳搜索页。
                    boolean exhaustedBeforeFailure = !switching && probeAheadExhausted[0];
                    if (switching) {
                        // 同样是"还可能有别的来源"：派发了探测就说正在尝试，别先报错。
                        ui.setLoadingStatus(SWITCHING_SOURCE_STATUS);
                    } else if (exhaustedBeforeFailure) {
                        detailExhaustedHint(a, ui, valid, fallbackTitle).run();
                    } else {
                        ui.setLoadingStatus(a.message(error));
                    }
                    // A known alternative is the fastest recovery from a dead source; global
                    // search stays the fallback when the ledger knows only this one.
                    if (!exhaustedBeforeFailure
                            && !ui.showKnownSourceSwitch()
                            && !fallbackTitle.isEmpty()) {
                        ui.setSourceSwitchAction(
                                SEARCH_OTHER_SOURCES_LABEL, () -> a.openSearch(fallbackTitle));
                    }
                    if (ui.switchSourceBtn.getVisibility() == android.view.View.VISIBLE) {
                        ui.switchSourceBtn.requestFocus();
                    }
                };

        if (ready != null) {
            try {
                bindInPlace.accept(ready);
            } catch (Exception e) {
                a.notice("详情数据无法显示，请返回后重试。");
            }
        } else if (initiallyPlayable) {
            if (!a.reduced
                    && (listing.description == null || listing.description.trim().isEmpty())) {
                ui.setLoadingStatus("正在补全详情…");
                a.loadDetail(site, listing.id, bindInPlace, onDetailError);
            }
        } else {
            scheduleSlowSourceHint(a, ui, detailSettled, valid);
            if (cardSources != null
                    && cardSources.count() > 1
                    && !SourcePreferences.netdiskNamed(site)) {
                // 这一跳的等待预算：Guard 站 12 s、普通站 8 s；墙轮在跑（引擎队列忙）时再 ×1.5。
                // 只有"还没落地"（detailSettled=false）才换；已经绑定或已经报错就绝不切 —— 报错那条
                // 走 onDetailError 的立即换源，不在这里重复。
                final Cms.Site firstSite = site;
                final String kind =
                        firstSite != null
                                        && firstSite.key != null
                                        && firstSite.key.startsWith("w2x_")
                                ? "guard"
                                : "normal";
                long waitMs =
                        "guard".equals(kind) ? CARD_FAILOVER_GUARD_MS : CARD_FAILOVER_NORMAL_MS;
                final boolean busy = a.libraryWallLoading;
                if (busy) waitMs = (long) (waitMs * CARD_FAILOVER_BUSY_MULTIPLIER);
                final long budgetMs = waitMs;
                a.ui.postDelayed(
                        () -> {
                            if (!valid.get() || detailSettled[0]) return;
                            AppLog.w(
                                    "TvDetail",
                                    "card failover wait="
                                            + budgetMs
                                            + "ms kind="
                                            + kind
                                            + " busy="
                                            + busy
                                            + " site="
                                            + (firstSite == null ? "-" : firstSite.key));
                            hopCardSource(a, firstSite, listing, cardSources);
                        },
                        budgetMs);
            }
            // 默认源是网盘站时，若它 15 秒还没把详情拿回来（那 31 秒的网盘详情就是这样），就让其它候选
            // 的探测在后台开始，探到能播的就换过去。15 秒内答完的网盘站（原盘/高码率）不受影响：
            // 这条只是挂一个延迟检查，到点发现详情已落地就不探。没命中时失败分支里的
            // switchIfDefaultHasNoLines 仍是兜底（同一条 60 秒记忆，不会重复探同一批站）。
            SourceAutoSwitch.probeAhead(
                    a,
                    site,
                    initialVideo,
                    valid,
                    detailSettled,
                    () -> {
                        if (!valid.get()) return;
                        probeAheadExhausted[0] = true;
                        // 详情已经落地：失败了就把这一轮排空的结论画成"没有可播放线路 + 用户自己点
                        // 的搜索按钮"；已经有可播线路就什么都不用做，别把成功页盖成死路。
                        if (detailSettled[0]) {
                            if (!hasUsableLine(a, site, activeVideo[0])) {
                                detailExhaustedHint(a, ui, valid, initialVideo.title).run();
                            }
                            return;
                        }
                        // 默认源还在加载：这一轮只是"先探的几个都不行"，先给个死路文案，等详情落地
                        // 由失败分支接手（不在这里跳页、也不在这里放搜索按钮）。
                        switchExhaustedHint(ui, valid).run();
                    },
                    false,
                    cardSources);
            a.loadDetail(site, listing.id, bindInPlace, onDetailError);
        }
    }

    /**
     * 探测一轮全灭时的收尾：把「正在尝试其他来源…」换回死路文案（状态行 + 播放按钮）。
     *
     * <p>为什么必须有它：派发探测时页面已经切成过渡态，探完一个能播的都没有时没人收回那句话，
     * 过渡态就成了新的死路。文案全部来自本类的常量，不在这里另写句子；页面已经换掉就不动。
     */
    private static Runnable switchExhaustedHint(TvV17DetailScreen ui, Valid valid) {
        return () -> {
            if (!valid.get()) return;
            ui.setLoadingStatus(NO_PLAYABLE_LINES_STATUS);
            ui.clearSourceSwitchInProgress(NO_PLAYABLE_LINES_PLAY_LABEL);
        };
    }

    /**
     * 详情页探测排空后的收尾：恢复「没有可播放线路」的死路态，并把「搜索其他来源」做成用户自己点的
     * 按钮 —— 只有点下去才 {@link MainActivity#openSearch}，不点就留在详情页。
     *
     * <p>与 {@link #switchExhaustedHint} 的区别只有一个落点：那句提示和那颗按钮只在详情页的失败
     * 分支上用（{@code switchIfDefaultHasNoLines} 与提前探测排空后的失败衔接），不能拿到
     * {@code probeAhead} 的"默认源只是慢"那一步去抢跑。
     */
    /**
     * 墙卡的顺序换源：首选站详情失败时，立刻打开卡片池里下一个「有希望」的站（池子的顺序由
     * {@code HomeScreen.cardSourcePool} 排好：上次播过的 → 非 csp_MacCMS → 其余），并把已失败的站
     * 从池子里去掉再传下去，所以最多跳「池子大小 - 1」次，不会来回弹。
     *
     * <p>只对带卡片池的打开生效（墙卡）；搜索卡、首页海报、收藏没有池子，行为不变。
     *
     * @return true 表示已经跳到下一个站（调用方不要再跑自动换源探测）
     */
    private static boolean hopCardSource(
            MainActivity a, Cms.Site failed, Cms.Video listing, SourceLedger.Sources cardSources) {
        if (a == null || cardSources == null || cardSources.isEmpty()) return false;
        Cms.Site next = null;
        Cms.Video nextListing = null;
        List<Cms.Site> rest = new ArrayList<>();
        java.util.Map<String, Cms.Video> restListings = new java.util.LinkedHashMap<>();
        for (Cms.Site candidate : cardSources.sites) {
            if (candidate == null || candidate.key == null || candidate.key.isEmpty()) continue;
            Cms.Video other = cardSources.listingAt(candidate.key);
            if (other == null || other.id == null || other.id.trim().isEmpty()) continue;
            if (failed != null && candidate.key.equals(failed.key)) continue;
            if (SourceCooling.isCooled(candidate.key)) {
                AppLog.w("TvDetail", "card failover skip cooled key=" + candidate.key);
                continue;
            }
            rest.add(candidate);
            restListings.put(candidate.key, other);
            if (next == null) {
                next = candidate;
                nextListing = other;
            }
        }
        if (next == null || nextListing == null) return false;
        AppLog.w(
                "TvDetail",
                "card failover site="
                        + (failed == null ? "-" : failed.key)
                        + " -> "
                        + next.key
                        + " title="
                        + (listing == null ? "" : listing.title));
        a.openListing(next, nextListing, SourceLedger.candidates(rest, restListings));
        return true;
    }

    private static Runnable detailExhaustedHint(
            MainActivity a, TvV17DetailScreen ui, Valid valid, String title) {
        final String query = title == null ? "" : title.trim();
        return () -> {
            if (!valid.get()) return;
            ui.setLoadingStatus(NO_PLAYABLE_LINES_STATUS);
            ui.clearSourceSwitchInProgress(NO_PLAYABLE_LINES_PLAY_LABEL);
            if (query.isEmpty()) return;
            ui.setSourceSwitchAction(SEARCH_OTHER_SOURCES_LABEL, () -> a.openSearch(query));
            a.toast(EXHAUSTED_SEARCH_HINT);
        };
    }

    /**
     * 「详情绑定了，但一条线路都没有」的收尾：先让自动换源去账本里探别的站，探得到就换，探不到就
     * 停在这条死路上。
     *
     * <p>为什么单独成方法：同一条收尾有两处要走到 —— 进详情时本来就没线路（同步），以及「同站夸克
     * 分享也没捞到」（{@link NetdiskShareLines} 的后台回调）。放在一处，两边的文案和判据不会走偏。
     */
    private static void recoverWithoutLines(
            MainActivity a,
            Cms.Site site,
            Cms.Video resolved,
            TvV17DetailScreen ui,
            Valid valid,
            SourceLedger.Sources cardSources) {
        if (a == null || resolved == null || ui == null) return;
        // The default source bound the title but has no episode: probe the other candidates
        // (the wall card's own sources first, the ledger after) in the background and switch to
        // the first one that really plays. Dispatched before the "no lines" state so that state is
        // what stays when no candidate plays. For a title with two or more ledger sources this
        // probe is also why the curated matching page is skipped
        // (SourceAutoSwitch.prefersProbeOverCurated).
        boolean switching =
                SourceAutoSwitch.switchIfDefaultHasNoLines(
                        a,
                        site,
                        resolved,
                        valid,
                        detailExhaustedHint(a, ui, valid, resolved.title),
                        false,
                        cardSources);
        // 探测派出去了就先说"正在尝试其他来源"，别让那两秒显示成"没有线路"；
        // 没派出去（账本没有别的站）才照旧说没有可播放线路。
        ui.setLoadingStatus(switching ? SWITCHING_SOURCE_STATUS : NO_PLAYABLE_LINES_STATUS);
        if (switching) {
            // 播放按钮同步换成"正在换源…"并保持不可点：点了也播不出来。
            ui.showSourceSwitchInProgress(SWITCHING_SOURCE_PLAY_LABEL);
        }
        String title = resolved.title == null ? "" : resolved.title.trim();
        if (!switching && !title.isEmpty()) {
            // 没有候选可探，这一页就停在死路上：搜索入口留给用户自己点，不自动跳。
            ui.setSourceSwitchAction(SEARCH_OTHER_SOURCES_LABEL, () -> a.openSearch(title));
            a.toast(EXHAUSTED_SEARCH_HINT);
        } else if (!ui.showKnownSourceSwitch() && !title.isEmpty()) {
            // 探测还在跑：先给账本内的切换入口；一轮全灭后由 detailExhaustedHint 换成全站搜索按钮。
            ui.setSourceSwitchAction(SEARCH_OTHER_SOURCES_LABEL, () -> a.openSearch(title));
        }
    }

    /**
     * 把 {@link NetdiskShareLines} 捞到的「夸克分享」并进当前详情并就地重绑：同一个站、同一部片，只
     * 多一条线路，不换站、不重建页面（海报、简介、来源行都不动）。
     *
     * <p>{@code forceDefault} 为真时顺手把页面的 {@link MainActivity.Screen#selection} 指到这条新线路
     * 上：「播放」因此直接走夸克分享，而不是又去试那条播不了的 UC 线路。
     *
     * <p>两个调用点现在都传 true，理由是盒子上实测出来的：即便走的是「详情没有可播线路」那个分支，
     * 片子身上也可能还挂着几条被 {@link LineHealth} 标坏的线路（《两京十五日》虎斑两条 UC、各 2 集），
     * {@code initialSelection} 会照样选中第一条，于是分享线并进去了、选中的却还是播不了的那条。
     * 参数留着，是因为"分享线要不要抢默认"是调用方的决定（卡 §6 情形 2 就是这么来的），不是恒真常量。
     */
    private static void rebindAfterShare(
            MainActivity a,
            Cms.Site site,
            Cms.Video resolved,
            WatchHistory.Entry bookmark,
            TvV17DetailScreen ui,
            Valid valid,
            List<Cms.Line> shareLines,
            boolean forceDefault) {
        if (a == null || resolved == null || ui == null) return;
        if (valid != null && !valid.get()) return;
        int index = NetdiskShareLines.append(resolved, shareLines);
        if (index < 0) return;
        MainActivity.Screen screen = a.current;
        EpisodeSelection selection = screen == null ? null : screen.selection;
        Cms.Line shareLine = resolved.lines.get(index);
        if (forceDefault && selection != null && shareLine != null) {
            // 分享线默认要被选中的前提是它自己没被标坏：百度这类"要验证"的分享会在播放失败后被
            // LineHealth 记成线路问题，这里不能再把它设成默认，否则一点播放又是一次失败。
            String badge = LineHealth.badge(a, site, shareLine);
            boolean shareUsable = badge.isEmpty() || "可用".equals(badge);
            if (shareUsable) {
                selection.select(
                        shareLine.name,
                        0,
                        shareLine.episodes == null ? 0 : shareLine.episodes.size());
            } else {
                for (Cms.Line line : resolved.lines) {
                    if (line == null || line.episodes == null || line.episodes.isEmpty()) continue;
                    String other = LineHealth.badge(a, site, line);
                    if (other.isEmpty() || "可用".equals(other)) {
                        selection.select(line.name, 0, line.episodes.size());
                        break;
                    }
                }
            }
        }
        int[] sel =
                initialSelection(
                        resolved,
                        bookmark,
                        a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                        selection == null ? new EpisodeSelection() : selection,
                        a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false));
        Cms.Line activeLine = resolved.lines.get(sel[0]);
        Cms.Episode activeEpisode = resolveActiveEpisode(activeLine, bookmark);
        String fileCaption = TvV17DetailScreen.formatEpisodeFileCaption(activeLine, activeEpisode);
        ui.bindResolvedVideo(
                site,
                resolved,
                bookmark,
                Favorites.contains(
                        a.prefs.getString(Prefs.FAVORITES, "[]"),
                        site != null ? site.key : "",
                        resolved.id),
                bookmark == null ? "► 开始播放" : "► 继续播放",
                buildEpisodesButtonLabel(resolved, sel[0]),
                fileCaption,
                formatHistoryCaption(a, bookmark, fileCaption));
        ui.setLoadingStatus("");
        bindSeasonPopupDiscovery(a, site, resolved, ui);
        View curFocus = a.getCurrentFocus();
        if (curFocus == null
                || curFocus == ui.favorite
                || curFocus == ui.back
                || (screen != null
                        && ("detail-favorite".equals(screen.focus)
                                || "detail-play".equals(screen.focus)))) {
            if (screen != null) screen.focus = "detail-play";
            ui.play.requestFocus();
        }
        AppLog.w(
                "Detail",
                "share line bound: "
                        + (shareLine == null ? "" : shareLine.name)
                        + " episodes="
                        + (shareLine == null || shareLine.episodes == null
                                ? 0
                                : shareLine.episodes.size())
                        + " default="
                        + forceDefault);
    }

    /**
     * One delayed task: if the lines still have not bound after {@link #SLOW_SOURCE_HINT_MS},
     * append the slow-source hint under the loading line. The loader keeps running; a bind or a
     * failure marks the detail settled, so the hint never fires then (or is replaced in place).
     */
    private static void scheduleSlowSourceHint(
            MainActivity a, TvV17DetailScreen ui, boolean[] settled, Valid valid) {
        a.ui.postDelayed(
                () -> {
                    if (!valid.get() || settled[0]) return;
                    ui.setLoadingStatus("正在获取播放线路…\n" + SLOW_SOURCE_HINT);
                },
                SLOW_SOURCE_HINT_MS);
    }

    /**
     * Opens a source-independent curated Home recommendation: shows the detail page immediately
     * using metadata + official HD landscape poster while resolving playable lines exclusively from
     * the user's already-added sources.
     */
    static void openCurated(MainActivity a, Cms.Video curated) {
        if (a == null
                || curated == null
                || curated.title == null
                || curated.title.trim().isEmpty()) {
            return;
        }
        List<Cms.Site> searchable = collectSearchableSites(a);
        List<Cms.Site> sources = collectUserSearchableSites(a);
        if (sources.isEmpty()) {
            if (!searchable.isEmpty()) {
                // 他有源，只是这些源都只给网盘分享：不能把「网盘站搜不出可播线路」说成「没配源」。
                // 网盘站和采集站是两条不同的播放路径，所以给两条出路：去登录网盘，或换一份片源包。
                new AppDialog.Builder(a)
                        .setTitle("这个来源只提供网盘分享")
                        .setMessage(
                                "当前片源包里能搜到这部剧的都是网盘站，播放需要先登录对应网盘；也可以在「源」里切换含采集站的片源包。")
                        .setPositiveButton(
                                "去网盘登录",
                                (d, w) ->
                                        a.open(
                                                new MainActivity.Screen(
                                                        () -> new NetdiskScreen(a).show()),
                                                true))
                        .setNeutralButton("切换片源包", (d, w) -> SourceSwitchDialog.show(a))
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
            new AppDialog.Builder(a)
                    .setTitle("请先添加片源")
                    .setMessage(
                            "首页热门推荐仅展示近期热剧与热门电影信息，不内置播放资源。\n\n添加您自己的片源后，点击《"
                                    + curated.title.trim()
                                    + "》即可自动从已添加片源中匹配播放。")
                    .setPositiveButton("添加片源", (d, w) -> a.importDialog())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        a.open(new MainActivity.Screen(() -> showCurated(a, curated)), true);
    }

    private static boolean isHeavyRuntimeSite(Cms.Site site) {
        if (site == null) return false;
        String api = site.api == null ? "" : site.api;
        return api.startsWith("catpaw://")
                || DrpyEngine.eligible(site)
                || GuardEngine.eligible(site)
                || JsEngine.eligible(site);
    }

    private static boolean isLightweightSite(Cms.Site site) {
        if (site == null) return false;
        String api = site.api == null ? "" : site.api;
        return site.supported()
                || HttpT4Engine.eligible(site)
                || api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)
                || api.startsWith(AlistEngine.PREFIX);
    }

    /**
     * Bounds automatic curated-home background matching so low-RAM Android TVs never fan out into
     * 16 simultaneous Node/WebView/CSP plugin runtimes on a single poster click.
     */
    static List<Cms.Site> selectCuratedSearchSources(
            List<Cms.Site> prioritizedSources, String selectedSiteKey, int maxCount) {
        List<Cms.Site> out = new ArrayList<>();
        if (prioritizedSources == null || prioritizedSources.isEmpty() || maxCount <= 0) return out;
        List<Cms.Site> selectedTier = new ArrayList<>();
        List<Cms.Site> lightTier = new ArrayList<>();
        List<Cms.Site> standardTier = new ArrayList<>();
        List<Cms.Site> heavyFallbackTier = new ArrayList<>();
        for (Cms.Site site : prioritizedSources) {
            if (site == null || site.key == null) continue;
            if (SearchCoordinator.isSiteWedged(site.key)) continue;
            boolean isSelected = selectedSiteKey != null && selectedSiteKey.equals(site.key);
            if (isSelected) {
                selectedTier.add(site);
            } else if (isLightweightSite(site)) {
                lightTier.add(site);
            } else if (isHeavyRuntimeSite(site)) {
                heavyFallbackTier.add(site);
            } else {
                standardTier.add(site);
            }
        }
        for (Cms.Site s : selectedTier) {
            if (out.size() < maxCount) out.add(s);
        }
        for (Cms.Site s : lightTier) {
            if (out.size() < maxCount) out.add(s);
        }
        for (Cms.Site s : standardTier) {
            if (out.size() < maxCount) out.add(s);
        }
        if (out.isEmpty() && !heavyFallbackTier.isEmpty()) {
            out.add(heavyFallbackTier.get(0));
        }
        return out;
    }

    /**
     * Last resort for a curated page whose search found nothing: the ledger may still hold a
     * playable listing for this title (the wall or the search page learned it earlier). Opening it
     * is strictly better than telling the user there is no source. Returns true when it took over.
     */
    private static boolean openLedgerListing(
            MainActivity a,
            Cms.Video curated,
            List<MatchedSource> matches,
            MatchedSource[] pendingPick,
            Runnable triggerLoadMatched) {
        if (a == null || curated == null || pendingPick == null || triggerLoadMatched == null) {
            return false;
        }
        SourceLedger.Sources known = SourceLedger.resolve(a, curated);
        if (known.defaultSite == null || known.defaultListing == null) return false;
        MatchedSource picked =
                new MatchedSource(known.defaultSite, known.defaultListing, 100);
        matches.add(picked);
        pendingPick[0] = picked;
        triggerLoadMatched.run();
        return true;
    }

    /**
     * Every site the user configured that can be searched, netdisk ones included. {@link
     * #collectUserSearchableSites} is this list minus the share-style sites, and the difference
     * lets {@link #openCurated} tell "has no sources" apart from "has only netdisk sources".
     */
    private static List<Cms.Site> collectSearchableSites(MainActivity a) {
        List<Cms.Site> sources = new ArrayList<>();
        if (a == null || a.config == null || a.config.sites == null) return sources;
        for (Cms.Site site : SourcePreferences.prioritized(a, a.config.sites)) {
            if (site == null || !site.searchable || !a.available(site)) continue;
            sources.add(site);
        }
        return sources;
    }

    private static List<Cms.Site> collectUserSearchableSites(MainActivity a) {
        List<Cms.Site> sources = new ArrayList<>();
        if (a == null) return sources;
        for (Cms.Site site : collectSearchableSites(a)) {
            if (SourceController.isConfigOrNetdiskSite(site)) continue;
            // Share-style sites ("…网盘") hand back a share id that still needs transferring, so
            // they cannot answer a match with a stream that plays now: searching them only spends
            // the budget to hear "no lines". They stay in 换站观看 and the automatic source switch;
            // this list is only about what gets a slice of the match budget.
            if (SourcePreferences.netdiskNamed(site)) continue;
            sources.add(site);
        }
        final String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
        Collections.sort(
                sources,
                (x, y) ->
                        Boolean.compare(
                                !Objects.equals(x.key, selected),
                                !Objects.equals(y.key, selected)));
        return sources;
    }

    private static void showCurated(MainActivity a, Cms.Video curated) {
        // The match budget starts with the page itself, before anything is read or drawn.
        final long started = android.os.SystemClock.elapsedRealtime();
        final String query = curated.title == null ? "" : curated.title.trim();
        a.renderPage(query.isEmpty() ? "热门推荐" : query, "");
        final MainActivity.Screen screen = a.current;
        final int ticket = a.generation;
        final TvSearchContext context = new TvSearchContext(a.config, a.privateMode, a.engineEpoch);
        final Valid valid =
                () ->
                        a.current == screen
                                && ticket == a.generation
                                && !a.isFinishing()
                                && context.matches(a.config, a.privateMode, a.engineEpoch);

        final List<Cms.Site> allSources = collectUserSearchableSites(a);
        final String selectedKey = a.prefs.getString(Prefs.SELECTED_SITE, "");
        final List<Cms.Site> targetSources =
                selectCuratedSearchSources(allSources, selectedKey, CURATED_MAX_SOURCES);
        final MatchTiming timing = new MatchTiming(started, targetSources.size());

        // One ledger read decides where this recommendation opens. A curated card carries only
        // metadata, so it can only open a source somebody already wrote down: the wall round, the
        // search page, or an earlier match. When that default is a netdisk site there is nothing to
        // search — open it and list the ledger's other sites in 换站观看. Otherwise fall through to
        // the bounded cross-site search and write every hit back into the ledger, so the wall card
        // and this page cannot end up naming different sources for the same show.
        final SourceLedger.Sources known = SourceLedger.resolve(a, curated);
        final Cms.Site ledgerSource = known.defaultSite;
        final Cms.Video ledgerListing = known.defaultListing;
        final boolean openFromLedger =
                ledgerSource != null
                        && ledgerListing != null
                        && HomeScreen.siteRank(ledgerSource) == 0;

        final Cms.Site[] activeSite = {TvCuratedHome.curatedSite()};
        final Cms.Video[] activeVideo = {curated};
        final WatchHistory.Entry[] activeBookmark = {null};
        final boolean[] playbackStarted = {false};
        final List<MatchedSource> matches = new ArrayList<>();
        final boolean[] detailBound = {false};
        final boolean[] detailLoading = {false};
        final int[] finishedCount = {0};
        final SearchCoordinator[] coordinatorRef = new SearchCoordinator[1];
        final Runnable closeCoordinator =
                () -> {
                    SearchCoordinator c = coordinatorRef[0];
                    if (c != null) c.close();
                };

        final Cms.Site primarySource =
                openFromLedger
                        ? ledgerSource
                        : (targetSources.isEmpty() ? ledgerSource : targetSources.get(0));
        final List<Cms.Site> fallbackSources =
                targetSources.size() > 1
                        ? new ArrayList<>(targetSources.subList(1, targetSources.size()))
                        : Collections.emptyList();
        final boolean[] fallbackStarted = {false};
        final boolean[] budgetExpired = {false};
        final Runnable[] startFallbackSearchRef = new Runnable[1];

        final TvV17DetailScreen[] holder = new TvV17DetailScreen[1];

        TvV17DetailScreen ui =
                new TvV17DetailScreen(
                        a,
                        activeSite[0],
                        curated,
                        null,
                        false,
                        false,
                        "正在匹配片源…",
                        "☰ 选集",
                        "",
                        "",
                        new TvV17DetailScreen.Actions() {
                            public void back() {
                                closeCoordinator.run();
                                if (valid.get()) a.onBackPressed();
                            }

                            public void play() {
                                closeCoordinator.run();
                                Cms.Video v = activeVideo[0];
                                Cms.Site s = activeSite[0];
                                if (!valid.get() || TvCuratedHome.isCuratedSite(s)) return;
                                if (!hasPlayableLines(v)) {
                                    // Same backstop as the ledger page: no silent dead press.
                                    TvV17DetailScreen page = holder[0];
                                    a.notice(
                                            page != null && page.showKnownSourceSwitch()
                                                    ? "当前来源没有可播放线路，请按「切换来源」换一个"
                                                    : "当前来源没有可播放线路");
                                    return;
                                }
                                playbackStarted[0] = true;
                                WatchHistory.Entry bm = activeBookmark[0];
                                int[] sel =
                                        initialSelection(
                                                v,
                                                bm,
                                                a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                                                screen.selection,
                                                a.prefs.getBoolean(
                                                        Prefs.EPISODES_DESCENDING, false));
                                if (bm != null
                                        && (!screen.selection.selected
                                                || Objects.equals(
                                                        v.lines.get(sel[0]).name, bm.line))) {
                                    a.playBookmark(s, v, bm);
                                } else {
                                    Cms.Line line = v.lines.get(sel[0]);
                                    if (line != null
                                            && line.episodes != null
                                            && !line.episodes.isEmpty()) {
                                        a.resolvePlayer(s, v, line, line.episodes.get(0));
                                    }
                                }
                            }

                            public void episodes() {
                                closeCoordinator.run();
                                if (!valid.get() || TvCuratedHome.isCuratedSite(activeSite[0])) {
                                    return;
                                }
                                showEpisodesPopup(
                                        a,
                                        activeSite[0],
                                        activeVideo[0],
                                        activeBookmark[0],
                                        screen,
                                        holder[0],
                                        null);
                            }

                            public void favorite() {
                                if (!valid.get()) return;
                                if (TvCuratedHome.isCuratedSite(activeSite[0])) {
                                    a.toast("正在匹配可播放片源，稍后即可收藏");
                                    return;
                                }
                                holder[0].setFavorite(
                                        DetailScreen.toggleFavorite(
                                                a, activeSite[0], activeVideo[0]));
                            }

                            public void synopsis() {
                                if (valid.get()) {
                                    new AppDialog.Builder(a)
                                            .setTitle(activeVideo[0].title)
                                            .setMessage(
                                                    DetailScreen.buildFullSynopsisMessage(
                                                            activeVideo[0]))
                                            .setPositiveButton("关闭", null)
                                            .show();
                                }
                            }
                        });
        holder[0] = ui;
        ui.setScrapeAction(
                () -> {
                    if (!valid.get() || TvCuratedHome.isCuratedSite(activeSite[0])) {
                        a.toast("正在匹配可播放片源，稍后即可刮削剧集");
                        return;
                    }
                    Cms.Video v = activeVideo[0];
                    Cms.Line curLine = hasPlayableLines(v) ? v.lines.get(0) : null;
                    TvSeriesScrapeDialog.show(
                            a,
                            activeSite[0],
                            v,
                            curLine,
                            () -> {
                                if (!valid.get()) return;
                                SeriesScrapeStore.OverrideRecord ov =
                                        SeriesScrapeStore.load(
                                                a,
                                                activeSite[0] != null ? activeSite[0].key : "",
                                                v.id,
                                                v.title);
                                if (ov != null) TvSeriesScrapeDialog.applyOverrideToVideo(v, ov);
                                ui.bindResolvedVideo(
                                        activeSite[0],
                                        v,
                                        activeBookmark[0],
                                        Favorites.contains(
                                                a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                activeSite[0].key,
                                                v.id),
                                        activeBookmark[0] == null ? "► 开始播放" : "► 继续播放",
                                        buildEpisodesButtonLabel(v, 0),
                                        TvV17DetailScreen.formatEpisodeFileCaption(
                                                curLine,
                                                resolveActiveEpisode(curLine, activeBookmark[0])),
                                        "");
                            });
                });
        ui.setLoadingStatus(
                primarySource != null ? ("正在从「" + primarySource.name + "」匹配资源…") : "正在匹配片源…");
        ui.setSourceSwitchAction(
                "🔍 全站搜索",
                () -> {
                    closeCoordinator.run();
                    a.openSearch(query);
                });

        a.root.removeView(a.scroll);
        a.root.addView(ui.view, new FrameLayout.LayoutParams(-1, -1));
        a.scroll = ui.scroll;
        a.body = ui.content;
        screen.focus = "detail-switch-source";
        a.restore(screen);

        // Hard budget for this match. Once it is spent the page stops searching and says so,
        // instead of leaving the viewer on a spinning line for a show the ledger does not know. It
        // only speaks while nothing has bound and no line fetch is in flight, so a slow source
        // (the slow-source hint's job) is never cut off here. Sites matched so far stay in the
        // ledger and in the 换站观看 list; only the search stops.
        final Runnable checkBudget =
                () -> {
                    if (!valid.get()
                            || detailBound[0]
                            || detailLoading[0]
                            || budgetExpired[0]
                            || android.os.SystemClock.elapsedRealtime() - started
                                    < CURATED_MATCH_BUDGET_MS) {
                        return;
                    }
                    budgetExpired[0] = true;
                    closeCoordinator.run();
                    timing.log("budget");
                    ui.refreshKnownSourcesRow();
                    ui.play.setText("暂未匹配片源");
                    ui.setLoadingStatus(CURATED_MATCH_BUDGET_MESSAGE);
                    ui.setSourceSwitchAction(
                            "🔍 全站搜索",
                            () -> {
                                closeCoordinator.run();
                                a.openSearch(query);
                            });
                    ui.switchSourceBtn.requestFocus();
                };
        a.ui.postDelayed(checkBudget, CURATED_MATCH_BUDGET_MS);

        final MainActivity.Done<MatchedSource> loadMatched =
                new MainActivity.Done<MatchedSource>() {
                    @Override
                    public void accept(MatchedSource picked) {
                        if (!valid.get() || picked == null || budgetExpired[0]) return;
                        timing.firstMatch();
                        // A cross-site match is a ledger write even when it only ends up as a
                        // 换站观看 entry; that is how the wall card learns this site too.
                        SourceLedger.remember(a, picked.site, picked.listing);
                        detailLoading[0] = true;
                        ui.setLoadingStatus("正在从「" + picked.site.name + "」加载播放线路…");
                        MainActivity.Done<Cms.Video> applyLoaded =
                                loaded -> {
                                    if (!valid.get() || loaded == null) return;
                                    detailLoading[0] = false;
                                    mergeListingFallback(loaded, picked.listing);
                                    mergeListingFallback(loaded, curated);
                                    if (!hasPlayableLines(loaded)) {
                                        // Find next matched source safely without mutating during
                                        // loop
                                        MatchedSource nextAlt = null;
                                        for (MatchedSource alt : matches) {
                                            if (alt != picked
                                                    && !Objects.equals(
                                                            alt.site.key, picked.site.key)) {
                                                nextAlt = alt;
                                                break;
                                            }
                                        }
                                        matches.remove(picked);
                                        if (nextAlt != null) {
                                            accept(nextAlt);
                                            return;
                                        }
                                        if (!detailBound[0]) {
                                            // The budget may have run out while this load was in
                                            // flight: then say that, instead of starting another
                                            // round of searching nobody asked for.
                                            checkBudget.run();
                                            if (budgetExpired[0]) return;
                                            if (!fallbackStarted[0]
                                                    && startFallbackSearchRef[0] != null) {
                                                startFallbackSearchRef[0].run();
                                                return;
                                            }
                                            timing.log("nosource");
                                            ui.setLoadingStatus("已匹配片源暂无可播放线路，可点击「全站搜索」查找更多来源");
                                        }
                                        return;
                                    }
                                    detailBound[0] = true;
                                    closeCoordinator.run();
                                    timing.firstPlayable();
                                    timing.log("bound");
                                    activeSite[0] = picked.site;
                                    activeVideo[0] = loaded;
                                    WatchHistory.Entry bm = findBookmark(a, picked.site, loaded);
                                    activeBookmark[0] = bm;
                                    int[] sel =
                                            initialSelection(
                                                    loaded,
                                                    bm,
                                                    a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                                                    screen.selection,
                                                    a.prefs.getBoolean(
                                                            Prefs.EPISODES_DESCENDING, false));
                                    Cms.Line line = loaded.lines.get(sel[0]);
                                    Cms.Episode ep = resolveActiveEpisode(line, bm);
                                    String fileCap =
                                            TvV17DetailScreen.formatEpisodeFileCaption(line, ep);
                                    String histCap = formatHistoryCaption(a, bm, fileCap);
                                    ui.bindResolvedVideo(
                                            picked.site,
                                            loaded,
                                            bm,
                                            Favorites.contains(
                                                    a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                    picked.site.key,
                                                    loaded.id),
                                            bm == null ? "► 开始播放" : "► 继续播放",
                                            buildEpisodesButtonLabel(loaded, sel[0]),
                                            fileCap,
                                            histCap);
                                    ui.setLoadingStatus("");
                                    {
                                        // The switch list is the ledger's, never the search hits':
                                        // it must match the wall card's badge, the "来自 N 个站点"
                                        // row and this page's own default source.
                                        final java.util.List<Object[]> merged =
                                                SourceLedger.resolve(a, loaded).withListings();
                                        ui.setSourceSwitchAction(
                                                merged.size() > 1
                                                        ? "⇄ 切换来源 (" + merged.size() + ")"
                                                        : "🔍 更多来源",
                                                () -> {
                                                    if (merged.size() > 1) {
                                                        String[] names = new String[merged.size()];
                                                        for (int i = 0; i < merged.size(); i++) {
                                                            Cms.Site s2 = (Cms.Site) merged.get(i)[0];
                                                            String mark =
                                                                    activeSite[0] != null
                                                                                    && s2.key.equals(
                                                                                            activeSite[0]
                                                                                                    .key)
                                                                            ? "（当前）"
                                                                            : "";
                                                            names[i] =
                                                                    s2.name
                                                                            + mark
                                                                            + (i == 0
                                                                                    ? " · 网盘优先"
                                                                                    : "");
                                                        }
                                                        new AppDialog.Builder(a)
                                                                .setTitle("切换来源")
                                                                .setItems(
                                                                        names,
                                                                        (d, which) -> {
                                                                            Cms.Site target =
                                                                                    (Cms.Site)
                                                                                            merged.get(
                                                                                                            which)
                                                                                                    [0];
                                                                            Cms.Video listing =
                                                                                    (Cms.Video)
                                                                                            merged.get(
                                                                                                            which)
                                                                                                    [1];
                                                                            closeCoordinator.run();
                                                                            a.detail(target, listing);
                                                                        })
                                                                .setNegativeButton("取消", null)
                                                                .show();
                                                        return;
                                                    }
                                                    closeCoordinator.run();
                                                    a.openSearch(query);
                                                });
                                    }
                                    bindSeasonPopupDiscovery(a, picked.site, loaded, ui);
                                    screen.focus = "detail-play";
                                    ui.play.requestFocus();
                                };
                        if (hasPlayableLines(picked.listing)) {
                            try {
                                applyLoaded.accept(picked.listing);
                            } catch (Exception ignored) {
                            }
                        } else {
                            a.loadDetail(picked.site, picked.listing.id, applyLoaded);
                        }
                    }
                };
        final Runnable[] triggerLoadMatched = new Runnable[1];
        final MatchedSource[] pendingPick = new MatchedSource[1];
        triggerLoadMatched[0] =
                () -> {
                    try {
                        loadMatched.accept(pendingPick[0]);
                    } catch (Exception ignored) {
                    }
                };

        // Before playback starts, a background match may make the ledger's default source better
        // than the one this page opened with (an ordinary site -> the netdisk site the wall's own
        // listing sample missed). Adopt it, so "当前来源" equals what every card would open.
        ui.setSourceUpgradeAction(
                () -> {
                    if (!valid.get()
                            || !detailBound[0]
                            || detailLoading[0]
                            || playbackStarted[0]
                            || activeSite[0] == null) {
                        return;
                    }
                    SourceLedger.Sources now = SourceLedger.resolve(a, curated);
                    if (now.defaultSite == null || now.defaultListing == null) return;
                    if (now.defaultSite.key.equals(activeSite[0].key)) return;
                    if (HomeScreen.siteRank(now.defaultSite) >= HomeScreen.siteRank(activeSite[0])) {
                        return;
                    }
                    MatchedSource better =
                            new MatchedSource(now.defaultSite, now.defaultListing, 100);
                    matches.add(better);
                    pendingPick[0] = better;
                    triggerLoadMatched[0].run();
                });

        if (primarySource == null) {
            timing.log("nosource");
            ui.play.setText("暂未匹配片源");
            ui.setLoadingStatus("当前可用片源繁忙，可点击「全站搜索」手动查找");
            ui.setSourceSwitchAction("🔍 全站搜索", () -> a.openSearch(query));
            ui.switchSourceBtn.requestFocus();
            return;
        }
        ui.view.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {}

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        closeCoordinator.run();
                    }
                });

        startFallbackSearchRef[0] =
                () -> {
                    if (!valid.get() || detailBound[0] || fallbackStarted[0] || budgetExpired[0]) {
                        return;
                    }
                    checkBudget.run();
                    if (budgetExpired[0]) return;
                    fallbackStarted[0] = true;
                    if (fallbackSources.isEmpty()) {
                        if (openLedgerListing(
                                a, curated, matches, pendingPick, triggerLoadMatched[0])) {
                            return;
                        }
                        timing.log("nosource");
                        ui.play.setText("暂未匹配片源");
                        ui.setLoadingStatus("已添加的片源中暂未找到《" + query + "》，可点击「全站搜索」手动查找");
                        ui.setSourceSwitchAction("🔍 全站搜索", () -> a.openSearch(query));
                        ui.switchSourceBtn.requestFocus();
                        return;
                    }
                    ui.setLoadingStatus("正在从其余 " + fallbackSources.size() + " 个常用片源依次匹配…");
                    SearchCoordinator.configureThreads(a.prefs.getInt(Prefs.SEARCH_THREADS, 6));
                    final SearchCoordinator coordinator =
                            new SearchCoordinator(
                                    (site, q) -> {
                                        SourceEngine eng =
                                                SourceController.engine(a, site, context);
                                        return Cms.listing(eng.search(q, 1)).videos;
                                    },
                                    (site, videos, outcome) ->
                                            a.ui.post(
                                                    () -> {
                                                        if (!valid.get()
                                                                || detailBound[0]
                                                                || budgetExpired[0]) {
                                                            return;
                                                        }
                                                        checkBudget.run();
                                                        if (budgetExpired[0]) return;
                                                        finishedCount[0]++;
                                                        if (videos != null && !videos.isEmpty()) {
                                                            Cms.Video bestCand = null;
                                                            int bestScore = 39;
                                                            for (Cms.Video cand : videos) {
                                                                int sc =
                                                                        TvCuratedHome
                                                                                .scoreSourceMatch(
                                                                                        curated,
                                                                                        cand);
                                                                if (sc > bestScore) {
                                                                    bestScore = sc;
                                                                    bestCand = cand;
                                                                }
                                                            }
                                                            if (bestCand != null) {
                                                                // Write before it becomes the
                                                                // default: the detail row and the
                                                                // wall badge must read the same list.
                                                                SourceLedger.remember(a, site, bestCand);
                                                                matches.add(
                                                                        new MatchedSource(
                                                                                site, bestCand,
                                                                                bestScore));
                                                                Collections.sort(
                                                                        matches,
                                                                        (x, y) ->
                                                                                Integer.compare(
                                                                                        y.score,
                                                                                        x.score));
                                                                if (!detailBound[0]
                                                                        && !detailLoading[0]) {
                                                                    pendingPick[0] = matches.get(0);
                                                                    triggerLoadMatched[0].run();
                                                                    return;
                                                                }
                                                            }
                                                        }
                                                        if (!detailBound[0]
                                                                && !detailLoading[0]
                                                                && finishedCount[0]
                                                                        >= fallbackSources.size()) {
                                                            if (!matches.isEmpty()) {
                                                                pendingPick[0] = matches.get(0);
                                                                triggerLoadMatched[0].run();
                                                            } else if (!openLedgerListing(
                                                                    a,
                                                                    curated,
                                                                    matches,
                                                                    pendingPick,
                                                                    triggerLoadMatched[0])) {
                                                                timing.log("nosource");
                                                                ui.play.setText("暂未匹配片源");
                                                                ui.setLoadingStatus(
                                                                        "已添加的片源中暂未找到《"
                                                                                + query
                                                                                + "》，可点击「全站搜索」手动查找");
                                                                ui.setSourceSwitchAction(
                                                                        "🔍 全站搜索",
                                                                        () -> a.openSearch(query));
                                                                ui.switchSourceBtn.requestFocus();
                                                            }
                                                        }
                                                    }),
                                    6000,
                                    14000,
                                    8,
                                    CURATED_MAX_CONCURRENCY);
                    coordinatorRef[0] = coordinator;
                    coordinator.start(fallbackSources, query);
                };

        if (openFromLedger) {
            // The ledger knows a netdisk site for this title: open it immediately and let the
            // switch list show the ledger's other sources. No per-open cross-site search, so the
            // wall card and this page cannot disagree about which source is the default.
            MatchedSource picked = new MatchedSource(ledgerSource, ledgerListing, 100);
            matches.add(picked);
            pendingPick[0] = picked;
            triggerLoadMatched[0].run();
            return;
        }

        // Phase 1: Search ONLY the user's primary/selected source first so clicking a show on
        // low-RAM TVs never fans out into extra spider JARs / Node / WebView runtimes
        // unnecessarily.
        a.submitPage(
                () -> {
                    Cms.Video bestCand = null;
                    int bestScore = 39;
                    try {
                        SourceEngine eng = SourceController.engine(a, primarySource, context);
                        List<Cms.Video> videos = Cms.listing(eng.search(query, 1)).videos;
                        if (videos != null) {
                            for (Cms.Video cand : videos) {
                                int sc = TvCuratedHome.scoreSourceMatch(curated, cand);
                                if (sc > bestScore) {
                                    bestScore = sc;
                                    bestCand = cand;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    final Cms.Video found = bestCand;
                    final int foundScore = bestScore;
                    a.ui.post(
                            () -> {
                                if (!valid.get() || detailBound[0] || budgetExpired[0]) return;
                                checkBudget.run();
                                if (budgetExpired[0]) return;
                                if (found != null) {
                                    timing.firstMatch();
                                    SourceLedger.remember(a, primarySource, found);
                                    MatchedSource picked =
                                            new MatchedSource(primarySource, found, foundScore);
                                    matches.add(picked);
                                    pendingPick[0] = picked;
                                    triggerLoadMatched[0].run();
                                } else {
                                    startFallbackSearchRef[0].run();
                                }
                            });
                });
    }
}
