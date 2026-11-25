package com.pilipala.marcomovie;

import android.graphics.Color;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owner of the library grid: builds one card, appends cards in budgeted batches, and wires the
 * in-grid focus order row by row.
 *
 * <p><b>Step 2 of the library refactor is a move, not a rewrite.</b> Every method below is the
 * code that used to sit in {@link HomeScreen} ({@code card}, {@code appendCards}, {@code
 * clearGrid}, and the {@code rows} loop at the tail of {@code wireNavigation}); it reaches the
 * screen's helpers through {@code host.} instead of calling them unqualified. Rendering, clicks,
 * status text, the batching budget (12 ms first batch / 5 ms later batches) and the {@code
 * home-video:<index>} focus tags are unchanged on purpose.
 *
 * <p>What deliberately stays in {@link HomeScreen}:
 *
 * <ul>
 *   <li>{@code drawGrid(boolean)} — the "which state to show" entry (status text, 加载更多
 *       visibility, {@code clearGrid}+batching kick-off). It now calls {@link #clear()} and {@link
 *       #appendCards} instead of owning them.
 *   <li>Window bookkeeping ({@code windowStart}, {@code windowSize()}, {@code windowEnd()}),
 *       {@code showWindow}, {@code resolvePendingFocus}, {@code updateMore},
 *       {@code updateFilterContinueButton}, {@code restoreFocus} — card focus restore and the
 *       scroll window belong to the screen's navigation state, and both wall and single-site modes
 *       share them.
 *   <li>{@code card}'s helper surface ({@code siteFor}, {@code bestWallPoster},
 *       {@code loadCardPoster}, {@code posterPlaceholder}, {@code attachSourceBadge},
 *       {@code focusable}, {@code outline}) — these are shared with the rest of the screen; only
 *       their caller moved.
 *   <li>{@code HomeScreen.rows} / {@code content} — the renderer mutates the screen's own list and
 *       host rather than owning a parallel tree, so every existing reader (empty-state checks,
 *       {@code refreshWallLayout}, dock edges) keeps seeing the same objects.
 * </ul>
 */
final class LibraryRenderer {

    private final HomeScreen host;

    /**
     * Model + visible slice of the last wall render. Step 2 does not read them back yet (the wall
     * still renders from {@code state.videos}); they are kept so the header/facet panel of step 3
     * can be generated from the same input that produced the grid instead of re-querying.
     */
    private LibraryModel model;

    private List<LibraryModel.MergedItem> visible = Collections.emptyList();

    LibraryRenderer(HomeScreen host) {
        this.host = host;
    }

    /** The model the grid was last built from, or null before the first wall render. */
    LibraryModel model() {
        return model;
    }

    /** The visible slice of {@link #model()} that produced the current grid. */
    List<LibraryModel.MergedItem> visible() {
        return visible;
    }

    /**
     * Full wall rebuild from the model and its visible slice; replaces the direct {@code
     * drawGrid(false)} call that {@code HomeScreen.applyLibraryWall} used to make.
     *
     * <p>The rebuild still runs through {@link HomeScreen#drawGrid(boolean)}: that method owns the
     * parts kept in the host (status text, 加载更多 visibility, focus restore, window reset). The
     * renderer takes ownership of the grid tree itself through {@link #clear()} and {@link
     * #appendCards(int, int, boolean)}.
     */
    void renderWall(LibraryModel model, List<LibraryModel.MergedItem> visible) {
        this.model = model;
        this.visible = visible == null ? Collections.<LibraryModel.MergedItem>emptyList() : visible;
        host.drawGrid(false);
    }

    /** Empties the grid: drops the card tags, the rows and every child of the content host. */
    void clear() {
        for (int i = 0; i < host.content.getChildCount(); i++) {
            android.view.ViewGroup row = (android.view.ViewGroup) host.content.getChildAt(i);
            for (int j = 0; j < row.getChildCount(); j++)
                if (row.getChildAt(j) instanceof android.view.ViewGroup) {
                    android.view.ViewGroup card = (android.view.ViewGroup) row.getChildAt(j);
                    if (card.getChildCount() > 0) card.getChildAt(0).setTag(null);
                }
        }
        host.content.removeAllViews();
        host.rows.clear();
    }

