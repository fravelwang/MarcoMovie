package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The anti-corruption adapter: the only place in the app that turns FongMi/FongMi-like site data
 * into {@link MediaRef} and folds vendor exceptions into {@link GatewayException}.
 *
 * <p>It resolves a {@link SiteRef} back to the current config's {@code Cms.Site}, calls the engine,
 * and maps the payload. Business decisions (which site is preferred, whether to search at all) stay
 * in the callers; this layer only answers "what does this site say".
 *
 * <p>{@link #search} backs the focused-card source expansion; {@link #home} backs the wall round;
 * {@link #category} backs the home screen's category tab and the catalog page. The wall's
 * first-page parser/cleaner still live in {@code HomeScreen} and are called from here (moving them
 * is a later slice).
 */
final class FongMiGateway implements SourceGateway {

    private final MainActivity activity;

    FongMiGateway(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public List<MediaRef> search(SiteRef site, String keyword, int page) throws GatewayException {
        Cms.Site target = resolve(site);
        String raw;
        try {
            raw = SourceController.engine(activity, target).search(keyword, page);
        } catch (Throwable failure) {
            throw normalize("search", site, failure);
        }
        return parse("search", site, raw);
    }

    /**
     * The wall's per-site fetch, moved here verbatim: home page → home video feed → up to three
     * category probes, then the same filtering and the same {@code limit}.
     *
     * <p>The engine was already created by the wall round for its init timing, so resolving it
     * again is a cache hit — no extra init and no extra request. An empty home page is a valid
     * answer (the wall counts it as an "empty site"), not a failure, so this returns an empty list
     * instead of throwing {@code EMPTY}.
     */
    @Override
    public List<MediaRef> home(SiteRef site, int limit) throws GatewayException {
        Cms.Site target = resolve(site);
        SourceEngine engine;
        String raw;
        try {
            engine = SourceController.engine(activity, target);
            raw = engine.home();
        } catch (Throwable failure) {
            throw normalize("home", site, failure);
        }
        // The wall's first-page parser and cleaner stay in HomeScreen for this slice.
        List<Cms.Category> categories;
        List<Cms.Video> listing;
        try {
            categories = Cms.categories(raw);
            listing = HomeScreen.parseHomeListing(raw).videos;
            HomeScreen.clean(listing);
        } catch (Throwable failure) {
            throw new GatewayException(
                    GatewayException.Kind.PARSE,
                    "home " + site.key + " returned an unparsable payload",
                    failure);
        }
        if (listing.isEmpty()) {
            try {
                List<Cms.Video> feed = Cms.listing(engine.homeVideos()).videos;
                HomeScreen.clean(feed);
                listing = feed;
            } catch (Throwable ignored) {
                // No video feed is normal; the category probes below are the fallback.
            }
        }
        // Sites whose home page only carries channel buttons (measured in the 2026-11-25 site
        // check: CNTV 69 categories, opera 68, Bilibili collection 42) have no home videos at all.
        // Their first channel is often empty too, so try a few in order and take the first that
        // lists something; one empty channel is not evidence that the site is dead.
        for (int index = 0;
                listing.isEmpty()
                        && index < Math.min(LibraryAggregator.MAX_CATEGORY_PROBES, categories.size());
                index++) {
            String categoryId = categories.get(index).id;
            try {
                List<Cms.Video> probe = Cms.listing(engine.category(categoryId, 1)).videos;
                HomeScreen.clean(probe);
                listing = probe;
            } catch (Throwable failure) {
                // Keep probing: one bad channel should not end the fallback, but it is logged.
                AppLog.w(
                        "SourceGateway",
                        "home " + site.key + " probe " + categoryId + ": " + describe(failure));
            }
        }
        List<MediaRef> picked = new ArrayList<>(Math.max(1, limit));
        for (Cms.Video video : listing) {
            if (picked.size() >= limit) break;
            if (video == null || LibraryAggregator.normalizeTitle(video.title).isEmpty()) continue;
            if (video.action != null && !video.action.trim().isEmpty()) continue;
            if (SourceController.isInternalActionCode(video.id, video.action)) continue;
            picked.add(toMedia(site.key, video));
        }
        return picked;
    }

    /**
     * One category page, fetched the way the home screen always fetched it: resolve the engine,
     * take the same {@link SourceGate#measured} path (the per-engine gate and its two phases are
     * unchanged), parse, then clean with the same rule the caller used to apply (empty ids and
     * duplicate ids dropped).
     *
     * <p>No caching and no paging decision: {@code MediaPage.pageCount} is the site's own number,
     * and whether another page is worth asking for stays with the caller. An empty page is a
     * normal answer, so it comes back as an empty list, not as a failure.
     */
    @Override
    public MediaPage category(
            SiteRef site, String categoryId, int page, Map<String, String> filters, long[] timings)
            throws GatewayException {
        Cms.Site target = resolve(site);
        SourceEngine engine;
        try {
            engine = SourceController.engine(activity, target);
        } catch (Throwable failure) {
            throw normalize("category", site, failure);
        }
        if (timings != null && timings.length >= 3) {
            timings[0] = android.os.SystemClock.elapsedRealtime();
        }
        // Some engines walk the map, so hand them an empty one rather than null.
        Map<String, String> values = filters == null ? Collections.emptyMap() : filters;
        long[] phases = new long[2];
        String raw;
        try {
            raw = SourceGate.measured(phases, () -> engine.category(categoryId, page, values));
        } catch (Throwable failure) {
            throw normalize("category", site, failure);
        }
        if (timings != null && timings.length >= 3) {
            timings[1] = phases[0];
            timings[2] = phases[1];
        }
        if (raw == null || raw.trim().isEmpty()) {
            // An empty body is a site saying "nothing on this page", not a failure: the caller's
            // local scan stops on an empty page and the catalog shows its "no videos" line. It
            // must not turn into an error banner.
            return new MediaPage(Collections.emptyList(), 0);
        }
        Cms.Listing listing;
        try {
            listing = Cms.listing(raw);
        } catch (Throwable failure) {
            throw new GatewayException(
                    GatewayException.Kind.PARSE,
                    "category " + site.key + " returned an unparsable payload",
                    failure);
        }
        if (listing == null) return new MediaPage(Collections.emptyList(), 0);
        HomeScreen.clean(listing.videos);
        List<MediaRef> items = new ArrayList<>(listing.videos.size());
        for (Cms.Video video : listing.videos) items.add(toMedia(site.key, video));
        return new MediaPage(items, listing.pageCount);
    }

    /**
     * The detail fetch: resolve the engine, note when it became ready, then take the same
     * {@link SourceGate#measured} path (the per-engine gate and its two phases are unchanged).
     *
     * <p>No caching and no single-flight here: the caller owns both. The engine is the one the
     * caller's detail flight is about; resolving it again is an engine-cache hit.
     */
    @Override
    public String detailRaw(SiteRef site, String id, long[] timings) throws GatewayException {
        Cms.Site target = resolve(site);
        SourceEngine engine;
        try {
            engine = SourceController.engine(activity, target);
        } catch (Throwable failure) {
            throw normalize("detail", site, failure);
        }
        if (timings != null && timings.length >= 3) {
            timings[0] = android.os.SystemClock.elapsedRealtime();
        }
        long[] phases = new long[2];
        String raw;
        try {
            raw = SourceGate.measured(phases, () -> engine.detail(id));
        } catch (Throwable failure) {
            throw normalize("detail", site, failure);
        }
        if (timings != null && timings.length >= 3) {
            timings[1] = phases[0];
            timings[2] = phases[1];
        }
        return raw;
    }

    /**
     * Parses a raw detail payload. Returns null when the payload cannot be parsed or carries no
     * title at all — the cache may legitimately hold bad data, and the caller then refetches.
     */
    @Override
    public MediaDetail parseDetail(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        Cms.Listing listing;
        try {
            listing = Cms.listing(raw);
        } catch (Throwable failure) {
            return null;
        }
        if (listing == null || listing.videos == null || listing.videos.isEmpty()) return null;
        Cms.Video video = listing.videos.get(0);
        if (video == null) return null;
        List<LineRef> lines = new ArrayList<>();
        if (video.lines != null) {
            for (Cms.Line line : video.lines) {
                if (line == null) continue;
                List<EpisodeRef> episodes = new ArrayList<>();
                if (line.episodes != null) {
                    for (Cms.Episode episode : line.episodes) {
                        if (episode == null) continue;
                        episodes.add(new EpisodeRef(episode.name, episode.url));
                    }
                }
                lines.add(new LineRef(line.name, episodes));
            }
        }
        // A detail parsed outside a site context does not need siteKey; toVideo ignores it.
        return new MediaDetail(toMedia("", video), lines);
    }

    /** MediaDetail → the base listing object the detail UI and the cache validation consume. */
    Cms.Video toVideo(MediaDetail detail) {
        if (detail == null || detail.meta == null) return null;
        Cms.Video video = toListing(detail.meta);
        if (video == null) return null;
        for (LineRef line : detail.lines) {
            if (line == null) continue;
            Cms.Line target = new Cms.Line();
            target.name = line.name;
            for (EpisodeRef episode : line.episodes) {
                if (episode == null) continue;
                Cms.Episode item = new Cms.Episode();
                item.name = episode.name;
                item.url = episode.url;
                target.episodes.add(item);
            }
            // Parsers never kept an episode-less line; keep it out of the UI too.
            if (!target.episodes.isEmpty()) video.lines.add(target);
        }
        return video;
    }

    /**
     * Turns a gateway hit back into the base's listing object, which is what the source ledger
     * stores. This conversion lives here and nowhere else: the rest of the app never builds a
     * {@code Cms.Video} from a gateway result.
     */
    Cms.Video toListing(MediaRef ref) {
        if (ref == null) return null;
        Cms.Video video = new Cms.Video();
        video.id = ref.id;
        video.title = ref.title;
        video.poster = emptyToNull(ref.poster);
        video.year = emptyToNull(ref.year);
        video.area = emptyToNull(ref.area);
        video.genre = emptyToNull(ref.genre);
        video.remarks = emptyToNull(ref.remarks);
        video.doubanScore = emptyToNull(ref.doubanScore);
        video.action = emptyToNull(ref.action);
        video.tag = emptyToNull(ref.tag);
        video.description = emptyToNull(ref.description);
        video.actor = emptyToNull(ref.actor);
        video.director = emptyToNull(ref.director);
        video.doubanId = emptyToNull(ref.doubanId);
        return video;
    }

    /**
     * Turns a gateway page back into the base's listing object, for the callers whose filter and
     * paging pipelines still run on {@code Cms.Listing}. The conversion lives here and nowhere
     * else, the same rule {@link #toListing(MediaRef)} follows.
     */
    Cms.Listing toListing(MediaPage page) {
        Cms.Listing listing = new Cms.Listing();
        if (page == null) return listing;
        for (MediaRef ref : page.items) {
            Cms.Video video = toListing(ref);
            if (video != null) listing.videos.add(video);
        }
        listing.pageCount = page.pageCount;
        return listing;
    }

    /** Current config's site for this reference; a switch mid-flight is a normal gateway failure. */
    private Cms.Site resolve(SiteRef site) throws GatewayException {
        if (site == null || site.key.isEmpty()) {
            throw new GatewayException(GatewayException.Kind.OTHER, "missing site reference");
        }
        Cms.Config config = activity == null ? null : activity.config;
        if (config != null && config.sites != null) {
            for (Cms.Site candidate : config.sites) {
                if (candidate != null && site.key.equals(candidate.key)) return candidate;
            }
        }
        throw new GatewayException(
                GatewayException.Kind.OTHER, "site is no longer in the config: " + site.key);
    }

    private List<MediaRef> parse(String operation, SiteRef site, String raw)
            throws GatewayException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new GatewayException(
                    GatewayException.Kind.EMPTY,
                    operation + " " + site.key + " returned an empty payload");
        }
        List<Cms.Video> videos;
        try {
            videos = Cms.listing(raw).videos;
        } catch (Throwable failure) {
            throw new GatewayException(
                    GatewayException.Kind.PARSE,
                    operation + " " + site.key + " returned an unparsable payload",
                    failure);
        }
        if (videos == null || videos.isEmpty()) {
            throw new GatewayException(
                    GatewayException.Kind.EMPTY, operation + " " + site.key + " returned no items");
        }
        List<MediaRef> out = new ArrayList<>(videos.size());
        for (Cms.Video video : videos) {
            if (video != null) out.add(toMedia(site.key, video));
        }
        if (out.isEmpty()) {
            throw new GatewayException(
                    GatewayException.Kind.EMPTY, operation + " " + site.key + " returned no items");
        }
        return out;
    }

    private static MediaRef toMedia(String siteKey, Cms.Video video) {
        return new MediaRef(
                siteKey,
                video.id,
                video.title,
                video.poster,
                video.year,
                video.area,
                video.genre,
                video.remarks,
                video.doubanScore,
                video.action,
                video.tag,
                video.description,
                video.actor,
                video.director,
                video.doubanId);
    }

    /** Folds a vendor failure into one kind; the caller logs the kind, never the raw stack. */
    private static GatewayException normalize(String operation, SiteRef site, Throwable failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        GatewayException.Kind kind = classify(failure);
        return new GatewayException(
                kind, operation + " " + site.key + " failed: " + describe(failure), failure);
    }

    private static GatewayException.Kind classify(Throwable failure) {
        int depth = 0;
        for (Throwable cause = failure;
                cause != null && depth++ < 8;
                cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.io.InterruptedIOException) {
                return GatewayException.Kind.TIMEOUT;
            }
            if (cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.NoRouteToHostException
                    || cause instanceof java.net.SocketException
                    || cause instanceof javax.net.ssl.SSLException) {
                return GatewayException.Kind.DEAD;
            }
            if (cause instanceof org.json.JSONException
                    || cause instanceof NumberFormatException
                    || cause instanceof ClassCastException) {
                return GatewayException.Kind.PARSE;
            }
            if (cause instanceof InterruptedException) return GatewayException.Kind.OTHER;
            if (cause.getCause() == cause) break;
        }
        String message = failure == null ? null : failure.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("timeout") || message.contains("超时")) {
                return GatewayException.Kind.TIMEOUT;
            }
            if (lower.contains("connect") || lower.contains("unable to resolve")) {
                return GatewayException.Kind.DEAD;
            }
        }
        return GatewayException.Kind.OTHER;
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) return failure.getClass().getSimpleName();
        return failure.getClass().getSimpleName() + ": " + message.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
