package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One title's detail as the gateway parsed it: its metadata plus the play lines. Lines may be
 * empty (the site returned the title but no usable episodes); {@link #playable()} is the single
 * definition of "this detail can actually play".
 */
final class MediaDetail {
    final MediaRef meta;
    final List<LineRef> lines;

    MediaDetail(MediaRef meta, List<LineRef> lines) {
        this.meta = meta;
        List<LineRef> copy = new ArrayList<>();
        if (lines != null) copy.addAll(lines);
        this.lines = Collections.unmodifiableList(copy);
    }

    /**
     * True when at least one line carries at least one episode — the same test the detail cache
     * used before the gateway took over ({@code hasPlayableDetailLines}). Deliberately not stricter
     * than that: requiring a non-empty URL would stop caching payloads the previous build cached,
     * so a title whose site returns episode names without URLs would be refetched on every open.
     */
    boolean playable() {
        for (LineRef line : lines) {
            if (line == null || line.episodes.isEmpty()) continue;
            return true;
        }
        return false;
    }
}
