package com.pilipala.marcomovie;

/** One playable episode as the gateway reports it: display name plus the address to play. */
final class EpisodeRef {
    final String name;
    final String url;

    EpisodeRef(String name, String url) {
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
    }
}
