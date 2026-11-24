package com.pilipala.marcomovie;

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
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Self-contained 夸克网盘 (Quark) QR-code login client.
 *
 * <p>This replaces the "ask the spider config center, then guess from its side effects" flow: the
 * app owns the whole exchange (create the QR token, poll it, swap the CAS ticket for pan cookies,
 * verify the account) and hands back one credential blob that {@link AccountPanel} and the playback
 * proxy read as the single source of truth.
 *
 * <p>Protocol reference: {@code flutter/lib/core/netdisk_service.dart} +
 * {@code flutter/lib/core/quark_account.dart}, re-measured against the live service on 2026-11-24
 * (create-token returns {@code status=2000000} and sets {@code _UP_28A_52_=532}; an unscanned poll
 * returns {@code 50004001}). No Android APIs are used here on purpose, so the class can be compiled
 * and exercised on a desktop JDK before it ever runs on the box.
 */
final class QuarkQrLogin {

    static final String PROVIDER = "quark";
    static final int CREDENTIAL_VERSION = 1;

    /** CAS / uop endpoints want a browser-ish UA; the pan endpoints accept a bare one. */
    private static final String UA_CAS =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like"
                    + " Gecko) Version/17.4 Safari/605.1.15";

    private static final String UA_PAN = "Mozilla/5.0";

    private static final String CLIENT_ID = "532";
    private static final String CAS_VERSION = "1.2";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final int MAX_COOKIE_HEADER = 16 * 1024;

    /** Poll outcomes. 夸克 has no "scanned, waiting for confirm" state: it stays WAITING. */
    enum Poll {
        WAITING,
        CONFIRMED,
        EXPIRED,
        ERROR
    }

    /** A freshly created QR code: the raw token plus the URL the QR image must encode. */
    static final class QrSession {
        final String token;
        final String contentUrl;

        QrSession(String token, String contentUrl) {
            this.token = token;
            this.contentUrl = contentUrl;
        }
    }

    /** One poll attempt. {@code credential} is non-null only when {@code state == CONFIRMED}. */
    static final class PollResult {
        final Poll state;
        final String message;
        final Credential credential;

        PollResult(Poll state, String message, Credential credential) {
            this.state = state;
            this.message = message;
            this.credential = credential;
        }
    }

    /** The persisted credential. {@code cookie} is the only thing playback needs. */
    static final class Credential {
        final String cookie;
        final String driveSt;
        final String accountId;
        final String validatedAt;

        Credential(String cookie, String driveSt, String accountId, String validatedAt) {
            this.cookie = cookie;
            this.driveSt = driveSt;
            this.accountId = accountId;
            this.validatedAt = validatedAt;
        }

        String toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("provider", PROVIDER);
            out.addProperty("version", CREDENTIAL_VERSION);
            out.addProperty("cookie", cookie);
            out.addProperty("driveSt", driveSt);
            out.addProperty("accountId", accountId);
            out.addProperty("validatedAt", validatedAt);
            return out.toString();
        }

