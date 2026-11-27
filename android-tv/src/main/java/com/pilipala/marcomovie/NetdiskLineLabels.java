package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网盘线路的展示标记：站点把视频改名成音频（{@code .mp3/.m4a/.flac/.wav/.ape}）时，这条线路在
 * 选线路列表里标成「夸克原画 · 音频」并排到视频线路之后，整部片只有音频线路时给一句提示。
 *
 * <p>只动展示用的 {@code Cms.Line.name} 与列表顺序，不动 episode url/解析：解析依旧按 Guard id 走，
 * 播放失败时的原因也仍是我们自己的那句真话（站点描述里的「温馨提示」不参与）。
 */
final class NetdiskLineLabels {

    static final String SUFFIX = " · 音频";

    /** 逐集标记：音频集在选集列表里的后缀。 */
    static final String EPISODE_AUDIO_SUFFIX = " · 音频";

    /** 逐集标记：压缩包集在选集列表里的后缀。 */
    static final String EPISODE_ARCHIVE_SUFFIX = " · 压缩包";

    private NetdiskLineLabels() {}

    /**
     * 这一集的真实文件名。
     *
     * <p>Guard 线路的 episode url 里带着内层 {@code e}/{@code b}（真文件名），优先用它；其它网盘
     * 分享线的 episode 名本身就是分享里的文件名。站点有时在文件名后面再挂一段「【LIANGJIN15-RI半月】」
     * 这类装饰，{@link #trimTrailingDecoration} 只把扩展名后面的这段尾巴去掉。
     */
    static String episodeFileName(Cms.Episode episode) {
        if (episode == null) return "";
        GuardSpiderId guard = GuardSpiderId.decode(episode.url);
        if (guard != null) {
            String fromGuard = guard.fileName();
            if (fromGuard != null && !fromGuard.trim().isEmpty()) return fromGuard.trim();
        }
        return episode.name == null ? "" : episode.name.trim();
    }

