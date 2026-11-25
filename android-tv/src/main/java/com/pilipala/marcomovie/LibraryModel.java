package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of one merged-library round. Pure data: the class must not touch Android
 * views or framework types so the wall can be reasoned about (and compared) without rendering.
 *
 * <p>{@link #from} defensive-copies everything it keeps: {@link SourceLedger} keeps learning sites
 * for a title after the round finished, so holding its live lists would make an already-built model
 * change under the UI.
 */
final class LibraryModel {

    enum Mode {
        WALL,
        SINGLE_SITE
    }

    final Mode mode;
    /** Every site the aggregation targeted, in config order. */
    final List<Cms.Site> sources;
    /** Every wall card, in aggregation order; never null elements. */
    final List<MergedItem> items;
    final Facets facets;

    private LibraryModel(Mode mode, List<Cms.Site> sources, List<MergedItem> items, Facets facets) {
        this.mode = mode;
        this.sources = sources;
        this.items = items;
        this.facets = facets;
    }

    /** One poster card and every site that carries this title. */
    static final class MergedItem {
        final Cms.Video video;
        final List<Cms.Site> sites;
        final Map<String, Cms.Video> videosBySite;
        /** Precomputed so filtering never re-walks the site list (same rule as the aggregator). */
        final boolean netdisk;

        MergedItem(
                Cms.Video video,
                List<Cms.Site> sites,
                Map<String, Cms.Video> videosBySite) {
            this.video = video;
            this.sites = sites;
            this.videosBySite = videosBySite;
            this.netdisk = hasShareSource(sites);
        }

        boolean hasSite(String key) {
            return key != null && videosBySite.containsKey(key);
        }

        int sourceCount() {
            return sites.size();
        }
    }

    /**
     * Year / area / genre value sets for the wall filter panel, with the counts they were ranked
     * on. Built from the whole wall (not from a filtered view), so a filter change never shifts
     * the values on offer.
     */
    static final class Facets {
        final Map<String, Integer> yearCounts;
        final Map<String, Integer> areaCounts;
        final Map<String, Integer> genreCounts;
        private final List<String> years;
        private final List<String> areas;
        private final List<String> genres;

        private Facets(
                Map<String, Integer> yearCounts,
                Map<String, Integer> areaCounts,
                Map<String, Integer> genreCounts,
                List<String> years,
                List<String> areas,
                List<String> genres) {
            this.yearCounts = yearCounts;
            this.areaCounts = areaCounts;
            this.genreCounts = genreCounts;
            this.years = years;
            this.areas = areas;
            this.genres = genres;
        }

        /** Years, newest first (the old panel sorted them descending as strings). */
        List<String> years() {
            return years;
        }

        /** Areas in first-appearance order. */
        List<String> areas() {
            return areas;
        }

        /** Genres by item count, ties keeping first-appearance order. */
        List<String> genres() {
            return genres;
        }
    }

    static LibraryModel from(LibraryAggregator.Result result) {
        return from(result, Mode.WALL, null);
    }

    static LibraryModel from(LibraryAggregator.Result result, Mode mode) {
        return from(result, mode, null);
    }

    /**
     * The card list is the same merged wall in both modes; {@code mode} only records which entry
     * point produced it, so the query layer can pick the matching source filter field.
     *
     * <p>{@code ledger} folds every source the process has learned for a title into its card, so
     * the wall's own filters (只看网盘 / 多来源 / 按来源筛选) agree with the badge and the detail
     * page. The card objects still come from this round only — the ledger never adds cards.
     */
    static LibraryModel from(
            LibraryAggregator.Result result, Mode mode, SourceLedger ledger) {
        if (result == null) return empty(mode);
        List<Cms.Site> sources = new ArrayList<>();
        if (result.targets != null) {
            for (Cms.Site site : result.targets) {
                if (site != null) sources.add(site);
            }
        }
        List<MergedItem> items = new ArrayList<>();
        if (result.items != null) {
            for (LibraryAggregator.Item item : result.items) {
                if (item == null || item.video == null) continue;
                List<Cms.Site> sites = new ArrayList<>();
                Map<String, Cms.Video> bySite = new LinkedHashMap<>();
                if (item.sites != null) sites.addAll(item.sites);
                if (item.videosBySite != null) bySite.putAll(item.videosBySite);
                if (ledger != null) {
                    for (Cms.Site site : ledger.sourcesOf(item.video)) {
                        if (site == null || site.key == null || bySite.containsKey(site.key)) {
                            continue;
                        }
                        Cms.Video listing = ledger.videoAt(item.video, site.key);
                        if (listing == null) continue;
                        sites.add(site);
                        bySite.put(site.key, listing);
                    }
                }
                items.add(
                        new MergedItem(
                                item.video,
                                Collections.unmodifiableList(sites),
                                Collections.unmodifiableMap(bySite)));
            }
        }
        return new LibraryModel(
                mode == null ? Mode.WALL : mode,
                Collections.unmodifiableList(sources),
                Collections.unmodifiableList(items),
                facets(items));
    }

    private static LibraryModel empty(Mode mode) {
        return new LibraryModel(
                mode == null ? Mode.WALL : mode,
                Collections.<Cms.Site>emptyList(),
                Collections.<MergedItem>emptyList(),
                facets(Collections.<MergedItem>emptyList()));
    }

    private static Facets facets(List<MergedItem> items) {
        Map<String, Integer> yearCounts = new LinkedHashMap<>();
        Map<String, Integer> areaCounts = new LinkedHashMap<>();
        Map<String, Integer> genreCounts = new LinkedHashMap<>();
        for (MergedItem item : items) {
            if (item == null || item.video == null) continue;
            String year = valueOf(item.video.year);
            if (!year.isEmpty() && year.length() <= 8) yearCounts.merge(year, 1, Integer::sum);
            String area = valueOf(item.video.area);
            if (!area.isEmpty() && area.length() <= 8) areaCounts.merge(area, 1, Integer::sum);
            String genre = valueOf(item.video.genre);
            if (genre.isEmpty()) continue;
            for (String part : genre.split("[/,、| ]+")) {
                String value = part.trim();
                if (value.isEmpty() || value.length() > 6) continue;
                genreCounts.merge(value, 1, Integer::sum);
            }
        }
        List<String> years = new ArrayList<>(yearCounts.keySet());
        years.sort((left, right) -> right.compareTo(left));
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(genreCounts.entrySet());
        ranked.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        List<String> genres = new ArrayList<>(ranked.size());
        for (Map.Entry<String, Integer> entry : ranked) genres.add(entry.getKey());
        return new Facets(
                Collections.unmodifiableMap(yearCounts),
                Collections.unmodifiableMap(areaCounts),
                Collections.unmodifiableMap(genreCounts),
                Collections.unmodifiableList(years),
                Collections.unmodifiableList(new ArrayList<>(areaCounts.keySet())),
                Collections.unmodifiableList(genres));
    }

    /** Trim helper; null becomes the empty string, exactly like the old HomeScreen helper. */
    static String valueOf(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * Netdisk rule, owned here since the model is what filtering reads: a site counts as a share
     * source when its name says 网盘/盘搜 or its api points at a maccms endpoint.
     */
    private static boolean hasShareSource(List<Cms.Site> sites) {
        for (Cms.Site site : sites) {
            if (site == null) continue;
            String name = site.name == null ? "" : site.name;
            String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("网盘") || name.contains("盘搜") || api.contains("maccms")) return true;
        }
        return false;
    }
}
