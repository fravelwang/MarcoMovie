package com.pilipala.marcomovie;

import java.io.IOException;
import java.util.ArrayList;
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

    /**
     * One page of a directory, plus the cursor that asks for the next one.
     *
     * <p>Drives that report a server-side total fill {@link #total}; 百度 does not (measured
     * 2026-11-27: {@code /api/list} answers {@code errno/guid_info/list/request_id/guid} and no
     * count), so a caller must treat {@code total == -1} as "unknown" and rely on {@link #next}.
     */
    final class Page {
        final List<NetdiskEntry> rows;
        /** Cursor for the next page; null/empty when this listing ends here. */
        final String next;
        /** Entries the drive says the whole directory holds, or -1 when it does not say. */
        final int total;

        Page(List<NetdiskEntry> rows, String next, int total) {
            this.rows = rows == null ? new ArrayList<NetdiskEntry>() : rows;
            this.next = next == null || next.isEmpty() ? null : next;
            this.total = total;
        }

        boolean hasMore() {
            return next != null;
        }
    }

    /** Name used in the 网盘 pill and the player's site title, e.g. 「夸克」「百度」. */
    String label();

    /** The dir id this drive calls its root; {@link #list(String)} accepts it verbatim. */
    String rootDir();

    /**
     * First page only, the shape every caller that never pages wants (分享线, historically the
     * browser too). Kept as a default method so the paging rule lives in exactly one place.
     */
    default List<NetdiskEntry> list(String dir) throws IOException {
        return listPage(dir, null).rows;
    }

    /**
     * One page of a directory.
     *
     * @param cursor null/empty for the first page, otherwise the {@link Page#next} of the previous
     *     page. Opaque to callers: each drive decides what it encodes.
     * @throws IOException when this page fails; pages already handed out stay valid
     */
    Page listPage(String dir, String cursor) throws IOException;

    /**
     * Resolves one playable file into a URL the player can open.
     *
     * @param maxRank highest tier to accept (4k=6 … normal=1); 0 or negative means "best available"
     * @return null when the service lists tiers but none at or below {@code maxRank}; the caller
     *     decides whether to relax the cap
     */
    Play play(String fileId, int maxRank) throws IOException;
}