        /** Returns null when the blob is missing, from another provider, or self-inconsistent. */
        static Credential parse(String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                JsonElement root = JsonParser.parseString(json);
                if (root == null || !root.isJsonObject()) return null;
                JsonObject obj = root.getAsJsonObject();
                if (!PROVIDER.equals(optString(obj, "provider"))) return null;
                if (obj.has("version") && obj.get("version").getAsInt() != CREDENTIAL_VERSION) {
                    return null;
                }
                String cookie = optString(obj, "cookie");
                if (cookie.isEmpty() || cookie.length() > 40 * 1024) return null;
                String pus = cookieValue(cookie, "__pus");
                if (pus.isEmpty()) return null;
                String accountId = optString(obj, "accountId");
                if (!accountId.isEmpty() && !accountId.equals(sha256Hex(pus))) return null;
                String validatedAt = optString(obj, "validatedAt");
                if (validatedAt.isEmpty()) return null;
                return new Credential(cookie, optString(obj, "driveSt"), accountId, validatedAt);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * cookie domain -> (cookie name -> value). Insertion ordered, so the Cookie header this class
     * emits for the same jar is byte-stable. The domain is the Set-Cookie {@code Domain} attribute
     * when present (a leading dot is stripped), otherwise the request host — that is what lets the
     * {@code .quark.cn} session cookies reach {@code drive-pc.quark.cn}.
     */
    private final Map<String, LinkedHashMap<String, String>> jar = new LinkedHashMap<>();

    /** Clears the jar. Call before creating a new QR code so no stale session leaks in. */
    void reset() {
        jar.clear();
    }

    /** Step 1: ask CAS for a QR token bound to this session's cookies. */
    QrSession start() throws IOException {
        reset();
        String url =
                "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id="
                        + CLIENT_ID
                        + "&v="
                        + CAS_VERSION
                        + "&request_id="
                        + System.currentTimeMillis();
        Response resp = get(url, UA_CAS, null, true);
        if (resp.code != 200) {
            throw new IOException("夸克登录服务暂时不可用（HTTP " + resp.code + "）");
        }
        JsonObject body = parseObject(resp.body);
        int status = optInt(body, "status", -1);
        String token = memberString(body, "token");
        if (status != 2000000 || !validToken(token)) {
            throw new IOException("夸克二维码创建失败（status=" + status + "）");
        }
        return new QrSession(token, buildQrContent(token));
    }

    /** Step 2: poll. Returns WAITING until the phone confirms; never throws for a normal poll. */
    PollResult poll(String token) {
        if (!validToken(token)) {
            return new PollResult(Poll.ERROR, "夸克二维码已失效，请刷新后重试", null);
        }
        String url =
                "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id="
                        + CLIENT_ID
                        + "&v="
                        + CAS_VERSION
                        + "&token="
                        + encodeQuery(token)
                        + "&request_id="
                        + System.currentTimeMillis();
        Response resp;
        JsonObject body;
        try {
            resp = get(url, UA_CAS, null, true);
            if (resp.code != 200) {
                return new PollResult(Poll.ERROR, "夸克账号验证失败，请重新扫码", null);
            }
            body = parseObject(resp.body);
        } catch (IOException e) {
            return new PollResult(Poll.ERROR, "夸克账号验证失败，请重新扫码", null);
        }
        int status = optInt(body, "status", -1);
        if (status == 2000000) {
            String ticket = memberString(body, "service_ticket");
            if (!validTicket(ticket)) {
                return new PollResult(Poll.ERROR, "扫码授权凭据无效，请重新扫码", null);
            }
            try {
                return new PollResult(Poll.CONFIRMED, "夸父（夸克网盘）扫码登录成功！", exchange(ticket));
            } catch (IOException e) {
                String msg = e.getMessage();
                return new PollResult(
                        Poll.ERROR, msg == null || msg.isEmpty() ? "夸克账号验证失败" : msg, null);
            }
        }
        if (status == 50004001) {
            return new PollResult(Poll.WAITING, "等待使用手机「夸克 App」扫码并确认…", null);
        }
        if (status == 50004002) {
            return new PollResult(Poll.EXPIRED, "二维码已过期，请点击刷新二维码", null);
        }
        return new PollResult(Poll.ERROR, "夸克扫码授权尚未完成，请刷新后重试", null);
    }

    /** Steps 3-5: CAS ticket -> pan cookies -> account verification -> optional Drive ST. */
    private Credential exchange(String ticket) throws IOException {
        // No Cookie header on the first hop: this is what mints __pus/__puus for us.
        Response info =
                get("https://pan.quark.cn/account/info?st=" + encodeQuery(ticket) + "&lw=scan",
                        UA_PAN, null, false);
        if (info.code != 200) {
            throw new IOException("夸克账号验证失败（HTTP " + info.code + "）");
        }
        String pus = cookieValue(cookieHeader("pan.quark.cn"), "__pus");
        if (pus.isEmpty()) {
            throw new IOException("夸克未返回登录凭据，请重新扫码");
        }
        String identity = sha256Hex(pus);

        JsonObject member =
                parseObject(
                        get(
                                        "https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc"
                                                + "&uc_param_str=&fetch_subscribe=true&_ch=home"
                                                + "&fetch_identity=true",
                                        UA_PAN,
                                        null,
                                        true)
                                .body);
        if (optInt(member, "status", -1) != 200 || optInt(member, "code", -1) != 0) {
            throw new IOException("夸克账号校验未通过，请重新扫码");
        }
        JsonElement data = member.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new IOException("夸克账号校验未通过，请重新扫码");
        }
        JsonObject dataObj = data.getAsJsonObject();
        boolean hasTier = dataObj.has("member_type") && dataObj.get("member_type").isJsonPrimitive();
        boolean hasCapacity =
                dataObj.has("total_capacity") && dataObj.get("total_capacity").isJsonPrimitive();
        if (!hasTier && !hasCapacity) {
            throw new IOException("夸克账号校验未通过，请重新扫码");
        }
        String rotated = cookieValue(cookieHeader("pan.quark.cn"), "__pus");
        if (rotated.isEmpty() || !sha256Hex(rotated).equals(identity)) {
            throw new IOException("夸克账号已变化，请重新扫码");
        }

        String driveSt = "";
        try {
            JsonObject mobile =
                    parseObject(
                            get(
                                            "https://pan.quark.cn/account/mobileinfo?pr=ucpro&fr=h5&st="
                                                    + encodeQuery(ticket),
                                            UA_PAN,
                                            null,
                                            true)
                                    .body);
            if (optInt(mobile, "status", -1) == 200 && optInt(mobile, "code", -1) == 0) {
                JsonElement md = mobile.get("data");
                String st = md != null && md.isJsonObject() ? optString(md.getAsJsonObject(), "serviceTicket") : "";
                if (validTicket(st)) driveSt = st;
            }
        } catch (IOException ignored) {
            // Drive ST is optional: the PC endpoints work with cookies alone.
        }

        return new Credential(cookieHeader("pan.quark.cn"), driveSt, identity, isoNow());
    }

