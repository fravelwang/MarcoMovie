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

    private NetdiskLineLabels() {}

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
            boolean audio = isAudioLine(line);
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

    /** 这条线路的第一集是不是音频文件名（能解出 Guard 内层 e 才算）。 */
    private static boolean isAudioLine(Cms.Line line) {
        if (line == null || line.episodes == null) return false;
        for (Cms.Episode episode : line.episodes) {
            if (episode == null || episode.url == null || episode.url.trim().isEmpty()) continue;
            GuardSpiderId guard = GuardSpiderId.decode(episode.url);
            if (guard != null && guard.looksLikeAudioFile()) return true;
        }
        return false;
    }
}
