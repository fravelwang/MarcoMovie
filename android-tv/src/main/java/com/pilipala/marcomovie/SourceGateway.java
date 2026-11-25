package com.pilipala.marcomovie;

import java.util.List;

/**
 * The anti-corruption seam between the app and the FongMi base: the only way the rest of the app
 * asks a site for data. Implementations map the vendor payload into {@link MediaRef} and fold every
 * failure into {@link GatewayException}.
 *
 * <p>Pure Java on purpose — no Android, no FongMi base types, no catvod. Those live only in the
 * implementation ({@link FongMiGateway}).
 */
interface SourceGateway {
    /** Search one page of a site by keyword; failures are thrown as {@link GatewayException}. */
    List<MediaRef> search(SiteRef site, String keyword, int page) throws GatewayException;

    /**
     * The site's first home page listing, up to {@code limit} playable entries, using the same
     * fallback order the wall always used (home → video feed → a few category probes).
     */
    List<MediaRef> home(SiteRef site, int limit) throws GatewayException;
}