    /** 扩展名后面挂着的站点装饰（{@code E03.mp3【…】} 的 {@code 【…】}）不是文件名的一部分。 */
    private static String trimTrailingDecoration(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name;
        for (int i = dot + 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '【' || c == '[' || c == '（' || c == '(') {
                return name.substring(0, i);
            }
        }
        return name;
    }

    /**
     * 这一集的展示后缀：文件名是音频给「 · 音频」，压缩包给「 · 压缩包」，视频/解不出文件名返回空。
     * 判据与整条线路的 {@link #isNonVideoName} 同一张扩展名表。
     */
    static String episodeSuffix(Cms.Episode episode) {
        String name = episodeFileName(episode);
        String bare = trimTrailingDecoration(name);
        if (isArchiveName(name) || isArchiveName(bare)) return EPISODE_ARCHIVE_SUFFIX;
        if (isAudioName(name) || isAudioName(bare)) return EPISODE_AUDIO_SUFFIX;
        return "";
    }

    /** 「音频」/「压缩包」；视频或解不出文件名返回空。提示语用，与 {@link #episodeSuffix} 同口径。 */
    static String nonVideoKind(Cms.Episode episode) {
        String suffix = episodeSuffix(episode);
        if (EPISODE_ARCHIVE_SUFFIX.equals(suffix)) return "压缩包";
        if (EPISODE_AUDIO_SUFFIX.equals(suffix)) return "音频";
        return "";
    }

    /**
     * 这一集是不是明确播不了：文件名是音频/压缩包。解不出文件名的按可播处理 —— 未知不等于坏，
     * 与 {@link #hasVideoLine} 的口径一致。
     */
    static boolean isPlayableEpisode(Cms.Episode episode) {
        return episodeSuffix(episode).isEmpty();
    }

    /** 这条线路里第一条可播的集；一条可播的都没有时返回 null。 */
    static Cms.Episode firstPlayable(Cms.Line line) {
        if (line == null || line.episodes == null) return null;
        for (Cms.Episode episode : line.episodes) {
            if (episode != null && isPlayableEpisode(episode)) return episode;
        }
        return null;
    }

    /**
     * 非视频扩展名：音频 + 压缩包。这些站常把视频改名成音频/压缩包躲扫描，夸克 v2/play 不给档、
     * download 回 23018，用户只会看到失败。
     */
    static boolean isNonVideoName(String fileName) {
        return isAudioName(fileName) || isArchiveName(fileName);
    }

    /** 压缩包扩展名（小写，带点）。 */
    static boolean isArchiveName(String fileName) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = name.substring(dot);
        switch (extension) {
            case ".7z":
            case ".zip":
            case ".rar":
            case ".tar":
            case ".gz":
                return true;
            default:
                return false;
        }
    }

    /** 这部片至少有一条"视频线路"（文件名不是音频/压缩包，或解不出文件名）。 */
    static boolean hasVideoLine(Cms.Video video) {
        if (video == null || video.lines == null) return false;
        for (Cms.Line line : video.lines) {
            if (line == null || line.episodes == null) continue;
            boolean any = false;
            for (Cms.Episode episode : line.episodes) {
                if (episode == null || episode.url == null || episode.url.trim().isEmpty()) continue;
                GuardSpiderId guard = GuardSpiderId.decode(episode.url);
                if (guard != null && !guard.looksLikeNonVideoFile()) continue;
                any = true;
                break;
            }
            if (any) return true;
        }
        return false;
    }

    /** 音频扩展名（小写，带点）。 */
    static boolean isAudioName(String fileName) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = name.substring(dot);
        switch (extension) {
            case ".mp3":
            case ".m4a":
            case ".flac":
            case ".wav":
            case ".ape":
            case ".aac":
            case ".ogg":
                return true;
            default:
                return false;
        }
    }

    /**
     * 给一部片的线路打标记并把音频线排到最后。
     *
     * @return true 表示整部片只有音频线路（详情页据此提示"可能播不出画面"）
     */
    static boolean apply(Cms.Video video) {
        if (video == null || video.lines == null || video.lines.isEmpty()) return false;
        boolean anyVideo = false;
        for (Cms.Line line : video.lines) {
            if (line == null) continue;
            boolean audio = isNonVideoLine(line);
            if (audio) {
                if (line.name != null && !line.name.endsWith(SUFFIX.trim())) {
                    line.name = (line.name == null ? "" : line.name) + SUFFIX;
                }
            } else {
                anyVideo = true;
            }
        }
        if (!anyVideo) {
            // 全是音频：顺序没意义，标记打上就行。
            return true;
        }
        List<Cms.Line> ordered = new ArrayList<>(video.lines.size());
        for (Cms.Line line : video.lines) {
            if (line != null && !line.name.endsWith(SUFFIX.trim())) ordered.add(line);
        }
        for (Cms.Line line : video.lines) {
            if (line != null && line.name.endsWith(SUFFIX.trim())) ordered.add(line);
        }
        video.lines.clear();
        video.lines.addAll(ordered);
        return false;
    }

    /**
     * 这条线路是不是「整条都是非视频文件名」：只要有一集是视频文件（或解不出 Guard 内层 e），
     * 就算视频线路。判据与 {@link #hasVideoLine} 同口径。
     *
     * <p>以前是「任意一集非视频就算整条音频」：4K 站常把一季里的前几集改名成 {@code .mp3}、
     * 后面的集仍是 {@code .mp4}，这种线路会被整条标成「音频」，进而触发「只有音频线路 → 自动
     * 换源」，把 16 集的 4K 源换成只有 5 集的旧源。2026-11-27 盒子实测《两京十五日》虎斑4K：
     * 16 集（E01–E10 被站点改名 .mp3、E11–E16 是 .mp4）被自动切到旧木偶的 5 集。
     */
    private static boolean isNonVideoLine(Cms.Line line) {
        if (line == null || line.episodes == null) return false;
        boolean decoded = false;
        for (Cms.Episode episode : line.episodes) {
            if (episode == null || episode.url == null || episode.url.trim().isEmpty()) continue;
            GuardSpiderId guard = GuardSpiderId.decode(episode.url);
            if (guard == null) return false;
            decoded = true;
            if (!guard.looksLikeNonVideoFile()) return false;
        }
        return decoded;
    }
}
