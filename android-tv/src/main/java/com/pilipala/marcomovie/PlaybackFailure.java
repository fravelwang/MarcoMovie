package com.pilipala.marcomovie;

/**
 * User-facing categories contain no URLs, response bodies or account tokens.
 *
 * <p>Every failure names who is responsible before saying what happened: the 【...】 prefix is
 * followed by one short sentence and one actionable suggestion, so the same kind of problem always
 * reads the same way and a line problem is never mistaken for a device problem.
 */
final class PlaybackFailure {
    /**
     * Who is responsible for a playback failure. The label is both the user-visible prefix and the
     * {@code kind=} value of the PlayAttribution log line, so a support report can be matched to
     * the class without replaying the whole session.
     */
    enum Attribution {
        /** Line or site API: unreachable, timed out, or answered with an error. */
        LINE("线路问题", "当前线路接口没有响应，建议换一条线路"),
        /** The episode needs an external parser and none of the parse interfaces returned video. */
        PARSE("解析问题", "解析接口没有返回视频，建议换一条线路"),
        /** A media address was obtained but the source rejected it or the stream broke. */
        SOURCE("片源问题", "片源返回错误或取流失败，建议换一条线路"),
        /** Decoder, audio track or resolution that this device cannot handle. */
        DEVICE("设备问题", "当前设备解码不了这个格式，建议换清晰度或音轨"),
        /** Unclassified: report it as our own defect and make clear that it was recorded. */
        UNKNOWN("未知问题", "播放没有成功，已记录，建议重试或换一条线路");

        final String label;
        final String advice;

        Attribution(String label, String advice) {
            this.label = label;
            this.advice = advice;
        }

        /** Prefix plus the class advice: the shortest complete message for this class. */
        String text() {
            return text(advice);
        }

        /** Prefix plus a caller-supplied sentence; an empty detail falls back to the advice. */
        String text(String detail) {
            String body = detail == null ? "" : detail.trim();
            return "【" + label + "】" + (body.isEmpty() ? advice : body);
        }
    }

    private PlaybackFailure() {}

    static boolean decoderFallback(int code) {
        return code == 4001
                || code == 4002
                || code == 4003
                || code == 4004
                || code == 4005
                || code == 5001
                || code == 5002;
    }

    /**
     * Maps the engine error code plus the last HTTP status onto one attribution class. An HTTP
     * error wins because a rejected or failing response proves the source side whatever the engine
     * code says; decoder, audio and DRM codes point at the device instead.
     */
    static Attribution attribution(int code, int http) {
        if (http >= 400 && http < 600) return Attribution.SOURCE;
        if (code >= 2000 && code < 4000) return Attribution.SOURCE;
        if (code >= 4000 && code < 7000) return Attribution.DEVICE;
        return Attribution.UNKNOWN;
    }

    /** Full user-facing text: attribution prefix, one plain sentence, one suggestion. */
    static String message(int code, int http) {
        return attribution(code, http).text(detail(code, http));
    }

    /** The sentence after the prefix, kept separate so the log and the message cannot drift. */
    static String detail(int code, int http) {
        if (http == 401 || http == 403) return "片源拒绝访问，可能是登录或链接失效，建议刷新或换一条线路";
        if (http == 404 || http == 410) return "播放链接已失效或内容不存在，建议换一条线路";
        if (http >= 400 && http < 600) return "片源服务器返回错误，建议稍后重试或换一条线路";
        if (code >= 2000 && code < 3000) return "连接片源失败或取流超时，建议换一条线路";
        if (code >= 3000 && code < 4000) return "片源返回的内容不是可播放视频，建议换一条线路";
        if (code >= 4000 && code < 5000) return "当前设备解码不了这段视频，建议换清晰度或音轨";
        if (code >= 5000 && code < 6000) return "音频解码失败，建议换一条音轨或其他版本";
        if (code >= 6000 && code < 7000) return "内容授权在当前设备不可用，建议换清晰度或换播放内核";
        return "播放没有成功，已记录，建议重试或换一条线路";
    }

    /**
     * Terminal text of the parse chain. Keeps the attempt count the user already saw in the old
     * message, because "tried five interfaces" is what tells a parse problem from a line problem.
     */
    static String parseFailure(int attempted) {
        return Attribution.PARSE.text(
                attempted <= 0
                        ? "此剧集需要外部解析，但没有可用接口，建议到设置里添加解析接口"
                        : "试过 " + attempted + " 个解析接口均未返回视频，建议换一条线路");
    }

    /**
     * One diagnostic line per failure. Deliberately limited to the attribution class and the config
     * labels: never the media URL, request headers, cookies or account tokens.
     */
    static String logLine(Attribution kind, int code, int http, String line, String site) {
        return "kind="
                + (kind == null ? Attribution.UNKNOWN.name() : kind.name())
                + " code="
                + code
                + " http="
                + http
                + " line="
                + label(line)
                + " site="
                + label(site);
    }

    /** Bounds one config label so a stray long name cannot bloat the log ring buffer. */
    private static String label(String value) {
        if (value == null) return "-";
        String trimmed = value.trim().replace('\n', ' ').replace('\r', ' ');
        if (trimmed.isEmpty()) return "-";
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }
}
