package com.pilipala.marcomovie;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期冷却表：某个源这一轮被判成「接口已关闭或异常」之后，十分钟内不再拿它去探测。
 *
 * <p>为什么需要：虎斑/木偶/多多/小飒/欧哥 这些站的接口现在直接回 {@code closed}（6 字节非 JSON）
 * 或 520，自动换源和片库墙每次仍会挨个去问，白等一整轮。冷却只进内存、不落盘：重启或十分钟后
 * 自动恢复，站点修好了不需要用户清缓存。
 *
 * <p>只由 {@link CspEngine} 在识别出「站点侧关闭」时写入；其它异常不冷却（真 bug 要能复现）。
 */
final class SourceCooling {

    /** 冷却时长：十分钟。 */
    static final long COOLDOWN_MS = 10L * 60L * 1000L;

    private static final Map<String, Long> UNTIL = new ConcurrentHashMap<>();

    private SourceCooling() {}

    /** {@code true} when this site key is still cooling down; an expired entry is dropped. */
    static boolean isCooled(String key) {
        if (key == null || key.isEmpty()) return false;
        Long until = UNTIL.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() < until.longValue()) return true;
        UNTIL.remove(key, until);
        return false;
    }

    /** Puts one site on cooldown and logs the single evidence line the callers grep for. */
    static void cool(String key, String reason) {
        if (key == null || key.isEmpty()) return;
        long until = System.currentTimeMillis() + COOLDOWN_MS;
        UNTIL.put(key, until);
        AppLog.w("SourceCooling", "source cooled key=" + key + " until=" + until
                + " reason=" + (reason == null ? "" : reason));
    }

    /** Test/handover helper: forget every cooldown. */
    static void clear() {
        UNTIL.clear();
    }
}
