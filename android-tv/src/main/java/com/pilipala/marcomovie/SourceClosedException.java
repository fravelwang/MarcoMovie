package com.pilipala.marcomovie;

/**
 * 站点侧已经关闭/异常（spider 抛 NPE、返回体不是 JSON（例如 {@code closed}）、HTTP 5xx/520）。
 *
 * <p>和普通 {@code EngineException} 分开，是因为两者对用户是两句话：这一种说「<站名>
 * 接口已关闭或异常，已跳过」，其它异常仍照原样报。识别在 {@link CspEngine#call} 完成，同时把该站
 * 放进 {@link SourceCooling} 的十分钟冷却，后续自动换源/墙取样直接跳过。
 */
final class SourceClosedException extends Exception {

    /** Display name of the site, used in the user-facing sentence. */
    final String siteName;

    SourceClosedException(String siteName, String message, Throwable cause) {
        super(message, cause);
        this.siteName = siteName == null ? "" : siteName;
    }
}
