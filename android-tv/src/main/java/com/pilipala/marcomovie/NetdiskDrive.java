package com.pilipala.marcomovie;

import java.io.IOException;
import java.util.List;

/**
 * What the 网盘 browser needs from one drive: list a directory, and turn one file in that listing
 * into a playable URL with the headers the player must send. Deliberately just these two things —
 * login, sharing, search and everything drive-specific stays in that drive's own client.
 *
 * <p>Implementations are synchronous and throw {@link IOException}: callers run them off the UI
 * thread (see {@link NetdiskScreen#WORKER}).
 */
interface NetdiskDrive {

    /** A playable URL plus the request headers that URL requires (Cookie / UA / Referer). */
    final class Play {
        final String url;
        final String resolution;
        final int rank;
        final String cookie;
        final String userAgent;
        final String referer;

        Play(
                String url,
                String resolution,
                int rank,
                String cookie,
                String userAgent,
                String referer) {
            this.url = url == null ? "" : url;
            this.resolution = resolution == null ? "" : resolution;
            this.rank = rank;
            this.cookie = cookie == null ? "" : cookie;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.referer = referer == null ? "" : referer;
        }
    }

    /** Name used in the 网盘 pill and the player's site title, e.g. 「夸克」「百度」. */
    String label();

    /** The dir id this drive calls its root; {@link #list(String)} accepts it verbatim. */
    String rootDir();

    /** Lists one directory; pass {@link #rootDir()} for the top level. */
    List<NetdiskEntry> list(String dir) throws IOException;

    /**
     * Resolves one playable file into a URL the player can open.
     *
     * @param maxRank highest tier to accept (4k=6 … normal=1); 0 or negative means "best available"
     * @return null when the service lists tiers but none at or below {@code maxRank}; the caller
     *     decides whether to relax the cap
     */
    Play play(String fileId, int maxRank) throws IOException;
}
