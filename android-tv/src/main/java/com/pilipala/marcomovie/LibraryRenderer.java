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
import java.util.List;
import java.util.Map;

/**
 * Owner of the library chrome and grid: one card builder, budgeted card batches, the in-grid focus
 * order, and (step 3) the inline {@code 筛选} facet panel plus the header/facet/grid focus chain.
 *
 * <p><b>Step 2 of the library refactor was a move, not a rewrite.</b> The grid code below is the
 * code that used to sit in {@link HomeScreen} ({@code card}, {@code appendCards}, {@code
 * clearGrid}, and the {@code rows} loop at the tail of {@code wireNavigation}); it reaches the
 * screen's helpers through {@code host.} instead of calling them unqualified. Rendering, clicks,
 * status text, the batching budget (12 ms first batch / 5 ms later batches) and the {@code
 * home-video:<index>} focus tags are unchanged on purpose.
 *
 * <p><b>Step 3 adds the facet panel under one rule: build once, then restyle.</b> {@link
 * #bindModel(LibraryModel, Object)} keys the rebuild on the wall <em>result</em> identity (the
 * {@code LibraryAggregator.Result} that produced the render), not on the facet fingerprint: a chip
 * press re-renders the same result, so the chip tree — and the focused chip — must stay attached,
 * while a new aggregation round rebuilds exactly once. A chip press never calls {@link #bindModel}:
 * it mutates the screen's filter fields, restyles the existing chips in place ({@link
 * #restyleChips()}) and asks the screen to re-query and re-render the grid. The only view-removal
 * calls in this file are the panel rebuild in {@link #buildPanel} and the grid reset in {@link
 * #clear()}.
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

    /** Panel host handed over by {@link HomeScreen#show()}; the renderer owns everything inside. */
    private LinearLayout panel;
    /** Panel rows in display order; each entry is the row's focusable chips, left to right. */
    private final List<List<View>> panelRows = new ArrayList<>();
    /** Every panel chip with the axis/value it selects; the source of both styling and clicks. */
    private final List<Chip> panelChips = new ArrayList<>();
    /**
     * Signature of the model the panel was built from (sources + facet values/counts). Only a
     * fallback for the case where no wall-result identity is available.
     */
    private String panelSignature = "";

    /**
     * Identity of the {@code LibraryAggregator.Result} the panel was built from. This — not the
     * facet fingerprint — is the "the wall result changed" key: a filter press re-renders the very
     * same result (so the panel must not be touched), while retry / a new aggregation hands over a
     * new result object (so it is rebuilt exactly once). Facet counts derive from video metadata
     * that prefetch can backfill at any moment; keying on them used to rebuild the panel under the
     * user's finger on the first press after a page load.
     */
    private Object wallToken;

    /** True once {@link #buildPanel} has produced the current chip tree. */
    private boolean panelBuilt;

    private boolean panelExpanded;

    private static final int AXIS_SOURCE = 0, AXIS_NETDISK = 1, AXIS_MULTI = 2, AXIS_SORT = 3;
    private static final int AXIS_YEAR = 4, AXIS_AREA = 5, AXIS_GENRE = 6;
    /** Same 10-value budget the old taxonomy rows used; values beyond it are not shown. */
    private static final int MAX_FACET_VALUES = 10;

    /** One focusable facet chip and the filter field it drives. */
    private static final class Chip {
        final TextView view;
        final int axis;
        /** Facet value; empty for the "全部" / "合并墙" entry. */
        final String value;

        Chip(TextView view, int axis, String value) {
            this.view = view;
            this.axis = axis;
            this.value = value == null ? "" : value;
        }
    }

    LibraryRenderer(HomeScreen host) {
        this.host = host;
    }

    // -------------------------------------------------------------------------------------------
    // Facet panel (step 3): build once per wall result, then only restyle / show / hide.
    // -------------------------------------------------------------------------------------------

    /**
     * Takes the panel host the screen created in {@link HomeScreen#show()}. The screen keeps the
     * container (so page layout is still its business); every child inside is built and owned here.
     * Re-wires the header/panel focus ids once the panel has been laid out.
     */
    void attachPanel(LinearLayout panelHost) {
        panel = panelHost;
        if (panelHost == null) return;
        panelHost.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (!panelExpanded || panelRows.isEmpty()) return;
                    if (right - left == oldRight - oldLeft
                            && bottom - top == oldBottom - oldTop) return;
                    // Row widths (and therefore the nearest-chip links) are only known after layout.
                    host.wireNavigation();
                });
        applyPanelVisibility();
    }

    /**
     * Binds the wall render. The chip tree is rebuilt only when the wall <em>result</em> changed
     * ({@code wallResult} identity): a filter press re-renders the same result and must leave the
     * panel and its focused chip attached. {@code null} falls back to the facet fingerprint.
     */
    void bindModel(LibraryModel model, Object wallResult) {
        if (panel == null) return;
        if (wallResult != null) {
            if (wallResult == wallToken && panelBuilt) return;
            wallToken = wallResult;
            String signature = panelSignature(model);
            panelSignature = signature;
            buildPanel(model);
            return;
        }
        String signature = panelSignature(model);
        if (signature.equals(panelSignature) && panelBuilt) return;
        panelSignature = signature;
        buildPanel(model);
    }

    /** True while the facet panel is expanded (only possible in wall mode with a built panel). */
    boolean panelExpanded() {
        return panelExpanded;
    }

    /**
     * Expand / collapse. Visibility and focus only: the chip tree is not touched, so the user's
     * selection and the panel's own navigation ids stay valid. Expanding puts focus on the first
     * panel chip; collapsing hands it back to the 筛选 pill.
     */
    void setExpanded(boolean expanded) {
        if (panel == null) return;
        if (expanded && panelRows.isEmpty()) return;
        boolean changed = panelExpanded != expanded;
        panelExpanded = expanded;
        applyPanelVisibility();
        updateFilterPillLabel();
        if (!changed) return;
        host.wireNavigation();
        if (panelExpanded) {
            View first = firstPanelChip();
            if (first != null) {
                first.requestFocus();
                // The panel was GONE until this frame; if focus did not take, retry once after it
                // is measured. Never leave focus on the pill with the panel already open.
                if (!first.hasFocus()) first.post(first::requestFocus);
                revealFocusSoon();
            }
        } else if (filterPillShown()) {
            host.filterToggle.requestFocus();
        }
    }

    /**
     * Re-applies the selected look to the live chips from the current filter state. The only thing
     * a chip press does to the panel; nothing is added or removed.
     */
    void restyleChips() {
        for (Chip chip : panelChips) {
            if (chip == null || chip.view == null) continue;
            styleChip(chip, chip.view.hasFocus());
        }
    }

    /** Panel rows as focusable chip lists, for the dock-edge pass in {@code wireNavigation}. */
    List<List<View>> panelLines() {
        return panelRows;
    }

    /**
     * HeaderBar focus order (合并墙 pill → 筛选 pill). Down goes to the panel's first chip while it
     * is expanded, otherwise straight to the grid. {@code gridDownId} is the screen's
     * {@code contentDownId} (-1 when the grid is empty).
     *
     * <p>Both pills' links are written even when the 筛选 pill is temporarily hidden: they are then
     * already correct the moment it becomes visible, and a wire that ran before the heading had a
     * parent can never leave them at {@link View#NO_ID}. Visibility — not {@code isShown()} — is the
     * test, so a wire that runs before the first layout still counts.
     */
    void wireWallHeader(int sourceUpId, int gridDownId) {
        if (host.source == null) return;
        host.source.setNextFocusUpId(sourceUpId);
        View firstChip = panelExpanded ? firstPanelChip() : null;
        int downId =
                firstChip != null
                        ? firstChip.getId()
                        : gridDownId != -1 ? gridDownId : host.source.getId();
        host.source.setNextFocusDownId(downId);
        if (host.filterToggle == null) {
            host.source.setNextFocusRightId(host.source.getId());
            return;
        }
        boolean pillShown = filterPillShown();
        host.filterToggle.setNextFocusLeftId(host.source.getId());
        host.filterToggle.setNextFocusRightId(host.filterToggle.getId());
        host.filterToggle.setNextFocusUpId(sourceUpId);
        host.filterToggle.setNextFocusDownId(downId);
        host.source.setNextFocusRightId(pillShown ? host.filterToggle.getId() : host.source.getId());
    }

    /** The wall 筛选 pill counts as part of the header whenever it is VISIBLE in the hierarchy. */
    private boolean filterPillShown() {
        return host.filterToggle != null && host.filterToggle.getVisibility() == View.VISIBLE;
    }

    /**
     * Hard fallback for the two header pills, called from {@code HomeScreen.navigationKey} before
     * the generic nextFocus lookup: 合并墙 →(right) 筛选 pill, 筛选 →(down) panel first chip. The
     * outcome is identical to the wired links, but it cannot be defeated by a wire that ran before
     * the heading was laid out. Returns true when it moved focus.
     */
    boolean headerKey(int key) {
        View focus = host.a.getCurrentFocus();
        if (focus == null || !host.usesLibraryWall()) return false;
        if (focus == host.source
                && key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                && filterPillShown()) {
            host.filterToggle.requestFocus();
            return true;
        }
        if (focus == host.filterToggle
                && key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                && panelExpanded) {
            View first = firstPanelChip();
            if (first != null) {
                first.requestFocus();
                return true;
            }
        }
        return false;
    }

    /**
     * Panel focus order, generated in one pass from the row order: left/right inside a row,
     * up/down between rows by nearest chip, first row up to the 筛选 pill, last row down to the
     * grid. Called on every {@code wireNavigation}, including after a chip press, so the order
     * always matches the current panel.
     */
    void wirePanelFocus(int gridDownId) {
        if (panelRows.isEmpty()) return;
        int aboveId =
                filterPillShown()
                        ? host.filterToggle.getId()
                        : host.source == null ? View.NO_ID : host.source.getId();
        for (int r = 0; r < panelRows.size(); r++) {
            List<View> chips = panelRows.get(r);
            if (chips.isEmpty()) continue;
            host.horizontal(chips);
            for (View chip : chips) {
                chip.setNextFocusUpId(
                        r == 0 ? aboveId : nearest(panelRows.get(r - 1), chip).getId());
                chip.setNextFocusDownId(
                        r + 1 < panelRows.size()
                                ? nearest(panelRows.get(r + 1), chip).getId()
                                : gridDownId != -1 ? gridDownId : chip.getId());
            }
        }
    }

    /**
     * Id of the last panel row's chip closest to {@code from}, or {@link View#NO_ID} when the panel
     * is closed. Used by the screen for 查看前面的影片 / 加载更多 links above the grid.
     */
    int lastPanelRowNearestId(View from) {
        if (!panelExpanded || panelRows.isEmpty() || from == null) return View.NO_ID;
        return nearest(panelRows.get(panelRows.size() - 1), from).getId();
    }

    /** First focusable panel chip (row order, then left to right), or null while unbuilt. */
    View firstPanelChip() {
        for (List<View> row : panelRows) {
            if (!row.isEmpty()) return row.get(0);
        }
        return null;
    }

    private void applyPanelVisibility() {
        if (panel == null) return;
        boolean show =
                panelExpanded
                        && !panelRows.isEmpty()
                        && host.usesLibraryWall();
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateFilterPillLabel() {
        if (host.filterToggle == null) return;
        host.filterToggle.setText(panelExpanded ? "筛选  ⌃" : "筛选  ⌄");
        host.filterToggle.setContentDescription(
                panelExpanded ? "收起年份、地区、类别筛选" : "展开年份、地区、类别筛选");
    }

    private void revealFocusSoon() {
        if (!(host.a.scroll instanceof TvScrollView)) return;
        host.a.scroll.post(
                () -> {
                    if (!host.active()) return;
                    View focused = host.a.getCurrentFocus();
                    if (focused != null) ((TvScrollView) host.a.scroll).reveal(focused);
                });
    }

    /**
     * The panel tree is built here and only here. Called from {@link #bindModel} when the wall
     * result changed — never from a chip press.
     */
    private void buildPanel(LibraryModel model) {
        if (panel == null) return;
        Chip focusedBefore = focusedPanelChip();
        panel.removeAllViews();
        panelRows.clear();
        panelChips.clear();
        panelBuilt = false;
        if (model == null || model.mode != LibraryModel.Mode.WALL) {
            applyPanelVisibility();
            return;
        }
        addSourceRow(model);
        addOptionRow();
        addFacetRow(
                "年份",
                model.facets == null ? null : model.facets.years(),
                model.facets == null ? null : model.facets.yearCounts,
                AXIS_YEAR);
        addFacetRow(
                "地区",
                model.facets == null ? null : model.facets.areas(),
                model.facets == null ? null : model.facets.areaCounts,
                AXIS_AREA);
        addFacetRow(
                "类别",
                model.facets == null ? null : model.facets.genres(),
                model.facets == null ? null : model.facets.genreCounts,
                AXIS_GENRE);
        panelBuilt = true;
        applyPanelVisibility();
        // A rebuilt panel drops focus (the tree above was detached). Put it back on the same
        // axis/value, not on the panel's first item; only a value that vanished falls back.
        if (panelExpanded && focusedBefore != null) {
            Chip match = findChip(focusedBefore.axis, focusedBefore.value);
            View target = match != null ? match.view : firstPanelChip();
            if (target != null && target.isAttachedToWindow()) target.requestFocus();
        }
    }

    /** The panel chip that currently holds focus, or null when focus is elsewhere. */
    private Chip focusedPanelChip() {
        for (Chip chip : panelChips) {
            if (chip != null && chip.view != null && chip.view.hasFocus()) return chip;
        }
        return null;
    }

    /** The live chip for an axis/value pair, or null when the value is no longer present. */
    private Chip findChip(int axis, String value) {
        String wanted = value == null ? "" : value;
        for (Chip chip : panelChips) {
            if (chip != null && chip.axis == axis && wanted.equals(chip.value)) return chip;
        }
        return null;
    }

    /** Row 1: 合并墙 (no source restriction) then every aggregation target from the model. */
    private void addSourceRow(LibraryModel model) {
        LinearLayout row = panelRow();
        addChip(
                row,
                "合并墙",
                "wall-facet:source:all",
                AXIS_SOURCE,
                "",
                () -> {
                    if (host.wallSourceFilter == null || host.wallSourceFilter.isEmpty()) return;
                    host.wallSourceFilter = "";
                    host.applyWallFilterChange();
                });
        for (Cms.Site site : model.sources) {
            if (site == null || site.key == null || site.key.trim().isEmpty()) continue;
            final String key = site.key;
            String label =
                    site.name == null || site.name.trim().isEmpty() ? site.key : site.name;
            addChip(
                    row,
                    label,
                    "wall-facet:source:" + key,
                    AXIS_SOURCE,
                    key,
                    () -> {
                        if (key.equals(host.wallSourceFilter)) return;
                        host.wallSourceFilter = key;
                        host.applyWallFilterChange();
                    });
        }
        addPanelRow(row);
    }

    /** Row 2: the wall-local switches, same three the old panel offered. */
    private void addOptionRow() {
        LinearLayout row = panelRow();
        addChip(
                row,
                "只看网盘",
                "wall-facet:netdisk",
                AXIS_NETDISK,
                "",
                () -> {
                    host.wallNetdiskOnly = !host.wallNetdiskOnly;
                    host.applyWallFilterChange();
                });
        addChip(
                row,
                "多源合并",
                "wall-facet:multi",
                AXIS_MULTI,
                "",
                () -> {
                    host.wallMultiOnly = !host.wallMultiOnly;
                    host.applyWallFilterChange();
                });
        addChip(
                row,
                "按名称",
                "wall-facet:sort",
                AXIS_SORT,
                "",
                () -> {
                    host.wallSortByName = !host.wallSortByName;
                    host.applyWallFilterChange();
                });
        addPanelRow(row);
    }

    /** Rows 3-5: one taxonomy row: 全部 + up to ten values from the model facets, with counts. */
    private void addFacetRow(
            String label, List<String> values, Map<String, Integer> counts, int axis) {
        LinearLayout row = panelRow();
        TextView head = host.a.text(label, 12f * host.navScale, MainActivity.MUTED);
        head.setPadding(0, 0, host.a.dp(8), 0);
        row.addView(head);
        addChip(
                row,
                "全部",
                "wall-facet:" + label + ":all",
                axis,
                "",
                () -> {
                    if (facetValue(axis).isEmpty()) return;
                    setFacet(axis, "");
                    host.applyWallFilterChange();
                });
        if (values != null) {
            int shown = 0;
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) continue;
                if (shown++ >= MAX_FACET_VALUES) break;
                Integer count = counts == null ? null : counts.get(value);
                final String picked = value;
                final String text = count == null ? value : value + " · " + count;
                addChip(
                        row,
                        text,
                        "wall-facet:" + label + ":" + value,
                        axis,
                        value,
                        () -> {
                            // Second press on the selected value clears it (old panel behavior).
                            setFacet(axis, picked.equals(facetValue(axis)) ? "" : picked);
                            host.applyWallFilterChange();
                        });
            }
        }
        addPanelRow(row);
    }

    private LinearLayout panelRow() {
        LinearLayout row = host.a.row();
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    private void addPanelRow(LinearLayout row) {
        List<View> chips = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof TextView && row.getChildAt(i).isFocusable()) {
                chips.add(row.getChildAt(i));
            }
        }
        panelRows.add(chips);
        // A row can hold 合并墙 + every site, or 全部 + ten values with counts, which is wider than
        // the 960dp TV canvas. Scroll the row instead of clipping chips: a clipped chip can still
        // take focus (invisible focus), which is worse than a slightly narrow viewport.
        android.widget.HorizontalScrollView track = new android.widget.HorizontalScrollView(host.a);
        track.setHorizontalScrollBarEnabled(false);
        track.setOverScrollMode(View.OVER_SCROLL_NEVER);
        track.setFocusable(false);
        track.setClipChildren(false);
        track.setClipToPadding(false);
        track.addView(row, new android.widget.HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (panel.getChildCount() > 0) lp.topMargin = host.a.dp(6);
        panel.addView(track, lp);
    }

    private TextView addChip(
            LinearLayout row,
            String label,
            String tag,
            int axis,
            String value,
            Runnable action) {
        final TextView chip = host.pill(label, tag, () -> {});
        chip.setPadding(host.a.dp(13), 0, host.a.dp(13), 0);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-2, host.a.dp(Math.round(32 * host.navScale)));
        lp.rightMargin = host.a.dp(8);
        row.addView(chip, lp);
        final Chip spec = new Chip(chip, axis, value);
        panelChips.add(spec);
        // The press only changes filter state; the chip itself must keep focus while the grid is
        // re-queried underneath the panel.
        chip.setOnClickListener(
                v -> {
                    action.run();
                    holdFocusAfterPress(chip);
                });
        // Replaces the generic pill listener: the selected look has to survive focus changes.
        chip.setOnFocusChangeListener((v, focused) -> styleChip(spec, focused));
        styleChip(spec, false);
        return chip;
    }

    /**
     * Safety net for a press: if focus was pulled onto the panel's first chip (the signature of a
     * control-tree rebuild) or lost entirely, put it back on the chip the user pressed. A
     * deliberate move to another chip or down into the grid is left alone.
     */
    private void holdFocusAfterPress(TextView chip) {
        if (chip == null) return;
        chip.postDelayed(
                () -> {
                    if (!host.active() || !panelExpanded || chip.getParent() == null) return;
                    if (chip.hasFocus() || !chip.isShown()) return;
                    View current = host.a.getCurrentFocus();
                    View first = firstPanelChip();
                    boolean stolen = current == null || (first != null && current == first);
                    if (!stolen) return;
                    chip.requestFocus();
                },
                120);
    }

    private void styleChip(Chip chip, boolean focused) {
        boolean selected = selected(chip);
        int idleFill = selected ? 0xff2f6bd8 : 0xff24242b;
        int focusFill = selected ? 0xff3b82f6 : 0xff344452;
        int idleText = selected ? MainActivity.WHITE : 0xffc8c8d0;
        FocusMotion.style(
                host.a,
                chip.view,
                focused,
                idleFill,
                focusFill,
                Math.round(18 * host.navScale),
                idleText,
                MainActivity.WHITE,
                true,
                1.02f);
        chip.view.setContentDescription(
                chip.view.getText() + (selected ? "，已选择" : "，按确认键应用"));
    }

    private boolean selected(Chip chip) {
        switch (chip.axis) {
            case AXIS_SOURCE:
                return chip.value.isEmpty()
                        ? host.wallSourceFilter == null || host.wallSourceFilter.isEmpty()
                        : chip.value.equals(host.wallSourceFilter);
            case AXIS_NETDISK:
                return host.wallNetdiskOnly;
            case AXIS_MULTI:
                return host.wallMultiOnly;
            case AXIS_SORT:
                return host.wallSortByName;
            case AXIS_YEAR:
                return chip.value.isEmpty()
                        ? host.wallYear.isEmpty()
                        : chip.value.equals(host.wallYear);
            case AXIS_AREA:
                return chip.value.isEmpty()
                        ? host.wallArea.isEmpty()
                        : chip.value.equals(host.wallArea);
            case AXIS_GENRE:
                return chip.value.isEmpty()
                        ? host.wallGenre.isEmpty()
                        : chip.value.equals(host.wallGenre);
            default:
                return false;
        }
    }

    private String facetValue(int axis) {
        switch (axis) {
            case AXIS_YEAR:
                return host.wallYear;
            case AXIS_AREA:
                return host.wallArea;
            case AXIS_GENRE:
                return host.wallGenre;
            default:
                return "";
        }
    }

    private void setFacet(int axis, String value) {
        String next = value == null ? "" : value;
        switch (axis) {
            case AXIS_YEAR:
                host.wallYear = next;
                break;
            case AXIS_AREA:
                host.wallArea = next;
                break;
            case AXIS_GENRE:
                host.wallGenre = next;
                break;
            default:
                break;
        }
    }

    /** Sources + facet value/count fingerprint; equal means the built panel still matches. */
    private String panelSignature(LibraryModel model) {
        if (model == null) return "none";
        StringBuilder out = new StringBuilder(model.mode == null ? "" : model.mode.name());
        out.append('|');
        for (Cms.Site site : model.sources) {
            if (site == null) continue;
            out.append(site.key).append(',');
        }
        out.append('|');
        appendFacetSignature(out, model.facets == null ? null : model.facets.years(),
                model.facets == null ? null : model.facets.yearCounts);
        out.append('|');
        appendFacetSignature(out, model.facets == null ? null : model.facets.areas(),
                model.facets == null ? null : model.facets.areaCounts);
        out.append('|');
        appendFacetSignature(out, model.facets == null ? null : model.facets.genres(),
                model.facets == null ? null : model.facets.genreCounts);
        return out.toString();
    }

    private void appendFacetSignature(
            StringBuilder out, List<String> values, Map<String, Integer> counts) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            Integer count = counts == null ? null : counts.get(value);
            out.append(value).append('=').append(count == null ? 1 : count).append(',');
        }
    }

    /** Screen-space nearest chip, same rule the single-site panel uses. */
    private View nearest(List<View> line, View from) {
        if (line.isEmpty()) return from;
        int[] location = new int[2];
        from.getLocationOnScreen(location);
        float center = location[0] + from.getWidth() / 2f;
        View best = line.get(0);
        float distance = Float.MAX_VALUE;
        Rect visible = new Rect();
        for (View option : line) {
            if (!option.getGlobalVisibleRect(visible) || visible.width() < option.getWidth() / 2)
                continue;
            float next = Math.abs(visible.exactCenterX() - center);
            if (next < distance) {
                best = option;
                distance = next;
            }
        }
        return best;
    }

    /**
     * Full wall rebuild from the model; replaces the direct {@code drawGrid(false)} call that
     * {@code HomeScreen.applyLibraryWall} used to make.
     *
     * <p>The rebuild still runs through {@link HomeScreen#drawGrid(boolean)}: that method owns the
     * parts kept in the host (status text, 加载更多 visibility, focus restore, window reset). The
     * renderer takes ownership of the grid tree itself through {@link #clear()} and {@link
     * #appendCards(int, int, boolean)}.
     *
     * <p>The facet panel is bound before the grid is touched: {@link #bindModel} rebuilds it only
     * when the wall <em>result</em> changed ({@code wallResult} identity), so a filter press (which
     * lands here too) leaves the panel — and the focused chip — attached.
     */
    void renderWall(LibraryModel model, Object wallResult) {
        bindModel(model, wallResult);
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
        // One ledger read: which site this card opens on, and that site's own listing. The card's
        // video object belongs to whichever site the wall sampled first, so opening its id against
        // another site would fail — the default site must be opened with its own listing.
        SourceLedger.Sources resolved = SourceLedger.resolve(host.a, video);
        Cms.Site cardSite = resolved.defaultSite != null ? resolved.defaultSite : host.site;
        Cms.Video cardVideo = resolved.defaultListing != null ? resolved.defaultListing : video;
        boolean showDoubanBadge =
                !SourceController.isConfigOrNetdiskActionCard(cardSite, cardVideo);
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
        SourceController.prefetchVisible(host.a, cardSite, cardVideo);
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
        // Resolve again on the press: the ledger grows while the card sits there (focus expansion,
        // search, detail matching), and the default source must be the one the ledger holds now —
        // not the one captured when this view was built. Otherwise the list says 虎斑｜网盘 first
        // and the page still opens 王子｜APP.
        card.setOnClickListener(
                v -> {
                    SourceLedger.Sources now = SourceLedger.resolve(host.a, video);
                    Cms.Site target = now.defaultSite != null ? now.defaultSite : cardSite;
                    Cms.Video listing = now.defaultListing != null ? now.defaultListing : cardVideo;
                    host.a.openListing(target, listing);
                });
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
     * the grid — 更早 / facet panel / 筛选 pill / 分类标签), DOWN walks to the row below (the last
     * row falls to 加载更多 when it is shown), LEFT/RIGHT stay inside the row.
     *
     * <p>The single-site "what sits above the grid" flags are passed in rather than re-derived
     * here: the caller already resolved them for the rest of {@code wireNavigation}, and re-reading
     * {@code inlineFilters.expanded()} mid-wiring could disagree with the panel's own up-links. The
     * facet panel is different: the renderer owns it, so its expansion and chips are the same
     * objects the up-links were just written against.
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
                                ? aboveGridId(
                                        card,
                                        earlierShown,
                                        inlineExpanded,
                                        lastInlineRow,
                                        inlineSummary)
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

    /** One "what is directly above row 0 of the grid" answer, shared by every card of that row. */
    private int aboveGridId(
            View card,
            boolean earlierShown,
            boolean inlineExpanded,
            int lastInlineRow,
            boolean inlineSummary) {
        if (earlierShown) return host.earlier.getId();
        if (panelExpanded && !panelRows.isEmpty()) {
            return nearest(panelRows.get(panelRows.size() - 1), card).getId();
        }
        if (inlineExpanded) {
            return host.inlineFilters
                    .nearest(host.inlineFilters.rows.get(lastInlineRow), card)
                    .getId();
        }
        if (inlineSummary) return host.inlineFilters.expandButton.getId();
        if (filterPillShown()) return host.filterToggle.getId();
        return host.selectedTab().getId();
    }
}