    /**
     * Appends the next budgeted batch of cards. Same batching rule as before: the first batch
     * ({@code start == 0}) spends up to 12 ms and builds at least {@code COLUMNS * 2} cells, later
     * batches spend up to 5 ms; the remainder is re-posted through {@code postOnAnimation} so the
     * wall fills over a few frames instead of blocking the input thread.
     */
    void appendCards(int start, int epoch, boolean restore) {
        host.pendingGrid = null;
        if (!host.active() || epoch != host.gridEpoch) return;
        int end = start;
        int batchCount = start == 0 ? Math.max(host.COLUMNS * 2, 10) : Math.max(host.COLUMNS, 5);
        long budgetNs = start == 0 ? 12000000L : 5000000L;
        long batchStarted = System.nanoTime();
        for (int index = start; index < host.windowEnd() && index < start + batchCount; index++) {
            int column = index % host.COLUMNS;
            LinearLayout row;
            List<View> focusRow;
            if (column == 0) {
                row = host.a.row();
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(0, host.a.dp(5), 0, host.a.dp(18));
                host.content.addView(row);
                focusRow = new ArrayList<>();
                host.rows.add(focusRow);
                for (int c = 0; c < host.COLUMNS; c++) {
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    cell.rightMargin = host.a.dp(c == host.COLUMNS - 1 ? 0 : 14);
                    row.addView(new View(host.a), cell);
                }
            } else {
                row = (LinearLayout) host.content.getChildAt(host.content.getChildCount() - 1);
                focusRow = host.rows.get(host.rows.size() - 1);
            }
            android.view.ViewGroup.LayoutParams size = row.getChildAt(column).getLayoutParams();
            row.removeViewAt(column);
            View card = card(host.state.videos.get(index), index);
            row.addView(card, column, size);
            focusRow.add(card);
            end = index + 1;
            if (System.nanoTime() - batchStarted >= budgetNs) break;
        }
        host.wireNavigation(Math.max(0, (start - host.state.windowStart) / host.COLUMNS - 1));
        host.resolvePendingFocus(false);
        if (end < host.windowEnd()) {
            final int next = end;
            host.pendingGrid = () -> appendCards(next, epoch, restore);
            Runnable nextBatch = host.pendingGrid;
            host.content.postOnAnimation(
                    () -> {
                        if (host.active() && epoch == host.gridEpoch) host.a.ui.post(nextBatch);
                    });
        } else {
            host.resolvePendingFocus(true);
            host.updateMore();
            host.updateFilterContinueButton();
            host.wireNavigation(Math.max(0, host.rows.size() - 1));
            if (restore
                    && host.a.current != null
                    && host.a.current.focus != null
                    && host.a.current.focus.startsWith("home-video:")) host.restoreFocus();
        }
    }

    /** One wall/single-site card: poster + placeholder, badges, title, click and focus styling. */
    View card(Cms.Video video, int index) {
        LinearLayout card = host.a.vertical();
        card.setPadding(host.a.dp(4), host.a.dp(4), host.a.dp(4), host.a.dp(4));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        ImageView art = host.newAmbientPoster();
        if (host.a.isTvDevice()) TvV17PosterCard.artwork(host.a, art);
        else {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(host.a.bg(0xff25252d, 10));
            art.setClipToOutline(true);
        }
        Cms.Site cardSite = host.siteFor(video);
        boolean showDoubanBadge = !SourceController.isConfigOrNetdiskActionCard(cardSite, video);
        FrameLayout artWrap =
                TvV17PosterCard.wrapArtworkWithDoubanBadge(host.a, art, video, showDoubanBadge);
        card.addView(
                artWrap,
                new LinearLayout.LayoutParams(
                        host.a.dp(host.posterWidth), host.a.dp(host.posterWidth * 1.5f)));
        int sourceCount = host.usesLibraryWall() ? host.librarySourceCount(video) : 0;
        if (sourceCount > 0) host.attachSourceBadge(artWrap, sourceCount);
        // Pass the source's headers so poster hosts that require a referer still render, and fall
        // back to a site-derived referer when the spider provides none.
        java.util.Map<String, String> posterHeaders =
                cardSite == null ? null : new java.util.LinkedHashMap<>(cardSite.headers);
        if (cardSite != null && posterHeaders != null && !posterHeaders.containsKey("Referer")) {
            String referer = cardSite.api == null ? "" : cardSite.api.trim();
            if (referer.startsWith("http")) posterHeaders.put("Referer", referer);
        }
        // Draw a readable placeholder immediately: many poster hosts are unreachable from this box
        // (socket timeouts), and an empty dark rectangle reads as "broken". The real artwork
        // replaces it as soon as it loads.
        art.setImageBitmap(
                HomeScreen.posterPlaceholder(
                        host.a,
                        Math.max(1, host.a.dp(host.posterWidth)),
                        Math.max(1, Math.round(host.a.dp(host.posterWidth) * 1.5f)),
                        video.title));
        // The wall appends rows lazily, so the art view has no size yet when this runs. Loading at
        // w=0 means the decode target collapses and nothing is ever drawn; defer until laid out.
        final java.util.Map<String, String> deferredHeaders = posterHeaders;
        // Warm this card's detail while the user is still browsing, so pressing OK is instant.
        SourceController.prefetchVisible(host.a, cardSite, video);
        final String deferredPoster = host.bestWallPoster(host.a, video);
        host.loadCardPoster(host.a, art, deferredPoster, deferredHeaders, 0);
        TextView title = host.a.text(video.title, 13, 0xffc8c8d0);
        host.single(title);
        title.setPadding(host.a.dp(2), host.a.dp(7), host.a.dp(2), 0);
        card.addView(title, new LinearLayout.LayoutParams(-1, host.a.dp(27)));
        host.focusable(card, "home-video:" + index);
        if (host.a.isTvDevice()) TvFocusFrame.attachCard(card, "home-library-grid");
        host.outline(card, false, 13, Color.TRANSPARENT);
        card.setContentDescription(
                sourceCount > 0 ? video.title + "，" + sourceCount + " 个来源" : video.title);
        card.setOnClickListener(v -> host.a.openListing(cardSite, video));
        card.setOnFocusChangeListener(
                (v, f) -> {
                    if (host.a.isTvDevice()) TvV17PosterCard.focus(host.a, card, title, f);
                    else {
                        host.outline(card, f, 13, Color.TRANSPARENT);
                        title.setTextColor(f ? Color.WHITE : 0xffc8c8d0);
                    }
                    if (f && host.ambient != null) host.ambient.follow(art);
                    if (f) {
                        host.collapseFiltersForBrowsing();
                        int localRow = (index - host.state.windowStart) / Math.max(1, host.COLUMNS);
                        if (localRow >= Math.max(1, host.rows.size() - 2)) {
                            host.maybeAutoLoadMore();
                        }
                        if (!(host.a.scroll instanceof TvScrollView)) {
                            v.requestRectangleOnScreen(
                                    new Rect(
                                            0,
                                            -host.a.dp(8),
                                            v.getWidth(),
                                            v.getHeight() + host.a.dp(10)),
                                    false);
                        }
                        if (!host.a.reduced) {
                            v.postDelayed(
                                    () -> {
                                        if (v.hasFocus() && host.active()) {
                                            SourceController.warmEngineOnFocus(host.a, cardSite);
                                            SourceController.prefetchDetail(
                                                    host.a, cardSite, video);
                                            SourceController.expandFocusedSources(
                                                    host.a, cardSite, video);
                                        }
                                    },
                                    360);
                        }
                    }
                });
        if (index == 0
                && host.ambient != null
                && (host.ambient.wanted == null || !host.ambient.wanted.isAttachedToWindow()))
            host.ambient.follow(art);
        return card;
    }

