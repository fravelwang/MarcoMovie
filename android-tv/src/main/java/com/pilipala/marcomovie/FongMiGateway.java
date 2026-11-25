package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;

/**
 * The anti-corruption adapter: the only place in the app that turns FongMi/FongMi-like site data
 * into {@link MediaRef} and folds vendor exceptions into {@link GatewayException}.
 *
 * <p>It resolves a {@link SiteRef} back to the current config's {@code Cms.Site}, calls the engine,
 * and maps the payload. Business decisions (which site is preferred, whether to search at all) stay
 * in the callers; this layer only answers "what does this site say".
 *
 * <p>This slice wires only {@link #search}; {@link #home} is implemented for the interface and will
 * be connected (and its first-page parser unified with the wall) in the next slice.
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

    @Override
    public List<MediaRef> home(SiteRef site) throws GatewayException {
        Cms.Site target = resolve(site);
        String raw;
        try {
            raw = SourceController.engine(activity, target).home();
        } catch (Throwable failure) {
            throw normalize("home", site, failure);
        }
        return parse("home", site, raw);
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
        return video;
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
                video.doubanScore);
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
