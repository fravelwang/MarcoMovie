package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 网盘分享接口的公共管道：一次 JSON 请求 + 信封校验、JSON 取值、形状判据、文件名自然序。
 *
 * <p>为什么单独成类：夸克与 UC 的分享接口长得几乎一样（{@code status == 200 && code == 0}、
 * {@code data.list[]}、{@code fid} / {@code share_fid_token} / {@code file_type}），差别只在域名、
 * 必需的头与 query。这些管道抄两份的话，下次调超时或信封判断要改两处，必然改漏一处；所以留在
 * 这里，provider 里只剩"这一家的协议长什么样"。
 *
 * <p>所有日志都用 {@link #TAG}（logcat 里是 {@code NetdiskShare: …}），与
 * {@link NetdiskShareLines} 同一个 tag，盒子上抓一条日志就能看全程。
 */
final class NetdiskShareHttp {

    /** 分享侧统一的日志 tag。 */
    static final String TAG = "NetdiskShare";

    private static final String ACCEPT = "application/json";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    private NetdiskShareHttp() {}

    // ------------------------------------------------------------------ 请求

    /**
     * 一次请求 + 信封校验（{@code status == 200 && code == 0}）。
     *
     * <p>头按调用方给的样子设置：{@code userAgent} 必给（UC 那边换成普通浏览器 UA 会被拒），
     * {@code referer} / {@code origin} / {@code cookie} 为空就不发（UC 免登录，没有 cookie）。
     *
     * @param label 只用于错误文案：{@code 夸克} / {@code UC}
     * @return 已确认 {@code status == 200 && code == 0} 的信封
     * @throws IOException 网络失败、响应不是 JSON、或信封里的 code 不为 0
     */
    static JsonObject call(
            String label,
            String method,
            String url,
            String jsonBody,
            String userAgent,
            String referer,
            String origin,
            String cookie)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            RequestScope.attach(conn);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", text(userAgent));
            conn.setRequestProperty("Accept", ACCEPT);
            if (!text(referer).isEmpty()) conn.setRequestProperty("Referer", referer);
            if (!text(origin).isEmpty()) conn.setRequestProperty("Origin", origin);
            if (!text(cookie).isEmpty()) conn.setRequestProperty("Cookie", cookie.trim());
            if (jsonBody != null) {
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                    out.flush();
                } finally {
                    closeQuietly(out);
                }
            }
            int http = conn.getResponseCode();
            String body = readAll(http >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JsonObject parsed = parseObjectOrNull(body);
            if (parsed == null) {
                throw new IOException(text(label) + "接口响应格式异常（HTTP " + http + "）");
            }
            int status = optInt(parsed, "status", -1);
            int code = optInt(parsed, "code", -1);
            if (status == 200 && code == 0) return parsed;
            String message = optString(parsed, "message");
            throw new IOException(
                    text(label)
                            + "接口返回错误"
                            + (message.isEmpty() ? "" : "：" + message)
                            + "（code="
                            + code
                            + "，status="
                            + status
                            + "）");
        } finally {
            if (conn != null) {
                RequestScope.detach(conn);
                conn.disconnect();
            }
        }
    }

    /** {@code data.list[]}；形状不对返回 null（调用方当成"这层没东西"）。 */
    static JsonArray dataList(JsonObject body) {
        JsonElement data = body == null ? null : body.get("data");
        if (data == null || !data.isJsonObject()) return null;
        JsonElement list = data.getAsJsonObject().get("list");
        if (list == null || !list.isJsonArray()) return null;
        return list.getAsJsonArray();
    }

    /**
     * query 值转义。与 {@code QuarkQrLogin.encodeQuery} 同口径（空格 {@code %20} 而不是 {@code +}，
     * {@code *} 也转义）：分享 id / stoken 都是不透明串，不能让转义差异改变服务端看到的值。
     *
     * <p>只用 {@code encode(String, String)} 这个重载：{@code encode(String, Charset)} 是 Android
     * API 33 才有的，盒子是 API 30，用了会在运行期直接 {@code NoSuchMethodError}（实测踩过，
     * UC provider 整条挂掉）。
     */
    static String encodeQuery(String value) {
        try {
            return URLEncoder.encode(text(value), "UTF-8").replace("+", "%20").replace("*", "%2A");
        } catch (UnsupportedEncodingException error) {
            // "UTF-8" 是 JDK 规定必须支持的字符集，走不到这里；真走到了就原样返回，
            // 让服务端回一个明确的错，比在拼 query 的地方抛运行时异常好定位。
            return text(value);
        }
    }

    /**
     * 从分享链接里取出「分享 id + 提取码」：{@code /s/<id>} 与 {@code ?pwd=xxxx}。
     *
     * <p>夸克与 UC 的分享链接都是这个形状（{@code https://pan.quark.cn/s/…}、
     * {@code https://drive.uc.cn/s/…}），所以解析只写一份。
     *
     * @return null 表示不像分享链接（没有 {@code /s/} 或 id 为空）
     */
    static ShareRef parseShare(String url) {
        String value = text(url).trim();
        int marker = value.indexOf("/s/");
        if (marker < 0) return null;
        int start = marker + 3;
        int end = start;
        while (end < value.length() && isAlnum(value.charAt(end))) end++;
        String id = value.substring(start, end);
        if (id.isEmpty()) return null;
        String passcode = "";
        int query = value.indexOf('?', end);
        if (query >= 0) {
            for (String pair : value.substring(query + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                if ("pwd".equalsIgnoreCase(pair.substring(0, eq))) {
                    passcode = pair.substring(eq + 1).trim();
                    break;
                }
            }
        }
        return new ShareRef(id, passcode);
    }

    /** 主机名（小写）；解析不出来返回空串。判据只认自己的域名，见各 provider 的 {@code matches}。 */
    static String hostOf(String url) {
        try {
            String host = new URL(text(url).trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 文件名自然序

    /**
     * 文件名自然序：{@code E02} 排在 {@code E10} 前面。接口本身按 {@code file_name:asc} 返回，
     * 但那是字典序（E10 &lt; E2），所以两家都要自己再排一遍。
     */
    static int compareName(String left, String right) {
        String a = text(left);
        String b = text(right);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (isDigitChar(ca) && isDigitChar(cb)) {
                int startA = i, startB = j;
                while (i < a.length() && isDigitChar(a.charAt(i))) i++;
                while (j < b.length() && isDigitChar(b.charAt(j))) j++;
                String na = stripLeadingZeros(a.substring(startA, i));
                String nb = stripLeadingZeros(b.substring(startB, j));
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
                continue;
            }
            if (ca != cb) return Character.compare(ca, cb);
            i++;
            j++;
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static boolean isDigitChar(char c) {
        return c >= '0' && c <= '9';
    }

    private static String stripLeadingZeros(String value) {
        int i = 0;
        while (i + 1 < value.length() && value.charAt(i) == '0') i++;
        return value.substring(i);
    }

    // ------------------------------------------------------------------ 形状判据

    /** 定长 hex（fid 是 32 位、夸克 pwd_id 是 12 位）。 */
    static boolean isHex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper) return false;
        }
        return true;
    }

    /** Base64 / URL-safe base64 字母表；stoken 与 share_fid_token 都当不透明串看。 */
    static boolean isToken(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum && c != '+' && c != '/' && c != '=' && c != '_' && c != '-') return false;
        }
        return true;
    }

    private static boolean isAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // ------------------------------------------------------------------ IO 小工具

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

    static JsonObject parseObjectOrNull(String body) {
        try {
            JsonElement element = JsonParser.parseString(text(body));
            if (element == null || !element.isJsonObject()) return null;
            return element.getAsJsonObject();
        } catch (RuntimeException error) {
            return null;
        }
    }

    static String describe(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    static String text(String value) {
        return value == null ? "" : value;
    }

    static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            return element.getAsString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    static int optInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    static long optLong(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ 小数据结构

    /** 一条分享链接里那两样东西：{@code /s/<id>} 的 id 与 {@code ?pwd=} 提取码。 */
    static final class ShareRef {
        final String pwdId;
        final String passcode;

        ShareRef(String pwdId, String passcode) {
            this.pwdId = pwdId;
            this.passcode = passcode;
        }
    }
}