    /** First card of the grid, or null while row 0 has not been built yet. */
    View firstCard() {
        if (host.rows == null || host.rows.isEmpty()) return null;
        List<View> first = host.rows.get(0);
        return first == null || first.isEmpty() ? null : first.get(0);
    }

    /** Id of {@link #firstCard()}, or {@link View#NO_ID} while the grid is empty. */
    int firstCardId() {
        View card = firstCard();
        return card == null ? View.NO_ID : card.getId();
    }

    /** Id of the first card of the last row, or {@link View#NO_ID} while the grid is empty. */
    int lastRowFirstCardId() {
        if (host.rows == null || host.rows.isEmpty()) return View.NO_ID;
        List<View> last = host.rows.get(host.rows.size() - 1);
        return last == null || last.isEmpty() ? View.NO_ID : last.get(0).getId();
    }

    /**
     * Grid focus order, row by row: UP walks to the row above (row 0 jumps to whatever sits above
     * the grid — 更早/筛选面板/分类标签), DOWN walks to the row below (the last row falls to 加载更多
     * when it is shown), LEFT/RIGHT stay inside the row.
     *
     * <p>The three "what sits above the grid" flags are passed in rather than re-derived here: the
     * caller already resolved them for the rest of {@code wireNavigation}, and re-reading {@code
     * inlineFilters.expanded()} mid-wiring could disagree with the panel's own up-links.
     */
    void wireGridFocus(
            int firstRow,
            boolean earlierShown,
            boolean inlineExpanded,
            int lastInlineRow,
            boolean inlineSummary) {
        List<List<View>> rows = host.rows;
        for (int r = Math.max(0, firstRow); r < rows.size(); r++) {
            List<View> row = rows.get(r);
            host.horizontal(row);
            for (int c = 0; c < row.size(); c++) {
                View card = row.get(c);
                card.setNextFocusUpId(
                        r == 0
                                ? (earlierShown
                                        ? host.earlier.getId()
                                        : inlineExpanded
                                                ? host.inlineFilters
                                                        .nearest(
                                                                host.inlineFilters.rows.get(
                                                                        lastInlineRow),
                                                                card)
                                                        .getId()
                                                : inlineSummary
                                                        ? host.inlineFilters.expandButton.getId()
                                                        : host.selectedTab().getId())
                                : rows.get(r - 1)
                                        .get(Math.min(c, rows.get(r - 1).size() - 1))
                                        .getId());
                card.setNextFocusDownId(
                        r + 1 < rows.size()
                                ? rows.get(r + 1)
                                        .get(Math.min(c, rows.get(r + 1).size() - 1))
                                        .getId()
                                : host.more.isShown() ? host.more.getId() : card.getId());
            }
        }
    }
}
