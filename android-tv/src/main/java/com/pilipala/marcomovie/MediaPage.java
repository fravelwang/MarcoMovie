package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One page of a site category as the gateway reports it: the entries plus the page count the site
 * itself claimed.
 *
 * <p>{@code items} may be empty — a category with nothing on this page is a valid answer, not a
 * failure. {@code pageCount <= 0} means the site did not say; whether another page is worth
 * asking for stays the caller's decision.
 *
 * <p>Pure Java on purpose: no platform types and no vendor structures — the gateway implementation
 * is the only place that knows how the page was fetched.
 */
final class MediaPage {
    final List<MediaRef> items;
    final int pageCount;

    MediaPage(List<MediaRef> items, int pageCount) {
        List<MediaRef> copy = new ArrayList<>();
        if (items != null) copy.addAll(items);
        this.items = Collections.unmodifiableList(copy);
        this.pageCount = pageCount;
    }
}
