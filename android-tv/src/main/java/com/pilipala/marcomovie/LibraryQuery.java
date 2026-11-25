package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only query layer over a {@link LibraryModel}: given the current filter state, which cards
 * are visible and in what order. No Android types, no mutation of the model.
 *
 * <p>The predicate chain below is a literal translation of the loop the wall used to run inline;
 * the semantics (exact equality on trimmed facet values, whole-segment genre match, stable title
 * sort) must stay byte-for-byte equivalent until the renderer replaces the old path.
 */
final class LibraryQuery {

    /** One wall filter selection. Empty strings mean "no restriction" on that axis. */
    static final class Filter {
        LibraryModel.Mode mode = LibraryModel.Mode.WALL;
        /**
         * Source restriction. WALL callers fill this from {@code wallSourceFilter}; SINGLE_SITE
         * callers from {@code librarySiteFilter}. The two fields are kept apart in HomeScreen
         * because choosing a wall chip must not flip the library into per-site browsing.
         */
        String sourceKey = "";

        boolean netdiskOnly, multiOnly, sortByName;
        String year = "", area = "", genre = "";
    }

    private LibraryQuery() {}

    static List<LibraryModel.MergedItem> visible(LibraryModel model, Filter filters) {
        if (model == null || model.items == null) return Collections.emptyList();
        Filter f = filters == null ? new Filter() : filters;
        String sourceKey = f.sourceKey == null ? "" : f.sourceKey;
        List<LibraryModel.MergedItem> out = new ArrayList<>();
        for (LibraryModel.MergedItem item : model.items) {
            if (item == null || item.video == null) continue;
            if (!sourceKey.isEmpty() && !item.hasSite(sourceKey)) continue;
            if (f.netdiskOnly && !item.netdisk) continue;
            if (f.multiOnly && item.sourceCount() < 2) continue;
            if (!f.year.isEmpty() && !f.year.equals(valueOf(item.video.year))) continue;
            if (!f.area.isEmpty() && !f.area.equals(valueOf(item.video.area))) continue;
            if (!f.genre.isEmpty() && !genreContains(item.video.genre, f.genre)) continue;
            out.add(item);
        }
        if (f.sortByName) {
            out.sort(
                    (left, right) ->
                            titleOf(left.video).compareTo(titleOf(right.video)));
        }
        return Collections.unmodifiableList(out);
    }

    private static String titleOf(Cms.Video video) {
        return video == null || video.title == null ? "" : video.title;
    }

    private static String valueOf(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * Whole-segment match on the split genre string, case sensitive: the old wall never matched
     * substrings, and "科幻" must not match "科幻片".
     */
    private static boolean genreContains(String genre, String value) {
        if (genre == null || value == null || value.isEmpty()) return false;
        for (String part : genre.split("[/,、| ]+")) {
            if (part.trim().equals(value)) return true;
        }
        return false;
    }
}
