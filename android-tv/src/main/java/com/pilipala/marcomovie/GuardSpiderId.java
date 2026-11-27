package com.pilipala.marcomovie;

import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 一份「影视仓 / CSP Guard spider」交出来的网盘线路 id。
 *
 * <p>这些站在 {@code files/config.json} 里以 {@code w2x_} 前缀接进来（各自带 spider jar），详情能取，
 * 但点播放时 episode url 不是 http 直链，而是一层标准 base64（带 padding）包着的 JSON：外层写明是
 * 哪家网盘（{@code a} = {@code quark} / {@code uc} / {@code baidu} / {@code pan115}），{@code b} 是
 * <strong>字符串</strong>形式的内层 JSON，{@code c} 固定 {@code original}，{@code d} 是一段
 * 「[31.5GB]文件名」。
 *
 * <p>2026-11-27 在盒子上抓到的样本，内层逐字段：
 *
 * <pre>
 * quark:  a=32 hex  fid     b=32 hex  file token  c=12 hex  pwd_id  d=44 字符 base64 stoken  e=文件名
 * uc:     a=32 hex  fid     b=32 hex  file token  c=13 hex  pwd_id  d=44 字符 base64 stoken  e=文件名
 * pan115: a=分享 id         b=提取码             c=文件 id            d=文件名               e=字节数
 * baidu:  a=fs_id           b=文件名             c=uk                 d=分享 id              e=surl
 *         f=提取码          g=访问者 Cookie（里面带 BDCLND，即 sekey）
 * </pre>
 *
 * <p>百度这一套是 2026-11-27 上机逐个接口钉死的（不是按形状猜）：
 *
 * <ul>
 *   <li>{@code GET https://pan.baidu.com/s/1<e>} → 302 到 {@code /share/init?surl=<e>}，页面里的
 *       {@code shareid} 正好等于 {@code d} —— 所以 {@code e} 是<strong>分享短 id（surl）</strong>，
 *       不是 sekey；{@code a} 是分享内文件 id（fs_id），{@code d} 才是 API 用的数字分享 id；</li>
 *   <li>{@code POST /share/verify?surl=<e>&pwd=<f>} 回 {@code errno=0}，{@code randsk} 与 {@code g}
 *       里的 {@code BDCLND} 逐字节相同；</li>
 *   <li>用 {@code shareid=d&from=c&sekey=<BDCLND>} 调 {@code /share/transfer} 回 {@code errno=0}，
 *       证明这套映射就是百度认的那套。</li>
 * </ul>
 *
 * <p>所以给 {@link BaiduShareResolver} 的六段 id 是
 * {@code bd ++ d(share_id) ++ c(uk) ++ a(fs_id) ++ encode(sekey) ++ encode(e+"||")}。第 6 段只带
 * surl（没有 sign/timestamp），播放侧网页接口失败时还能靠它再开一次分享页现取签名。
 *
 * <p>夸克的 ident 是 {@code fid++token++pwd_id++stoken++size}，UC 是
 * {@code fid++fileToken++pwd_id++stoken}（见 {@link QuarkShareResolver} / {@link UcShareResolver}
 * 的 {@code parseIdent}）。这两个 provider 的 a 与 b 都是 32 hex，唯一没被形状钉死的是谁当 fid、
 * 谁当 file token，所以 {@link #resolve} 先按 mapping A（fid=a、token=b）试，失败再按 mapping B
 * （fid=b、token=a）试一次；两行日志都带 {@code mapping=}，不蒙混。百度只有一套映射（mapping A）。
 *
 * <p>夸克 ident 的第五段 size 只能给 0：内层没有字节数（外层 d 只有「31.5GB」这种一位小数的估算，
 * 拿它当 size 会在空间预检上误判）。size 只用于「空间不足预检」和「按大小在盘里找副本」两个兜底，
 * 主路是按 fid 命中分享条目，命中后的真大小由分享列表给出（自动下一集用的就是它）。
 *
 * <p>115 我们还没有解析链路，{@link #isPan115()} 让调用方给一句明确文案；其它没接的盘（移动
 * {@code pan139}、天翼 {@code pan189}、123、迅雷等）也认出来，{@link #canResolve()} 为 false，
 * 播放时给「这个盘还没接（移动云盘）」这类真话，不再落到 {@code SpiderAsk} 之后静默回详情页。
 */
final class GuardSpiderId {

    /** 日志标签，固定几行便于父会话 grep 复核。 */
    static final String TAG = "GuardDecode";

    /**
     * 解析失败时留下的原因（优先保留第一条非空的）。夸克解析器每次尝试都会把自己的
     * lastFailureReason 清空重写，第二遍 mapping=B 往往只留下一句空白，界面就丢了 code=23018
     * 这种实话；这里替调用方记住"最好的一句"。百度失败时也收在这里。
     */
    private static volatile String lastFailureReason = "";

    /** 上一次解析失败的原因；成功或未解析时为空。 */
    static String lastFailureReason() {
        return lastFailureReason;
    }

    static final String QUARK = "quark";
    static final String UC = "uc";
    static final String PAN115 = "pan115";
    static final String BAIDU = "baidu";

    /** 夸克 ident 的 size 段：未知就是 0（见类注释，主路不依赖它）。 */
    private static final String SIZE_UNKNOWN = "0";

    final String provider;
    private final String innerA;
    private final String innerB;
    private final String innerC;
    private final String innerD;
    /** 内层 e：夸克/UC 是文件名（115 是字节数，百度是 surl），用来判断"视频被改名成音频"。 */
    private final String innerE;
    /** 内层 f：百度提取码；其它 provider 目前不用。 */
    private final String innerF;
    /** 内层 g：百度访问者 Cookie（含 BDCLND / sekey）；其它 provider 目前不用。 */
    private final String innerG;
    /** 外层 d：「[31.5GB]文件名」，只用于日志（剧集名以分享列表里的文件名为准）。 */
    private final String label;

    private GuardSpiderId(
            String provider,
            String innerA,
            String innerB,
            String innerC,
            String innerD,
            String innerE,
            String innerF,
            String innerG,
            String label) {
        this.provider = provider;
        this.innerA = innerA;
        this.innerB = innerB;
        this.innerC = innerC;
        this.innerD = innerD;
        this.innerE = innerE;
        this.innerF = innerF;
        this.innerG = innerG;
        this.label = label;
    }

    /**
     * 认出并解开这一层 base64 JSON。
     *
     * <p>判据是「能解开 + 外层 {@code a} 像 provider 名 + 内层是 JSON」：已知四家的内层 a/b/c/d 必须
     * 有值；没接的盘只要求外层 {@code c=original} 且内层非空，这样移动/天翼也能被认出来给一句真话。
     * 不是这个形状的 episode url（http 直链、我们自己的 {@code ++} 分享 id、插件返回的播放地址）
     * 一律返回 null，调用方原来的流程一个字节都不变。
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
            if (!looksLikeProvider(provider)) return null;
            // 内层是「字符串里的 JSON」，不是对象：父会话的样本里 b 带转义引号。
            JSONObject inner;
            try {
                inner = new JSONObject(outer.optString("b", ""));
            } catch (org.json.JSONException error) {
                return null;
            }
            String a = inner.optString("a", "").trim();
            String b = inner.optString("b", "").trim();
            String c = inner.optString("c", "").trim();
            String d = inner.optString("d", "").trim();
            String e = inner.optString("e", "").trim();
            String f = inner.optString("f", "").trim();
            String g = inner.optString("g", "").trim();
            if (QUARK.equals(provider) || UC.equals(provider) || PAN115.equals(provider)) {
                if (a.isEmpty() || b.isEmpty() || c.isEmpty() || d.isEmpty()) return null;
            } else if (BAIDU.equals(provider)) {
                // 百度样本的 a/b/c/d 都有值；缺字段也认下来，resolve 时给带字段名的真话。
                if (a.isEmpty() && b.isEmpty() && c.isEmpty() && d.isEmpty()) return null;
            } else {
                // 没接的盘：外层 c=original 是 Guard 包装的强判据；内层只要非空就够给一句真话。
                if (!"original".equalsIgnoreCase(outer.optString("c", "").trim())) return null;
                if (inner.length() == 0) return null;
            }
            return new GuardSpiderId(
                    provider,
                    a,
                    b,
                    c,
                    d,
                    e,
                    f,
                    g,
                    outer.optString("d", "").trim());
        } catch (org.json.JSONException error) {
            // 不是这一层包装（或者样本变了）：交给原来的流程。
            return null;
        }
    }

    /** provider 名（小写）：字母开头，后面只允许字母数字与 {@code . _ -}，最长 32。 */
    private static boolean looksLikeProvider(String value) {
        if (value == null || value.isEmpty() || value.length() > 32) return false;
        char first = value.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z')
                            || (c >= '0' && c <= '9')
                            || c == '.'
                            || c == '_'
                            || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    boolean isQuark() {
        return QUARK.equals(provider);
    }

    boolean isBaidu() {
        return BAIDU.equals(provider);
    }

    boolean isPan115() {
        return PAN115.equals(provider);
    }

    /** 这条 Guard 线路我们是否真有一套解析链路可以走（115 与其它盘都还没有）。 */
    boolean canResolve() {
        return isQuark() || isUc() || isBaidu();
    }

    private boolean isUc() {
        return UC.equals(provider);
    }

    /** 没接的盘在界面上怎么说：移动 / 天翼这些至少给对名字。 */
    String unsupportedMessage() {
        if (isPan115()) return "115 盘还没接，先换夸克/UC 线路";
        String name = unsupportedName();
        return "这个盘还没接（" + name + "），先换夸克/UC/百度线路";
    }

    private String unsupportedName() {
        switch (provider) {
            case "pan139":
                return "移动云盘";
            case "pan189":
                return "天翼云盘";
            case "pan123":
            case "123pan":
                return "123 云盘";
            case "panxunlei":
            case "xunlei":
                return "迅雷云盘";
            case "ali":
            case "aliyun":
                return "阿里云盘";
            default:
                return provider;
        }
    }

    /** 内层 b（百度）或 e（夸克/UC）的文件名（可能为空）。 */
    String fileName() {
        return isBaidu() ? innerB : innerE;
    }

    /** 文件名是不是音频扩展（这些站常把视频改名成音频躲扫描）。 */
    boolean looksLikeAudioFile() {
        return NetdiskLineLabels.isAudioName(fileName());
    }

    /** 文件名是不是非视频（音频或压缩包），详情页换源用它判断"这条线播不出画面"。 */
    boolean looksLikeNonVideoFile() {
        return NetdiskLineLabels.isNonVideoName(fileName());
    }

    /** 外层 d 的「[31.5GB]文件名」，只给日志用（剧集名以分享列表里的文件名为准）。 */
    String label() {
        return label;
    }

    /** 解析器那一侧的日志标签，与 {@code nativeNetdiskCandidate} 里原来那套一致。 */
    String logTag() {
        if (isBaidu()) return "BaiduShare";
        return isQuark() ? "QuarkShare" : "UcShare";
    }

    /**
     * 按 mapping A 再按 mapping B 把内层字段喂给 {@link QuarkShareResolver} / {@link UcShareResolver}；
     * 百度只走自己那一套（{@link #resolveBaidu}）。
     *
     * <p>每次尝试都打一行固定的
     * {@code guard decode provider=quark fields=a,b,c,d → resolver id=... mapping=A|B}：token 与
     * stoken 打码，fid 与 pwd_id 留明文（分享列表那边的日志本来就会打 fid，便于两行对起来）。
     *
     * @return 成功返回带直链的剧集；失败返回 null（失败原因由解析器的 {@code lastFailureReason()}
     *     给出，{@link PlaybackController} 会去读）
     */
    Cms.Episode resolve(String siteKey, String lineName) {
        lastFailureReason = "";
        if (isBaidu()) return resolveBaidu(siteKey, lineName);
        if (!canResolve()) {
            lastFailureReason = unsupportedMessage();
            return null;
        }
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
            if (reason != null && !reason.trim().isEmpty() && lastFailureReason.isEmpty()) {
                lastFailureReason = reason.trim();
            }
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

    /**
     * 百度：{@code a=fs_id、c=uk、d=share_id、e=surl、f=提取码、g=Cookie(BDCLND)}。
     *
     * <p>先拿 {@code e/f} 走 {@code share/verify} 换一份新 sekey（提取码分享的 sekey 有时效），换不到
     * 再用 {@code g} 里的 {@code BDCLND}；两样都没有且这条分享要提取码时直接给带 errno 的真话，不硬闯。
     */
    private Cms.Episode resolveBaidu(String siteKey, String lineName) {
        String shareId = innerD;
        String uk = innerC;
        String fsId = innerA;
        String surl = innerE;
        String pwd = innerF;
        boolean pwdAvailable = !pwd.isEmpty();
        if (!isDigits(shareId, 1, 32) || !isDigits(uk, 1, 32) || !isDigits(fsId, 1, 32)) {
            lastFailureReason = "百度分享参数形状不对（share/uk/fid），请换一条线路";
            AppLog.w(
                    TAG,
                    "guard decode provider=baidu mapping=A share="
                            + mask(shareId, 3)
                            + " uk="
                            + uk
                            + " fid="
                            + fsId
                            + " sekey=no pwd="
                            + (pwdAvailable ? "yes" : "no"));
            return null;
        }
        String sekey = "";
        String verifyError = "";
        if (!surl.isEmpty() && pwdAvailable) {
            try {
                sekey = text(BaiduShareProvider.verifySekey(surl, pwd));
            } catch (IOException error) {
                verifyError = text(error.getMessage());
                AppLog.w(
                        TAG,
                        "guard baidu verify failed surl="
                                + mask(surl, 4)
                                + " error="
                                + verifyError);
            }
        }
        if (sekey.isEmpty()) sekey = cookieValue(innerG, "BDCLND");
        AppLog.w(
                TAG,
                "guard decode provider=baidu mapping=A share="
                        + mask(shareId, 3)
                        + " uk="
                        + uk
                        + " fid="
                        + fsId
                        + " sekey="
                        + (sekey.isEmpty() ? "no" : "yes")
                        + " pwd="
                        + (pwdAvailable ? "yes" : "no"));
        if (sekey.isEmpty() && pwdAvailable) {
            lastFailureReason =
                    verifyError.isEmpty()
                            ? "百度分享需要提取码，但没有拿到 sekey，请换一条线路"
                            : "百度分享没拿到 sekey：" + verifyError;
            AppLog.w(
                    TAG,
                    "guard resolve failed provider=baidu mapping=A reason=" + lastFailureReason);
            return null;
        }
        String ident = baiduIdent(shareId, uk, fsId, sekey, surl);
        Cms.Episode episode = BaiduShareResolver.resolve(siteKey, lineName, ident);
        if (episode != null && episode.url != null && !episode.url.trim().isEmpty()) {
            return episode;
        }
        String reason = BaiduShareResolver.lastFailureReason();
        if (reason != null && !reason.trim().isEmpty()) lastFailureReason = reason.trim();
        AppLog.w(
                TAG,
                "guard resolve failed provider=baidu mapping=A reason=" + lastFailureReason);
        return null;
    }

    /** 六段：{@code bd ++ share_id ++ uk ++ fs_id ++ encode(sekey) ++ encode(surl+"||")}。 */
    private static String baiduIdent(
            String shareId, String uk, String fsId, String sekey, String surl) {
        StringBuilder id = new StringBuilder("bd");
        id.append("++").append(shareId);
        id.append("++").append(uk);
        id.append("++").append(fsId);
        id.append("++").append(BaiduShareResolver.encodePart(text(sekey)));
        if (!surl.isEmpty()) {
            id.append("++").append(BaiduShareResolver.encodePart(surl + "||"));
        }
        return id.toString();
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

    /** 从 {@code name=value; name2=value2} 的 Cookie 串里取一个值（保留值里的 {@code =}）。 */
    private static String cookieValue(String cookie, String name) {
        String value = text(cookie);
        if (value.isEmpty()) return "";
        for (String pair : value.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!pair.substring(0, eq).trim().equalsIgnoreCase(name)) continue;
            return pair.substring(eq + 1).trim();
        }
        return "";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isDigits(String value, int min, int max) {
        String text = text(value);
        if (text.length() < min || text.length() > max) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** 留头几个字符，够对样本，不把整段分享凭据写进日志。 */
    private static String mask(String value) {
        return mask(value, 6);
    }

    private static String mask(String value, int keep) {
        String text = text(value);
        if (text.isEmpty()) return "";
        if (text.length() <= keep + 2) return "***";
        return text.substring(0, keep) + "***";
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
