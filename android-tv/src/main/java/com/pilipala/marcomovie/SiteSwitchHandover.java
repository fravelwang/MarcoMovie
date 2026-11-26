package com.pilipala.marcomovie;

import java.util.Objects;

/**
 * 换站之后「从第几集接着看」的交接凭据。
 *
 * <p>为什么需要它：换站走 {@link MainActivity#openListing}，目标站是一个新页面，选集状态
 * （{@link MainActivity.Screen#selection}）是新的、书签是按 {@code (站, 影片 id)} 取的。上一个站
 * 看到第 13 集，那个"13"到了目标站既不是线路下标也不是集下标，按线路名更对不上；结果要么从第 1
 * 集重开，要么撞上目标站自己很久以前留下的旧书签。跨站真正有意义的量只有一个：**第 N 集**。
 * 所以这里只记它，落位时按目标站自己的规则重算。
 *
 * <p>三条口径（任务卡 2026-11-26 第 2 节）：
 *
 * <ul>
 *   <li>线路：只用目标站自己的选择规则（{@link TvDetailHost#initialSelection}）。{@link #lineName}
 *       只用于提示和日志，从不拿去目标站按名字匹配 —— 那是「沿用上一站」的来源。
 *   <li>集：先按集号对上（{@link TvSeriesScraper#extractEpisodeNumber}，所以「第05集」和「第5集」
 *       是同一集）；对不上再试集名；都没有就从这条线路的第 1 集开始。
 *   <li>书签：只有在片名对得上（{@link #sameTitle}）、且那一集在目标站真的存在时才算数；交接口径
 *       与历史续播一致，判定仍在 {@link HistoryResumeMatcher#matchEpisode}。
 * </ul>
 *
 * <p>同一个时刻只留一份凭据：一次换站只跳一次，页面绑好（{@link #take}）即消费，过期自动作废，
 * 免得第二次进同一部片的详情还按上一站的集号落位。
 */
final class SiteSwitchHandover {
    /** 换站凭据的有效期：换站是立刻发生的，目标页绑定超过这个时间就不该再按旧集号落位。 */
    static final long TTL_MS = 120000L;

    /** 一次换站的交接凭据。字段不可变，过期与匹配由持有方判。 */
    static final class Want {
        /** 来源片名（规范化后比对）：换成了别的片就不算数。 */
        final String title;

        /** 目标站 key。 */
        final String siteKey;

        /** 目标站的影片 id；为空时只按片名认。 */
        final String videoId;

        /** 第 N 集；0 表示来源站也没有可用的集号（例如整片还没开播过）。 */
        final int episodeNumber;

        /** 出处集名，集号对不上时用它再找一次。 */
        final String episodeName;

        /** 来源线路名：只用于提示与日志，绝不拿去目标站按名匹配。 */
        final String lineName;

        final long at;

        Want(
                String title,
                String siteKey,
                String videoId,
                int episodeNumber,
                String episodeName,
                String lineName,
                long at) {
            this.title = title == null ? "" : title;
            this.siteKey = siteKey == null ? "" : siteKey;
            this.videoId = videoId == null ? "" : videoId;
            this.episodeNumber = episodeNumber;
            this.episodeName = episodeName == null ? "" : episodeName;
            this.lineName = lineName == null ? "" : lineName;
            this.at = at;
        }

        boolean hasEpisode() {
            return episodeNumber > 0 || !episodeName.isEmpty();
        }
    }

    private Want pending;

    /** 记下一份凭据（覆盖旧的：只跳一次，后来的那次换站才是用户要的）。 */
    void remember(Want want) {
        pending = want != null && want.hasEpisode() ? want : null;
    }

    /** 当前待用的凭据；不消费，供调用方判"这部片这次是不是换站进来的"。 */
    Want peek(Cms.Site site, Cms.Video video) {
        if (pending == null) return null;
        if (android.os.SystemClock.elapsedRealtime() - pending.at > TTL_MS) {
            pending = null;
            return null;
        }
        return matches(pending, site, video) ? pending : null;
    }

    /** 取走这部片这次的凭据：命中即消费，后面的页面不再重复按旧集号落位。 */
    Want take(Cms.Site site, Cms.Video video) {
        Want want = peek(site, video);
        if (want != null) pending = null;
        return want;
    }

    void clear() {
        pending = null;
    }

