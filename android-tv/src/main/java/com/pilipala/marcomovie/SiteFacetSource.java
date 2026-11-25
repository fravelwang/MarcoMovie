package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single-site {@link FacetSource}: dimensions come from the site's own filter schema
 * ({@link VideoFilters.Definition}) and a selection is written into the screen's existing
 * {@code state.selectedFilters} map (keyed by dimension, exactly what the current inline filter
 * panel and the remote request already consume).
 *
 * <p><b>Seam only in this axis:</b> {@link InlineFilters#facetSource()} exposes this source, but
 * the inline panel still renders through its own code. The next axis can render the same
 * dimensions without re-deriving them.
 */
final class SiteFacetSource implements FacetSource {
    static final SiteFacetSource EMPTY =
            new SiteFacetSource(
                    Collections.<VideoFilters.Definition>emptyList(), null);

    private final List<VideoFilters.Definition> definitions;
    private final Map<String, String> selected;
    private final Map<String, VideoFilters.Definition> byFacetId = new LinkedHashMap<>();

    SiteFacetSource(
            List<VideoFilters.Definition> definitions, Map<String, String> selectedFilters) {
        List<VideoFilters.Definition> copy = new ArrayList<>();
        if (definitions != null) {
            for (VideoFilters.Definition definition : definitions) {
                if (definition != null) copy.add(definition);
            }
        }
        this.definitions = Collections.unmodifiableList(copy);
        this.selected = selectedFilters;
        for (VideoFilters.Definition definition : copy) {
            byFacetId.put(facetId(definition), definition);
        }
    }

    /**
     * The site filter map is keyed by {@code Definition.dimension}; a custom (non-canonical) filter
     * has no dimension, so its facet id falls back to the raw key.
     */
    private static String facetId(VideoFilters.Definition definition) {
        String dimension = definition.dimension == null ? "" : definition.dimension.trim();
        if (!dimension.isEmpty()) return dimension;
        String key = definition.key == null ? "" : definition.key.trim();
        return key.isEmpty() ? "filter" : key;
    }

    @Override
    public List<Facet> facets() {
        List<Facet> out = new ArrayList<>(definitions.size());
        for (VideoFilters.Definition definition : definitions) {
            List<Facet.Value> values = new ArrayList<>();
            for (VideoFilters.Choice choice : definition.choices) {
                if (choice == null) continue;
                // Site schemas do not carry counts.
                values.add(new Facet.Value(choice.value, choice.label, 0));
            }
            if (values.isEmpty() || !values.get(0).isAll()) {
                values.add(0, new Facet.Value("", "全部", 0));
            }
            // The site UI is one choice per dimension, like the wall's taxonomy rows.
            out.add(new Facet(facetId(definition), definition.label, false, values));
        }
        return out;
    }

    @Override
    public boolean isSelected(Facet facet, String value) {
        VideoFilters.Definition definition = definitionFor(facet);
        if (definition == null || selected == null) return false;
        String wanted = value == null ? "" : value;
        String current = selected.get(definition.dimension);
        if (wanted.isEmpty()) return current == null || current.isEmpty();
        return wanted.equals(current);
    }

    @Override
    public void select(Facet facet, String value) {
        VideoFilters.Definition definition = definitionFor(facet);
        if (definition == null || selected == null) return;
        String wanted = value == null ? "" : value;
        // Mirrors the existing single-site UI: 全部 removes the entry, anything else stores it.
        if (wanted.isEmpty()) selected.remove(definition.dimension);
        else selected.put(definition.dimension, wanted);
    }

    private VideoFilters.Definition definitionFor(Facet facet) {
        if (facet == null) return null;
        return byFacetId.get(facet.id);
    }
}
