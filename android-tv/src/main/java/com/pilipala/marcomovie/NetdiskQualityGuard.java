package com.pilipala.marcomovie;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Locale;

/**
 * Adaptive quality guard for native netdisk playback (夸克): when the box cannot decode the tier
 * being played, restart the same file one tier lower instead of letting it play as a slideshow.
 *
 * <p>Why this exists: on the Amlogic TV box, 4K60 H.264 is decoded by
 * {@code OMX.amlogic.avc.decoder.awesome2} at roughly 40 dropped frames per second (50 dropped
 * frames in 1.2 s, measured 2026-11-24 through the {@code PlayStats} AnalyticsListener in
 * {@link MoviePlayer}). HEVC and 4K30 clips play smoothly on the same box, so this is a
 * tier/decoder limit, not a network problem. {@link QuarkDriveClient#playVariant} returns the URL
 * plus the tier it actually got, and {@link MoviePlayer} reports dropped-frame batches; this class
 * joins the two.
 *
 * <p>Observation rules, with the basis for each number:
 * <ul>
 *   <li>Only the first {@value #OBSERVE_WINDOW_MS} ms after playback is armed are observed. A
 *       decode limit shows up immediately; stalls after that window are network/buffering.
 *   <li>The first {@value #WARMUP_MS} ms are ignored because player setup and the first rendered
 *       frames legitimately drop a burst of frames.
 *   <li>A "hot" report is one where dropped frames divided by elapsed seconds is at least
 *       {@value #MIN_DROP_FPS}. The measured failure is ~40 fps, so 15 fps is well clear of normal
 *       jitter while still catching a box that drops every other frame.
 *   <li>{@value #CONSECUTIVE_HOT_REPORTS} hot reports in a row mean sustained drops, not a one-off
 *       hiccup; at the measured rate that lands within a few seconds of playback. The hot streak
 *       must also span at least {@value #MIN_HOT_SPAN_MS} ms: a startup or seek burst drops frames
 *       for well under a second, a genuine 4K60 decode failure keeps dropping for seconds.
 *   <li>At most {@value #MAX_DOWNGRADES} automatic downgrades per playback, so a box that drops
 *       frames at every tier cannot restart in a loop. When the limit or the lowest tier is
 *       reached the guard only shows a hint.
 * </ul>
 *
 * <p>Seek false positives: a seek inside the observation window can drop a burst of frames. The
 * warm-up, the consecutive-report rule and {@link #MIN_HOT_SPAN_MS} keep sub-second bursts from
 * looking like a decode limit; the hot streak is also reset by any below-threshold report. Full
 * immunity would need seek events, which the allowed MoviePlayer change does not expose.
 * TODO(verify): watch the device logs for a downgrade right after a seek and add a seek reset if
 * it happens.
 *
 * <p>The memory is per device, not per file. Keys are
 * {@code netdisk_tier_unfit_<resolution>} (for example {@code netdisk_tier_unfit_4k}) in the
 * {@link Prefs#FILE} preferences. They are deliberately not added to {@link Prefs#KEYS}: the
 * memory describes this box's decoder, so a settings reset should keep it and a settings backup
 * should not copy it to a different device. Once a tier is marked unfit, the next playback calls
 * {@link #preferredStartRank(Context, int)} and asks {@link QuarkDriveClient#playVariant} for the
 * next tier down from the start, instead of trying 4K again first.
 *
 * <p>The memory is keyed by resolution only. TODO(verify): the box plays 4K30 and HEVC 4K fine, so
 * this key may downgrade those unnecessarily; if that shows up on the box, extend the key with
 * frame rate and codec.
 *
 * <p>Manual choice wins: {@link #preferredStartRank(Context, int)} returns a valid manual rank
 * before it looks at the memory. {@code NetdiskScreen.play(entry, manualRank)} is the manual entry
 * point; today the netdisk browser has no quality picker, so it passes -1 and a future picker only
 * has to pass 1..6.
 *
 * <p>Scope: only native netdisk playback is observed. {@link #arm} refuses any episode without the
 * {@code _netdiskNative} marker that {@link NetdiskScreen} sets, so ordinary web lines never arm
 * this guard even though {@link MoviePlayer} reports frames process-wide.
 *
 * <p>Note: native netdisk episodes also carry {@code _forceMpv}. When libmpv is available the
 * playback runs in {@link MpvPlayer}, which extends {@link MoviePlayer} but does not install the
 * Media3 analytics listener, so no drop reports arrive and the guard stays idle for that playback.
 * That is intended: the guard is the safety net for the Media3 path (MPV init failed, or the build
 * has no libmpv), which is the path that plays 4K60 as a slideshow. TODO(verify): if the box
 * reports 4K60 drops while MPV is in use, MPV needs its own drop counter.
 */
final class NetdiskQualityGuard {

    // Tier ranks copied from QuarkDriveClient.resolutionRank:
    // 4k > 2k > super > high > low > normal.
    static final int RANK_NORMAL = 1;
    static final int RANK_4K = 6;

