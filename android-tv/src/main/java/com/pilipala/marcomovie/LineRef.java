package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One play line of a title (a site's "线路"), with its episodes in order. */
final class LineRef {
    final String name;
    final List<EpisodeRef> episodes;

    LineRef(String name, List<EpisodeRef> episodes) {
        this.name = name == null ? "" : name;
        List<EpisodeRef> copy = new ArrayList<>();
        if (episodes != null) copy.addAll(episodes);
        this.episodes = Collections.unmodifiableList(copy);
    }
}
