package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The wall panel's {@link FacetSource}: dimensions come from the merged wall model, selections are
 * written into the screen's existing wall fields ({@code wallSourceFilter}, {@code wallYear},
 * {@code wallArea}, {@code wallGenre} and the three switches), which is exactly the state
 * {@link LibraryQuery.Filter} already consumes.
 *
 * <p>Five rows, in the order the panel has always shown them: 来源 (合并墙 + each target), the
 * three independent switches (one row), then 年份 / 地区 / 类别 with counts. The taxonomy rows keep
 * the old ten-value budget so this seam changes no pixel.
 */
final class WallFacetSource implements FacetSource {
    static final String SOURCE = "source";
    static final String OPTIONS = "options";
    static final String YEAR = "year";
    static final String AREA = "area";
    static final String GENRE = "genre";

    /** Same ten-value budget the old taxonomy rows used; sources are not capped. */
    static final int MAX_TAXONOMY_VALUES = 10;

    private final HomeScreen host;
    private final LibraryModel model;

    WallFacetSource(HomeScreen host, LibraryModel model) {
        this.host = host;
        this.model = model;
    }

    @Override
    public List<Facet> facets() {
        List<Facet> out = new ArrayList<>(5);
        out.add(sourceFacet());
        out.add(optionFacet());
        out.add(taxonomy(YEAR, "年份"));
        out.add(taxonomy(AREA, "地区"));
        out.add(taxonomy(GENRE, "类别"));
        return out;
    }

    /** Row 1: 合并墙 (no source restriction) then every aggregation target, in model order. */
    private Facet sourceFacet() {
        List<Facet.Value> values = new ArrayList<>();
        values.add(new Facet.Value("", "合并墙", 0));
        if (model != null && model.sources != null) {
            for (Cms.Site site : model.sources) {
                if (site == null || site.key == null || site.key.trim().isEmpty()) continue;
                String label =
                        site.name == null || site.name.trim().isEmpty() ? site.key : site.name;
                values.add(new Facet.Value(site.key, label, 0));
            }
        }
        return new Facet(SOURCE, "", false, values);
    }

    /**
     * Row 2: the wall-local switches. One facet with {@code multi = true} because they are
     * independent toggles, not one choice; {@link #select} flips the matching flag.
     */
    private Facet optionFacet() {
        List<Facet.Value> values = new ArrayList<>(3);
        values.add(new Facet.Value("netdisk", "只看网盘", 0));
        values.add(new Facet.Value("multi", "多源合并", 0));
        values.add(new Facet.Value("sort", "按名称", 0));
        return new Facet(OPTIONS, "", true, values);
    }

    /** Rows 3-5: 全部 + up to ten values from the model facets, with their counts. */
    private Facet taxonomy(String id, String label) {
        List<Facet.Value> values = new ArrayList<>();
        values.add(new Facet.Value("", "全部", 0));
        List<String> axisValues =
                model == null || model.facets == null ? null : model.facets.valuesFor(id);
        Map<String, Integer> counts =
                model == null || model.facets == null ? null : model.facets.countsFor(id);
        if (axisValues != null) {
            int shown = 0;
            for (String value : axisValues) {
                if (value == null || value.trim().isEmpty()) continue;
                if (shown++ >= MAX_TAXONOMY_VALUES) break;
                Integer count = counts == null ? null : counts.get(value);
                values.add(new Facet.Value(value, value, count == null ? 0 : count));
            }
        }
        return new Facet(id, label, false, values);
    }

    @Override
    public boolean isSelected(Facet facet, String value) {
        if (facet == null) return false;
        String wanted = value == null ? "" : value;
        if (SOURCE.equals(facet.id)) {
            return wanted.isEmpty()
                    ? host.wallSourceFilter == null || host.wallSourceFilter.isEmpty()
                    : wanted.equals(host.wallSourceFilter);
        }
        if (OPTIONS.equals(facet.id)) {
            if ("netdisk".equals(wanted)) return host.wallNetdiskOnly;
            if ("multi".equals(wanted)) return host.wallMultiOnly;
            if ("sort".equals(wanted)) return host.wallSortByName;
            return false;
        }
        String current = facetValue(facet.id);
        return wanted.isEmpty() ? current.isEmpty() : wanted.equals(current);
    }

    @Override
    public void select(Facet facet, String value) {
        if (facet == null) return;
        String wanted = value == null ? "" : value;
        if (SOURCE.equals(facet.id)) {
            // A site stays selected when pressed again (old panel behavior); only 合并墙 clears.
            host.wallSourceFilter = wanted;
            return;
        }
        if (OPTIONS.equals(facet.id)) {
            switch (wanted) {
                case "netdisk":
                    host.wallNetdiskOnly = !host.wallNetdiskOnly;
                    return;
                case "multi":
                    host.wallMultiOnly = !host.wallMultiOnly;
                    return;
                case "sort":
                    host.wallSortByName = !host.wallSortByName;
                    return;
                default:
                    return;
            }
        }
        // Single-select taxonomy row: pressing the selected value clears it (old panel behavior).
        String current = facetValue(facet.id);
        setFacet(facet.id, wanted.equals(current) ? "" : wanted);
    }

    private String facetValue(String id) {
        if (YEAR.equals(id)) return host.wallYear == null ? "" : host.wallYear;
        if (AREA.equals(id)) return host.wallArea == null ? "" : host.wallArea;
        if (GENRE.equals(id)) return host.wallGenre == null ? "" : host.wallGenre;
        return "";
    }

    private void setFacet(String id, String value) {
        String next = value == null ? "" : value;
        if (YEAR.equals(id)) host.wallYear = next;
        else if (AREA.equals(id)) host.wallArea = next;
        else if (GENRE.equals(id)) host.wallGenre = next;
    }
}
