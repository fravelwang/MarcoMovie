package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The process-wide source ledger: for every normalized title, every site that carries it and that
 * site's own listing.
 *
 * <p>This is the one place that answers "which sites have this show, and which one do we open".
 * It exists because two parallel answers used to exist — the wall merged each site's home listing,
 * while the curated home page searched sites on demand — so 《两京十五日》 showed 「💗｜虎斑｜网盘」
 * on the home page and「💗｜王子｜APP」behind the same card in 片库.
 *
 * <p>It is deliberately independent of {@link LibraryAggregator.Result}: that result is only the
 * wall's card sample (200 cards), while a search or a detail match can teach the ledger titles the
 * wall never aggregated. Keeping them apart means a search cannot grow search-only cards into the
 * wall.
 *
 * <p>Writers (all synchronized): the wall round, the search page's merged results, a detail page's
 * cross-site match, and the focused-card source search. Readers: nothing but
 * {@link #resolve(MainActivity, Cms.Video)} (plus the raw {@link #sourcesOf} the wall model uses to
 * fold known sources into its cards). Ordering is {@link HomeScreen#netdiskFirst}: cloud drive
 * first, then the user's pinned site order, then an ordinary source.
 */
final class SourceLedger {

    /** A long session cannot accumulate titles without limit; the oldest entry goes first. */
    static final int MAX_TITLES = 600;

    /**
     * One resolved answer for one title. {@code sites} is already ordered for playback, so
     * {@code defaultSite} is simply the first entry and {@code defaultListing} is that site's own
     * listing (never another site's id opened against this site).
     */
    static final class Sources {
        static final Sources EMPTY =
                new Sources(Collections.<Cms.Site>emptyList(), null, null, Collections.<String, Cms.Video>emptyMap());

        final List<Cms.Site> sites;
        final Cms.Site defaultSite;
        final Cms.Video defaultListing;
        private final Map<String, Cms.Video> listings;

        Sources(
                List<Cms.Site> sites,
                Cms.Site defaultSite,
                Cms.Video defaultListing,
                Map<String, Cms.Video> listings) {
            this.sites = Collections.unmodifiableList(new ArrayList<>(sites));
            this.defaultSite = defaultSite;
            this.defaultListing = defaultListing;
            this.listings = listings;
        }

        boolean isEmpty() {
            return sites.isEmpty();
        }

        int count() {
            return sites.size();
        }

        /** That site's own listing, or null when it does not carry the title. */
        Cms.Video listingAt(String siteKey) {
            return siteKey == null ? null : listings.get(siteKey);
        }

        /** {site, listing} pairs in playback order, for switch-source dialogs. */
        List<Object[]> withListings() {
            List<Object[]> out = new ArrayList<>(sites.size());
            for (Cms.Site site : sites) {
                Cms.Video listing = listingAt(site.key);
                if (listing != null) out.add(new Object[] {site, listing});
            }
            return out;
        }
    }

    /** One title's row: sites in first-writer order plus each site's own listing. */
    private static final class Entry {
        final String title;
        final List<Cms.Site> sites = new ArrayList<>();
        final Map<String, Cms.Video> listings = new LinkedHashMap<>();

        Entry(String title) {
            this.title = title;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** Adds one discovered source for the title {@code video} belongs to. */
    synchronized void add(Cms.Site site, Cms.Video video) {
        if (site == null || site.key == null || video == null) return;
        add(LibraryAggregator.normalizeTitle(video.title), site, video);
    }

    private void add(String key, Cms.Site site, Cms.Video video) {
        if (key == null || key.isEmpty() || site == null || site.key == null || video == null) return;
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_TITLES) {
                java.util.Iterator<String> oldest = entries.keySet().iterator();
                if (oldest.hasNext()) entries.remove(oldest.next());
            }
            entry = new Entry(key);
            entries.put(key, entry);
        }
        if (!entry.listings.containsKey(site.key)) {
            entry.sites.add(site);
            entry.listings.put(site.key, video);
        }
    }

    /**
     * Folds a wall round or a search page merge into the ledger. Called once per round and once per
     * search render, so a title keeps the sites that answered last time even when this round's home
     * listing omitted them (site timeouts made the card's badge flicker between 1 源 and 2 源).
     */
    void absorb(LibraryAggregator.Result result) {
        if (result == null) return;
        List<LibraryAggregator.Item> items;
        synchronized (result) {
            items = new ArrayList<>(result.items);
        }
        for (LibraryAggregator.Item item : items) {
            if (item == null || item.video == null) continue;
            String key = LibraryAggregator.normalizeTitle(item.video.title);
            List<Cms.Site> sites;
            Map<String, Cms.Video> listings;
            synchronized (result) {
                sites = new ArrayList<>(item.sites);
                listings = new LinkedHashMap<>(item.videosBySite);
            }
            synchronized (this) {
                for (Cms.Site site : sites) {
                    if (site == null || site.key == null) continue;
                    add(key, site, listings.get(site.key));
                }
            }
        }
    }

    /** Raw known sources for a title in first-writer order; the wall model folds these into cards. */
    synchronized List<Cms.Site> sourcesOf(Cms.Video video) {
        Entry entry = entryFor(video);
        if (entry == null || entry.sites.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(entry.sites);
    }

    /** That site's own listing for this title, or null when the ledger does not know it. */
    synchronized Cms.Video videoAt(Cms.Video video, String siteKey) {
        Entry entry = entryFor(video);
        return entry == null || siteKey == null ? null : entry.listings.get(siteKey);
    }

    /** Forgets every source; called when a config change bumps the engine epoch. */
    synchronized void clear() {
        entries.clear();
    }

    synchronized int titleCount() {
        return entries.size();
    }

    private Entry entryFor(Cms.Video video) {
        if (video == null) return null;
        String key = LibraryAggregator.normalizeTitle(video.title);
        return key.isEmpty() ? null : entries.get(key);
    }

    /** The single writer entry point every "补源" path calls; safe from worker threads. */
    static void remember(MainActivity a, Cms.Site site, Cms.Video video) {
        if (a == null || site == null || video == null) return;
        a.sourceLedger.add(site, video);
        // A ledger write must show up on the cards already on screen, in place: the wall is a view
        // of this model, and rebuilding the page here is what used to throw it back to the top.
        a.ui.post(
                () -> {
                    if (a.current == null) return;
                    HomeScreen home = a.current.retainedHome;
                    if (home != null) home.refreshCardSourceBadges();
                });
    }

    /**
     * The single read every entry point uses — wall card badge and default source, search card,
     * home poster, the detail page's "来自 N 个站点" row, its switch-source dialog and the click
     * that opens playback. Callers must not re-sort or re-filter the result.
     */
    static Sources resolve(MainActivity a, Cms.Video video) {
        if (a == null || video == null) return Sources.EMPTY;
        List<Cms.Site> known = a.sourceLedger.sourcesOf(video);
        if (known.isEmpty()) return Sources.EMPTY;
        List<Cms.Site> ordered = HomeScreen.netdiskFirst(a, known);
        if (ordered == null || ordered.isEmpty()) return Sources.EMPTY;
        Cms.Site primary = ordered.get(0);
        Map<String, Cms.Video> listings = new LinkedHashMap<>();
        for (Cms.Site site : ordered) {
            if (site == null || site.key == null) continue;
            Cms.Video listing = a.sourceLedger.videoAt(video, site.key);
            if (listing != null) listings.put(site.key, listing);
        }
        return new Sources(ordered, primary, listings.get(primary.key), listings);
    }
}
