package com.pilipala.marcomovie;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 一份「影视仓 / CSP Guard spider」交出来的网盘线路 id。
 *
 * <p>这些站在 {@code files/config.json} 里以 {@code w2x_} 前缀接进来（各自带 spider jar），详情能取，
 * 但点播放时 episode url 不是 http 直链，而是一层标准 base64（带 padding）包着的 JSON：外层写明是
 * 哪家网盘（{@code a} = {@code quark} / {@code uc} / {@code pan115}），{@code b} 是<strong>字符串</strong>
 * 形式的内层 JSON，{@code c} 固定 {@code original}，{@code d} 是一段「[31.5GB]文件名」。
 *
 * <p>2026-11-27 在盒子上抓到的三种真样本，内层逐字段：
 *
 * <pre>
 * quark:  a=32 hex  fid     b=32 hex  file token  c=12 hex  pwd_id  d=44 字符 base64 stoken  e=文件名
 * uc:     a=32 hex  fid     b=32 hex  file token  c=13 hex  pwd_id  d=44 字符 base64 stoken  e=文件名
 * pan115: a=分享 id         b=提取码             c=文件 id            d=文件名               e=字节数
 * </pre>
 *
 * <p>夸克的 ident 是 {@code fid++token++pwd_id++stoken++size}，UC 是
 * {@code fid++fileToken++pwd_id++stoken}（见 {@link QuarkShareResolver} / {@link UcShareResolver}
 * 的 {@code parseIdent}）。上面那四个字段与它们的逐段形状对得上：32 hex 的文件 id、32 hex 的 file
 * token、12/13 hex 的 pwd_id、44 字符的 stoken。<b>唯一没被形状钉死的是 a 与 b 谁当 fid、谁当
 * file token</b>（两个都是 32 hex），所以 {@link #resolve} 先按 mapping A（fid=a、token=b）试，
 * 失败再按 mapping B（fid=b、token=a）试一次；两行日志都带 {@code mapping=}，不蒙混。
 *
 * <p>夸克 ident 的第五段 size 只能给 0：内层没有字节数（外层 d 只有「31.5GB」这种一位小数的估算，
 * 拿它当 size 会在空间预检上误判）。size 只用于「空间不足预检」和「按大小在盘里找副本」两个兜底，
 * 主路是按 fid 命中分享条目，命中后的真大小由分享列表给出（自动下一集用的就是它）。
 *
 * <p>115 我们还没有解析链路（没有 115 盘客户端），{@link #isPan115()} 让调用方给一句明确文案，
 * 而不是落到 {@code SpiderAsk} 之后静默回详情页。
 */
final class GuardSpiderId {

    /** 日志标签，固定几行便于父会话 grep 复核。 */
    static final String TAG = "GuardDecode";

    static final String QUARK = "quark";
    static final String UC = "uc";
    static final String PAN115 = "pan115";

    /** 夸克 ident 的 size 段：未知就是 0（见类注释，主路不依赖它）。 */
    private static final String SIZE_UNKNOWN = "0";

    final String provider;
    private final String innerA;
    private final String innerB;
    private final String innerC;
    private final String innerD;
    /** 外层 d：「[31.5GB]文件名」，只用于日志（剧集名以分享列表里的文件名为准）。 */
    private final String label;

    private GuardSpiderId(
            String provider,
            String innerA,
            String innerB,
            String innerC,
            String innerD,
            String label) {
        this.provider = provider;
        this.innerA = innerA;
        this.innerB = innerB;
        this.innerC = innerC;
        this.innerD = innerD;
        this.label = label;
    }

    /**
     * 认出并解开这一层 base64 JSON。
     *
     * <p>判据是「能解开 + 外层 {@code a} 是三家里的一家 + 内层 a/b/c/d 都有值」：不是这个形状的
     * episode url（http 直链、我们自己的 {@code ++} 分享 id、插件返回的播放地址）一律返回 null，
     * 调用方原来的流程一个字节都不变。
     *
     * @return null 当且仅当这条 episode url 不是 Guard 的网盘线路 id
     */
    static GuardSpiderId decode(String raw) {
        String value = raw == null ? "" : raw.trim();
        // 最短的真样本也在 200 字节以上；短于这个长度不可能是这一层包装。
        if (value.length() < 24 || value.length() > 8192) return null;
        if (!looksBase64(value)) return null;
        String outerText = unwrap(value);
        if (outerText.isEmpty()) return null;
        try {
            JSONObject outer = new JSONObject(outerText);
            String provider = outer.optString("a", "").trim().toLowerCase(Locale.ROOT);
            if (!QUARK.equals(provider) && !UC.equals(provider) && !PAN115.equals(provider)) {
                return null;
            }
            // 内层是「字符串里的 JSON」，不是对象：父会话的样本里 b 带转义引号。
            JSONObject inner = new JSONObject(outer.optString("b", ""));
            String a = inner.optString("a", "").trim();
            String b = inner.optString("b", "").trim();
            String c = inner.optString("c", "").trim();
            String d = inner.optString("d", "").trim();
            if (a.isEmpty() || b.isEmpty() || c.isEmpty() || d.isEmpty()) return null;
            return new GuardSpiderId(
                    provider,
                    a,
                    b,
                    c,
                    d,
                    outer.optString("d", "").trim());
        } catch (org.json.JSONException error) {
            // 不是这一层包装（或者样本变了）：交给原来的流程。
            return null;
        }
    }

    boolean isQuark() {
        return QUARK.equals(provider);
    }

    boolean isPan115() {
        return PAN115.equals(provider);
    }

    /** 外层 d 的「[31.5GB]文件名」，只给日志用（剧集名以分享列表里的文件名为准）。 */
    String label() {
        return label;
    }

    /** 解析器那一侧的日志标签，与 {@code nativeNetdiskCandidate} 里原来那套一致。 */
    String logTag() {
        return isQuark() ? "QuarkShare" : "UcShare";
    }

    /**
     * 按 mapping A 再按 mapping B 把内层字段喂给 {@link QuarkShareResolver} / {@link UcShareResolver}。
     *
     * <p>每次尝试都打一行固定的
     * {@code guard decode provider=quark fields=a,b,c,d → resolver id=... mapping=A|B}：token 与
     * stoken 打码，fid 与 pwd_id 留明文（分享列表那边的日志本来就会打 fid，便于两行对起来）。
     *
     * @return 成功返回带直链的剧集；两种映射都失败返回 null（失败原因由解析器的
     *     {@code lastFailureReason()} 给出，{@link PlaybackController} 会去读）
     */
    Cms.Episode resolve(String siteKey, String lineName) {
        for (int pass = 0; pass < 2; pass++) {
            boolean swapped = pass == 1;
            String mapping = swapped ? "B" : "A";
            String ident = ident(swapped);
            AppLog.w(
                    TAG,
                    "guard decode provider="
                            + provider
                            + " fields=a,b,c,d → resolver id="
                            + maskedIdent(swapped)
                            + " mapping="
                            + mapping);
            Cms.Episode episode =
                    isQuark()
                            ? QuarkShareResolver.resolve(siteKey, lineName, ident)
                            : UcShareResolver.resolve(siteKey, lineName, ident);
            if (episode != null && episode.url != null && !episode.url.trim().isEmpty()) {
                return episode;
            }
            String reason =
                    isQuark()
                            ? QuarkShareResolver.lastFailureReason()
                            : UcShareResolver.lastFailureReason();
            AppLog.w(
                    TAG,
                    "guard resolve failed provider="
                            + provider
                            + " mapping="
                            + mapping
                            + " reason="
                            + (reason == null ? "" : reason));
        }
        return null;
    }

    /** 解析器要的 {@code ++} ident；mapping B 只把 a/b 对调，pwd_id 与 stoken 不动。 */
    private String ident(boolean swapped) {
        String fid = swapped ? innerB : innerA;
        String token = swapped ? innerA : innerB;
        String head = fid + "++" + token + "++" + innerC + "++" + innerD;
        return isQuark() ? head + "++" + SIZE_UNKNOWN : head;
    }

    private String maskedIdent(boolean swapped) {
        String fid = swapped ? innerB : innerA;
        String token = swapped ? innerA : innerB;
        return "fid="
                + fid
                + " token="
                + mask(token)
                + " pwd_id="
                + innerC
                + " stoken="
                + mask(innerD)
                + (isQuark() ? " size=" + SIZE_UNKNOWN : "");
    }

    /** 留头 6 个字符，够对样本，不把整段分享凭据写进日志。 */
    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 8) return "***";
        return value.substring(0, 6) + "***";
    }

    /** 标准 base64 与 URL-safe 都试：父会话的样本是标准 + padding。 */
    private static String unwrap(String value) {
        int[] flags = {Base64.DEFAULT, Base64.URL_SAFE | Base64.NO_WRAP};
        for (int flag : flags) {
            try {
                byte[] bytes = Base64.decode(value, flag);
                if (bytes == null || bytes.length == 0) continue;
                String text = new String(bytes, StandardCharsets.UTF_8).trim();
                if (text.startsWith("{")) return text;
            } catch (IllegalArgumentException ignored) {
                // 换下一个字母表。
            }
        }
        return "";
    }

    private static boolean looksBase64(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok =
                    (c >= 'A' && c <= 'Z')
                            || (c >= 'a' && c <= 'z')
                            || (c >= '0' && c <= '9')
                            || c == '+'
                            || c == '/'
                            || c == '='
                            || c == '-'
                            || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