    /** Observe only the first 20 s of playback; see the class comment. */
    static final long OBSERVE_WINDOW_MS = 20_000L;

    /**
     * Ignore the startup burst; see the class comment. The exact 1.5 s is a judgement call.
     * TODO(verify): tune on the box if startup drops are still counted, or if a real 4K60 stall
     * starts inside this guard window and is ignored.
     */
    static final long WARMUP_MS = 1_500L;

    /** Dropped frames per second that counts as "cannot decode this tier". */
    static final double MIN_DROP_FPS = 15.0;

    /** Consecutive hot reports required before acting. */
    static final int CONSECUTIVE_HOT_REPORTS = 3;

    /**
     * Minimum wall-clock length of the hot streak. basis: startup and seek bursts drop frames for
     * well under a second, while the measured 4K60 failure drops continuously.
     * TODO(verify): raise or lower from device logs if a seek still triggers a downgrade.
     */
    static final long MIN_HOT_SPAN_MS = 700L;

    /** Hard cap on automatic restarts per playback. */
    static final int MAX_DOWNGRADES = 2;

    private static final String TAG = "NetdiskQuality";
    private static final String UNFIT_KEY_PREFIX = "netdisk_tier_unfit_";

    /** Index = rank; the strings are the service's {@code resolution} values. */
    private static final String[] RESOLUTIONS = {
        null, "normal", "low", "high", "super", "2k", "4k"
    };

    /** Index = rank; short Chinese labels for on-screen hints, matching the resolution names. */
    private static final String[] LABELS = {null, "流畅", "标清", "高清", "超清", "2K", "4K"};

    private static final Object LOCK = new Object();

    /** The playback currently being observed, or null. Guarded by {@link #LOCK}. */
    private static Session active;

    private static long nextSessionId = 1L;
    private static volatile boolean installed;

    /** What the guard can do when it decides the current tier is too heavy. */
    interface Hooks {
        /**
         * Restart the same file at a lower cap. Called on the UI thread; the implementation must
         * fetch the URL off the UI thread and call {@link #arm} again after restarting.
         *
         * @param maxRank highest tier to accept
         * @param downgrades downgrades already spent, including this one
         * @param fromRank tier the playback is leaving
         */
        void restart(int maxRank, int downgrades, int fromRank);

        /** Show one Chinese line on the player screen without restarting playback. */
        void hint(String message);
    }

    private static final class Session {
        final long id;
        final MainActivity activity;
        final int rank;
        final String resolution;
        final int downgrades;
        final long armedAtMs;
        final Hooks hooks;
        int consecutiveHot;
        long hotSinceMs;
        boolean fired;

        Session(
                long id,
                MainActivity activity,
                int rank,
                String resolution,
                int downgrades,
                long armedAtMs,
                Hooks hooks) {
            this.id = id;
            this.activity = activity;
            this.rank = rank;
            this.resolution = resolution;
            this.downgrades = downgrades;
            this.armedAtMs = armedAtMs;
            this.hooks = hooks;
        }
    }

    private NetdiskQualityGuard() {}

    /**
     * Start observing one native netdisk playback. Safe to call again for the restarted playback;
     * the newest session replaces the previous one.
     *
     * @param rank tier of the URL that is about to play; 0 (original-file fallback) is not observed
     *     because its quality is unknown
     */
    static void arm(
            MainActivity activity,
            Cms.Episode episode,
            int rank,
            String resolution,
            int downgrades,
            Hooks hooks) {
        if (activity == null || hooks == null) return;
        // Requirement: web lines are never touched; only native netdisk episodes carry the marker.
        if (episode == null || !episode.media.optBoolean("_netdiskNative", false)) return;
        if (rank < RANK_NORMAL || rank > RANK_4K) {
            // Original-file fallback or a resolution label QuarkDriveClient does not rank: no
            // lower tier can be chosen, so observing would only produce hints. Device logs can
            // confirm whether a play was expected to be guarded.
            AppLog.w(
                    NetdiskQualityGuard.TAG,
                    "not observed: rank=" + rank + " resolution=" + resolution);
            return;
        }
        install();
        final Session session;
        synchronized (LOCK) {
            session =
                    new Session(
                            nextSessionId++,
                            activity,
                            rank,
                            normalizeResolution(resolution, rank),
                            Math.max(0, downgrades),
                            SystemClock.elapsedRealtime(),
                            hooks);
            active = session;
        }
        // The observer also clears expired sessions, but this keeps the activity reference from
        // outliving the observation window when no further drop reports arrive.
        activity.ui.postDelayed(() -> clearIfActive(session.id), OBSERVE_WINDOW_MS + 1_000L);
        AppLog.i(
                NetdiskQualityGuard.TAG,
                "armed rank=" + rank + " (" + LABELS[rank] + ") downgrades=" + downgrades);
    }

    /**
     * Stops observing the current playback. Called when the user starts another netdisk video, so
     * a decision queued for the previous playback cannot reopen it after the click.
     */
    static void disarm() {
        synchronized (LOCK) {
            if (active == null) return;
            active = null;
        }
        AppLog.i(NetdiskQualityGuard.TAG, "disarmed");
    }