    /** The QR image must encode this URL; the phone's 夸克 App opens it and asks to confirm. */
    static String buildQrContent(String token) {
        return "https://su.quark.cn/4_eMHBJ"
                + "?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt"
                + "&token="
                + encodeQuery(token)
                + "&client_id="
                + CLIENT_ID
                + "&ssb=weblogin"
                + "&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401"
                + "%7COPT%3ABACK_BTN_STYLE%400";
    }

    // ---------------------------------------------------------------- HTTP plumbing

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Response get(String url, String ua, String explicitCookie, boolean withCookies)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Referer", "https://pan.quark.cn/");
            conn.setRequestProperty("Origin", "https://pan.quark.cn");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            String cookie = explicitCookie != null
                    ? explicitCookie
                    : (withCookies ? cookieHeader(hostOf(url)) : "");
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            absorbCookies(hostOf(url), conn.getHeaderFields().get("Set-Cookie"));
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new Response(code, body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        InputStream src = in;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = src.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY_BYTES) break;
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                src.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return "";
        }
    }

    private void absorbCookies(String requestHost, List<String> setCookies) {
        if (requestHost.isEmpty() || setCookies == null) return;
        for (String raw : setCookies) {
            if (raw == null || raw.isEmpty()) continue;
            String[] parts = raw.split(";");
            int eq = parts[0].indexOf('=');
            if (eq <= 0) continue;
            String name = parts[0].substring(0, eq).trim();
            String value = parts[0].substring(eq + 1).trim();
            if (name.isEmpty() || !validCookieName(name)) continue;
            String domain = requestHost;
            boolean drop = false;
            boolean expired = false;
            for (int i = 1; i < parts.length; i++) {
                String attr = parts[i].trim();
                int aeq = attr.indexOf('=');
                String an = (aeq < 0 ? attr : attr.substring(0, aeq)).trim().toLowerCase(Locale.ROOT);
                String av = aeq < 0 ? "" : attr.substring(aeq + 1).trim();
                if ("path".equals(an) && !"/".equals(av)) drop = true;
                if ("domain".equals(an) && !av.isEmpty()) {
                    domain = av.startsWith(".") ? av.substring(1) : av;
                    domain = domain.toLowerCase(Locale.ROOT);
                }
                if ("max-age".equals(an)) {
                    try {
                        if (Long.parseLong(av) <= 0) expired = true;
                    } catch (NumberFormatException ignored) {
                        // keep the cookie when the attribute is unparseable
                    }
                }
            }
            if (drop || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) continue;
            LinkedHashMap<String, String> domainJar = jar.get(domain);
            if (expired) {
                if (domainJar != null) domainJar.remove(name);
                continue;
            }
            if (domainJar == null) {
                domainJar = new LinkedHashMap<>();
                jar.put(domain, domainJar);
            }
            domainJar.put(name, value);
        }
    }

    /**
     * Cookies visible to {@code host}: its own domain jar plus every parent domain, in RFC 6265
     * order (more specific domain first, then insertion order inside each jar).
     */
    private String cookieHeader(String host) {
        if (host == null || host.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String domain = host;
        while (true) {
            appendJar(sb, domain);
            int dot = domain.indexOf('.');
            if (dot < 0) break;
            domain = domain.substring(dot + 1);
            if (domain.indexOf('.') < 0) {
                // still include the registrable-ish parent (e.g. quark.cn)
                appendJar(sb, domain);
                break;
            }
        }
        return sb.length() > MAX_COOKIE_HEADER ? sb.substring(0, MAX_COOKIE_HEADER) : sb.toString();
    }

    private void appendJar(StringBuilder sb, String domain) {
        LinkedHashMap<String, String> domainJar = jar.get(domain);
        if (domainJar == null) return;
        for (Map.Entry<String, String> e : domainJar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
    }

    // ---------------------------------------------------------------- small helpers

    private JsonObject parseObject(String body) throws IOException {
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el == null || !el.isJsonObject()) throw new IOException("响应格式异常");
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("响应格式异常");
        }
    }

    /** {@code data.members.<key>} as a string; empty when the shape does not match. */
    private static String memberString(JsonObject body, String key) {
        JsonElement data = body.get("data");
        if (data == null || !data.isJsonObject()) return "";
        JsonElement members = data.getAsJsonObject().get("members");
        if (members == null || !members.isJsonObject()) return "";
        return optString(members.getAsJsonObject(), key);
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return "";
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static String cookieValue(String header, String name) {
        if (header == null || header.isEmpty() || name == null || name.isEmpty()) return "";
        for (String part : header.split(";")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            if (item.substring(0, eq).trim().equals(name)) return item.substring(eq + 1).trim();
        }
        return "";
    }

    private static boolean validCookieName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == ';' || c == ',' || c == '=') return false;
        }
        return true;
    }

    private static boolean validToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 8192) return false;
        if (token.startsWith("fallback_")) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c == 0x7f) return false;
        }
        return true;
    }

    private static boolean validTicket(String ticket) {
        if (ticket == null || ticket.isEmpty() || ticket.length() > 8192) return false;
        if (ticket.startsWith("fallback_") || ticket.contains("qr_auth_")) return false;
        for (int i = 0; i < ticket.length(); i++) {
            char c = ticket.charAt(i);
            if (c <= 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /** RFC 3986 percent-encoding, matching Dart's {@code Uri.encodeQueryComponent}. */
    static String encodeQuery(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    static String sha256Hex(String value) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    /** Verifies a saved blob without touching the network. */
    static boolean isUsable(String json) {
        return Credential.parse(json) != null;
    }

    /** Reads {@code files/netdisk/quark.json}; null when absent or unreadable. */
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
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /** Writes the blob atomically (tmp + rename) so a crash never leaves a half file. */
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
        } catch (IOException e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
            if (tmp.exists() && !tmp.equals(file) && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /**
     * The app's {@code getFilesDir()}, installed once from {@link MainActivity#onCreate}. Playback
     * header injection has no Activity in scope, so the credential location travels with the class.
     */
    private static File appFilesDir;

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

    static void clear(File filesDir) {
        File file = credentialFile(filesDir);
        if (file != null && file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static File credentialFile(File filesDir) {
        if (filesDir == null) return null;
        return new File(new File(filesDir, "netdisk"), "quark.json");
    }

    /** One-liner for the drive card: badge text has room for an id, not a full summary. */
    static String describeShort(Credential credential) {
        if (credential == null) return "未登录";
        String id = credential.accountId;
        return "账号 " + (id.length() >= 8 ? id.substring(0, 8) : id);
    }

    /** Human-readable summary for the account panel. */
    static String describe(Credential credential) {
        if (credential == null) return "未登录";
        String id = credential.accountId;
        String shortId = id.length() >= 8 ? id.substring(0, 8) : id;
        List<String> bits = new ArrayList<>();
        bits.add("账号 " + shortId);
        if (!credential.driveSt.isEmpty()) bits.add("Drive ST 已取");
        bits.add("校验于 " + credential.validatedAt);
        StringBuilder sb = new StringBuilder();
        for (String bit : bits) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(bit);
        }
        return sb.toString();
    }
}
