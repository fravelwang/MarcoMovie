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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 阿里云盘登录：粘贴 {@code refresh_token}（{@code access_token} 有也一起收），存
 * {@code files/netdisk/ali.json}。
 *
 * <p>为什么是粘贴而不是扫码：阿里云的扫码/账密登录都挂在它自己的风控链路上，盒子上没有实测样本；
 * 而本应用播放分享只需要账号能换到 {@code access_token}，也就是一条 {@code refresh_token}。所以本次
 * 把「收下 refresh_token、按需换成 access_token」做扎实，协议一步都不自己编（照
 * {@code flutter/lib/core/ali_share.dart} 的 {@code _refreshSession} 移植）。
 *
 * <p>凭据结构照 {@code files/netdisk/quark.json} 那一套：{@code provider} / {@code version} /
 * {@code refreshToken} / {@code accessToken} / {@code driveId} / {@code accountId} /
 * {@code expiresAt} / {@code validatedAt}。
 *
 * <p>{@code access_token} 缓存 1 小时（卡里定的口径）：{@link #session()} 在过期前一分钟内刷新一次，
 * 刷新时服务端可能轮换 {@code refresh_token}，新值立刻落盘 —— 不落盘的话下次开机就只剩一条作废的
 * refresh_token。
 *
 * <p>线程：{@link #load} / {@link #save} 只碰本地文件；{@link #session()} 会打网络（单请求 8 秒连接、
 * 10 秒读超时），**必须在后台线程调用**；{@link #showPasteDialog} 必须在 UI 线程调用，它保存成功后
 * 会派一个后台任务校验凭据并把结论回投主线程。
 */
final class AliLogin {

    static final String PROVIDER = "ali";
    static final int CREDENTIAL_VERSION = 1;

    /** access_token 缓存 1 小时；服务端给的 expires_in 更短时按更短的来。 */
    private static final long SESSION_TTL_MS = 60 * 60 * 1000L;

    /** 刷新 access_token 的唯一入口（与 Flutter 端同一个）。 */
    private static final String TOKEN_URL = "https://auth.aliyundrive.com/v2/account/token";

    private static final String UA = "Mozilla/5.0";
    private static final String REFERER = "https://www.aliyundrive.com/";

    /**
     * 阿里客户端串（照 Flutter 端）。auth 接口不强制，但带上与分享侧口径一致，免得被当成陌生客户端。
     */
    private static final String CANARY = "client=Android,app=adrive,version=v4.1.4";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final int MAX_TOKEN_CHARS = 8192;

    /** token 里不能出现的字符（换行/分号/空白会破坏请求体与 Cookie 头）。 */
    private static final Pattern ILLEGAL_TOKEN = Pattern.compile("[\\x00-\\x20;\\r\\n]");

    /** 本地没有凭据：播放侧原样把这句话给 Boss 看。 */
    static final String LOGIN_REQUIRED = "需要登录阿里云盘";

    /** 有凭据但服务端不认（过期/被撤销/账号切换）。 */
    static final String LOGIN_EXPIRED = "阿里云盘登录已失效，请在网盘中心重新粘贴 refresh_token";

    /** 刷新的串行锁：两条播放同时刷新会各拿一份轮换后的 refresh_token，后写的把先写的作废。 */
    private static final Object LOCK = new Object();

    private static Session cached;
    private static File appFilesDir;

    private AliLogin() {}

    // ------------------------------------------------------------------ 凭据

    /** 存下来的凭据；{@code refreshToken} 是唯一的必需品。 */
    static final class Credential {
        final String refreshToken;
        final String accessToken;
        final String driveId;
        final String accountId;
        /** access_token 的过期时刻（epoch ms）；0 表示未知（刚粘贴、还没刷新过）。 */
        final long expiresAt;

        final String validatedAt;

        Credential(
                String refreshToken,
                String accessToken,
                String driveId,
                String accountId,
                long expiresAt,
                String validatedAt) {
            this.refreshToken = refreshToken;
            this.accessToken = accessToken;
            this.driveId = driveId;
            this.accountId = accountId;
            this.expiresAt = expiresAt;
            this.validatedAt = validatedAt;
        }

        String toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("provider", PROVIDER);
            out.addProperty("version", CREDENTIAL_VERSION);
            out.addProperty("refreshToken", refreshToken);
            out.addProperty("accessToken", accessToken);
            out.addProperty("driveId", driveId);
            out.addProperty("accountId", accountId);
            out.addProperty("expiresAt", expiresAt);
            out.addProperty("validatedAt", validatedAt);
            return out.toString();
        }

        /** 缺字段、来自别的 provider、或 refresh_token 形状不对时返回 null。 */
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
                String refresh = NetdiskShareHttp.optString(obj, "refreshToken").trim();
                if (!isTokenShape(refresh)) return null;
                String validatedAt = NetdiskShareHttp.optString(obj, "validatedAt").trim();
                if (validatedAt.isEmpty()) return null;
                return new Credential(
                        refresh,
                        cleanOptional(NetdiskShareHttp.optString(obj, "accessToken")),
                        NetdiskShareHttp.optString(obj, "driveId").trim(),
                        NetdiskShareHttp.optString(obj, "accountId").trim(),
                        NetdiskShareHttp.optLong(obj, "expiresAt", 0L),
                        validatedAt);
            } catch (RuntimeException error) {
                return null;
            }
        }
    }

    /**
     * 换好的会话：调用方拿 {@code accessToken} / {@code driveId} 发请求，拿 {@code headers()}
     * 当账号请求头。
     */
    static final class Session {
        final String accessToken;
        final String driveId;
        final String accountId;
        final String deviceId;
        /** access_token 过期时刻（epoch ms）。 */
        final long expiresAt;

        /** 刷新后实际用着的那条 refresh_token（可能被服务端轮换过）。 */
        final String refreshToken;

        Session(
                String accessToken,
                String driveId,
                String accountId,
                String deviceId,
                long expiresAt,
                String refreshToken) {
            this.accessToken = accessToken;
            this.driveId = driveId;
            this.accountId = accountId;
            this.deviceId = deviceId;
            this.expiresAt = expiresAt;
            this.refreshToken = refreshToken;
        }

        /** 账号请求头：Bearer + x-device-id（照 Flutter 的 {@code _AliSession.headers}）。 */
        Map<String, String> headers() {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("Authorization", "Bearer " + accessToken);
            if (!deviceId.isEmpty()) out.put("x-device-id", deviceId);
            return out;
        }
    }

    // ------------------------------------------------------------------ 换 token

    /**
     * 拿到一份可用的 {@link Session}：有缓存且未临近过期就复用，否则用 {@code refresh_token} 换一份。
     *
     * @throws IOException 本地没登录（{@link #LOGIN_REQUIRED}）、登录失效（{@link #LOGIN_EXPIRED}）、
     *     或刷新失败；消息已经是给人看的一句话，播放侧直接当失败原因用
     */
    static Session session() throws IOException {
        Credential credential = loadSaved();
        if (credential == null) throw new IOException(LOGIN_REQUIRED);
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (cached != null
                    && cached.refreshToken.equals(credential.refreshToken)
                    && cached.expiresAt > now + 60 * 1000L) {
                return cached;
            }
            // 等锁期间可能被另一条播放刷新过：重读一次文件，别拿旧凭据再刷一次（会作废轮换值）。
            Credential current = loadSaved();
            if (current == null || !current.refreshToken.equals(credential.refreshToken)) {
                throw new IOException(LOGIN_EXPIRED);
            }
            return refresh(current);
        }
    }

    /** 用 refresh_token 换 access_token，并把轮换后的值落盘。调用方已持有 {@link #LOCK}。 */
    private static Session refresh(Credential credential) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("grant_type", "refresh_token");
        payload.addProperty("refresh_token", credential.refreshToken);
        JsonObject body;
        try {
            body = post(TOKEN_URL, payload);
        } catch (IOException error) {
            String message = NetdiskShareHttp.describe(error);
            if (LOGIN_EXPIRED.equals(message)) throw new IOException(LOGIN_EXPIRED, error);
            throw new IOException("阿里云盘登录刷新失败（" + message + "）", error);
        }
        String access = NetdiskShareHttp.optString(body, "access_token").trim();
        if (!isTokenShape(access)) throw new IOException(LOGIN_EXPIRED);
        String refreshed = NetdiskShareHttp.optString(body, "refresh_token").trim();
        if (!isTokenShape(refreshed)) refreshed = credential.refreshToken;
        String drive =
                firstNonEmpty(
                        NetdiskShareHttp.optString(body, "resource_drive_id"),
                        NetdiskShareHttp.optString(body, "default_drive_id"),
                        NetdiskShareHttp.optString(body, "backup_drive_id"));
        if (drive.isEmpty()) {
            throw new IOException("阿里云盘账号信息不完整（缺少 drive_id），请重新登录");
        }
        String account = NetdiskShareHttp.optString(body, "user_id").trim();
        String device = NetdiskShareHttp.optString(body, "device_id").trim();
        long expiresIn = NetdiskShareHttp.optLong(body, "expires_in", SESSION_TTL_MS / 1000);
        long ttlSeconds =
                expiresIn > 0 ? Math.min(expiresIn, SESSION_TTL_MS / 1000) : SESSION_TTL_MS / 1000;
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        Session session = new Session(access, drive, account, device, expiresAt, refreshed);
        // 轮换后的 refresh_token 必须落盘；只在磁盘上还是同一份凭据时写，避免覆盖刚粘贴的新账号。
        Credential onDisk = loadSaved();
        if (onDisk != null && onDisk.refreshToken.equals(credential.refreshToken)) {
            saveSaved(
                    new Credential(
                            refreshed,
                            access,
                            drive,
                            account,
                            expiresAt,
                            credential.validatedAt));
        }
        cached = session;
        return session;
    }

    /**
     * 把 Boss 粘贴的一整段东西变成凭据；没有形状合法的 refresh_token 返回 null。
     *
     * <p>容忍几种常见形态：裸 token、{@code ali_token=…}、{@code refresh_token=…&access_token=…}、
     * 以及从 localStorage 里复制出来的 {@code {"refresh_token":"…","access_token":"…"}}。只留本类认的
     * 两个名字，其余一律丢掉。
     */
    static Credential parsePasted(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return null;
        String refresh = "";
        String access = "";
        if (text.startsWith("{")) {
            try {
                JsonElement root = JsonParser.parseString(text);
                if (root != null && root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    refresh = firstNonEmpty(refresh, optAny(obj, "refresh_token", "refreshToken"));
                    access = firstNonEmpty(access, optAny(obj, "access_token", "accessToken"));
                    JsonElement nested = obj.get("token");
                    if (nested != null && nested.isJsonObject()) {
                        JsonObject inner = nested.getAsJsonObject();
                        refresh =
                                firstNonEmpty(
                                        refresh, optAny(inner, "refresh_token", "refreshToken"));
                        access =
                                firstNonEmpty(
                                        access, optAny(inner, "access_token", "accessToken"));
                    }
                }
            } catch (RuntimeException ignored) {
                // 不是 JSON 就按普通 token 串处理
            }
        }
        if (refresh.isEmpty()) {
            String body = text;
            if (body.regionMatches(true, 0, "cookie:", 0, 7)) body = body.substring(7).trim();
            for (String part : body.split("[;&\\r\\n]+")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String name = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = unquote(part.substring(eq + 1).trim());
                if ("refresh_token".equals(name)
                        || "refreshtoken".equals(name)
                        || "ali_token".equals(name)) {
                    refresh = value;
                } else if ("access_token".equals(name) || "accesstoken".equals(name)) {
                    access = value;
                }
            }
        }
        if (refresh.isEmpty()) {
            // 裸 token：整段就是 refresh_token（形状会在下面统一校验）。
            refresh = unquote(text);
            access = "";
        }
        if (!isTokenShape(refresh)) return null;
        return new Credential(refresh, isTokenShape(access) ? access : "", "", "", 0L, isoNow());
    }

    // ------------------------------------------------------------------ 落盘

    static File credentialFile(File filesDir) {
        if (filesDir == null) return null;
        return new File(new File(filesDir, "netdisk"), "ali.json");
    }

    /** 读 {@code files/netdisk/ali.json}；没有或读不出来返回 null。 */
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
        synchronized (LOCK) {
            cached = null;
        }
    }

    /**
     * 网页扫码登录（{@link NetdiskWebLogin}）从 {@code localStorage.token} 拿到的原文 → 凭据并落盘。
     *
     * <p>与粘贴走同一条解析路（{@link #parsePasted}）：{@code {"refresh_token":"…"}} 这种 JSON、以及
     * {@code refresh_token=…&access_token=…} 这种串都认。解析或落盘失败抛 {@link IOException}，
     * 消息已经是给人看的一句话。
     */
    static Credential saveRawCookie(String raw) throws IOException {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) throw new IOException("网页登录没有拿到阿里云盘的 token");
        Credential parsed = parsePasted(text);
        if (parsed == null) throw new IOException("网页登录拿到的 token 里没有 refresh_token");
        if (!saveSaved(parsed)) throw new IOException("凭据保存失败，请检查存储权限");
        return parsed;
    }

    static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    /** UI 里的一行短状态。 */
    static String describeShort(Credential credential) {
        if (credential == null) return "未登录";
        return credential.accessToken.isEmpty() ? "refresh_token 已存" : "refresh_token 与 access_token 已存";
    }

    /** 账号面板里的一句话摘要。 */
    static String describe(Credential credential) {
        if (credential == null) return "未登录";
        StringBuilder sb = new StringBuilder("refresh_token 已存");
        if (!credential.accessToken.isEmpty()) sb.append(" · access_token 已存");
        if (!credential.driveId.isEmpty()) sb.append(" · drive ").append(credential.driveId);
        sb.append(" · 保存于 ").append(credential.validatedAt);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 粘贴框

    /**
     * 网盘中心「阿里」pill 的入口：一个粘贴框 + 保存。保存失败留在对话框里说原因，成功才关；关掉之后
     * 再派一个后台任务真刷一次 access_token，把「这条 refresh_token 能不能用」当场告诉 Boss。
     *
     * @param done 保存成功后的回调（网盘中心用它刷新 pill 状态），可 null
     */
    static void showPasteDialog(MainActivity a, Runnable done) {
        if (a == null) return;
        Credential saved = loadSaved();
        AddressInput input = new AddressInput(a, "refresh_token（有 access_token 也可一起粘贴）", "", true);
        LinearLayout box = a.vertical();
        TextView hint =
                a.text(
                        saved == null
                                ? "把阿里云盘的 refresh_token 粘进来：浏览器登录 alipan.com → F12 →"
                                        + " Application → Local Storage，复制 token 里的 refresh_token；"
                                        + "也可以直接粘 {\"refresh_token\":\"…\"}。凭据只存在本机。"
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
                        .setTitle("登录阿里云盘")
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
                                    input.invalid("请先粘贴 refresh_token");
                                    return;
                                }
                                Credential parsed = parsePasted(raw);
                                if (parsed == null) {
                                    input.invalid("缺少有效的 refresh_token");
                                    return;
                                }
                                if (!saveSaved(parsed)) {
                                    input.invalid("凭据保存失败，请检查存储权限");
                                    return;
                                }
                                AccountPanel.markDriveLoggedIn(a, PROVIDER, true);
                                dialog.dismiss();
                                a.toast("阿里云盘已登录 · refresh_token 已存，正在校验…");
                                if (done != null) done.run();
                                verifySavedAsync(a);
                            });
                });
        dialog.show();
    }

    /**
     * 后台刷一次 access_token 校验凭据；结论回投主线程（失败只提示，不改已存凭据）。
     *
     * <p>包内可见：粘贴框（{@link #showPasteDialog}）与网页扫码登录（{@link NetdiskWebLogin}）保存
     * 成功后都会调它，让 Boss 当场知道这条 refresh_token 能不能用。
     */
    static void verifySavedAsync(MainActivity a) {
        if (a == null) return;
        a.worker.execute(
                () -> {
                    String error = "";
                    try {
                        session();
                    } catch (IOException failure) {
                        error = NetdiskShareHttp.describe(failure);
                    } catch (RuntimeException failure) {
                        error = NetdiskShareHttp.describe(failure);
                    }
                    final String message = error;
                    a.ui.post(
                            () -> {
                                if (a.isFinishing()) return;
                                if (message.isEmpty()) {
                                    a.toast("阿里云盘凭据校验通过");
                                } else {
                                    a.notice(
                                            "阿里云盘凭据校验失败："
                                                    + message
                                                    + "（凭据已保存，播放时会再试一次）");
                                }
                            });
                });
    }

    // ------------------------------------------------------------------ HTTP（只要 auth 一个接口）

    /** 一次 JSON POST；非 2xx 也把正文读回来翻译。 */
    private static JsonObject post(String url, JsonObject payload) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            RequestScope.attach(conn);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("X-Canary", CANARY);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            int http = conn.getResponseCode();
            String reply = readAll(http >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JsonObject parsed = NetdiskShareHttp.parseObjectOrNull(reply);
            if (http == 400 || http == 401 || http == 403) throw new IOException(LOGIN_EXPIRED);
            if (parsed == null) {
                throw new IOException("阿里云盘登录服务响应异常（HTTP " + http + "）");
            }
            String code = NetdiskShareHttp.optString(parsed, "code").trim();
            if (!code.isEmpty() && !"0".equals(code)) {
                String message = NetdiskShareHttp.optString(parsed, "message").trim();
                String lower = message.toLowerCase(Locale.ROOT);
                if (code.toLowerCase(Locale.ROOT).contains("refresh") || lower.contains("refresh")) {
                    throw new IOException(LOGIN_EXPIRED);
                }
                throw new IOException(
                        "阿里云盘登录服务返回错误（code="
                                + code
                                + (message.isEmpty() ? "" : "，message=" + message)
                                + "）");
            }
            if (http < 200 || http >= 300) {
                throw new IOException("阿里云盘登录服务访问失败（HTTP " + http + "）");
            }
            return parsed;
        } finally {
            if (conn != null) {
                RequestScope.detach(conn);
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_BODY_BYTES) break;
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // 关不掉也没有别的补救
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // 同上
        }
    }

    // ------------------------------------------------------------------ 小判据

    /** token 形状：非空、不超长、不含空白/分号、不是 URL。 */
    static boolean isTokenShape(String value) {
        String token = value == null ? "" : value.trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_CHARS) return false;
        if (ILLEGAL_TOKEN.matcher(token).find()) return false;
        return !token.contains("://");
    }

    private static String cleanOptional(String value) {
        String token = value == null ? "" : value.trim();
        return isTokenShape(token) ? token : "";
    }

    private static String optAny(JsonObject obj, String first, String second) {
        String value = NetdiskShareHttp.optString(obj, first).trim();
        return value.isEmpty() ? NetdiskShareHttp.optString(obj, second).trim() : value;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String unquote(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2
                && ((text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"')
                        || (text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\''))) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }
}
