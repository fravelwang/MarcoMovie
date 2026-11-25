package com.pilipala.marcomovie;

/**
 * The identity of one configured site, as the domain layer sees it.
 *
 * <p>{@code key} is the stable identity used by the source ledger and the filters; {@code name} is
 * only for display. Deliberately free of vendor and Android types: the gateway maps this to
 * whatever the FongMi base calls a site.
 */
final class SiteRef {
    final String key;
    final String name;

    SiteRef(String key, String name) {
        this.key = key == null ? "" : key;
        this.name = name == null ? "" : name;
    }
}