    /**
     * 目标页是不是这份凭据说的那个页面：站 key 必须相同；影片 id 有就比 id，没有就比片名。
     *
     * <p>为什么不只比片名：换站时目标站的 listing id 已经拿在手上（{@code SourceLedger} 给的），
     * 比 id 能挡住"同名不同片"；只有 id 缺失（例如跨站续播只拿到片名）时才退回片名比对。
     */
    static boolean matches(Want want, Cms.Site site, Cms.Video video) {
        if (want == null || site == null || video == null) return false;
        if (site.key == null || !want.siteKey.equals(site.key)) return false;
        String videoId = video.id == null ? "" : video.id.trim();
        if (!want.videoId.isEmpty() && !videoId.isEmpty()) {
            return want.videoId.equals(videoId);
        }
        return sameTitle(want.title, video.title);
    }

    /**
     * 从来源侧的三样东西里拼出凭据：明确的书签优先，其次当前在播的集。来源站没有可用的集时返回
     * null —— 「换站顺手换成第 1 集」比「按错的集号落位」更安全。
     */
    static Want of(
            Cms.Site target,
            Cms.Video targetListing,
            Cms.Video fromVideo,
            Cms.Line fromLine,
            Cms.Episode fromEpisode,
            WatchHistory.Entry fromBookmark) {
        if (target == null || target.key == null) return null;
        String title =
                fromVideo != null && fromVideo.title != null
                        ? fromVideo.title
                        : (fromBookmark != null && fromBookmark.title != null
                                ? fromBookmark.title
                                : "");
        String lineName = "";
        String episodeName = "";
        int number = 0;
        if (fromBookmark != null) {
            episodeName = fromBookmark.episode == null ? "" : fromBookmark.episode;
            number = TvSeriesScraper.extractEpisodeNumber(episodeName);
            lineName = fromBookmark.line == null ? "" : fromBookmark.line;
        }
        if (number <= 0 && episodeName.isEmpty() && fromEpisode != null) {
            episodeName = fromEpisode.name == null ? "" : fromEpisode.name;
            number = TvSeriesScraper.extractEpisodeNumber(episodeName);
            lineName = fromLine == null || fromLine.name == null ? "" : fromLine.name;
        }
        return new Want(
                HistoryResumeMatcher.normalizeTitle(title),
                target.key,
                targetListing == null ? "" : targetListing.id,
                number,
                episodeName,
                lineName,
                android.os.SystemClock.elapsedRealtime());
    }

    /**
     * 在目标线路里找交接的那一集：先集号，再集名；找不到返回 null，调用方按「从第 1 集开始」处理。
     *
     * <p>两站集数不同正是这里要解决的：虎斑只有第 13/14 集，天堂有 14 集，集号对得上就接着看
     * 第 13 集；对不上（例如目标站只有 10 集）就从第 1 集开始，绝不拿下标硬套。
     */
    static Cms.Episode resolve(Cms.Line line, Want want) {
        if (line == null || line.episodes == null || line.episodes.isEmpty() || want == null) {
            return null;
        }
        if (want.episodeNumber > 0) {
            for (Cms.Episode ep : line.episodes) {
                if (ep != null && TvSeriesScraper.extractEpisodeNumber(ep.name) == want.episodeNumber) {
                    return ep;
                }
            }
        }
        if (!want.episodeName.isEmpty()) {
            for (Cms.Episode ep : line.episodes) {
                if (ep != null && want.episodeName.equals(ep.name)) return ep;
            }
        }
        return null;
    }

    /**
     * 片名是不是同一部：两边规范化后相同，或一边包含另一边且长度差不大（与历史续播的
     * {@link HistoryResumeMatcher#scoreTitleMatch} 同一套宽松度，「两京十五日」和
     * 「两京十五日 更新至14集」算同一部）。
     *
     * <p>季号是硬条件：两边都看得出季号、且季号不同时一律不算同一部 ——「第二季第 3 集」不能搬到
     * 「第一季第 3 集」上，集号相同也不代表是同一集。
     */
    static boolean sameTitle(String a, String b) {
        String left = HistoryResumeMatcher.normalizeTitle(a);
        String right = HistoryResumeMatcher.normalizeTitle(b);
        if (left.isEmpty() || right.isEmpty()) return false;
        int leftSeason = HistoryResumeMatcher.extractSeasonNumber(a);
        int rightSeason = HistoryResumeMatcher.extractSeasonNumber(b);
        if (leftSeason > 0 && rightSeason > 0 && leftSeason != rightSeason) return false;
        if (Objects.equals(left, right)) return true;
        if (left.contains(right) || right.contains(left)) {
            return Math.abs(left.length() - right.length()) <= 4;
        }
        return false;
    }
}
