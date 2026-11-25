package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One filter dimension a {@link FacetSource} offers to a panel: a stable id, the heading to show
 * (empty for rows without one), whether several values can be active at the same time, and the
 * values in display order.
 *
 * <p>The first value is the "全部 / 不限制" entry by convention; its value is the empty string.
 * A value with a count renders as {@code label · count}; {@code count == 0} means "no count".
 *
 * <p>Pure data on purpose (no android imports): the same type describes the wall's local facets
 * and a site's remote filter schema, so a panel can render either without knowing which it is.
 */
final class Facet {
    final String id;
    final String label;
    final boolean multi;
    final List<Value> values;

    Facet(String id, String label, boolean multi, List<Value> values) {
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label;
        this.multi = multi;
        List<Value> copy = new ArrayList<>();
        if (values != null) copy.addAll(values);
        this.values = Collections.unmodifiableList(copy);
    }

    /** One selectable value of a facet. */
    static final class Value {
        final String value;
        final String label;
        final int count;

        Value(String value, String label, int count) {
            this.value = value == null ? "" : value;
            this.label = label == null ? "" : label;
            this.count = Math.max(0, count);
        }

        /** True for the "全部 / 不限制" entry, which always carries the empty value. */
        boolean isAll() {
            return value.isEmpty();
        }
    }
}
