package com.pilipala.marcomovie;

import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * UC 网盘登录：粘贴一段 Cookie，挑出 UC 的账号键（{@code __pus} / {@code __puus} / {@code __uid} /
 * {@code UCID}），存到 {@code files/netdisk/uc.json}。
 *
 * <p>为什么是粘贴而不是扫码：播放 UC 分享的直链只需要账号 Cookie（见 {@link UcShareResolver} 的
 * 账号兜底分支），而 UC 的扫码登录挂在它自己的风控链路上，盒子上还没有实测样本。所以本次把
 * 「收下一段能换直链的 Cookie」做扎实，协议一步都不自己编（照 {@code flutter/lib/core/
 * cloud_share.dart} 的 {@code _resolveUcAccount} 移植）。凭据结构与 {@code files/netdisk/baidu.json}
 * 同一套：{@code provider} / {@code version} / {@code cookie} / {@code validatedAt}。
 *
 * <p>不联网校验：保存时只做格式判据（至少要有一个换直链用的键），真登录态要等播放时才验证。这样
 * 粘贴立刻能用，也不会因为一次网络抖动把 Boss 刚贴进来的凭据判成坏。播放侧换不到直链时会说
 * 「UC 登录已失效」，那时再重新粘贴。
 *
 * <p>线程：{@link #load(File)} / {@link #save(File, Credential)} 只碰本地文件；{@link
 * #showPasteDialog} 必须在 UI 线程调用。
 */
final class UcLogin {

    static final String PROVIDER = "uc";
    static final int CREDENTIAL_VERSION = 1;

    /**
     * 从粘贴内容里留下的 Cookie。{@code __pus} / {@code __puus} 是换直链真正要发的两个（前者是账号
     * 身份、后者是短效会话，见 {@link #apiCookies()}）；{@code __uid} / {@code UCID} 有就一起存，
     * 用来认账号与排查，不参与请求。
     */
    private static final List<String> KEPT =
            Arrays.asList("__pus", "__puus", "__uid", "UCID");

    /** 换直链真正要发的两个键（与 Flutter 端 {@code _ucCredentialCookies} 同一组）。 */
    private static final List<String> API_KEYS = Arrays.asList("__pus", "__puus");

    /** Cookie 值里不能出现的字符（换行/分号/空白会破坏 Cookie 头）。 */
    private static final Pattern ILLEGAL_VALUE = Pattern.compile("[\\x00-\\x20;,\\r\\n]");

    private static final int MAX_COOKIE_CHARS = 40 * 1024;

    /** 一次性安装的 {@code getFilesDir()}；播放侧没有 Activity 可问，位置随类走。 */
    private static File appFilesDir;

    private UcLogin() {}

    // ------------------------------------------------------------------ 凭据

    /**
     * 存下来的凭据。{@code cookie} 是规范化的 {@code 名=值; 名=值} 串（只含上面四个名字，固定顺序），
     * 播放侧按需挑出 {@code __pus} / {@code __puus} 拼 Cookie 头。
     */
    static final class Credential {
        final String cookie;
        final String pus;
        final String puus;
        final String validatedAt;

        Credential(String cookie, String pus, String puus, String validatedAt) {
            this.cookie = cookie;
            this.pus = pus;
            this.puus = puus;
            this.validatedAt = validatedAt;
        }

        String toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("provider", PROVIDER);
            out.addProperty("version", CREDENTIAL_VERSION);
            out.addProperty("cookie", cookie);
            out.addProperty("validatedAt", validatedAt);
            return out.toString();
        }

        /** 缺字段、来自别的 provider、或 Cookie 里没有 {@code __pus}/{@code __puus} 时返回 null。 */
        static Credential parse(String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                JsonElement root = JsonParser.parseString(json);
                if (root == null || !root.isJsonObject()) return null;
                JsonObject obj = root.getAsJsonObject();
                if (!PROVIDER.equals(NetdiskShareHttp.optString(obj, "provider"))) return null;
                if (obj.has("version")
                        && NetdiskShareHttp.optInt(obj, "version", -1) != CREDENTIAL_VERSION) {
                    return null;
                }
                String cookie = NetdiskShareHttp.optString(obj, "cookie").trim();
                if (cookie.isEmpty() || cookie.length() > MAX_COOKIE_CHARS) return null;
                String validatedAt = NetdiskShareHttp.optString(obj, "validatedAt").trim();
                if (validatedAt.isEmpty()) return null;
                return fromCookie(cookie, validatedAt);
            } catch (RuntimeException error) {
                return null;
            }
        }

        /** 规范化后的 Cookie → 凭据；{@code __pus} 与 {@code __puus} 都没有就返回 null。 */
        static Credential fromCookie(String cookie, String validatedAt) {
            String pus = cookieValue(cookie, "__pus");
            String puus = cookieValue(cookie, "__puus");
            if (pus.isEmpty() && puus.isEmpty()) return null;
            return new Credential(cookie, pus, puus, validatedAt);
        }
    }

    /**
     * 把 Boss 粘贴的一整段东西变成凭据；{@code __pus}/{@code __puus} 都没有返回 null（调用方提示
     * 「缺少 __pus / __puus」）。
     *
     * <p>容忍三种常见形态：浏览器里复制的 {@code __pus=…; __puus=…}、带 {@code Cookie:} 前缀的整行、
     * 以及 devtools 导出的 {@code {"cookie":"…"}}。只留下本类认的四个名字，其余一律丢掉。
     */
    static Credential parseCookie(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("{")) {
            try {
                JsonElement root = JsonParser.parseString(text);
                if (root != null && root.isJsonObject()) {
                    text = NetdiskShareHttp.optString(root.getAsJsonObject(), "cookie").trim();
                }
            } catch (RuntimeException ignored) {
                // 不是 JSON 就按普通 Cookie 串处理
            }
        }
        if (text.regionMatches(true, 0, "cookie:", 0, 7)) text = text.substring(7).trim();
        if (text.isEmpty()) return null;
        Map<String, String> kept = new LinkedHashMap<>();
        for (String part : text.split("[;\\r\\n]+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            // UC 的键名带下划线，大小写不敏感地匹配（浏览器偶尔会把它们按原样/小写混着给）。
            String match = keptName(name);
            if (match.isEmpty() || value.isEmpty()) continue;
            if (ILLEGAL_VALUE.matcher(value).find()) continue;
            kept.put(match, value);
        }
        boolean hasApiKey = false;
        for (String name : API_KEYS) {
            if (kept.containsKey(name)) hasApiKey = true;
        }
        if (!hasApiKey) return null;
        StringBuilder cookie = new StringBuilder();
        for (String name : KEPT) {
            String value = kept.get(name);
            if (value == null || value.isEmpty()) continue;
            if (cookie.length() > 0) cookie.append("; ");
            cookie.append(name).append('=').append(value);
        }
        if (cookie.length() > MAX_COOKIE_CHARS) return null;
        return Credential.fromCookie(cookie.toString(), isoNow());
    }

    /** {@code KEPT} 里与 {@code name} 同名的规范写法；不认识返回 ""。 */
    private static String keptName(String name) {
        if (name == null || name.isEmpty()) return "";
        for (String known : KEPT) {
            if (known.equalsIgnoreCase(name.trim())) return known;
        }
        return "";
    }

    /** {@code 名=值} 里取值；没有返回 ""。名字按大小写不敏感匹配。 */
    static String cookieValue(String cookie, String name) {
        String wanted = NetdiskShareHttp.text(name).trim();
        if (wanted.isEmpty()) return "";
        for (String part : NetdiskShareHttp.text(cookie).split("[;\\r\\n]+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (!part.substring(0, eq).trim().equalsIgnoreCase(wanted)) continue;
            String value = part.substring(eq + 1).trim();
            if (value.isEmpty()) continue;
            if (ILLEGAL_VALUE.matcher(value).find()) continue;
            return value;
        }
        return "";
    }

    static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    /** UI 里的一行短状态（与夸克那边同口径：登录卡片没地方放整段摘要）。 */
    static String describeShort(Credential credential) {
        if (credential == null) return "未登录";
        if (!credential.pus.isEmpty() && !credential.puus.isEmpty()) return "__pus 与 __puus 已存";
        return credential.pus.isEmpty() ? "__puus 已存" : "__pus 已存";
    }

    /** 账号面板里的一句话摘要。 */
    static String describe(Credential credential) {
        if (credential == null) return "未登录";
        StringBuilder sb = new StringBuilder(describeShort(credential));
        sb.append(" · 保存于 ").append(credential.validatedAt);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 落盘

    static File credentialFile(File filesDir) {
        if (filesDir == null) return null;
        return new File(new File(filesDir, "netdisk"), "uc.json");
    }

    /** 读 {@code files/netdisk/uc.json}；没有或读不出来返回 null。 */
    static Credential load(File filesDir) {
        File file = credentialFile(filesDir);
        if (file == null || !file.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Credential.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 没有可做的事
                }
            }
        }
    }

    /** 原子写（tmp + rename），半截文件不会被当成凭据读进来。 */
    static boolean save(File filesDir, Credential credential) {
        File file = credentialFile(filesDir);
        if (file == null || credential == null) return false;
        File parent = file.getParentFile();
        if (parent == null) return false;
        if (!parent.isDirectory() && !parent.mkdirs()) return false;
        File tmp = new File(parent, file.getName() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(credential.toJson().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return tmp.renameTo(file);
            }
            return true;
        } catch (IOException error) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 没有可做的事
                }
            }
            if (!tmp.equals(file) && tmp.exists() && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    static void clear(File filesDir) {
        File file = credentialFile(filesDir);
        if (file != null && file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static void useFilesDir(File dir) {
        appFilesDir = dir;
    }

    static Credential loadSaved() {
        return load(appFilesDir);
    }

    static boolean saveSaved(Credential credential) {
        return save(appFilesDir, credential);
    }

    static void clearSaved() {
        clear(appFilesDir);
    }

    // ------------------------------------------------------------------ 请求用

    /**
     * 换直链要发的 Cookie 键值：只含 {@code __pus} / {@code __puus}，值为空的不放。
     *
     * <p>只挑这两个（不是整段 Cookie）：UC 的 API 只认账号会话，多带的键不帮忙还可能被判成陌生
     * 客户端。两者同时带的冲突口径在 {@link UcShareResolver} 里按 Flutter 端处理。
     */
    static Map<String, String> apiCookies(Credential credential) {
        Map<String, String> out = new LinkedHashMap<>();
        if (credential == null) return out;
        for (String name : API_KEYS) {
            String value = cookieValue(credential.cookie, name);
            if (value.isEmpty()) continue;
            out.put(name, value);
        }
        return out;
    }

    // ------------------------------------------------------------------ 粘贴框

    /**
     * 网盘中心「UC」pill 的入口：一个粘贴框 + 保存。保存失败留在对话框里说原因，成功才关。
     *
     * @param done 保存成功后的回调（网盘中心用它刷新 pill 状态），可 null
     */
    static void showPasteDialog(MainActivity a, Runnable done) {
        if (a == null) return;
        Credential saved = loadSaved();
        AddressInput input =
                new AddressInput(a, "__pus=…; __puus=…; __uid=…; UCID=…", "", true);
        LinearLayout box = a.vertical();
        TextView hint =
                a.text(
                        saved == null
                                ? "把浏览器里 drive.uc.cn 的整段 Cookie 粘进来。必须含 __pus 或 __puus；"
                                        + "__uid / UCID 有就一起粘。凭据只存在本机。"
                                : "当前已存一份凭据（"
                                        + saved.validatedAt
                                        + "）。重新粘贴会覆盖它。",
                        12f,
                        TvV17Theme.MUTED);
        hint.setLineSpacing(a.dp(4), 1f);
        box.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        a.gap(box, 8);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        final AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle("登录 UC 网盘")
                        .setView(box)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", null)
                        .create();
        dialog.setOnShowListener(
                ignored -> {
                    android.widget.Button save = dialog.getButton(-1);
                    if (save == null) return;
                    save.setOnClickListener(
                            view -> {
                                String raw = input.value();
                                if (raw.isEmpty()) {
                                    input.invalid("请先粘贴 Cookie");
                                    return;
                                }
                                Credential parsed = parseCookie(raw);
                                if (parsed == null) {
                                    input.invalid("缺少 __pus / __puus（UC 登录凭据）");
                                    return;
                                }
                                if (!saveSaved(parsed)) {
                                    input.invalid("凭据保存失败，请检查存储权限");
                                    return;
                                }
                                AccountPanel.markDriveLoggedIn(a, PROVIDER, true);
                                dialog.dismiss();
                                a.toast("UC 网盘已登录 · " + describeShort(parsed));
                                if (done != null) done.run();
                            });
                });
        dialog.show();
    }
}
