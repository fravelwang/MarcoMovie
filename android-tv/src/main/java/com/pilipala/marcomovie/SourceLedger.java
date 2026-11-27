package com.pilipala.marcomovie;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The process-wide source ledger: for every normalized title, every site that carries it and that
 * site's own listing, plus when that claim was last verified and how often opening it failed.
 *
 * <p>This is the one place that answers "which sites have this show, and which one do we open".
 * It exists because two parallel answers used to exist — the wall merged each site's home listing,
 * while the curated home page searched sites on demand — so 《两京十五日》 showed 「💗｜虎斑｜网盘」
 * on the home page and「💗｜王子｜APP」behind the same card in 片库.
 *
 * <p>It is deliberately independent of {@link LibraryAggregator.Result}: that result is only the
 * wall's card sample, while a search or a detail match can teach the ledger titles the wall never
 * aggregated.
 *
 * <p>Writers (synchronized): a wall round ({@link #absorb} refreshes {@code checkedAt} but not
 * {@code fails}), the search page's merged results ({@link #absorb} with {@code verified}), a
 * detail/match hit ({@link #remember}: {@code checkedAt=now, fails=0}), a real play start
 * ({@link #rememberPlayed}: {@code lastPlayedSite}, the one field that outranks the netdisk
 * ordering in {@link #resolve}), and a failed open ({@link #markFailed}: {@code fails+1}).
 * Readers: {@link #resolve} for the UI, plus the raw {@link #sourcesOf} the wall model uses to fold
 * known sources into its cards.
 *
 * <p>Persistence: {@code source-ledger-v1.json} in the app files dir, only the minimal fields that
 * open one listing (site key, id, title) plus each title's {@code lastPlayedSite}. The field is
 * additive inside version 1: a file written before it existed simply has no such key, and an older
 * build reading a newer file keeps ignoring an unknown key instead of discarding the whole ledger.
 * Writes are atomic and debounced by the activity; pruning runs only after a load and before a
 * write, never on a render path.
 *
 * <p>Not persisted: {@link #protect} keeps the titles the screen is showing, so a wall round that
 * samples hundreds of other titles cannot evict them. It is a hint set, never a ledger field — the
 * experience layer reports it, the ledger only reads it while choosing eviction victims.
 */
final class SourceLedger {

    static final String FILE_NAME = "source-ledger-v1.json";
    static final int VERSION = 1;

    /** Longest a source claim survives without being verified again. */
    static final long TTL_MS = 14L * 24L * 60L * 60L * 1000L;

    /** Two failed opens are enough evidence that a source no longer has this title. */
    static final int MAX_FAILS = 2;

    /** Titles kept on disk (LRU by last verification). */
    static final int MAX_TITLES = 600;

    /**
     * Ceiling for the protected set: the titles the screen is showing may fill at most this share
     * of {@link #MAX_TITLES}; beyond that they are evicted by the same least-recent rule. Without
     * it a wall that shows everything would keep the ledger growing without bound.
     */
    static final int MAX_PROTECTED_TITLES = MAX_TITLES * 80 / 100;

    /** Sources kept per title: the twelve that matter, by playback order then freshness. */
    static final int MAX_SOURCES_PER_TITLE = 12;

    /** Hard cap on the persisted file; the snapshot drops oldest titles until it fits. */
    static final int MAX_BYTES = 1024 * 1024;

    /** Activity-side debounce: the last change waits this long before the file is rewritten. */
    static final long SAVE_DEBOUNCE_MS = 5000L;

    /**
     * One resolved answer for one title. {@code sites} is already ordered for playback, so
     * {@code defaultSite} is simply the first entry and {@code defaultListing} is that site's own
     * listing (never another site's id opened against this site).
     */
    static final class Sources {
        static final Sources EMPTY =
                new Sources(
                        Collections.<Cms.Site>emptyList(),
                        null,
                        null,
                        Collections.<String, Cms.Video>emptyMap());

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

    /** Which listing one site offers for a title, and how trustworthy that claim still is. */
    private static final class Source {
        Cms.Site site;
        Cms.Video listing;
        long checkedAt;
        int fails;

        Source(Cms.Site site, Cms.Video listing) {
            this.site = site;
            this.listing = listing;
        }
    }

    /** One title's row: sources in first-writer order. */
    private static final class Entry {
        final String title;
        final List<Source> sources = new ArrayList<>();
        final Map<String, Source> bySite = new LinkedHashMap<>();
        long updatedAt;
        /**
         * Site key this title last really started playing from, or null when it never played here.
         * {@link #resolve} puts that site first so the next open does not start from a netdisk site
         * that carries the title with zero episodes. Dropped whenever the site stops being one of
         * this title's sources, so it can never point at a dead site.
         */
        String lastPlayedSite;

        Entry(String title) {
            this.title = title;
        }
    }

    /** A serialized ledger plus the revision it covers, so a failed write stays dirty. */
    static final class Snapshot {
        final String json;
        final long revision;

        Snapshot(String json, long revision) {
            this.json = json;
            this.revision = revision;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /**
     * Normalized titles the screen is showing right now: the wall's visible cards plus the home
     * recommendation shelves. Reported by the experience layer through {@link #protect} and only
     * ever held in memory — it is not a ledger field, is never serialized, and does not touch
     * {@link #revision}.
     */
    private final Set<String> protectedTitles = new HashSet<>();
    /** The exact config site list the stored {@link Cms.Site} references came from. */
    private List<Cms.Site> boundSites;
    private long revision;
    private long savedRevision;

    // ---------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------

    /** A live hit from a search/detail match: the site answered, so the claim is verified. */
    synchronized void verify(Cms.Site site, Cms.Video video) {
        put(site, video, true, true, System.currentTimeMillis());
    }

    /**
     * "This site really played this title": remembered as the title's {@code lastPlayedSite} so the
     * next {@link #resolve} opens it first. Only a site the ledger already lists for the title can
     * be recorded — anything else would be a dead reference the next prune would have to clean up,
     * and a title the ledger has never heard of has no order to change.
     *
     * @return true when the stored pointer changed (the caller then schedules the debounced write)
     */
    synchronized boolean markPlayed(Cms.Video video, String siteKey) {
        if (video == null || siteKey == null) return false;
        String key = siteKey.trim();
        if (key.isEmpty()) return false;
        Entry entry = entryFor(video);
        if (entry == null || !entry.bySite.containsKey(key)) return false;
        if (key.equals(entry.lastPlayedSite)) return false;
        entry.lastPlayedSite = key;
        revision++;
        return true;
    }

    private void put(
            Cms.Site site, Cms.Video video, boolean checked, boolean resetFails, long now) {
        if (site == null || site.key == null || video == null) return;
        String title = video.title == null ? "" : video.title.trim();
        if (title.isEmpty()) return;
        String key = LibraryAggregator.normalizeTitle(title);
        if (key.isEmpty()) return;
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_TITLES) {
                // A long session must stay bounded before the disk prune runs. Same rule as prune
                // step 3: the titles the screen is showing go last, so a round that samples 600
                // other titles cannot push the wall's own cards out mid-round.
                evictForCapacity();
            }
            entry = new Entry(key);
            entries.put(key, entry);
        }
        Source source = entry.bySite.get(site.key);
        if (source == null) {
            source = new Source(site, video);
            entry.sources.add(source);
            entry.bySite.put(site.key, source);
        } else {
            // Keep the freshest listing: ids and titles change between rounds.
            source.site = site;
            source.listing = video;
        }
        if (checked) source.checkedAt = now;
        if (resetFails) source.fails = 0;
        entry.updatedAt = now;
        revision++;
    }

    /**
     * 「谁在墙上」: replaces the protected set with the titles the screen is showing. The experience
     * layer reports what it rendered (the wall's visible cards, the home recommendation shelves) in
     * one batch per render, so nothing here reads a view or a screen state.
     *
     * <p>Replacement, not accumulation: a title that left the screen stops being protected at the
     * next report. Calls are ignored for the ledger's own state (no revision bump), so the debounced
     * writer never sees a protection-only change.
     */
    synchronized void protect(Collection<String> titleKeys) {
        protectedTitles.clear();
        if (titleKeys == null || titleKeys.isEmpty()) return;
        for (String raw : titleKeys) {
            String key = LibraryAggregator.normalizeTitle(raw);
            if (!key.isEmpty()) protectedTitles.add(key);
        }
    }

    /** Titles currently protected, for the prune log and a box-side check. */
    synchronized int protectedCount() {
        return protectedTitles.size();
    }

    /**
     * Drops one title so the in-memory ledger stays at {@link #MAX_TITLES} while a round is still
     * being absorbed. Victim order matches prune step 3: least recently updated first, protected
     * titles only when every remaining title is protected (the cap then still holds).
     */
    private void evictForCapacity() {
        Entry victim = oldestEntry(true);
        if (victim == null) victim = oldestEntry(false);
        if (victim != null) entries.remove(victim.title);
    }

    /** Oldest entry by {@code updatedAt}, optionally skipping the protected titles. */
    private Entry oldestEntry(boolean skipProtected) {
        Entry oldest = null;
        for (Entry entry : entries.values()) {
            if (skipProtected && protectedTitles.contains(entry.title)) continue;
            if (oldest == null || entry.updatedAt < oldest.updatedAt) oldest = entry;
        }
        return oldest;
    }

    /**
     * One failed open: a detail fetch that came back empty/unreachable, or a switch-source pick
     * whose listing was missing. The source is dropped by the next prune once {@link #MAX_FAILS}
     * is reached.
     */
    synchronized boolean markFailed(Cms.Video video, String siteKey) {
        if (video == null || siteKey == null) return false;
        Entry entry = entryFor(video);
        if (entry == null) return false;
        Source source = entry.bySite.get(siteKey);
        if (source == null) return false;
        source.fails++;
        revision++;
        return true;
    }

    /** Same as {@link #markFailed(Cms.Video, String)} but keyed by the listing id a fetch used. */
    synchronized boolean markFailed(String siteKey, String listingId) {
        if (siteKey == null || listingId == null || listingId.isEmpty()) return false;
        boolean marked = false;
        for (Entry entry : entries.values()) {
            Source source = entry.bySite.get(siteKey);
            if (source == null || source.listing == null) continue;
            if (!listingId.equals(source.listing.id)) continue;
            source.fails++;
            marked = true;
            break;
        }
        if (marked) revision++;
        return marked;
    }

    /**
     * Folds a wall round or a search page merge into the ledger. Called once per round and once per
     * search render, so a title keeps the sites that answered last time even when this round's home
     * listing omitted them (site timeouts made the card's badge flicker between 1 源 and 2 源).
     */
    void absorb(LibraryAggregator.Result result, boolean verified) {
        if (result == null) return;
        List<LibraryAggregator.Item> items;
        synchronized (result) {
            items = new ArrayList<>(result.items);
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (LibraryAggregator.Item item : items) {
                if (item == null || item.video == null) continue;
                List<Cms.Site> sites;
                Map<String, Cms.Video> listings;
                synchronized (result) {
                    sites = new ArrayList<>(item.sites);
                    listings = new LinkedHashMap<>(item.videosBySite);
                }
                for (Cms.Site site : sites) {
                    if (site == null || site.key == null) continue;
                    put(site, listings.get(site.key), true, verified, now);
                }
            }
        }
    }

    /**
     * Config switch: keep every source whose site key still exists in the config, and re-point it
     * at the new {@link Cms.Site} instance (the old config's objects are dead). Sources from a
     * removed package are dropped; the rest survive and are aged out by the TTL instead.
     */
    synchronized boolean rebindSites(List<Cms.Site> configSites) {
        // An empty config (still loading, or a temporarily removed package) is not evidence that
        // every site is gone; keep the ledger and let the next real config compare it.
        if (configSites == null || configSites.isEmpty()) return false;
        boolean changed = dropAndRebind(indexSites(configSites));
        boundSites = configSites;
        return changed;
    }

    private boolean dropAndRebind(Map<String, Cms.Site> byKey) {
        boolean changed = false;
        java.util.Iterator<Entry> titles = entries.values().iterator();
        while (titles.hasNext()) {
            Entry entry = titles.next();
            java.util.Iterator<Source> sources = entry.sources.iterator();
            while (sources.hasNext()) {
                Source source = sources.next();
                Cms.Site fresh = source.site == null ? null : byKey.get(source.site.key);
                if (fresh == null) {
                    sources.remove();
                    if (source.site != null) entry.bySite.remove(source.site.key);
                    changed = true;
                    continue;
                }
                source.site = fresh;
            }
            if (dropDeadPlayedSite(entry)) changed = true;
            if (entry.sources.isEmpty()) {
                titles.remove();
                changed = true;
            }
        }
        if (changed) revision++;
        return changed;
    }

    /**
     * "上次播过的站" cleanup: a {@code lastPlayedSite} whose site is no longer one of this title's
     * sources (deleted site, package switch, TTL, two failed opens, capacity trim) is dropped here,
     * so {@link #resolve} never has to skip a pointer at nothing.
     */
    private boolean dropDeadPlayedSite(Entry entry) {
        if (entry == null || entry.lastPlayedSite == null) return false;
        if (entry.bySite.containsKey(entry.lastPlayedSite)) return false;
        entry.lastPlayedSite = null;
        return true;
    }

    /**
     * Cleanup order from the design: 过期 → 失效 → 标题 LRU → 单标题来源上限, plus the config
     * comparison by siteKey. Runs after a load and before a write; never on a render path.
     */
    synchronized boolean prune(List<Cms.Site> configSites, long now) {
        boolean changed = false;
        int droppedSites = 0;
        int expired = 0;
        int failed = 0;
        int trimmed = 0;
        if (configSites != null && !configSites.isEmpty()) {
            int before = countSources();
            changed = dropAndRebind(indexSites(configSites));
            droppedSites = before - countSources();
            boundSites = configSites;
        }
        // 1 过期, 2 失效: both are per-source and independent of the title caps.
        java.util.Iterator<Entry> titles = entries.values().iterator();
        while (titles.hasNext()) {
            Entry entry = titles.next();
            java.util.Iterator<Source> sources = entry.sources.iterator();
            while (sources.hasNext()) {
                Source source = sources.next();
                if (now - source.checkedAt > TTL_MS) {
                    sources.remove();
                    entry.bySite.remove(source.site.key);
                    expired++;
                    changed = true;
                } else if (source.fails >= MAX_FAILS) {
                    sources.remove();
                    entry.bySite.remove(source.site.key);
                    failed++;
                    changed = true;
                }
            }
            if (entry.sources.isEmpty()) {
                titles.remove();
                changed = true;
            }
        }
        // 3 标题总量 + 保护清单: drop the least recently verified titles first, but never a title
        // the screen is showing (reported through protect). Two caps, both required: MAX_TITLES
        // overall, and MAX_PROTECTED_TITLES for the protected share so "everything is on the wall"
        // cannot grow the ledger without bound.
        if (!protectedTitles.isEmpty()) {
            // A protection report may name titles the ledger never learned (a curated card with no
            // site yet); only a title that has an entry can be spared from eviction.
            protectedTitles.retainAll(entries.keySet());
        }
        int droppedProtected = 0;
        if (protectedTitles.size() > MAX_PROTECTED_TITLES) {
            List<Entry> onScreen = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (protectedTitles.contains(entry.title)) onScreen.add(entry);
            }
            onScreen.sort(Comparator.comparingLong(e -> e.updatedAt));
            int over = protectedTitles.size() - MAX_PROTECTED_TITLES;
            for (int i = 0; i < over && i < onScreen.size(); i++) {
                String title = onScreen.get(i).title;
                if (entries.remove(title) == null) continue;
                protectedTitles.remove(title);
                droppedProtected++;
                changed = true;
            }
        }
        if (entries.size() > MAX_TITLES) {
            List<Entry> byAge = new ArrayList<>(entries.values());
            byAge.sort(Comparator.comparingLong(e -> e.updatedAt));
            int drop = entries.size() - MAX_TITLES;
            // Pass 0 takes only the titles that are not on screen; pass 1 is the fallback for a
            // wall that covers everything, so the total cap can never be exceeded either.
            for (int pass = 0; pass < 2 && drop > 0; pass++) {
                for (Entry entry : byAge) {
                    if (drop == 0) break;
                    if (!entries.containsKey(entry.title)) continue;
                    boolean onScreen = protectedTitles.contains(entry.title);
                    if (pass == 0 && onScreen) continue;
                    entries.remove(entry.title);
                    if (onScreen) {
                        protectedTitles.remove(entry.title);
                        droppedProtected++;
                    }
                    drop--;
                    changed = true;
                }
            }
        }
        // 4 单标题来源上限: keep the twelve that matter (cloud drive first, freshest).
        for (Entry entry : entries.values()) {
            int before = entry.sources.size();
            if (before > MAX_SOURCES_PER_TITLE && trimSources(entry)) {
                trimmed += before - entry.sources.size();
                changed = true;
            }
        }
        // 5 「上次播过的站」: the pointer must not survive the site it names.
        for (Entry entry : entries.values()) {
            if (dropDeadPlayedSite(entry)) changed = true;
        }
        if (changed) revision++;
        if (changed) {
            // One line per prune, so a box-side check can see why a source disappeared.
            AppLog.w(
                    "SourceLedger",
                    "prune: titles="
                            + entries.size()
                            + " sources="
                            + countSources()
                            + " droppedSites="
                            + droppedSites
                            + " expired="
                            + expired
                            + " failed="
                            + failed
                            + " trimmed="
                            + trimmed
                            + " protected="
                            + protectedCount()
                            + " droppedProtected="
                            + droppedProtected);
        }
        return changed;
    }

    private int countSources() {
        int count = 0;
        for (Entry entry : entries.values()) count += entry.sources.size();
        return count;
    }

    /**
     * Keeps the twelve sources that matter: cloud drive first, then the most recently verified.
     * The survivors keep their original order so playback order does not shuffle.
     */
    private boolean trimSources(Entry entry) {
        List<Source> ranked = new ArrayList<>(entry.sources);
        ranked.sort(
                (left, right) -> {
                    int byRank =
                            Integer.compare(
                                    HomeScreen.siteRank(left.site), HomeScreen.siteRank(right.site));
                    if (byRank != 0) return byRank;
                    return Long.compare(right.checkedAt, left.checkedAt);
                });
        Set<String> keep = new LinkedHashSet<>();
        for (Source source : ranked) {
            if (keep.size() >= MAX_SOURCES_PER_TITLE) break;
            if (source.site != null && source.site.key != null) keep.add(source.site.key);
        }
        boolean changed = false;
        java.util.Iterator<Source> sources = entry.sources.iterator();
        while (sources.hasNext()) {
            Source source = sources.next();
            if (source.site != null && keep.contains(source.site.key)) continue;
            sources.remove();
            if (source.site != null) entry.bySite.remove(source.site.key);
            changed = true;
        }
        return changed;
    }

    // ---------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------

    /** Raw known sources for a title in first-writer order; the wall model folds these into cards. */
    synchronized List<Cms.Site> sourcesOf(Cms.Video video) {
        Entry entry = entryFor(video);
        if (entry == null || entry.sources.isEmpty()) return Collections.emptyList();
        List<Cms.Site> out = new ArrayList<>(entry.sources.size());
        for (Source source : entry.sources) {
            if (source.site != null) out.add(source.site);
        }
        return out;
    }

    /** That site's own listing for this title, or null when the ledger does not know it. */
    synchronized Cms.Video videoAt(Cms.Video video, String siteKey) {
        Source source = sourceFor(video, siteKey);
        return source == null ? null : source.listing;
    }

    /** The site this title last really played from, or null when it never played here. */
    synchronized String lastPlayedSite(Cms.Video video) {
        Entry entry = entryFor(video);
        return entry == null ? null : entry.lastPlayedSite;
    }

    synchronized int titleCount() {
        return entries.size();
    }

    synchronized int sourceCount() {
        int count = 0;
        for (Entry entry : entries.values()) count += entry.sources.size();
        return count;
    }

    private Entry entryFor(Cms.Video video) {
        if (video == null) return null;
        String title = video.title == null ? "" : video.title.trim();
        if (title.isEmpty()) return null;
        String key = LibraryAggregator.normalizeTitle(title);
        return key.isEmpty() ? null : entries.get(key);
    }

    private Source sourceFor(Cms.Video video, String siteKey) {
        if (siteKey == null) return null;
        Entry entry = entryFor(video);
        return entry == null ? null : entry.bySite.get(siteKey);
    }

    /** The single writer entry point every "补源" path calls; safe from worker threads. */
    static void remember(MainActivity a, Cms.Site site, Cms.Video video) {
        if (a == null || site == null || video == null) return;
        a.sourceLedger.verify(site, video);
        a.onLedgerChanged();
    }

    /**
     * Report entry point for the experience layer: "these are the titles on screen right now".
     * Called once per render batch (the wall's visible cards, the home recommendation shelves),
     * never once per card. The ledger keeps nothing but the set; which titles count as on screen is
     * the screen's decision, not the ledger's.
     */
    static void protect(MainActivity a, Collection<String> titleKeys) {
        if (a == null || titleKeys == null) return;
        a.sourceLedger.protect(titleKeys);
    }

    /**
     * "从这一站起播了": records the site that really started playing this title, so the next open of
     * the same title resolves to it first instead of the netdisk default. Called from the
     * activity's play entry points, which are the last place outside the player state machine that
     * still knows both the site and the title.
     */
    static void rememberPlayed(MainActivity a, Cms.Site site, Cms.Video video) {
        if (a == null || site == null || video == null) return;
        if (a.sourceLedger.markPlayed(video, site.key)) a.onLedgerChanged();
    }

    /** Records one failed open for that title on that site. */
    static void markFailed(MainActivity a, Cms.Video video, String siteKey) {
        if (a == null || video == null || siteKey == null) return;
        if (a.sourceLedger.markFailed(video, siteKey)) a.onLedgerChanged();
    }

    /** Records one failed detail fetch by the (site, listing id) the fetch used. */
    static void markFailed(MainActivity a, String siteKey, String listingId) {
        if (a == null || siteKey == null || listingId == null) return;
        if (a.sourceLedger.markFailed(siteKey, listingId)) a.onLedgerChanged();
    }

    /**
     * Re-binds the stored site references when the activity installed a different config. Called
     * from the one read path, so a package switch cannot leave dead {@link Cms.Site} objects in a
     * badge, a switch list or a default source for longer than one read.
     */
    void syncConfig(MainActivity a) {
        List<Cms.Site> sites = a == null || a.config == null ? null : a.config.sites;
        boolean changed;
        synchronized (this) {
            if (sites == null || sites == boundSites) return;
            changed = rebindSites(sites);
        }
        if (changed) a.onLedgerChanged();
    }

    /**
     * The single read every entry point uses — wall card badge and default source, search card,
     * home poster, the detail page's "来自 N 个站点" row, its switch-source dialog and the click
     * that opens playback. Callers must not re-sort or re-filter the result.
     */
    static Sources resolve(MainActivity a, Cms.Video video) {
        if (a == null || video == null) return Sources.EMPTY;
        a.sourceLedger.syncConfig(a);
        List<Cms.Site> known = a.sourceLedger.sourcesOf(video);
        if (known.isEmpty()) return Sources.EMPTY;
        List<Cms.Site> ordered = HomeScreen.netdiskFirst(a, known);
        if (ordered == null || ordered.isEmpty()) return Sources.EMPTY;
        // 「记住能播的站」: the site this title last really played from wins over the netdisk order.
        ordered = lastPlayedFirst(a.sourceLedger.lastPlayedSite(video), ordered);
        Cms.Site primary = ordered.get(0);
        Map<String, Cms.Video> listings = new LinkedHashMap<>();
        for (Cms.Site site : ordered) {
            if (site == null || site.key == null) continue;
            Cms.Video listing = a.sourceLedger.videoAt(video, site.key);
            if (listing != null) listings.put(site.key, listing);
        }
        return new Sources(ordered, primary, listings.get(primary.key), listings);
    }

    /**
     * {@link #resolve(MainActivity, Cms.Video)} 再加一片由调用方给的候选：片库墙卡片自己记着
     * "这个标题在哪些站有"（{@link LibraryAggregator.Item#sites}），点开卡片时那条信息不该丢。
     *
     * <p>顺序：卡片来源（调用方已按配置序排好）在前，账本只补卡片没列的站；两层都按 site key
     * 去重，listing 各取自己那份（卡片的是该站自己的目录项，账本的是它记下的那份）。卡片没有
     * listing 的站不进候选——{@code SourceAutoSwitch} 没法用一个空 id 去问详情。
     *
     * <p>账本为空时直接返回卡片池：这时 {@code openTitle} 也能打开卡片的第一个来源，而不是因为
     * "账本还不认识这部片"掉到推荐匹配页。
     *
     * <p>默认站仍按账本原来的选择（上次能播的站优先、网盘优先）：这张池子只扩候选，不抢"点开
     * 卡片先打开哪一页"的决定权。只有账本没有可用默认值时，才落到卡片第一个有 listing 的站。
     */
    static Sources resolve(MainActivity a, Cms.Video video, Sources cardSources) {
        Sources ledger = resolve(a, video);
        if (cardSources == null || cardSources.isEmpty()) return ledger;
        if (ledger.isEmpty()) return cardSources;
        List<Cms.Site> sites = new ArrayList<>(cardSources.sites.size() + ledger.sites.size());
        Map<String, Cms.Video> listings = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Cms.Site site : cardSources.sites) {
            if (site == null || site.key == null || site.key.isEmpty() || !seen.add(site.key)) {
                continue;
            }
            Cms.Video listing = cardSources.listingAt(site.key);
            if (listing == null) continue;
            sites.add(site);
            listings.put(site.key, listing);
        }
        for (Cms.Site site : ledger.sites) {
            if (site == null || site.key == null || site.key.isEmpty() || !seen.add(site.key)) {
                continue;
            }
            sites.add(site);
            Cms.Video listing = ledger.listingAt(site.key);
            if (listing != null) listings.put(site.key, listing);
        }
        Cms.Site primary = ledger.defaultSite;
        Cms.Video primaryListing = ledger.defaultListing;
        if (primary == null
                || primaryListing == null
                || !listings.containsKey(primary.key)) {
            primary = null;
            primaryListing = null;
            for (Cms.Site site : sites) {
                Cms.Video listing = listings.get(site.key);
                if (listing == null) continue;
                primary = site;
                primaryListing = listing;
                break;
            }
        }
        return new Sources(sites, primary, primaryListing, listings);
    }

    /**
     * 把片库墙卡片自己记的来源做成一片候选池；只留"有这个站自己的 listing"的站，顺序原样。
     * 返回 null 表示这张卡没有自带来源（例如只有豆瓣元数据的推荐卡）。
     */
    static Sources candidates(List<Cms.Site> sites, Map<String, Cms.Video> listings) {
        if (sites == null || sites.isEmpty() || listings == null || listings.isEmpty()) return null;
        List<Cms.Site> ordered = new ArrayList<>(sites.size());
        Map<String, Cms.Video> byKey = new LinkedHashMap<>();
        for (Cms.Site site : sites) {
            if (site == null || site.key == null || site.key.isEmpty()) continue;
            Cms.Video listing = listings.get(site.key);
            if (listing == null || listing.id == null || listing.id.trim().isEmpty()) continue;
            if (byKey.containsKey(site.key)) continue;
            ordered.add(site);
            byKey.put(site.key, listing);
        }
        if (ordered.isEmpty()) return null;
        Cms.Site primary = ordered.get(0);
        return new Sources(ordered, primary, byKey.get(primary.key), byKey);
    }

    /**
     * Moves the site that last really played this title to the head of {@code ordered}; the rest of
     * the list keeps the {@link HomeScreen#netdiskFirst} order, so nothing but the primary changes.
     * A pointer at a site that is no longer one of the title's sources is ignored (and cleaned up by
     * the next prune), which is why the reorder needs no extra validation here.
     */
    private static List<Cms.Site> lastPlayedFirst(String playedSiteKey, List<Cms.Site> ordered) {
        if (playedSiteKey == null || playedSiteKey.isEmpty()) return ordered;
        if (ordered == null || ordered.size() < 2) return ordered;
        Cms.Site head = ordered.get(0);
        if (head != null && playedSiteKey.equals(head.key)) return ordered;
        int index = -1;
        for (int i = 1; i < ordered.size(); i++) {
            Cms.Site site = ordered.get(i);
            if (site != null && playedSiteKey.equals(site.key)) {
                index = i;
                break;
            }
        }
        if (index < 0) return ordered;
        List<Cms.Site> reordered = new ArrayList<>(ordered.size());
        reordered.add(ordered.get(index));
        for (int i = 0; i < ordered.size(); i++) {
            if (i != index) reordered.add(ordered.get(i));
        }
        return reordered;
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    /**
     * Replaces the in-memory ledger with the persisted one, then prunes it against the current
     * config. Called once at startup, before any round can write. A missing or broken file simply
     * means an empty ledger.
     */
    void loadFrom(File file, List<Cms.Site> configSites) {
        if (file == null || !file.isFile()) return;
        String raw;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), (long) MAX_BYTES)];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) break;
                read += n;
            }
            raw = new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            AppLog.w("SourceLedger", "read failed", failure);
            return;
        }
        Map<String, Entry> loaded = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != VERSION) return;
            JSONArray titles = root.optJSONArray("titles");
            if (titles == null) return;
            long now = System.currentTimeMillis();
            for (int i = 0; i < titles.length() && loaded.size() < MAX_TITLES; i++) {
                JSONObject row = titles.optJSONObject(i);
                if (row == null) continue;
                JSONArray sources = row.optJSONArray("sources");
                if (sources == null || sources.length() == 0) continue;
                String key = row.optString("key", "").trim();
                if (key.isEmpty()) continue;
                Entry entry = new Entry(key);
                long updatedAt = row.optLong("updatedAt", 0L);
                // Missing in a ledger written before the field existed: simply "never played".
                String playedSite = row.optString("lastPlayedSite", "").trim();
                long newestCheck = 0L;
                for (int j = 0; j < sources.length(); j++) {
                    JSONObject item = sources.optJSONObject(j);
                    if (item == null) continue;
                    String siteKey = item.optString("site", "").trim();
                    String id = item.optString("id", "").trim();
                    String title = item.optString("title", "").trim();
                    if (siteKey.isEmpty() || id.isEmpty() || title.isEmpty()) continue;
                    Cms.Video listing = new Cms.Video();
                    listing.id = id;
                    listing.title = title;
                    Cms.Site site = new Cms.Site();
                    site.key = siteKey;
                    site.name = siteKey;
                    Source source = new Source(site, listing);
                    source.checkedAt = item.optLong("checkedAt", 0L);
                    source.fails = item.optInt("fails", 0);
                    if (now - source.checkedAt > TTL_MS) continue;
                    entry.sources.add(source);
                    entry.bySite.put(siteKey, source);
                    newestCheck = Math.max(newestCheck, source.checkedAt);
                }
                if (!entry.sources.isEmpty()) {
                    entry.updatedAt = Math.max(updatedAt, newestCheck);
                    // Only a pointer at a loaded source survives; a stale one is not worth keeping.
                    if (!playedSite.isEmpty() && entry.bySite.containsKey(playedSite)) {
                        entry.lastPlayedSite = playedSite;
                    }
                    loaded.put(key, entry);
                }
            }
        } catch (Throwable failure) {
            AppLog.w("SourceLedger", "parse failed", failure);
            return;
        }
        synchronized (this) {
            entries.clear();
            entries.putAll(loaded);
            revision++;
        }
        prune(configSites, System.currentTimeMillis());
        AppLog.w(
                "SourceLedger",
                "loaded from disk: titles=" + titleCount() + " sources=" + sourceCount());
    }

    /**
     * Prunes, caps the file size and serializes the ledger. Runs on the caller's thread (the
     * activity calls it on the UI thread right before a write); the returned revision tells the
     * caller which state the JSON represents, so a failed write does not mark it saved.
     */
    Snapshot snapshot(List<Cms.Site> configSites, long now) {
        synchronized (this) {
            prune(configSites, now);
            JSONObject root = toJson(configSites, now);
            // Drop the oldest titles until the file fits the hard cap. A ledger of this size is
            // pathological (600 titles x 12 sources is a few hundred KB), so this is a safety net —
            // it still spares the on-screen titles while anything else is left to drop.
            while (!entries.isEmpty() && utf8Length(root) > MAX_BYTES) {
                Entry oldest = oldestEntry(true);
                if (oldest == null) oldest = oldestEntry(false);
                if (oldest == null) break;
                entries.remove(oldest.title);
                protectedTitles.remove(oldest.title);
                revision++;
                root = toJson(configSites, now);
            }
            return new Snapshot(root.toString(), revision);
        }
    }

    private JSONObject toJson(List<Cms.Site> configSites, long now) {
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("savedAt", now);
            root.put("configSignature", configSignature(configSites));
            JSONArray titles = new JSONArray();
            for (Entry entry : entries.values()) {
                if (entry.sources.isEmpty()) continue;
                JSONObject row = new JSONObject();
                row.put("key", entry.title);
                row.put("updatedAt", entry.updatedAt);
                if (entry.lastPlayedSite != null) {
                    row.put("lastPlayedSite", entry.lastPlayedSite);
                }
                JSONArray sources = new JSONArray();
                for (Source source : entry.sources) {
                    if (source.site == null || source.site.key == null || source.listing == null) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put("site", source.site.key);
                    item.put("id", source.listing.id == null ? "" : source.listing.id);
                    item.put("title", source.listing.title == null ? "" : source.listing.title);
                    item.put("checkedAt", source.checkedAt);
                    item.put("fails", source.fails);
                    sources.put(item);
                }
                if (sources.length() == 0) continue;
                row.put("sources", sources);
                titles.put(row);
            }
            root.put("titles", titles);
        } catch (Throwable ignored) {
        }
        return root;
    }

    /** Writes the snapshot atomically: temp file in the same directory, fsync, rename. */
    static boolean writeAtomic(File target, String raw) {
        if (target == null || raw == null) return false;
        File directory = target.getParentFile();
        if (directory == null) return false;
        if (!directory.exists() && !directory.mkdirs()) return false;
        File tmp = null;
        try {
            tmp = File.createTempFile("source-ledger-", ".tmp", directory);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(raw.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (tmp.renameTo(target)) return true;
            // Defensive fallback for file systems that cannot rename over an existing file.
            File backup = new File(directory, target.getName() + ".bak");
            if (backup.exists() && !backup.delete()) return false;
            if (target.exists() && !target.renameTo(backup)) return false;
            if (tmp.renameTo(target)) {
                backup.delete();
                return true;
            }
            if (backup.exists()) backup.renameTo(target);
            return false;
        } catch (Throwable failure) {
            AppLog.w("SourceLedger", "write failed", failure);
            return false;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** Signature of the site-key set, stored for diagnosis (a mismatch does not discard data). */
    static String configSignature(List<Cms.Site> sites) {
        if (sites == null || sites.isEmpty()) return "";
        Set<String> keys = new java.util.TreeSet<>();
        for (Cms.Site site : sites) {
            if (site != null && site.key != null && !site.key.trim().isEmpty()) keys.add(site.key);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("\n", keys).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.substring(0, 16);
        } catch (Throwable ignored) {
            return Integer.toHexString(keys.hashCode());
        }
    }

    synchronized boolean dirty() {
        return revision != savedRevision;
    }

    synchronized void markSaved(long revision) {
        if (revision > savedRevision) savedRevision = revision;
    }

    private static int utf8Length(JSONObject root) {
        try {
            return root.toString().getBytes(StandardCharsets.UTF_8).length;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Map<String, Cms.Site> indexSites(List<Cms.Site> sites) {
        Map<String, Cms.Site> byKey = new LinkedHashMap<>();
        if (sites == null) return byKey;
        for (Cms.Site site : sites) {
            if (site == null || site.key == null || site.key.trim().isEmpty()) continue;
            byKey.put(site.key, site);
        }
        return byKey;
    }
}