    /**
     * Initial tier cap for an automatic play: the highest rank whose resolution is not remembered
     * as unfit on this device.
     *
     * <p>Manual selection wins: a valid {@code manualRank} (1..6) is returned as-is, skipping the
     * memory entirely, so a user who explicitly picks 4K gets 4K even after the guard marked it
     * unfit. The netdisk browser has no picker yet, so NetdiskScreen passes -1 today.
     */
    static int preferredStartRank(Context context, int manualRank) {
        if (manualRank >= RANK_NORMAL && manualRank <= RANK_4K) return manualRank;
        int rank = RANK_4K;
        while (rank > RANK_NORMAL && isTierUnfit(context, rank)) rank--;
        return rank;
    }

    /** Display label for a rank; "原片" for an unknown/original-file URL. */
    static String labelOfRank(int rank) {
        return rank >= RANK_NORMAL && rank <= RANK_4K ? LABELS[rank] : "原片";
    }

    /** True when this device already proved it cannot decode that resolution tier. */
    static boolean isTierUnfit(Context context, int rank) {
        if (context == null || rank < RANK_NORMAL || rank > RANK_4K) return false;
        SharedPreferences prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        return prefs.getBoolean(UNFIT_KEY_PREFIX + RESOLUTIONS[rank], false);
    }

    private static void install() {
        if (installed) return;
        synchronized (LOCK) {
            if (installed) return;
            // Process-wide sink: MoviePlayer reports every ExoPlayer instance, the guard filters
            // by the session's player identity before it acts.
            MoviePlayer.setDropFrameSink(NetdiskQualityGuard::onDroppedFrames);
            installed = true;
        }
    }

    /**
     * Called from {@link MoviePlayer}'s analytics listener on the playback thread for every
     * dropped-frame batch. Never touches views: a decision is posted to the activity's UI handler.
     */
    private static void onDroppedFrames(MoviePlayer player, int droppedFrames, long elapsedMs) {
        if (player == null || droppedFrames <= 0 || elapsedMs <= 0) return;
        Session fired;
        synchronized (LOCK) {
            Session session = active;
            if (session == null || session.fired) return;
            long now = SystemClock.elapsedRealtime();
            long ageMs = now - session.armedAtMs;
            if (ageMs < WARMUP_MS) return;
            if (ageMs > OBSERVE_WINDOW_MS) {
                active = null;
                return;
            }
            double dropFps = droppedFrames * 1000.0 / elapsedMs;
            if (dropFps >= MIN_DROP_FPS) {
                if (session.consecutiveHot == 0) session.hotSinceMs = now;
                session.consecutiveHot++;
            } else {
                session.consecutiveHot = 0;
                session.hotSinceMs = 0L;
            }
            if (session.consecutiveHot < CONSECUTIVE_HOT_REPORTS) return;
            if (now - session.hotSinceMs < MIN_HOT_SPAN_MS) return;
            session.fired = true;
            active = null;
            fired = session;
        }
        AppLog.w(
                NetdiskQualityGuard.TAG,
                "sustained drops on rank="
                        + fired.rank
                        + " ("
                        + LABELS[fired.rank]
                        + ") last dropped="
                        + droppedFrames
                        + " overMs="
                        + elapsedMs);
        final Session session = fired;
        session.activity.ui.post(() -> decide(session, player));
    }

    /** Runs on the UI thread; verifies the report still belongs to the netdisk playback. */
    private static void decide(Session session, MoviePlayer player) {
        MainActivity activity = session.activity;
        if (activity == null || activity.isFinishing()) return;
        // The user may have switched lines or left the player while the reports were arriving.
        if (activity.player != player) return;
        if (session.downgrades >= MAX_DOWNGRADES) {
            session.hooks.hint("自动降档已到上限，仍然掉帧；可以换一条线路再试");
            return;
        }
        if (session.rank <= RANK_NORMAL) {
            session.hooks.hint("已经是最低画质，仍然掉帧；可以换一条线路再试");
            return;
        }
        markTierUnfit(activity, session.resolution);
        session.hooks.restart(session.rank - 1, session.downgrades + 1, session.rank);
    }

    private static void markTierUnfit(Context context, String resolution) {
        String key = normalizeResolution(resolution, 0);
        if (context == null || key.isEmpty()) return;
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(UNFIT_KEY_PREFIX + key, true)
                .apply();
        AppLog.w(NetdiskQualityGuard.TAG, "marked unfit: " + UNFIT_KEY_PREFIX + key);
    }

    /** Empty resolution (original-file fallback) falls back to the rank's service name. */
    private static String normalizeResolution(String resolution, int rank) {
        String value = resolution == null ? "" : resolution.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() && rank >= RANK_NORMAL && rank <= RANK_4K) value = RESOLUTIONS[rank];
        return value;
    }

    private static void clearIfActive(long id) {
        synchronized (LOCK) {
            if (active != null && active.id == id) active = null;
        }
    }
}
