package com.pilipala.marcomovie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native fallback for a 夸克 (Quark) netdisk line: the config-center plugin ({@code csp_MacCMS})
 * resolves playback URLs for us, but it has no netdisk credentials, so a 夸克 line answers
 * {@code {"list":[],"jx":0,"parse":0,"msg":"获取地址失败"}} and the player never starts. This class
 * takes the same episode id the plugin was given, reads the share it points at with our own saved
 * cookie, copies the file into our own drive when needed, and returns a playback URL.
 *
 * <p>Everything here is plain JDK + Gson on purpose (no Android APIs), like
 * {@link QuarkDriveClient}: the same code can be compiled and exercised on a desktop JDK first. The
 * single entry point {@link #resolve(String, String, String)} is synchronous and never throws - it
 * returns null so the caller keeps its original "no address" handling.
 *
 * <p>Episode id format, measured on device 2026-11-24: five {@code ++}-separated fields
 * <pre>
 *   [0] 32 hex    file fid inside the share
 *   [1] 32 hex    unconfirmed, not needed for resolution
 *   [2] 12 hex    pwd_id  (share id)
 *   [3] 44 base64 stoken  (share token)
 *   [4] digits    file size in bytes
 * </pre>
 *
 * <p>Endpoints used, all with the saved cookie plus {@code User-Agent: Mozilla/5.0},
 * {@code Referer: https://pan.quark.cn/}, {@code Accept: application/json}, and the shared envelope
 * {@code status == 200 && code == 0}:
 * <ul>
 *   <li>share listing: {@code GET /share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=&stoken=&pdir_fid=}
 *       - measured (returns {@code data.list[]} with {@code fid/file_name/file_type/size/
 *       share_fid_token})
 *   <li>own-drive search / list / play: delegated to {@link QuarkDriveClient} - measured
 *   <li>account quota: {@code GET /member?...&fetch_subscribe=true&_ch=home} - field names
 *       measured ({@code data.use_capacity} / {@code data.total_capacity}), unit unmeasured; see
 *       {@link #spaceShortage(String, long)}
 *   <li>create the save folder: {@code POST /file} - <b>not measured</b>, see
 *       {@link #createSaveDir(String)}
 *   <li>share save: {@code POST /share/sharepage/save} - <b>not measured</b>, see
 *       {@link #saveAndResolve(String, QuarkDriveClient, Ident, ShareHit, String)}
 *   <li>save task: {@code GET /task?task_id=} - <b>not measured</b>, same place
 * </ul>
 *
 * <p>Copies land in {@link #SAVE_DIR_NAME} under the drive root (created on demand) so every
 * transfer this app makes can be found, and later cleaned up, in one folder. Before each copy the
 * folder is listed and the earlier copies of the same episode (same size, same base name with the
 * {@code (n)} duplicate suffixes stripped) are deleted, so playing an episode repeatedly keeps one
 * file instead of stacking {@code (1)}, {@code (2)}, ...; when the drive still lacks the room for
 * the new copy the resolution stops with a reason the caller can show. When the folder cannot be
 * prepared the copy falls back to the root, i.e. the behaviour before the folder existed.
 */
final class QuarkShareResolver {

    private static final String TAG = "QuarkShare";

    private static final String UA = "Mozilla/5.0";
    private static final String REFERER = "https://pan.quark.cn/";
    private static final String ACCEPT = "application/json";
    private static final String BASE = "https://drive-pc.quark.cn/1/clouddrive";
    private static final String SORT = "file_type:asc,file_name:asc";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    /** One page of a share directory. */
    private static final int SHARE_PAGE_SIZE = 100;
    /** Upper bound on directories walked per resolution, so a deep share cannot hang playback. */
    private static final int SHARE_MAX_DIRS = 12;

    /** The save is asynchronous; poll its task a bounded number of times. */
    private static final int TASK_POLL_TIMES = 12;
    private static final long TASK_POLL_INTERVAL_MS = 1000L;

    /**
     * Hard cap on one resolution: a share that never answers must not keep the player spinner up
     * forever. Checked before every request, which also shortens the per-request timeouts.
     */
    private static final long TOTAL_BUDGET_MS = 60_000L;

    /** Deadline of the resolution running on this thread; unset when no resolution is running. */
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    /** {@code 2} is the "finished" value used by the spec; TODO(verify) against a real task. */
    private static final int TASK_STATUS_DONE = 2;

    /** The drive root: every 夸克 listing and save call uses {@code "0"} for it. */
    private static final String ROOT_FID = "0";

    /**
     * Dedicated destination folder for everything this app copies into the user's own drive. Kept
     * in one folder so a user can find and later clean up the app's transfers instead of hunting
     * for them among the files in the drive root.
     */
    private static final String SAVE_DIR_NAME = "MarcoPlayer";

    /**
     * Account quota, read right before a copy. Field names measured on the live service
     * 2026-11-24 ({@code data.use_capacity} / {@code data.total_capacity}); see
     * {@link #spaceShortage(String, long)} for how the numbers are interpreted.
     */
    private static final String MEMBER_URL =
            BASE + "/member?pr=ucpro&fr=pc&uc_param_str=&fetch_subscribe=true&_ch=home";

    /** Headroom kept over the file itself: a copy needs the file plus a little slack. */
    private static final double SPACE_MARGIN = 1.1d;

    private static final long GIB = 1024L * 1024L * 1024L;

    /**
     * The duplicate suffix 夸克 appends to every copy ({@code name(1).mp4}, {@code name(2).mp4}).
     * Applied to the stem only, so the extension still has to match.
     */
    private static final Pattern COPY_SUFFIX = Pattern.compile("\\s*\\(\\d{1,4}\\)\\s*$");

    /**
     * Why the last {@link #resolve(String, String, String)} refused to play, empty when it did not
     * refuse. A plain volatile field rather than a {@code ThreadLocal} because the caller reads it
     * after hopping to the UI thread. Reason text only (no 【...】 prefix): the caller renders it
     * with {@code PlaybackFailure.Attribution.SOURCE.text(reason)}.
     */
    private static volatile String lastRefusalReason = "";

    private static final String LOGIN_EXPIRED = "夸克登录已失效";

    private QuarkShareResolver() {}

    /**
     * Resolves one netdisk episode id into a playable episode, or null when this is not a 夸克 line,
     * the id is not a share id, nobody is logged in, or any request fails. Must be called from a
     * worker thread: it blocks on the network (8 s timeouts) and polls the save task in-line.
     *
     * @param siteKey the site api/key the episode came from (only used to spot a 夸克 line)
     * @param lineName the chosen line name, e.g. {@code quark原画12}
     * @param episodeId the episode url as the plugin received it, {@code ++}-separated
     */
    static Cms.Episode resolve(String siteKey, String lineName, String episodeId) {
        lastRefusalReason = "";
        Ident ident = parseIdent(episodeId);
        if (ident == null) return null;
        boolean named = mentionsQuark(siteKey) || mentionsQuark(lineName);
        if (!named && !ident.strict) {
            AppLog.w(TAG, "skip: neither the site/line nor the id shape looks like 夸克");
            return null;
        }
        QuarkQrLogin.Credential credential = QuarkQrLogin.loadSaved();
        if (credential == null || text(credential.cookie).isEmpty()) {
            AppLog.w(TAG, "skip: no saved 夸克 credential");
            return null;
        }
        AppLog.w(
                TAG,
                "start named="
                        + named
                        + " line="
                        + text(lineName)
                        + " fid="
                        + ident.fid
                        + " pwd_id="
                        + ident.pwdId
                        + " size="
                        + ident.size);
        try {
            return resolve(credential, ident, lineName);
        } catch (IOException error) {
            AppLog.w(TAG, "resolve failed: " + describe(error));
        } catch (RuntimeException error) {
            AppLog.w(TAG, "resolve failed unexpectedly", error);
        }
        return null;
    }

    /**
     * The refusal reason of the last resolution, empty when it did not refuse. Read it right after
     * {@link #resolve(String, String, String)} returned null: the value is only valid for that
     * resolution and every new one clears it.
     */
    static String lastFailureReason() {
        String reason = lastRefusalReason;
        return reason == null ? "" : reason;
    }

    /** Steps 2-5 of the spec, in order. */
    private static Cms.Episode resolve(QuarkQrLogin.Credential credential, Ident ident, String lineName)
            throws IOException {
        DEADLINE.set(Long.valueOf(System.currentTimeMillis() + TOTAL_BUDGET_MS));
        try {
            return resolveWithinBudget(credential, ident, lineName);
        } finally {
            DEADLINE.remove();
        }
    }

    private static Cms.Episode resolveWithinBudget(
            QuarkQrLogin.Credential credential, Ident ident, String lineName) throws IOException {
        QuarkDriveClient client = new QuarkDriveClient(credential.cookie);

        // Step 2: the file may already sit in our own drive from an earlier save of this episode.
        try {
            String existing = client.playUrl(ident.fid);
            if (!existing.isEmpty()) {
                AppLog.w(TAG, "step2 hit: fid already in our own drive");
                return episode(lineName, "", existing, credential.cookie);
            }
        } catch (IOException miss) {
            AppLog.w(TAG, "step2 miss: " + describe(miss));
        }

        // Step 3: walk the share and find the entry by fid, else by size.
        ShareHit hit = locateInShare(credential.cookie, ident);
        if (hit == null) {
            AppLog.w(TAG, "step3 miss: size " + ident.size + " not found inside the share");
            return null;
        }
        AppLog.w(TAG, "step3 hit: fid=" + hit.fid + " name=" + hit.name);

        // Step 4 destination: resolved once here and passed down, so one resolution never lists the
        // root twice for the folder. A failure degrades to the root and is logged, never thrown.
        String saveDirFid = ensureSaveDir(client, credential.cookie);

        // Step 4a: drop the earlier copies of this episode before copying it again, so the folder
        // never piles up name(1).mp4, name(2).mp4 ... of the same file. Only entries that match this
        // episode (same size, same base name) are candidates, and a failed delete is logged, not
        // thrown: playback must not depend on it.
        deleteOldCopies(credential.cookie, client, ident, hit, saveDirFid);

        // Step 4b: refuse the copy when the drive has no room left. Runs after the delete so the
        // space it just freed counts. Letting the save task fail instead only surfaces as "no
        // address", which hides the real cause. A capacity lookup that fails is not a refusal: the
        // copy goes ahead exactly as before.
        String shortage = spaceShortage(credential.cookie, ident.size);
        if (!shortage.isEmpty()) {
            lastRefusalReason = shortage;
            AppLog.w(TAG, "step4 refused, no transfer: " + shortage);
            return null;
        }

        // Steps 4-5: copy into our own drive, then swap the new fid for a playback URL.
        String url = saveAndResolve(credential.cookie, client, ident, hit, saveDirFid);
        if (url.isEmpty()) {
            AppLog.w(TAG, "step4/5 failed: no playable url after the save");
            return null;
        }
        return episode(lineName, hit.name, url, credential.cookie);
    }

    // ---------------------------------------------------------------- step 3: share listing

    /** The file inside the share that the episode id points at. */
    private static final class ShareHit {
        final String fid;
        final String token;
        final String name;

        ShareHit(String fid, String token, String name) {
            this.fid = fid;
            this.token = token;
            this.name = name;
        }
    }

    /**
     * Breadth-first walk over the share directories, bounded by {@link #SHARE_MAX_DIRS}. An entry
     * whose fid equals {@code ident.fid} wins outright (that is the field the device log matched);
     * otherwise the first file whose size matches is taken.
     */
    private static ShareHit locateInShare(String cookie, Ident ident) throws IOException {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add("0");
        ShareHit bySize = null;
        int visited = 0;
        while (!queue.isEmpty() && visited < SHARE_MAX_DIRS) {
            String dir = queue.poll();
            if (!seen.add(dir)) continue;
            visited++;
            JsonObject body = shareDetail(cookie, ident, dir);
            JsonArray list = dataList(body);
            if (list == null) continue;
            for (JsonElement item : list) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject obj = item.getAsJsonObject();
                if (optInt(obj, "file_type", -1) == 0) {
                    String child = optString(obj, "fid");
                    if (!child.isEmpty() && !seen.contains(child)) queue.add(child);
                    continue;
                }
                String fid = optString(obj, "fid");
                String token = optString(obj, "share_fid_token");
                if (fid.isEmpty() || token.isEmpty()) continue;
                if (fid.equals(ident.fid)) {
                    return new ShareHit(fid, token, optString(obj, "file_name"));
                }
                if (bySize == null && optLong(obj, "size", -1L) == ident.size) {
                    bySize = new ShareHit(fid, token, optString(obj, "file_name"));
                }
            }
        }
        return bySize;
    }

    /** One page of a share directory; measured against the live service (root only, page 1). */
    private static JsonObject shareDetail(String cookie, Ident ident, String dirFid) throws IOException {
        String url =
                BASE
                        + "/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id="
                        + QuarkQrLogin.encodeQuery(ident.pwdId)
                        + "&stoken="
                        + QuarkQrLogin.encodeQuery(ident.stoken)
                        + "&pdir_fid="
                        + QuarkQrLogin.encodeQuery(dirFid)
                        + "&force=0&_page=1&_size="
                        + SHARE_PAGE_SIZE
                        + "&_fetch_total=1&_sort="
                        + QuarkQrLogin.encodeQuery(SORT);
        return call("GET", url, null, cookie);
    }

    // ---------------------------------------------------------------- step 4a: destination folder

    /**
     * Returns the fid of {@link #SAVE_DIR_NAME}, creating it when it does not exist yet.
     *
     * <p>The lookup reuses {@link QuarkDriveClient#list(String)} on the drive root (the same call
     * the own-drive fallback uses), so no listing HTTP is duplicated here. The fid is cached only
     * for the current resolution, by being passed to the save as a parameter; no cross-run cache is
     * kept because the user may rename or delete the folder between runs and one root listing per
     * resolution is cheap.
     *
     * <p>Every failure (listing error, create error, answer without {@code data.fid}) degrades to
     * {@link #ROOT_FID}, which is exactly the pre-folder behaviour: playback must keep working even
     * when the folder cannot be prepared.
     *
     * @return the folder fid, or {@link #ROOT_FID} when it could not be prepared
     */
    private static String ensureSaveDir(QuarkDriveClient client, String cookie) {
        try {
            for (QuarkDriveClient.Entry entry : client.list(ROOT_FID)) {
                if (entry.dir && SAVE_DIR_NAME.equals(text(entry.name).trim())) {
                    AppLog.w(TAG, "save dir hit: " + SAVE_DIR_NAME + " fid=" + entry.fid);
                    return entry.fid;
                }
            }
        } catch (IOException error) {
            AppLog.w(TAG, "save dir lookup failed, falling back to the root: " + describe(error));
            return ROOT_FID;
        }
        try {
            String fid = createSaveDir(cookie);
            if (!fid.isEmpty()) {
                AppLog.w(TAG, "save dir created: " + SAVE_DIR_NAME + " fid=" + fid);
                return fid;
            }
            AppLog.w(TAG, "save dir create returned no fid, falling back to the root");
        } catch (IOException error) {
            AppLog.w(TAG, "save dir create failed, falling back to the root: " + describe(error));
        }
        return ROOT_FID;
    }

    /**
     * Creates {@link #SAVE_DIR_NAME} directly under the drive root.
     *
     * <p><b>Unmeasured.</b> The body below follows the public 夸克 web contract for
     * {@code POST /file} ({@code pdir_fid}, {@code file_name}, {@code dir_path},
     * {@code dir_init_lock}, answer {@code data.fid}) but has never been run against the live
     * service from this app; the raw answer is logged so the first real run pins the shape down.
     *
     * @return the new directory fid, or "" when the answer carries none
     */
    private static String createSaveDir(String cookie) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("pdir_fid", ROOT_FID);
        payload.addProperty("file_name", SAVE_DIR_NAME);
        payload.addProperty("dir_path", "");
        payload.addProperty("dir_init_lock", false);
        // TODO(verify): the /file create contract (field names, whether dir_path/dir_init_lock are
        // required, the response envelope and the location of the new fid) is unmeasured; this log
        // line and the one in ensureSaveDir are what pins it down on the first real run.
        JsonObject created = call("POST", BASE + "/file?pr=ucpro&fr=pc", payload.toString(), cookie);
        AppLog.w(TAG, "save dir create raw=" + created);
        JsonElement data = created.get("data");
        return data != null && data.isJsonObject() ? optString(data.getAsJsonObject(), "fid") : "";
    }

    // ---------------------------------------------------------------- step 4a: old copies

    /**
     * Deletes the earlier copies of the episode this resolution is about to save again.
     *
     * <p>A copy counts as this episode when its size is exactly {@code ident.size} <b>and</b> its
     * base name matches the share entry's once the duplicate suffixes 夸克 appends
     * ({@code 03.mp4}, {@code 03(1).mp4}, ...) are stripped from both sides. Nothing else in
     * {@link #SAVE_DIR_NAME} is touched: other episodes of the show keep their files.
     *
     * <p>Deleting is best effort. A lookup or delete failure is logged and the transfer goes ahead,
     * which may land the new copy as {@code name(1).mp4}; playback must not depend on this call.
     *
     * <p>TODO(verify): the delete body below ({@code action_type: 2}, {@code filelist},
     * {@code exclude_fids}) follows the public 夸克 web contract for {@code POST /file/delete} but
     * has never been run against the live service from this app. The raw answer is logged as
     * {@code delete raw=...} so the first real run pins the shape down.
     */
    private static void deleteOldCopies(
            String cookie, QuarkDriveClient client, Ident ident, ShareHit hit, String saveDirFid) {
        String wanted = baseName(hit.name);
        List<String> fids = new ArrayList<>();
        if (!wanted.isEmpty() && saveDirFid != null && !ROOT_FID.equals(saveDirFid)) {
            try {
                // Only the first page of the folder is listed (see QuarkDriveClient#list); a folder
                // grown past it leaves an extra copy behind, never a deleted wrong file.
                for (QuarkDriveClient.Entry entry : client.list(saveDirFid)) {
                    if (entry.dir || entry.size != ident.size) continue;
                    if (!wanted.equalsIgnoreCase(baseName(entry.name))) continue;
                    if (!fids.contains(entry.fid)) fids.add(entry.fid);
                }
            } catch (IOException error) {
                AppLog.w(TAG, "old copy lookup failed, transferring: " + describe(error));
            }
        }
        if (fids.isEmpty()) {
            AppLog.w(TAG, "delete done, transferring");
            return;
        }
        AppLog.w(TAG, "reuse dup delete n=" + fids.size() + " fids=" + fids);
        JsonObject payload = new JsonObject();
        payload.addProperty("action_type", 2);
        JsonArray filelist = new JsonArray();
        for (String fid : fids) filelist.add(fid);
        payload.add("filelist", filelist);
        payload.add("exclude_fids", new JsonArray());
        try {
            JsonObject answer =
                    call("POST", BASE + "/file/delete?pr=ucpro&fr=pc", payload.toString(), cookie);
            AppLog.w(TAG, "delete raw=" + answer);
        } catch (IOException error) {
            AppLog.w(TAG, "delete failed, transferring anyway: " + describe(error));
            return;
        }
        AppLog.w(TAG, "delete done, transferring");
    }

    /**
     * {@code 03.mp4} and {@code 03(1).mp4} both become {@code 03.mp4}. The suffix is stripped from
     * the stem only, so the extension stays part of the comparison.
     */
    private static String baseName(String name) {
        String value = text(name).trim();
        if (value.isEmpty()) return "";
        int dot = value.lastIndexOf('.');
        String stem = dot > 0 ? value.substring(0, dot) : value;
        String extension = dot > 0 ? value.substring(dot) : "";
        while (true) {
            Matcher matcher = COPY_SUFFIX.matcher(stem);
            if (!matcher.find()) break;
            stem = stem.substring(0, matcher.start());
        }
        if (stem.isEmpty()) return "";
        return stem + extension;
    }

    /**
     * Reads the account quota and returns a user-facing reason when the copy would not fit, or ""
     * when it may proceed.
     *
     * <p>The field names are measured ({@code data.use_capacity} / {@code data.total_capacity},
     * 2026-11-24); the unit is not. A total far below any real byte quota is read as GiB, which
     * covers both shapes; anything unreadable or implausible is a pass rather than a refusal,
     * because the save itself still enforces the real limit.
     *
     * <p>The threshold is the file size times {@link #SPACE_MARGIN} (1.1), compared against
     * {@code total_capacity - use_capacity}.
     */
    private static String spaceShortage(String cookie, long fileSize) {
        if (fileSize <= 0) return "";
        long used;
        long total;
        try {
            JsonObject body = call("GET", MEMBER_URL, null, cookie);
            JsonElement data = body == null ? null : body.get("data");
            if (data == null || !data.isJsonObject()) {
                AppLog.w(TAG, "space check: no data, transferring anyway");
                return "";
            }
            used = optLong(data.getAsJsonObject(), "use_capacity", -1L);
            total = optLong(data.getAsJsonObject(), "total_capacity", -1L);
        } catch (IOException error) {
            AppLog.w(TAG, "space check failed, transferring anyway: " + describe(error));
            return "";
        }
        if (used < 0 || total <= 0) {
            AppLog.w(TAG, "space check: capacity missing used=" + used + " total=" + total);
            return "";
        }
        // TODO(verify): the quota unit is unmeasured. A byte quota is never under 1 GiB and a GiB
        // quota is never near 2^30, so this split is safe for both shapes.
        boolean gib = total < GIB;
        long totalBytes = gib ? total * GIB : total;
        long usedBytes = gib ? used * GIB : used;
        long freeBytes = Math.max(0L, totalBytes - usedBytes);
        long requiredBytes = (long) Math.ceil(fileSize * SPACE_MARGIN);
        if (freeBytes >= requiredBytes) {
            AppLog.w(
                    TAG,
                    "space check free="
                            + gibText(freeBytes)
                            + "GB of "
                            + gibText(totalBytes)
                            + "GB, need="
                            + gibText(requiredBytes)
                            + "GB");
            return "";
        }
        AppLog.w(
                TAG,
                "space low free="
                        + gibText(freeBytes)
                        + "GB need="
                        + gibText(requiredBytes)
                        + "GB");
        return "网盘空间不足（剩 " + gibText(freeBytes) + " GB），请先清理 MarcoPlayer 里的旧文件";
    }

    /** One decimal of a byte count in GiB, used by the capacity log and the user-facing text. */
    private static String gibText(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (double) GIB);
    }

    // ---------------------------------------------------------------- step 4-5: save + play

    /**
     * Copies the share file into our own drive and returns the playback URL of the copy, or "" when
     * no candidate fid could be played.
     *
     * <p><b>Unmeasured.</b> The save body below (field names {@code fid_list}, {@code fid_token_list},
     * {@code to_pdir_fid}, {@code pwd_id}, {@code stoken}, {@code pdir_fid}, {@code scene}) and the
     * asynchronous task envelope ({@code data.task_id}, then {@code GET /task}) come from the
     * 2026-11-24 spec and have never seen a live response. Both raw bodies are therefore logged to
     * {@code AppLog.w("QuarkShare", ...)} so the first real run pins the shapes down. Because the
     * task payload may not contain the new fid, three independent ways of finding it are tried: the
     * task payload itself, an own-drive search by file name + size, and a listing of the destination
     * folder.
     *
     * @param saveDirFid destination folder fid from {@link #ensureSaveDir}, or {@link #ROOT_FID}
     */
    private static String saveAndResolve(
            String cookie,
            QuarkDriveClient client,
            Ident ident,
            ShareHit hit,
            String saveDirFid)
            throws IOException {
        JsonObject payload = new JsonObject();
        JsonArray fidList = new JsonArray();
        fidList.add(hit.fid);
        payload.add("fid_list", fidList);
        JsonArray tokenList = new JsonArray();
        tokenList.add(hit.token);
        payload.add("fid_token_list", tokenList);
        // Only to_pdir_fid selects where the copy lands. pdir_fid is the source directory inside
        // the share (0 = share root), not the destination, so it stays "0" whatever saveDirFid is.
        // TODO(verify): this split has not been measured against a live save; both fields are sent
        // together as the public examples do, and the raw answer is logged below.
        payload.addProperty("to_pdir_fid", saveDirFid);
        payload.addProperty("pwd_id", ident.pwdId);
        payload.addProperty("stoken", ident.stoken);
        payload.addProperty("pdir_fid", ROOT_FID);
        payload.addProperty("scene", "link");

        JsonObject saved =
                call("POST", BASE + "/share/sharepage/save?pr=ucpro&fr=pc", payload.toString(), cookie);
        AppLog.w(TAG, "step4 save raw=" + saved);

        List<String> candidates = new ArrayList<>();
        JsonElement savedData = saved.get("data");
        String taskId =
                savedData != null && savedData.isJsonObject()
                        ? optString(savedData.getAsJsonObject(), "task_id")
                        : "";
        if (!taskId.isEmpty()) {
            JsonObject last = null;
            for (int attempt = 0; attempt < TASK_POLL_TIMES; attempt++) {
                if (attempt > 0) sleep(TASK_POLL_INTERVAL_MS);
                if (Thread.currentThread().isInterrupted()) break;
                JsonObject poll =
                        call(
                                "GET",
                                BASE
                                        + "/task?pr=ucpro&fr=pc&task_id="
                                        + QuarkQrLogin.encodeQuery(taskId),
                                null,
                                cookie);
                last = poll;
                JsonElement pollData = poll.get("data");
                int status =
                        pollData != null && pollData.isJsonObject()
                                ? optInt(pollData.getAsJsonObject(), "status", -1)
                                : -1;
                // TODO(verify): the whole task envelope is unmeasured - the status value that means
                // "finished" (2 below) and where the new fid lives. The raw body is logged so the
                // first real run answers both.
                if (attempt == 0 || status == TASK_STATUS_DONE) {
                    AppLog.w(TAG, "step4 task#" + attempt + " status=" + status + " raw=" + poll);
                }
                collectFids(pollData, candidates);
                if (!candidates.isEmpty()) break;
                if (status == TASK_STATUS_DONE) break;
            }
            if (last != null) AppLog.w(TAG, "step4 task last raw=" + last);
        } else {
            // Some deployments answer the save inline instead of with a task; take what is there.
            AppLog.w(TAG, "step4 save returned no task_id, using the inline payload");
            collectFids(savedData, candidates);
        }

        if (Thread.currentThread().isInterrupted()) {
            AppLog.w(TAG, "step4 cancelled while waiting for the save task");
            return "";
        }

        // Step 5 on whatever the task handed us.
        String url = playFirst(client, candidates);
        if (!url.isEmpty()) return url;

        // The fids above may be the share ones rather than the copies. Look the copy up in our own
        // drive instead: by name + size (measured search endpoint), then by size in the destination
        // folder listing (saveDirFid is the root when the folder could not be prepared).
        List<String> looked = new ArrayList<>();
        if (!hit.name.isEmpty()) {
            try {
                for (QuarkDriveClient.Entry entry : client.search(hit.name)) {
                    if (!entry.dir && entry.size == ident.size) {
                        looked.add(entry.fid);
                        break;
                    }
                }
            } catch (IOException error) {
                AppLog.w(TAG, "step4 name search failed: " + describe(error));
            }
        }
        if (looked.isEmpty()) {
            try {
                for (QuarkDriveClient.Entry entry : client.list(saveDirFid)) {
                    if (!entry.dir && entry.size == ident.size) {
                        looked.add(entry.fid);
                        break;
                    }
                }
            } catch (IOException error) {
                AppLog.w(TAG, "step4 destination listing failed: " + describe(error));
            }
        }
        if (!looked.isEmpty()) AppLog.w(TAG, "step4 own-drive hit: " + looked.get(0));
        return playFirst(client, looked);
    }

    /** Swaps the first playable fid in {@code fids} for a URL; "" when none of them plays. */
    private static String playFirst(QuarkDriveClient client, List<String> fids) {
        for (int i = 0; i < fids.size() && i < 3; i++) {
            String fid = fids.get(i);
            try {
                String url = client.playUrl(fid);
                if (!url.isEmpty()) {
                    AppLog.w(TAG, "step5 fid=" + fid + " url=" + url);
                    return url;
                }
            } catch (IOException error) {
                AppLog.w(TAG, "step5 fid=" + fid + " not playable: " + describe(error));
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- episode

    private static Cms.Episode episode(String lineName, String fileName, String url, String cookie) {
        Cms.Episode episode = new Cms.Episode();
        String name = text(fileName).trim();
        if (name.isEmpty()) name = text(lineName).trim();
        if (name.isEmpty()) name = "夸克网盘";
        episode.name = name;
        episode.url = url;
        episode.headers.put("Cookie", cookie);
        episode.headers.put("User-Agent", UA);
        episode.headers.put("Referer", REFERER);
        boolean hls = looksLikeHls(url);
        try {
            // The netdisk page's flag: the sibling media cache refuses these signed URLs.
            episode.media.put("_noCache", true);
            if (hls) {
                // Quark hands out the transcoded file as HLS, and the playlist plus every
                // segment answer 412 without the saved Cookie (measured 2026-11-24). Leave the
                // file on the direct URL so the player forwards these headers on each request,
                // and mark the container so no player has to guess.
                episode.media.put("mimeType", "application/x-mpegURL");
                episode.media.put("_netdiskHls", true);
            } else {
                // A plain original: let MPV handle the high-bitrate file Media3 struggles with.
                episode.media.put("_forceMpv", true);
            }
        } catch (org.json.JSONException ignored) {
            // org.json's checked exception; every flag is optional.
        }
        return episode;
    }

    /** True when {@code url} addresses an HLS playlist, query string ignored. */
    private static boolean looksLikeHls(String url) {
        String path = text(url).trim().toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        return path.endsWith(".m3u8") || path.endsWith(".m3u");
    }

    // ---------------------------------------------------------------- episode id

    /** The five {@code ++}-separated fields of a netdisk episode id. */
    private static final class Ident {
        final String fid;
        final String pwdId;
        final String stoken;
        final long size;
        /** True when every field matches the shapes measured on device (fid 32 hex etc.). */
        final boolean strict;

        Ident(String fid, String pwdId, String stoken, long size, boolean strict) {
            this.fid = fid;
            this.pwdId = pwdId;
            this.stoken = stoken;
            this.size = size;
            this.strict = strict;
        }
    }

    /** @return null when the value is not five segments with the measured field shapes */
    private static Ident parseIdent(String raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return null;
        String[] segments = value.split("\\+\\+", -1);
        if (segments.length != 5) return null;
        if (!isHex(segments[0], 32)) return null;
        if (!isHex(segments[2], 12)) return null;
        if (!isToken(segments[3])) return null;
        if (!isDigits(segments[4])) return null;
        long size;
        try {
            size = Long.parseLong(segments[4]);
        } catch (NumberFormatException error) {
            return null;
        }
        boolean strict = isHex(segments[1], 32) && segments[3].length() == 44;
        return new Ident(segments[0], segments[2], segments[3], size, strict);
    }

    private static boolean mentionsQuark(String value) {
        String lower = text(value).toLowerCase(Locale.ROOT);
        return lower.contains("quark") || lower.contains("夸克") || lower.contains("夸父");
    }

    private static boolean isHex(String value, int length) {
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

    /** Base64 and URL-safe base64 alphabet; the stoken itself is treated as opaque. */
    private static boolean isToken(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum && c != '+' && c != '/' && c != '=' && c != '_' && c != '-') return false;
        }
        return true;
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty() || value.length() > 19) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- HTTP plumbing

    private static final class Response {
        final int http;
        final String body;

        Response(int http, String body) {
            this.http = http;
            this.body = body;
        }
    }

    /**
     * One request plus envelope validation. Returns a body that is guaranteed to carry
     * {@code status == 200 && code == 0}. No {@code Accept-Encoding} is added: the body is decoded as
     * UTF-8 and never gunzipped, so the service must answer in plain text.
     */
    private static JsonObject call(String method, String url, String jsonBody, String cookie)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            long remaining = remainingBudgetMs();
            if (remaining <= 0) throw new IOException("夸克网盘解析超时");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remaining));
            conn.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remaining));
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", ACCEPT);
            conn.setRequestProperty("Referer", REFERER);
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
            if (http == 401) throw new IOException(LOGIN_EXPIRED);
            JsonObject parsed = parseObjectOrNull(body);
            if (parsed != null) {
                int status = optInt(parsed, "status", -1);
                int code = optInt(parsed, "code", -1);
                if (status == 200 && code == 0) {
                    if (http != 200) throw new IOException("夸克接口请求失败（HTTP " + http + "）");
                    return parsed;
                }
                String message = optString(parsed, "message");
                throw new IOException(
                        "夸克接口返回错误"
                                + (message.isEmpty() ? "" : "：" + message)
                                + "（code="
                                + code
                                + "，status="
                                + status
                                + "）");
            }
            if (http != 200) throw new IOException("夸克接口请求失败（HTTP " + http + "）");
            throw new IOException("夸克接口响应格式异常");
        } catch (IOException error) {
            throw new IOException("夸克接口请求失败，请检查网络（" + describe(error) + "）", error);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Milliseconds left for the resolution on this thread; {@code Long.MAX_VALUE} when unset. */
    private static long remainingBudgetMs() {
        Long deadline = DEADLINE.get();
        if (deadline == null) return Long.MAX_VALUE;
        return deadline.longValue() - System.currentTimeMillis();
    }

    private static JsonArray dataList(JsonObject body) {
        JsonElement data = body == null ? null : body.get("data");
        if (data == null || !data.isJsonObject()) return null;
        JsonElement list = data.getAsJsonObject().get("list");
        if (list == null || !list.isJsonArray()) return null;
        return list.getAsJsonArray();
    }

    /**
     * Recursively collects plausible fids from a response, in document order: string members named
     * {@code fid} and the {@code save_as_top_fids} array the save task is expected to carry.
     */
    private static void collectFids(JsonElement element, List<String> out) {
        collectFids(element, out, 0);
    }

    private static void collectFids(JsonElement element, List<String> out, int depth) {
        if (element == null || depth > 6) return;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) collectFids(item, out, depth + 1);
            return;
        }
        if (!element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
            String key = member.getKey();
            JsonElement value = member.getValue();
            if ("save_as_top_fids".equals(key) && value != null && value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) addFid(item, out);
                continue;
            }
            if ("fid".equals(key) && value != null && value.isJsonPrimitive()) {
                addFid(value, out);
                continue;
            }
            collectFids(value, out, depth + 1);
        }
    }

    private static void addFid(JsonElement element, List<String> out) {
        if (element == null || !element.isJsonPrimitive()) return;
        String fid;
        try {
            fid = element.getAsString().trim();
        } catch (RuntimeException error) {
            return;
        }
        if (!isHex(fid, 32)) return;
        if (!out.contains(fid)) out.add(fid);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY_BYTES) break;
                out.write(buf, 0, n);
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
            // nothing useful to do
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static JsonObject parseObjectOrNull(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return null;
            return element.getAsJsonObject();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String describe(IOException error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            return element.getAsString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static long optLong(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException error) {
            return fallback;
        }
    }
}
