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
 * <p>Copies land in {@link #PLAY_DIR_NAME} inside {@link #ROOT_DIR_NAME} (both created on demand),
 * the same layout 百度 uses: one root per drive, current episode under {@code play/}. Before each
 * copy that folder is listed and the earlier copies of the same episode (same size, same base name
 * with the {@code (n)} duplicate suffixes stripped) are deleted, so playing an episode repeatedly
 * keeps one file instead of stacking {@code (1)}, {@code (2)}, ...; when the drive still lacks the
 * room for the new copy the resolution stops with a reason the caller can show. When {@code play/}
 * cannot be prepared the copy degrades to {@link #ROOT_DIR_NAME} (still ours, never the drive root);
 * that is the only place this app writes, so the user's root stays clean.
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

    /** 建目录后回读的重试次数与间隔（夸克目录列举有秒级延迟，见 {@link #ensureChildDir}）。 */
    private static final int DIR_READBACK_TRIES = 3;
    private static final long DIR_READBACK_MS = 400L;

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
     * The one folder this app owns on 夸克 (Boss 2026-11-27: 每台盘只留一个根，名字统一成
     * {@code MarcoMovie})，与百度同名同构。根目录里只放子目录，当前集的临时副本放
     * {@link #PLAY_DIR_NAME}。
     */
    private static final String ROOT_DIR_NAME = "MarcoMovie";

    /** 当前这一集的临时副本目录（{@code MarcoMovie/play}）；播前只清它，根目录不动。 */
    private static final String PLAY_DIR_NAME = "play";

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

    /** 这个 episode id 是不是夸克分享坐标（只读判据、不动网络）。 */
    static boolean looksLikeShareId(String rawEpisodeId) {
        return parseIdent(rawEpisodeId) != null;
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
                return episode(
                        lineName, "", existing, refreshPlayCookie(credential.cookie, ident.fid));
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

        // Step 4 destination: resolved once here and passed down, so one resolution lists each level
        // once. A failure never throws; with no folder of our own the copy is refused instead of being
        // dropped into the drive root (2026-11-27 定).
        String saveDirFid = ensureSaveDir(client, credential.cookie);
        if (saveDirFid.isEmpty()) {
            // 没有我们自己的落点就不转存：宁可这一集播不了，也不往盘根写文件（2026-11-27 定）。
            lastRefusalReason = "夸克临时目录 " + ROOT_DIR_NAME + "/" + PLAY_DIR_NAME + " 没备好，这次不转存";
            AppLog.w(TAG, "step4 refused, no save dir: " + lastRefusalReason);
            return null;
        }

        // Step 4a: drop the earlier copies of this episode before copying it again, so the folder
        // never piles up name(1).mp4, name(2).mp4 ... of the same file. Only entries that match this
        // episode (same size, same base name) are candidates, and a failed delete is logged, not
        // thrown: playback must not depend on it.
        // Check room BEFORE deleting anything: when the drive has no quota left, deleting the only
        // copy of this episode and then refusing would leave the user with neither a copy nor a
        // message about why. If there is enough room we delete and copy; if not we delete first
        // (that is exactly what frees the quota) and only then re-check.
        String shortage = spaceShortage(credential.cookie, ident.size);
        deleteOldCopies(credential.cookie, client, ident, hit, saveDirFid);
        if (!shortage.isEmpty()) shortage = spaceShortage(credential.cookie, ident.size);

        // Step 4b: refuse the copy when the drive still has no room. Letting the save task fail
        // instead only surfaces as "no address", which hides the real cause. A capacity lookup
        // that fails is not a refusal: the copy goes ahead exactly as before.
        if (!shortage.isEmpty()) {
            lastRefusalReason = shortage;
            AppLog.w(TAG, "step4 refused, no transfer: " + shortage);
            return null;
        }

        // Steps 4-5: copy into our own drive, then swap the new fid for a playback URL.
        PlayLink link = saveAndResolve(credential.cookie, client, ident, hit, saveDirFid);
        if (link.url.isEmpty()) {
            AppLog.w(TAG, "step4/5 failed: no playable url after the save");
            return null;
        }
        Cms.Episode playable = episode(lineName, hit.name, link.url, link.cookie);
        if (playable != null) {
            String nextIdent = identFor(ident, hit.next);
            if (!nextIdent.isEmpty()) {
                try {
                    playable.media.put("_nextShareUrl", nextIdent);
                    playable.media.put("_nextShareName", hit.next.name);
                } catch (org.json.JSONException ignored) {
                    // Auto-next then just stops at the end of this episode.
                }
            }
        }
        return playable;
    }

    // ---------------------------------------------------------------- step 3: share listing

    /** The file inside the share that the episode id points at. */
    private static final class ShareHit {
        final String fid;
        final String token;
        final String name;
        final long size;
        /** Next file in the same share directory, in name order (null on the last one). */
        final ShareHit next;

        ShareHit(String fid, String token, String name, long size, ShareHit next) {
            this.fid = fid;
            this.token = token;
            this.name = name;
            this.size = size;
            this.next = next;
        }
    }

    /**
     * Ident string for the next file of the same share, in the same {@code ++} shape the plugin
     * hands us: fid + file token + pwd_id + stoken + size.
     */
    private static String identFor(Ident ident, ShareHit hit) {
        if (hit == null) return "";
        return hit.fid + "++" + hit.token + "++" + ident.pwdId + "++" + ident.stoken + "++" + hit.size;
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
            final List<ShareHit> files = new ArrayList<>();
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
                files.add(
                        new ShareHit(
                                fid,
                                token,
                                optString(obj, "file_name"),
                                optLong(obj, "size", -1L),
                                null));
            }
            // The listing is sorted by name, so the file after the matched one is the next episode
            // inside this share. That is what auto-next follows when the source spreads a show over
            // several single-episode lines that all point at the same directory.
            for (int index = 0; index < files.size(); index++) {
                ShareHit candidate = files.get(index);
                boolean match = candidate.fid.equals(ident.fid);
                if (!match && bySize == null && candidate.size == ident.size) bySize = candidate;
                if (!match) continue;
                ShareHit next = index + 1 < files.size() ? files.get(index + 1) : null;
                return new ShareHit(
                        candidate.fid, candidate.token, candidate.name, candidate.size, next);
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
     * Returns the fid of {@code MarcoMovie/play}, creating the folders when they do not exist yet.
     *
     * <p>Same layout as 百度 (Boss 2026-11-27): one root per drive, the current episode under
     * {@code play/}. Both levels are looked up with {@link QuarkDriveClient#list(String)} (the same
     * call the own-drive fallback uses) so no listing HTTP is duplicated here, and the lookups stay
     * per-resolution: no cross-run cache, because the user may rename or delete the folders between
     * runs and one listing per level is cheap.
     *
     * <p>Every failure is logged, never thrown, and degrades **inside our own root**: {@code play/}
     * missing → use {@link #ROOT_DIR_NAME}; nothing usable at all → "" (the caller then refuses the
     * copy). It never degrades to the drive root any more: writing there is exactly what scattered
     * the user's root before 2026-11-27, and a failed resolution is cheaper than one more stray file.
     *
     * @return the folder fid, or "" when neither folder could be prepared
     */
    private static String ensureSaveDir(QuarkDriveClient client, String cookie) {
        String rootFid = ensureChildDir(client, cookie, ROOT_FID, ROOT_DIR_NAME);
        if (rootFid.isEmpty()) {
            AppLog.w(TAG, "save root " + ROOT_DIR_NAME + " unavailable, refusing the copy");
            return "";
        }
        String playFid = ensureChildDir(client, cookie, rootFid, PLAY_DIR_NAME);
        if (playFid.isEmpty()) {
            AppLog.w(
                    TAG,
                    "save dir " + ROOT_DIR_NAME + "/" + PLAY_DIR_NAME
                            + " unavailable, falling back to " + ROOT_DIR_NAME);
            return rootFid;
        }
        AppLog.w(TAG, "save dir " + ROOT_DIR_NAME + "/" + PLAY_DIR_NAME + " fid=" + playFid);
        return playFid;
    }

    /**
     * 幂等备好 {@code parentFid} 下名为 {@code name} 的子目录，返回它的 fid，失败返回 ""。
     *
     * <p>顺序照百度那边的教训：先 list，命中就用；不在才 create，create 之后**回读**确认名字没被
     * 服务端改成别的（百度对同名路径会自己加 {@code _yyyyMMdd_HHmmss} 后缀；夸克是否也这样没测过，
     * 所以只认回读到的同名目录）。
     */
    private static String ensureChildDir(
            QuarkDriveClient client, String cookie, String parentFid, String name) {
        try {
            for (NetdiskEntry entry : client.list(parentFid)) {
                if (entry.dir && name.equals(text(entry.name).trim())) {
                    AppLog.w(TAG, "dir hit " + name + " fid=" + entry.fid);
                    return entry.fid;
                }
            }
        } catch (IOException error) {
            AppLog.w(TAG, "dir lookup failed for " + name + "：" + describe(error));
            return "";
        }
        String created;
        try {
            created = createDir(cookie, parentFid, name);
        } catch (IOException error) {
            AppLog.w(TAG, "dir create failed for " + name + "：" + describe(error));
            return "";
        }
        if (created.isEmpty()) {
            AppLog.w(TAG, "dir create returned no fid for " + name);
            return "";
        }
        // 回读：夸克的目录列举有秒级延迟（2026-11-27 实测：create 成功后的下一次 list 里还没有它，
        // 下一次解析才看到）。只回读一次会把「刚建好」误判成「没备好」，进而退化到盘根写文件——
        // 那正是要消掉的老毛病。所以重试 3 次；仍看不到名字就信 create 给的 fid（名字是它回执里
        // 的那一个），绝不因为列举慢就放弃。
        for (int attempt = 1; attempt <= DIR_READBACK_TRIES; attempt++) {
            try {
                for (NetdiskEntry entry : client.list(parentFid)) {
                    if (entry.dir && name.equals(text(entry.name).trim())) {
                        AppLog.w(TAG, "dir created " + name + " fid=" + entry.fid);
                        return entry.fid;
                    }
                }
            } catch (IOException error) {
                AppLog.w(TAG, "dir readback#" + attempt + " failed for " + name + "：" + describe(error));
            }
            if (attempt < DIR_READBACK_TRIES) sleep(DIR_READBACK_MS);
        }
        AppLog.w(
                TAG,
                "dir readback missed "
                        + name
                        + " x"
                        + DIR_READBACK_TRIES
                        + "，按 create 回的 fid 用："
                        + created);
        return created;
    }

    /**
     * Creates {@code name} under {@code parentFid} and returns the new fid, or "" when the answer
     * carries none.
     *
     * <p><b>Unmeasured.</b> The body follows the public 夸克 web contract for {@code POST /file}
     * ({@code pdir_fid}, {@code file_name}, {@code dir_path}, {@code dir_init_lock}, answer
     * {@code data.fid}); the raw answer is logged so the shape stays pinned down on every run.
     */
    private static String createDir(String cookie, String parentFid, String name) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("pdir_fid", parentFid);
        payload.addProperty("file_name", name);
        payload.addProperty("dir_path", "");
        payload.addProperty("dir_init_lock", false);
        JsonObject created = call("POST", BASE + "/file?pr=ucpro&fr=pc", payload.toString(), cookie);
        AppLog.w(TAG, "dir create raw=" + created);
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
     * {@code MarcoMovie/play} is touched: other episodes of the show keep their files.
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
        if (saveDirFid == null || ROOT_FID.equals(saveDirFid)) {
            AppLog.w(TAG, "delete skipped: no save dir");
            return;
        }
        int deleted = 0;
        // The directory is a temp area owned by this app: every run starts from an empty folder, so
        // no name/size matching is needed and no stale copy can pile up. Pages are drained until the
        // listing comes back empty (QuarkDriveClient#list only returns the first page).
        for (int page = 0; page < 8; page++) {
            List<String> fids = new ArrayList<>();
            try {
                for (NetdiskEntry entry : client.list(saveDirFid)) {
                    if (entry.dir) continue;
                    if (!fids.contains(entry.fid)) fids.add(entry.fid);
                }
            } catch (IOException error) {
                AppLog.w(TAG, "clear dir listing failed: " + describe(error));
                break;
            }
            if (fids.isEmpty()) break;
            JsonObject payload = new JsonObject();
            payload.addProperty("action_type", 2);
            JsonArray filelist = new JsonArray();
            for (String fid : fids) filelist.add(fid);
            payload.add("filelist", filelist);
            payload.add("exclude_fids", new JsonArray());
            try {
                JsonObject answer =
                        call("POST", BASE + "/file/delete?pr=ucpro&fr=pc", payload.toString(), cookie);
                AppLog.w(TAG, "clear dir delete n=" + fids.size() + " raw=" + answer);
                deleted += fids.size();
            } catch (IOException error) {
                AppLog.w(TAG, "clear dir delete failed, transferring anyway: " + describe(error));
                break;
            }
        }
        AppLog.w(TAG, "clear dir done, deleted=" + deleted + ", transferring");
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
        return "网盘空间不足（剩 " + gibText(freeBytes) + " GB），请先清理 MarcoMovie/play 里的旧文件";
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
    private static PlayLink saveAndResolve(
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
            return new PlayLink("", cookie);
        }

        // Step 5 on whatever the task handed us.
        PlayLink link = playFirst(client, cookie, candidates);
        if (!link.url.isEmpty()) return link;

        // The fids above may be the share ones rather than the copies. Look the copy up in our own
        // drive instead: by name + size (measured search endpoint), then by size in the destination
        // folder listing (saveDirFid is the root when the folder could not be prepared).
        List<String> looked = new ArrayList<>();
        if (!hit.name.isEmpty()) {
            try {
                for (NetdiskEntry entry : client.search(hit.name)) {
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
                for (NetdiskEntry entry : client.list(saveDirFid)) {
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
        return playFirst(client, cookie, looked);
    }

    /** A playback URL plus the cookie it has to be fetched with (the play call rotates it). */
    private static final class PlayLink {
        final String url;
        final String cookie;

        PlayLink(String url, String cookie) {
            this.url = url;
            this.cookie = cookie;
        }
    }

    /** Swaps the first playable fid in {@code fids} for a URL; url "" when none of them plays. */
    private static PlayLink playFirst(QuarkDriveClient client, String cookie, List<String> fids) {
        for (int i = 0; i < fids.size() && i < 3; i++) {
            String fid = fids.get(i);
            String url = "";
            // 先走 /file/v2/play（和网盘中心"自己盘播放"同一条接口）。转存刚完成时服务端可能还
            // 没给出转码档，隔一会儿重试两次；只有确实没有档时才退回 file/download。
            for (int attempt = 0; attempt < 3 && url.isEmpty(); attempt++) {
                if (attempt > 0) sleep(1200L);
                if (Thread.currentThread().isInterrupted()) break;
                QuarkDriveClient.PlayVariant variant = client.transcodedVariant(fid, 6);
                if (variant != null && variant.url != null && !variant.url.isEmpty()) {
                    url = variant.url;
                    AppLog.w(
                            TAG,
                            "step5 fid=" + fid + " v2/play rank=" + variant.rank
                                    + " attempt=" + (attempt + 1));
                }
            }
            if (url.isEmpty()) {
                try {
                    url = client.directUrl(fid);
                    if (!url.isEmpty()) AppLog.w(TAG, "step5 fid=" + fid + " download fallback");
                } catch (IOException error) {
                    lastRefusalReason =
                            "夸克这条线路没拿到播放地址（" + errorCode(error) + "），已换下一条";
                    AppLog.w(TAG, "step5 fid=" + fid + " download fallback failed: " + describe(error));
                }
            }
            if (!url.isEmpty()) {
                AppLog.w(TAG, "step5 fid=" + fid + " url=" + url);
                return new PlayLink(url, refreshPlayCookie(cookie, fid));
            }
            if (lastRefusalReason.isEmpty()) {
                lastRefusalReason = "夸克这条线路没拿到播放地址（服务端没有转码档），已换下一条";
            }
        }
        return new PlayLink("", cookie);
    }

    /** {@code code=23018} 这种接口错误码，日志/文案里要说实话；抓不到就写"接口未返回地址"。 */
    private static String errorCode(IOException error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null) message = "";
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("code=(\\d{1,6})").matcher(message);
        if (matcher.find()) return "code=" + matcher.group(1);
        matcher = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(message);
        if (matcher.find()) return "HTTP " + matcher.group(1);
        return "接口未返回地址";
    }

    /**
     * 播放地址拿到之后还要补一次播放会话 Cookie（见 {@link QuarkPlayCookie}）：夸克
     * {@code /file/v2/play} 每次都在 Set-Cookie 里轮换 {@code __puus} 并下发 {@code Video-Auth}，
     * 视频 CDN 缺了这两个就回 412。失败只记日志，照旧用保存的 Cookie 去试。
     *
     * @return the saved cookie with {@code __puus} / {@code Video-Auth} replaced by the fresh ones
     */
    private static String refreshPlayCookie(String cookie, String fid) {
        long remaining = remainingBudgetMs();
        long deadline = remaining == Long.MAX_VALUE ? 0L : System.currentTimeMillis() + remaining;
        return QuarkPlayCookie.refresh(cookie, fid, deadline);
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
                // 网盘直链不再强制 mpv：盒子上的 mpv 打开这类签名直链会 audio=eof 卡住；
                // media3 + 默认优先 AAC 音轨已经能播（12:42、15:29 实测 4K 出画面）。
                episode.media.put("_forceMpv", false);
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
