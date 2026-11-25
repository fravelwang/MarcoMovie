package com.pilipala.marcomovie;

import java.util.List;

/**
 * The filter-dimension provider a panel renders. Implementations decide where the dimensions come
 * from (the wall's merged model, or a site's own filter schema) and where a selection is written;
 * the panel never reads those sources directly.
 *
 * <p>The renderer reads {@link #facets()} once per rebuild and afterwards only asks
 * {@link #isSelected} / {@link #select}, so pressing a chip never rebuilds the control tree.
 *
 * <p>Pure Java on purpose (no android imports): {@link WallFacetSource} and
 * {@link SiteFacetSource} are two implementations of the same seam.
 */
interface FacetSource {
    /** Dimensions in display order; each one becomes one panel row. */
    List<Facet> facets();

    /** True when {@code value} is currently selected for this facet. */
    boolean isSelected(Facet facet, String value);

    /** Writes the selection into the implementation's own state; never rebuilds controls. */
    void select(Facet facet, String value);
}
