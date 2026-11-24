// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.marcomovie;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * TV「片源与订阅」管理页: imported links on top, their sites grouped by subscription underneath,
 * and a keyword filter for the site list.
 *
 * <p>This page only manages what already exists; importing stays in {@link SourcePicker} /
 * {@code MainActivity.chooseOrImport()} and is reached from the「添加订阅」button. The data comes
 * from the same stores the rest of the app uses:
 *
 * <ul>
 *   <li>Subscriptions are {@link ConfigLibrary.Entry} rows (saved in
 *       {@code <configDir>/subscriptions/index.json}); each entry's content is re-parsed with
 *       {@link Cms#parseConfig(String, String)}.
 *   <li>The live config is {@code MainActivity.config} and its origin is
 *       {@code MainActivity.configOrigin}.
 *   <li>A merged config's member order and origin URLs are read from
 *       {@link SubscriptionManager#readMultiSourceMeta(String)}; per-site provenance therefore
 *       follows the exact merge rule in {@link SubscriptionManager#mergeSources(List)}: the first
 *       bundle keeps the plain key, later duplicates become {@code key + "__" + hash6(origin)}.
 * </ul>
 *
 * <p>Persistence deliberately reuses the existing per-config profile instead of adding new
 * preference keys ({@link Prefs} has no site-enable key):
 *
 * <ul>
 *   <li>停用/启用 is the existing {@code overrides[siteId].searchable} flag written to
 *       {@code source-picker:<origin>} ({@link SourcePicker#applyEditsToConfig}), i.e. the same
 *       switch as「编辑线路 → 允许参与全站聚合搜索」. It affects aggregated search and backup
 *       line selection; it does not remove the site from the config.
 *   <li>删除 is the existing {@code removed[]} array in the same profile, so
 *       {@link SourcePicker#manage(MainActivity)} can still restore it.
 * </ul>
 *
 * <p>Known simplifications (kept intentionally small inside this one page):
 *
 * <ul>
 *   <li>No RecyclerView/virtualization (TV module has none): every group previews its first
 *       {@link #SITE_PREVIEW_LIMIT} sites and can be expanded; a keyword filter renders all matches
 *       but caps at {@link #SITE_FILTER_LIMIT} per group.
 *   <li>A merged collection's own site group is not repeated when every member subscription is also
 *       saved in the library; its sites are shown under those members. A collection whose members
 *       are missing from the library still gets its own group.
 *   <li>「立即更新」refreshes one plain subscription in place. Members of a merged collection and any
 *       subscription that is not a plain HTTP URL fall back to the app-wide scheduler
 *       ({@link SourceController#runDailyAutoUpdateIfNeeded(MainActivity, boolean)}) because
 *       rebuilding a collection is that scheduler's job.
 *   <li>The subscription that is currently active cannot be deleted (same rule as
 *       {@code SourcePicker.showDeleteSavedConfigDialog}), so the app always keeps a live config.
 *   <li>{@link Cms.Config} has no {@code lives}/{@code flags} fields in this fork; only sites,
 *       origin and update time are managed here.
 * </ul>
 */
final class SourceManagerScreen {

    /** Mirrors {@code SourcePicker.profile(String)}: the per-config edit profile key. */
    private static final String PROFILE_PREFIX = "source-picker:";

    /** Sites rendered per group before the user presses「显示全部」. */
    private static final int SITE_PREVIEW_LIMIT = 12;

    /** Hard cap for one group while a keyword filter is active. */
    private static final int SITE_FILTER_LIMIT = 80;

    private final MainActivity a;

    private int loadTicket;

    private LinearLayout subsHost;
    private LinearLayout sitesHost;
    private TextView summaryBadge;
    private TextView statusText;
    private TextView sitesSummary;
    private Button searchButton;
    private Button clearButton;
    private View firstAction;

    private String query = "";
    private final Set<String> expanded = new LinkedHashSet<String>();
    private String pendingFocusTag;
    private Data data;

    SourceManagerScreen(MainActivity activity) {
        this.a = activity;
    }

    void show() {
        a.renderPage("片源与订阅", "订阅、来源地址与站点管理");
        if (a.body == null) return;
        a.body.setPadding(a.dp(48), a.dp(14), a.dp(48), a.dp(44));

        renderControlStrip();
        a.gap(a.body, 6);
        renderStatusRow();

        a.gap(a.body, 12);
        a.body.addView(
                sectionHeader("订阅", "每条导入链接独立保留来源地址与更新时间"),
                new LinearLayout.LayoutParams(-1, -2));
        a.gap(a.body, 8);
        subsHost = a.vertical();
        subsHost.setClipChildren(false);
        subsHost.setClipToPadding(false);
        a.body.addView(subsHost, new LinearLayout.LayoutParams(-1, -2));

        a.gap(a.body, 18);
        a.body.addView(
                sectionHeader("搜索站点", "按站点名称或 key 过滤下方列表，不区分大小写"),
                new LinearLayout.LayoutParams(-1, -2));
        a.gap(a.body, 8);
        renderSearchBar();

        a.gap(a.body, 16);
        a.body.addView(
                sectionHeader("站点", "按订阅分组；停用只影响聚合搜索，删除才移出配置"),
                new LinearLayout.LayoutParams(-1, -2));
        a.gap(a.body, 6);
        sitesSummary = a.text("", 11.5f, TvV17Theme.MUTED);
        sitesSummary.setPadding(a.dp(2), 0, a.dp(2), a.dp(2));
        a.body.addView(sitesSummary, new LinearLayout.LayoutParams(-1, -2));
        a.gap(a.body, 4);
        sitesHost = a.vertical();
        sitesHost.setClipChildren(false);
        sitesHost.setClipToPadding(false);
        a.body.addView(sitesHost, new LinearLayout.LayoutParams(-1, -2));

        loadAsync();
    }

    // ------------------------------------------------------------------ header

    private void renderControlStrip() {
        LinearLayout strip = a.row();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setClipChildren(false);

        summaryBadge = a.text("正在读取订阅…", 12f, 0xccebebf5);
        summaryBadge.setTypeface(null, Typeface.BOLD);
        summaryBadge.setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
        summaryBadge.setBackground(
                TvV17Surface.rounded(0x22ffffff, a.dp(999), 0x2a818cf8, Math.max(1, a.dp(1))));
        strip.addView(summaryBadge, new LinearLayout.LayoutParams(-2, -2));

        strip.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));

        Button refreshAll = addPill(strip, "刷新全部订阅", "source-mgr:refresh-all", this::refreshAll, 118);
        addPill(strip, "添加订阅", "source-mgr:add", a::chooseOrImport, 96);
        a.body.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        a.tvInitialFocus = refreshAll;
    }

    private void renderStatusRow() {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusText = a.text("正在读取已导入的订阅…", 12f, TvV17Theme.MUTED);
        statusText.setLineSpacing(a.dp(2), 1f);
        row.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1f));
        Button reload = a.navButton("重新读取", "source-mgr:reload", this::reload);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(96), a.dp(34));
        lp.rightMargin = a.dp(2);
        row.addView(reload, lp);
        a.body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderSearchBar() {
        LinearLayout box = a.row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(a.dp(12), a.dp(8), a.dp(8), a.dp(8));
        box.setBackground(
                TvV17Surface.rounded(0x18ffffff, a.dp(14), 0x26ffffff, Math.max(1, a.dp(1))));

        TextView label = a.text("关键字", 12.5f, TvV17Theme.MUTED);
        label.setPadding(0, 0, a.dp(10), 0);
        box.addView(label, new LinearLayout.LayoutParams(-2, -2));

        searchButton =
                a.navButton(
                        "全部站点 · 点此输入关键字", "source-mgr:search", this::askQuery);
        searchButton.setTextSize(12.5f);
        searchButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        searchButton.setPadding(a.dp(14), 0, a.dp(14), 0);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, a.dp(38), 1f);
        searchLp.rightMargin = a.dp(8);
        box.addView(searchButton, searchLp);

        clearButton = a.navButton("清除过滤", "source-mgr:search-clear", this::clearQuery);
        clearButton.setVisibility(View.GONE);
        box.addView(clearButton, new LinearLayout.LayoutParams(a.dp(96), a.dp(38)));
        a.body.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout sectionHeader(String title, String hint) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = a.text(title, 19f, TvV17Theme.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(-2, -2));
        TextView detail = a.text(hint, 11.5f, TvV17Theme.MUTED);
        detail.setPadding(a.dp(10), a.dp(3), 0, 0);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    // ------------------------------------------------------------------ data

    private void loadAsync() {
        final int ticket = ++loadTicket;
        final int gen = a.generation;
        if (statusText != null) statusText.setText("正在读取已导入的订阅…");

        // Snapshot the live config on the UI thread: a background refresh may replace it.
        final List<Cms.Site> activeSites;
        if (a.config == null) {
            activeSites = Collections.emptyList();
        } else {
            activeSites = new ArrayList<Cms.Site>(a.config.sites);
        }
        final String activeOrigin = a.configOrigin == null ? "" : a.configOrigin;
        final String activeRaw =
                a.config == null || a.config.raw == null ? "" : a.config.raw;
        final Cms.Config activeConfig = a.config;
        final boolean hasConfig = !activeSites.isEmpty();

        a.worker.execute(
                () -> {
                    final Data loaded =
                            readData(activeSites, activeConfig, activeOrigin, activeRaw, hasConfig);
                    a.ui.post(
                            () -> {
                                if (ticket != loadTicket
                                        || gen != a.generation
                                        || a.isFinishing()
                                        || a.isDestroyed()) return;
                                data = loaded;
                                render();
                            });
                });
    }

    private Data readData(
            List<Cms.Site> activeSites,
            Cms.Config activeConfig,
            String activeOrigin,
            String activeRaw,
            boolean hasConfig) {
        Data d = new Data();
        d.hasConfig = hasConfig;

        List<SubscriptionManager.MultiSourceMeta> meta;
        try {
            meta = SubscriptionManager.readMultiSourceMeta(activeRaw);
        } catch (Throwable ignored) {
            meta = Collections.emptyList();
        }

        if (a.configStore == null) {
            d.error = "配置目录不可用";
            return d;
        }

        String activeId = "";
        try {
            activeId = ConfigLibrary.identity(activeOrigin, activeRaw);
        } catch (Exception ignored) {
        }

        try {
            ConfigLibrary library = new ConfigLibrary(a.configStore.directory);
            // Same safety net as SourcePicker.showSavedConfigs: an already running config that was
            // never indexed still shows up here instead of an empty list.
            if (activeConfig != null
                    && activeConfig.raw != null
                    && !activeConfig.raw.isEmpty()) {
                try {
                    library.rememberIfAbsent(
                            activeConfig.raw,
                            activeOrigin,
                            SourceController.configName(activeConfig),
                            a.prefs.getString(Prefs.SELECTED_SITE, ""));
                } catch (Exception ignored) {
                }
            }
            List<ConfigLibrary.Entry> entries = library.entries();
            for (ConfigLibrary.Entry entry : entries) {
                Sub sub = new Sub(entry);
                sub.active = entry.id.equals(activeId);
                try {
                    String raw = library.raw(entry);
                    Cms.Config parsed = Cms.parseConfig(raw, entry.origin);
                    for (Cms.Site site : parsed.sites) {
                        if (site.siteId() != null && !site.siteId().isEmpty()) {
                            sub.rawNames.put(site.siteId(), site.name);
                        }
                    }
                    JSONObject profile = readProfile(entry.origin);
                    JSONArray removed = profile.optJSONArray("removed");
                    if (removed != null) {
                        for (int i = 0; i < removed.length(); i++) {
                            String id = removed.optString(i);
                            if (id != null && !id.isEmpty()) sub.removedIds.add(id);
                        }
                    }
                    // Same per-config edits the app applies before using this subscription.
                    SourcePicker.applyEditsToConfig(parsed, profile);
                    sub.sites.addAll(parsed.sites);
                    if (SubscriptionManager.isMergedOrigin(entry.origin)) {
                        sub.members = SubscriptionManager.readMultiSourceMeta(raw);
                    }
                    sub.merged =
                            SubscriptionManager.isMergedOrigin(entry.origin)
                                    && sub.members.size() >= 2;
                } catch (Throwable err) {
                    sub.error =
                            err.getMessage() == null ? "订阅内容读取失败" : err.getMessage();
                }
                d.subs.add(sub);
            }

            Set<String> memberUrls = new LinkedHashSet<String>();
            for (SubscriptionManager.MultiSourceMeta m : meta) {
                if (m != null && m.url != null && !m.url.isEmpty()) memberUrls.add(m.url);
            }
            for (Sub sub : d.subs) {
                sub.activeMember =
                        !sub.active
                                && sub.entry.origin != null
                                && memberUrls.contains(sub.entry.origin);
                if (sub.merged && !sub.members.isEmpty()) {
                    boolean everyMemberSaved = true;
                    for (SubscriptionManager.MultiSourceMeta m : sub.members) {
                        if (m.url == null || m.url.isEmpty()) {
                            everyMemberSaved = false;
                            break;
                        }
                        boolean saved = false;
                        for (Sub other : d.subs) {
                            if (other != sub
                                    && other.entry.origin != null
                                    && other.entry.origin.equals(m.url)) {
                                saved = true;
                                break;
                            }
                        }
                        if (!saved) {
                            everyMemberSaved = false;
                            break;
                        }
                    }
                    // Avoid rendering every site twice: the members' own groups already show them.
                    sub.hideFromSites = everyMemberSaved;
                }
            }
            attributeActiveSites(d, activeSites, meta);
        } catch (Throwable err) {
            d.error = err.getMessage() == null ? "无法读取订阅列表" : err.getMessage();
        }
        return d;
    }

    /**
     * Maps every subscription's sites to the live {@link Cms.Site} objects they became. The merge
     * order and the {@code key + "__" + hash6(origin)} suffix rule mirror
     * {@link SubscriptionManager#mergeSources(List)} (see its {@code usedSiteKeys} loop), so a site
     * row can show whether it is currently running and act on the live object.
     */
    private void attributeActiveSites(
            Data d,
            List<Cms.Site> activeSites,
            List<SubscriptionManager.MultiSourceMeta> meta) {
        Map<String, Cms.Site> byKey = new LinkedHashMap<String, Cms.Site>();
        for (Cms.Site site : activeSites) {
            if (site != null && site.key != null) byKey.put(site.key, site);
        }
        Set<String> claimed = new LinkedHashSet<String>();

        // 0. The saved entry that produced the live config maps 1:1 by key. A merged collection
        //    owns exact copies of every live key, so it must not compete with its members here.
        for (Sub sub : d.subs) {
            if (!sub.active || sub.rowsBuilt) continue;
            for (Cms.Site site : sub.sites) {
                SiteRow row = new SiteRow(site);
                row.active = byKey.get(site.key);
                sub.rows.add(row);
            }
            sub.rowsBuilt = true;
        }
        // 1. Merged members in the order stored in _marco_multi_sources; the first member keeps
        //    the plain key, later duplicates own the suffixed key.
        for (SubscriptionManager.MultiSourceMeta m : meta) {
            Sub sub = findByOrigin(d.subs, m.url);
            if (sub == null || sub.rowsBuilt) continue;
            claimSites(sub, byKey, claimed);
        }
        // 2. Everything else is saved but not running right now.
        for (Sub sub : d.subs) {
            if (!sub.rowsBuilt) {
                for (Cms.Site site : sub.sites) sub.rows.add(new SiteRow(site));
            }
        }
    }

    private void claimSites(Sub sub, Map<String, Cms.Site> byKey, Set<String> claimed) {
        String hash = hash6(sub.entry.origin);
        for (Cms.Site site : sub.sites) {
            SiteRow row = new SiteRow(site);
            Cms.Site direct = byKey.get(site.key);
            if (direct != null && claimed.add(site.key)) {
                row.active = direct;
            } else if (hash != null) {
                String suffixed = site.key + "__" + hash;
                Cms.Site match = byKey.get(suffixed);
                if (match != null && claimed.add(suffixed)) row.active = match;
            }
            sub.rows.add(row);
        }
        sub.rowsBuilt = true;
    }

    private static Sub findByOrigin(List<Sub> subs, String origin) {
        if (origin == null || origin.isEmpty()) return null;
        for (Sub sub : subs) {
            if (sub.entry.origin != null && origin.equals(sub.entry.origin)) return sub;
        }
        return null;
    }

    private static String hash6(String origin) {
        if (origin == null || origin.isEmpty()) return null;
        try {
            String hash = ConfigLibrary.hash(origin);
            return hash.length() >= 6 ? hash.substring(0, 6) : hash;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        renderSubscriptions();
        renderSites();
        updateSummary();
        restoreFocusLater();
    }

    private void updateSummary() {
        if (data == null) return;
        int subs = data.subs.size();
        int sites = 0;
        int disabled = 0;
        for (Sub sub : data.subs) {
            if (sub.hideFromSites) continue;
            sites += sub.rows.size();
            for (SiteRow row : sub.rows) if (!row.searchable()) disabled++;
        }
        if (summaryBadge != null) {
            summaryBadge.setText(subs + " 条订阅 · " + sites + " 个站点");
        }
        if (statusText != null) {
            if (data.error != null && !data.error.isEmpty()) {
                statusText.setText("读取订阅时出错：" + data.error);
            } else if (disabled > 0) {
                statusText.setText(
                        "已停用 "
                                + disabled
                                + " 个站点（只影响聚合搜索）；刷新订阅后仍会保留。删除的站点可在对应分组里恢复。");
            } else {
                statusText.setText(
                        "停用只影响聚合搜索，与「编辑线路」里的可搜索开关一致；删除的站点可在对应分组里恢复。");
            }
        }
    }

    private void renderSubscriptions() {
        if (subsHost == null) return;
        subsHost.removeAllViews();
        firstAction = null;
        if (data == null) return;
        if (data.subs.isEmpty()) {
            View empty =
                    buildEmptyCard(
                            "还没有导入订阅源",
                            "点右上角「添加订阅」导入地址；也可以回到首页，从片源入口导入。",
                            "添加订阅",
                            "source-mgr:empty-add",
                            a::chooseOrImport);
            subsHost.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (Sub sub : data.subs) {
            View card = buildSubCard(sub);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            subsHost.addView(card, lp);
        }
    }

    private View buildSubCard(final Sub sub) {
        LinearLayout card = a.row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipChildren(false);
        card.setPadding(a.dp(16), a.dp(11), a.dp(10), a.dp(11));
        card.setBackground(
                TvV17Surface.rounded(
                        sub.active ? 0x241f3a5f : 0x14ffffff,
                        a.dp(14),
                        sub.active ? 0x554b8bf0 : 0x1fffffff,
                        Math.max(1, a.dp(1))));

        LinearLayout info = a.vertical();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = a.text(displayName(sub), 16f, TvV17Theme.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (sub.active) titleRow.addView(statusPill("正在使用", 0xffa7f3d0, 0x3334d399), pillLp());
        if (sub.merged) titleRow.addView(statusPill("合集", 0xffbfdbfe, 0x333b82f6), pillLp());
        if (!sub.error.isEmpty()) {
            titleRow.addView(statusPill("读取失败", 0xfffecaca, 0x55ef4444), pillLp());
        } else if (!sub.rows.isEmpty() && sub.isDisabled()) {
            titleRow.addView(statusPill("已停用", 0xfffcd34d, 0x33f59e0b), pillLp());
        }
        info.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        TextView source = a.text(sourceLine(sub), 12f, 0xb8ebebf5);
        source.setSingleLine(true);
        source.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        source.setPadding(a.dp(1), a.dp(4), a.dp(1), 0);
        info.addView(source, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = a.text(metaLine(sub), 11.5f, TvV17Theme.MUTED);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(a.dp(1), a.dp(3), a.dp(1), 0);
        if (!sub.error.isEmpty()) meta.setTextColor(0xffffb4ad);
        info.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        card.addView(info);

        LinearLayout actions = a.row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button update =
                addPill(
                        actions,
                        "立即更新",
                        "source-mgr:sub:" + sub.entry.id + ":update",
                        () -> onRefreshSub(sub),
                        104);
        if (firstAction == null) firstAction = update;
        if (!sub.rows.isEmpty()) {
            String toggleLabel;
            if (sub.merged) {
                toggleLabel = sub.isDisabled() ? "全部启用" : "全部停用";
            } else {
                toggleLabel = sub.isDisabled() ? "启用" : "停用";
            }
            addPill(
                    actions,
                    toggleLabel,
                    "source-mgr:sub:" + sub.entry.id + ":toggle",
                    () -> onToggleSub(sub),
                    sub.merged ? 96 : 78);
        }
        addPill(
                actions,
                "删除",
                "source-mgr:sub:" + sub.entry.id + ":delete",
                () -> onDeleteSub(sub),
                72);
        card.addView(actions);
        return card;
    }

    private void renderSites() {
        if (sitesHost == null) return;
        sitesHost.removeAllViews();
        if (data == null) return;

        int total = 0;
        int shown = 0;
        boolean any = false;
        for (Sub sub : data.subs) {
            if (sub.hideFromSites || sub.rows.isEmpty()) continue;
            total += sub.rows.size();
            List<SiteRow> matches = new ArrayList<SiteRow>();
            for (SiteRow row : sub.rows) {
                if (matchesQuery(row)) matches.add(row);
            }
            if (matches.isEmpty()) continue;
            any = true;

            boolean filterActive = !query.isEmpty();
            boolean expandedNow = filterActive || expanded.contains(sub.entry.id);
            int limit = expandedNow ? matches.size() : Math.min(matches.size(), SITE_PREVIEW_LIMIT);
            if (filterActive && limit > SITE_FILTER_LIMIT) limit = SITE_FILTER_LIMIT;
            boolean collapsible = !filterActive && matches.size() > SITE_PREVIEW_LIMIT;

            sitesHost.addView(
                    buildGroupHeader(sub, matches.size(), expandedNow, collapsible),
                    new LinearLayout.LayoutParams(-1, -2));
            for (int i = 0; i < limit; i++) {
                View rowView = buildSiteRow(sub, matches.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = a.dp(5);
                sitesHost.addView(rowView, lp);
            }
            if (limit < matches.size()) {
                String moreText =
                        filterActive
                                ? "还有 "
                                        + (matches.size() - limit)
                                        + " 条匹配，请用更精确的关键字缩小范围"
                                : "还有 "
                                        + (matches.size() - limit)
                                        + " 条，点本组标题的「显示全部」展开";
                TextView more = a.text(moreText, 11.5f, TvV17Theme.MUTED);
                more.setPadding(a.dp(14), a.dp(2), a.dp(2), a.dp(6));
                sitesHost.addView(more, new LinearLayout.LayoutParams(-1, -2));
            }
            shown += limit;
        }

        if (!any) {
            if (!query.isEmpty()) {
                View empty =
                        buildEmptyCard(
                                "没有匹配「" + query + "」的站点",
                                "换个关键字试试，或清除过滤查看全部站点。",
                                "清除过滤",
                                "source-mgr:empty-clear",
                                this::clearQuery);
                sitesHost.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            } else if (data.subs.isEmpty()) {
                sitesHost.addView(
                        buildEmptyCard(
                                "还没有站点",
                                "导入订阅后，站点会按订阅分组显示在这里。",
                                "添加订阅",
                                "source-mgr:empty-add-sites",
                                a::chooseOrImport),
                        new LinearLayout.LayoutParams(-1, -2));
            } else {
                sitesHost.addView(
                        buildEmptyCard(
                                data.hasConfig ? "当前没有可显示的站点" : "当前没有启用的片源配置",
                                data.hasConfig
                                        ? "这些订阅的站点可能已被删除或停用。可在上方分组里恢复已删除的站点。"
                                        : "回到首页导入一份片源配置，再回来管理站点。",
                                "回到首页",
                                "source-mgr:empty-home",
                                () -> a.open(new MainActivity.Screen(a::home), false)),
                        new LinearLayout.LayoutParams(-1, -2));
            }
        }

        if (sitesSummary != null) {
            if (data.subs.isEmpty()) {
                sitesSummary.setText("");
            } else if (!query.isEmpty()) {
                sitesSummary.setText(
                        "关键字「" + query + "」显示 " + shown + " 个站点（共 " + total + " 个）");
            } else {
                sitesSummary.setText(
                        "共 " + total + " 个站点，当前显示 " + shown + " 个（每组默认显示前 "
                                + SITE_PREVIEW_LIMIT + " 条）");
            }
        }
        if (searchButton != null) {
            searchButton.setText(
                    query.isEmpty() ? "全部站点 · 点此输入关键字" : "关键字：" + query);
        }
        if (clearButton != null) {
            clearButton.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private View buildGroupHeader(
            final Sub sub, int matched, boolean expandedNow, boolean collapsible) {
        LinearLayout head = a.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(a.dp(4), a.dp(12), a.dp(2), a.dp(6));

        View accent = new View(a);
        accent.setBackground(TvV17Surface.rounded(0xff60a5fa, a.dp(2), 0, 0));
        LinearLayout.LayoutParams accentLp =
                new LinearLayout.LayoutParams(Math.max(1, a.dp(3)), a.dp(18));
        accentLp.rightMargin = a.dp(9);
        head.addView(accent, accentLp);

        TextView title = a.text(displayName(sub), 14.5f, TvV17Theme.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        String countText =
                (matched == sub.rows.size() ? "" : matched + " / ") + sub.rows.size() + " 个站点";
        TextView count = a.text(countText, 11.5f, TvV17Theme.MUTED);
        count.setPadding(0, 0, a.dp(10), 0);
        head.addView(count, new LinearLayout.LayoutParams(-2, -2));

        if (!sub.removedIds.isEmpty()) {
            addPill(
                    head,
                    "恢复已删除（" + sub.removedIds.size() + "）",
                    "source-mgr:restore:" + sub.entry.id,
                    () -> onRestoreRemoved(sub),
                    150);
        }
        if (collapsible) {
            addPill(
                    head,
                    expandedNow ? "收起" : "显示全部",
                    "source-mgr:expand:" + sub.entry.id,
                    () -> toggleExpanded(sub),
                    expandedNow ? 64 : 84);
        }
        return head;
    }

    private View buildSiteRow(final Sub sub, final SiteRow row) {
        LinearLayout line = a.row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setClipChildren(false);
        line.setPadding(a.dp(14), a.dp(7), a.dp(10), a.dp(7));
        line.setBackground(
                TvV17Surface.rounded(0x12ffffff, a.dp(10), 0x1affffff, Math.max(1, a.dp(1))));

        LinearLayout info = a.vertical();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = a.text(row.site.name, 13.5f, TvV17Theme.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!row.searchable()) {
            titleRow.addView(statusPill("已停用", 0xfffcd34d, 0x33f59e0b), pillLp());
        } else if (row.active != null) {
            titleRow.addView(statusPill("正在使用", 0xffa7f3d0, 0x3334d399), pillLp());
        } else {
            titleRow.addView(statusPill("未使用", 0xffcbd5e1, 0x1fffffff), pillLp());
        }
        info.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout detailRow = a.row();
        detailRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView group =
                a.text("分组：" + SourcePicker.siteGroup(row.site), 11.5f, TvV17Theme.MUTED);
        group.setSingleLine(true);
        group.setEllipsize(TextUtils.TruncateAt.END);
        detailRow.addView(group, new LinearLayout.LayoutParams(-2, -2));
        TextView key = a.text("key：" + row.site.key, 11.5f, 0xff8fb8e8);
        key.setTypeface(Typeface.MONOSPACE);
        key.setSingleLine(true);
        key.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        keyLp.leftMargin = a.dp(12);
        detailRow.addView(key, keyLp);
        info.addView(detailRow, new LinearLayout.LayoutParams(-1, -2));
        line.addView(info);

        LinearLayout actions = a.row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addSmallPill(
                actions,
                row.searchable() ? "停用" : "启用",
                "source-mgr:site:" + sub.entry.id + ":" + row.site.siteId() + ":toggle",
                () -> onToggleSite(sub, row),
                76);
        addSmallPill(
                actions,
                "删除",
                "source-mgr:site:" + sub.entry.id + ":" + row.site.siteId() + ":delete",
                () -> onDeleteSite(sub, row),
                66);
        line.addView(actions);
        return line;
    }

    private View buildEmptyCard(
            String title,
            String detail,
            String actionLabel,
            String tag,
            final Runnable action) {
        LinearLayout box = a.vertical();
        box.setPadding(a.dp(24), a.dp(20), a.dp(24), a.dp(20));
        box.setBackground(
                TvV17Surface.rounded(0x14ffffff, a.dp(16), 0x22ffffff, Math.max(1, a.dp(1))));
        TextView heading = a.text(title, 16f, TvV17Theme.TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        box.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        a.gap(box, 6);
        TextView sub = a.text(detail, 12.5f, TvV17Theme.MUTED);
        sub.setLineSpacing(a.dp(3), 1f);
        box.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        a.gap(box, 12);
        LinearLayout actions = a.row();
        Button button = addPill(actions, actionLabel, tag, action, 108);
        box.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        if (firstAction == null) firstAction = button;
        return box;
    }

    private TextView statusPill(String text, int fg, int bg) {
        TextView pill = a.text(text, 10.5f, fg);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setSingleLine(true);
        pill.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        pill.setBackground(TvV17Surface.rounded(bg, a.dp(999), 0, 0));
        return pill;
    }

    private LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = a.dp(6);
        return lp;
    }

    private Button addPill(
            LinearLayout parent, String label, String tag, final Runnable action, int widthDp) {
        Button button = a.navButton(label, tag, action);
        button.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(widthDp), a.dp(36));
        lp.rightMargin = a.dp(8);
        parent.addView(button, lp);
        return button;
    }

    private Button addSmallPill(
            LinearLayout parent, String label, String tag, final Runnable action, int widthDp) {
        Button button = a.navButton(label, tag, action);
        button.setTextSize(12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(widthDp), a.dp(32));
        lp.rightMargin = a.dp(7);
        parent.addView(button, lp);
        return button;
    }

    // ------------------------------------------------------------------ actions

    private void refreshAll() {
        if (a.privateMode) {
            a.notice("隐私模式下不会联网刷新订阅源");
            return;
        }
        SourceController.runDailyAutoUpdateIfNeeded(a, true);
        if (statusText != null) {
            statusText.setText("刷新在后台进行；完成后点「重新读取」查看最新订阅与站点。");
        }
    }

    private void onRefreshSub(final Sub sub) {
        String origin = sub.entry.origin == null ? "" : sub.entry.origin.trim();
        boolean merged = SubscriptionManager.isMergedOrigin(origin);
        if (!merged && !Cms.isHttp(origin)) {
            a.notice("这条订阅没有保存可更新的在线地址（本地或脚本配置），如需更新请重新导入。");
            return;
        }
        if (merged || sub.activeMember) {
            // Rebuilding a collection belongs to the app-wide scheduler; one member cannot be
            // re-merged safely from here.
            a.toast("该订阅属于多源合集，正在刷新全部在线订阅源…");
            refreshAll();
            return;
        }
        if (a.privateMode) {
            a.notice("隐私模式下不会联网刷新订阅源");
            return;
        }
        if (a.configStore == null) {
            a.notice("配置目录不可用，暂时无法更新订阅");
            return;
        }
        a.toast("正在更新「" + displayName(sub) + "」…");
        a.worker.execute(
                () -> {
                    try {
                        String fetched = SourceController.fetchImport(a, origin);
                        final Cms.Config parsed = Cms.parseConfig(fetched, origin);
                        ConfigLibrary library = new ConfigLibrary(a.configStore.directory);
                        library.remember(
                                parsed.raw,
                                origin,
                                sub.entry.name,
                                sub.entry.selected,
                                System.currentTimeMillis());
                        CloudSyncDialog.onSubscriptionSaved(a, origin, parsed.raw);
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || a.isDestroyed()) return;
                                    if (!isActive(sub)) {
                                        a.toast("已更新「" + displayName(sub) + "」，切换到该订阅后生效");
                                        reload();
                                        return;
                                    }
                                    writeActiveRefreshed(sub, parsed);
                                });
                    } catch (Throwable err) {
                        final String message = SourceController.message(err);
                        a.ui.post(
                                () -> {
                                    if (!a.isFinishing()) {
                                        a.notice("更新「" + displayName(sub) + "」失败：" + message);
                                    }
                                });
                    }
                });
    }

    /** Persists a refreshed config that is currently live, then reloads this page. */
    private void writeActiveRefreshed(final Sub sub, final Cms.Config parsed) {
        a.worker.execute(
                () -> {
                    try {
                        a.configStore.write(parsed.raw);
                    } catch (Exception err) {
                        final String message = SourceController.message(err);
                        a.ui.post(
                                () -> {
                                    if (!a.isFinishing()) a.notice("保存更新失败：" + message);
                                });
                        return;
                    }
                    a.ui.post(
                            () -> {
                                if (a.isFinishing() || a.isDestroyed()) return;
                                SourcePicker.applyEdits(a, parsed, sub.entry.origin);
                                a.closeEngines();
                                a.config = parsed;
                                a.configOrigin = sub.entry.origin;
                                fixSelectedSite(parsed);
                                GlobalSearchScreen.invalidateContext(a);
                                a.toast("已更新「" + displayName(sub) + "」并重新载入");
                                reload();
                            });
                });
    }

    private void onToggleSub(final Sub sub) {
        if (sub.rows.isEmpty()) {
            a.notice("这条订阅没有可启停的站点");
            return;
        }
        final boolean enable = sub.isDisabled();
        final String currentOrigin = activeOrigin();
        final boolean sameProfile = currentOrigin.equals(sub.entry.origin);
        List<String> activeIds = new ArrayList<String>();
        List<String> ownIds = new ArrayList<String>();
        for (SiteRow row : sub.rows) {
            if (row.active != null && row.active.searchable != enable) {
                activeIds.add(row.active.siteId());
            }
            // With the same profile a mapped row is already covered by activeIds; only unmapped
            // rows need their own id written.
            if (row.site.searchable != enable && (!sameProfile || row.active == null)) {
                ownIds.add(row.site.siteId());
            }
        }
        setSearchable(currentOrigin, activeIds, enable);
        setSearchable(sub.entry.origin, ownIds, enable);
        for (SiteRow row : sub.rows) {
            if (row.active != null) row.active.searchable = enable;
            row.site.searchable = enable;
        }
        if (!activeIds.isEmpty() || (sameProfile && !ownIds.isEmpty())) reloadActiveConfig();
        String name = displayName(sub);
        boolean changed = !activeIds.isEmpty() || !ownIds.isEmpty();
        if (!changed) {
            a.toast("「" + name + "」的站点已经是" + (enable ? "启用" : "停用") + "状态");
        } else if (enable) {
            a.toast(
                    "已启用「" + name + "」" + (activeIds.isEmpty() ? "，切换该订阅后生效" : ""));
        } else {
            a.toast(
                    "已停用「" + name + "」"
                            + (activeIds.isEmpty() ? "，切换该订阅后生效" : "，不再参与聚合搜索"));
        }
        reload();
    }

    private void onDeleteSub(final Sub sub) {
        if (isActive(sub)) {
            a.notice("当前正在使用的订阅不能删除，请先切换到其他订阅源再删除。");
            return;
        }
        final boolean member = sub.activeMember;
        String message =
                member
                        ? "该订阅正被当前多源合集使用；删除后，下次刷新或合并时不再包含它的站点。"
                        : "删除后将从已添加源中移除，下次导入列表里不会自动恢复。";
        new AppDialog.Builder(a)
                .setTitle("删除订阅「" + displayName(sub) + "」？")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "删除",
                        (d, which) -> deleteSubConfirmed(sub))
                .show();
    }

    private void deleteSubConfirmed(final Sub sub) {
        a.worker.execute(
                () -> {
                    try {
                        ConfigLibrary library = new ConfigLibrary(a.configStore.directory);
                        library.remove(sub.entry.id);
                        CloudSyncDialog.onSubscriptionDeleted(a, sub.entry.id);
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || a.isDestroyed()) return;
                                    a.toast("已删除订阅：" + displayName(sub));
                                    reload();
                                });
                    } catch (Throwable err) {
                        final String message = SourceController.message(err);
                        a.ui.post(
                                () -> {
                                    if (!a.isFinishing()) a.notice("删除订阅失败：" + message);
                                });
                    }
                });
    }

    private void onToggleSite(final Sub sub, final SiteRow row) {
        final boolean enable = !row.searchable();
        final boolean live = row.active != null || sameProfileAsActive(sub);
        if (row.active != null) {
            setSearchable(
                    activeOrigin(), Collections.singletonList(row.active.siteId()), enable);
            row.active.searchable = enable;
        } else {
            setSearchable(
                    sub.entry.origin, Collections.singletonList(row.site.siteId()), enable);
        }
        row.site.searchable = enable;
        if (live) reloadActiveConfig();
        a.toast(
                (enable ? "已启用「" : "已停用「")
                        + row.site.name
                        + (enable ? "」" : "」，不再参与聚合搜索"));
        reload();
    }

    private void onDeleteSite(final Sub sub, final SiteRow row) {
        final boolean live = row.active != null || sameProfileAsActive(sub);
        String message =
                live
                        ? "删除后该站点不会在刷新订阅时重现；可在本组「恢复已删除」里找回。"
                        : "将从订阅「"
                                + displayName(sub)
                                + "」中移除，切换到该订阅后生效；可在本组「恢复已删除」里找回。";
        new AppDialog.Builder(a)
                .setTitle("删除站点「" + row.site.name + "」？")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, which) -> deleteSiteConfirmed(sub, row))
                .show();
    }

    private void deleteSiteConfirmed(Sub sub, SiteRow row) {
        if (row.active != null) {
            addRemoved(activeOrigin(), row.active.siteId());
            // Also remove it from the subscription's own profile so switching to the subscription
            // directly keeps the same deletion.
            if (!activeOrigin().equals(sub.entry.origin)) {
                addRemoved(sub.entry.origin, row.site.siteId());
            }
            reloadActiveConfig();
        } else {
            addRemoved(sub.entry.origin, row.site.siteId());
            if (sameProfileAsActive(sub)) reloadActiveConfig();
        }
        a.toast("已删除站点：" + row.site.name);
        reload();
    }

    private void onRestoreRemoved(final Sub sub) {
        final List<String> ids = new ArrayList<String>(sub.removedIds);
        if (ids.isEmpty()) return;
        String[] labels = new String[ids.size() + 1];
        labels[0] = "恢复全部（" + ids.size() + " 条）";
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String name = sub.rawNames.get(id);
            labels[i + 1] = "恢复 · " + (name == null || name.isEmpty() ? id : name);
        }
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("已删除的站点 · " + displayName(sub))
                .setItems(
                        labels,
                        (d, index) -> {
                            List<String> chosen = new ArrayList<String>();
                            if (index == 0) chosen.addAll(ids);
                            else chosen.add(ids.get(index - 1));
                            restoreRemoved(sub, chosen);
                            a.toast("已恢复 " + chosen.size() + " 个站点");
                            reload();
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void toggleExpanded(Sub sub) {
        String tag = "source-mgr:expand:" + sub.entry.id;
        if (!expanded.remove(sub.entry.id)) expanded.add(sub.entry.id);
        renderSites();
        restoreFocus(tag);
    }

    private void askQuery() {
        LinearLayout box = a.vertical();
        box.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(4));
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setText(query);
        input.setHint("输入站点名称或 key 的一部分");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff8e8e93);
        input.setTextSize(14f);
        input.setBackground(a.bg(0x33ffffff, 10));
        input.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        box.addView(
                TvQrInputHelper.wrap(a, input, "站点关键字"),
                new LinearLayout.LayoutParams(-1, -2));
        new AppDialog.Builder(a)
                .setTitle("按名称或 key 过滤站点")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "应用",
                        (d, which) -> {
                            String next = input.getText().toString().trim();
                            query = next.length() > 48 ? next.substring(0, 48) : next;
                            renderSites();
                            restoreFocus("source-mgr:search");
                        })
                .show();
    }

    private void clearQuery() {
        query = "";
        renderSites();
        restoreFocus("source-mgr:search");
    }

    private boolean matchesQuery(SiteRow row) {
        if (query.isEmpty()) return true;
        String needle = query.toLowerCase(Locale.ROOT);
        if (row.site.name != null && row.site.name.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (row.site.key != null && row.site.key.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return row.active != null
                && row.active.name != null
                && row.active.name.toLowerCase(Locale.ROOT).contains(needle);
    }

    // ------------------------------------------------------------------ persistence

    private String profileKey(String origin) {
        return PROFILE_PREFIX + (origin == null ? "" : origin);
    }

    private JSONObject readProfile(String origin) {
        try {
            return new JSONObject(a.prefs.getString(profileKey(origin), "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    /**
     * Writes the same {@code overrides[siteId].searchable} field {@link SourcePicker} writes, so
     * the two screens never disagree about a site's state.
     */
    private void setSearchable(String origin, List<String> siteIds, boolean searchable) {
        if (siteIds == null || siteIds.isEmpty()) return;
        JSONObject saved = readProfile(origin);
        try {
            JSONObject overrides = saved.optJSONObject("overrides");
            if (overrides == null) overrides = new JSONObject();
            for (String id : siteIds) {
                if (id == null || id.isEmpty()) continue;
                JSONObject override = overrides.optJSONObject(id);
                if (override == null) override = new JSONObject();
                override.put("searchable", searchable);
                overrides.put(id, override);
            }
            saved.put("overrides", overrides);
            a.prefs.edit().putString(profileKey(origin), saved.toString()).apply();
        } catch (JSONException err) {
            a.notice("无法保存启停状态");
        }
    }

    private void addRemoved(String origin, String siteId) {
        if (siteId == null || siteId.isEmpty()) return;
        JSONObject saved = readProfile(origin);
        try {
            Set<String> ids = new LinkedHashSet<String>();
            JSONArray removed = saved.optJSONArray("removed");
            if (removed != null) {
                for (int i = 0; i < removed.length(); i++) {
                    String id = removed.optString(i);
                    if (id != null && !id.isEmpty()) ids.add(id);
                }
            }
            ids.add(siteId);
            JSONArray next = new JSONArray();
            for (String id : ids) next.put(id);
            saved.put("removed", next);
            a.prefs.edit().putString(profileKey(origin), saved.toString()).apply();
        } catch (JSONException err) {
            a.notice("无法保存删除操作");
        }
    }

    /**
     * Restores removed ids for one subscription. When that subscription is also part of the live
     * config, the matching merged id ({@code key} or {@code key + "__" + hash6(origin)}) is restored
     * from the live profile too, so the live config comes back immediately.
     */
    private void restoreRemoved(Sub sub, List<String> ids) {
        JSONObject saved = readProfile(sub.entry.origin);
        try {
            Set<String> keep = new LinkedHashSet<String>();
            JSONArray removed = saved.optJSONArray("removed");
            if (removed != null) {
                for (int i = 0; i < removed.length(); i++) {
                    String id = removed.optString(i);
                    if (id != null && !id.isEmpty()) keep.add(id);
                }
            }
            for (String id : ids) keep.remove(id);
            JSONArray next = new JSONArray();
            for (String id : keep) next.put(id);
            saved.put("removed", next);
            a.prefs.edit().putString(profileKey(sub.entry.origin), saved.toString()).apply();
        } catch (JSONException err) {
            a.notice("无法恢复站点");
            return;
        }
        if (sub.active || sub.activeMember) {
            String activeOrigin = a.configOrigin == null ? "" : a.configOrigin;
            if (!activeOrigin.equals(sub.entry.origin)) {
                List<String> candidates = new ArrayList<String>();
                String hash = hash6(sub.entry.origin);
                for (String id : ids) {
                    candidates.add(id);
                    if (hash != null) candidates.add(id + "__" + hash);
                }
                restoreRemovedFrom(activeOrigin, candidates);
            }
            reloadActiveConfig();
        }
    }

    private void restoreRemovedFrom(String origin, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        JSONObject saved = readProfile(origin);
        try {
            JSONArray removed = saved.optJSONArray("removed");
            if (removed == null) return;
            Set<String> keep = new LinkedHashSet<String>();
            for (int i = 0; i < removed.length(); i++) {
                String id = removed.optString(i);
                if (id != null && !id.isEmpty()) keep.add(id);
            }
            int before = keep.size();
            for (String id : candidates) keep.remove(id);
            if (keep.size() == before) return;
            JSONArray next = new JSONArray();
            for (String id : keep) next.put(id);
            saved.put("removed", next);
            a.prefs.edit().putString(profileKey(origin), saved.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /**
     * Rebuilds the live config from its pristine raw plus the profile edits. Mirrors
     * {@code SourcePicker.reloadAndReopen} but stays on this page instead of navigating home.
     */
    private void reloadActiveConfig() {
        Cms.Config current = a.config;
        if (current == null || current.raw == null || current.raw.isEmpty()) return;
        try {
            Cms.Config fresh = Cms.parseConfig(current.raw, a.configOrigin);
            SourcePicker.applyEdits(a, fresh, a.configOrigin);
            a.closeEngines();
            a.config = fresh;
            fixSelectedSite(fresh);
            GlobalSearchScreen.invalidateContext(a);
        } catch (Exception err) {
            AppLog.w("SourceManager", "reload active config failed", err);
            a.notice("启停状态已保存，但当前片源配置重载失败：" + SourceController.message(err));
        }
    }

    /** Mirrors {@code SourcePicker.ensureValidSelectedSite} for the rebuilt config. */
    private void fixSelectedSite(Cms.Config config) {
        String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
        for (Cms.Site site : config.sites) {
            if (site.key.equals(selected) && SourceController.available(a, site)) return;
        }
        android.content.SharedPreferences.Editor editor =
                a.prefs.edit().remove(Prefs.SELECTED_SITE);
        for (Cms.Site site : config.sites) {
            if (SourceController.available(a, site)) {
                editor.putString(Prefs.SELECTED_SITE, site.key);
                break;
            }
        }
        editor.apply();
    }

    private boolean isActive(Sub sub) {
        if (sub == null || sub.entry == null) return false;
        if (a.config == null) return false;
        try {
            String activeId =
                    ConfigLibrary.identity(
                            a.configOrigin, a.config.raw == null ? "" : a.config.raw);
            return activeId.equals(sub.entry.id);
        } catch (Exception ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ small helpers

    private String activeOrigin() {
        return a.configOrigin == null ? "" : a.configOrigin;
    }

    /** True when the subscription's own profile is the profile the live config reads. */
    private boolean sameProfileAsActive(Sub sub) {
        return sub != null && sub.entry != null && activeOrigin().equals(sub.entry.origin);
    }

    private void reload() {
        String tag = focusTag();
        if (tag != null) pendingFocusTag = tag;
        loadAsync();
    }

    private String focusTag() {
        View focused = a.getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) {
            return (String) focused.getTag();
        }
        return null;
    }

    private void restoreFocus(String tag) {
        restoreFocusLater(tag);
    }

    private void restoreFocusLater() {
        String tag = pendingFocusTag;
        pendingFocusTag = null;
        restoreFocusLater(tag);
    }

    private void restoreFocusLater(final String tag) {
        if (a.root == null) return;
        a.root.post(
                () -> {
                    if (a.root == null) return;
                    View target = tag == null ? null : a.root.findViewWithTag(tag);
                    if (target != null && target.isShown() && target.isFocusable()) {
                        target.requestFocus();
                        return;
                    }
                    if (a.root.findFocus() == null
                            && firstAction != null
                            && firstAction.isAttachedToWindow()
                            && firstAction.isShown()) {
                        firstAction.requestFocus();
                    }
                });
    }

    private String displayName(Sub sub) {
        String name = sub.entry.name == null ? "" : sub.entry.name.trim();
        if (!name.isEmpty()) return name;
        if (sub.merged) return "多源合集";
        String origin = sub.entry.origin == null ? "" : sub.entry.origin.trim();
        return origin.isEmpty() ? "未命名订阅" : origin;
    }

    private String sourceLine(Sub sub) {
        String origin = sub.entry.origin == null ? "" : sub.entry.origin.trim();
        if (sub.merged) {
            List<String> names = new ArrayList<String>();
            for (SubscriptionManager.MultiSourceMeta m : sub.members) {
                if (m != null && m.name != null && !m.name.isEmpty()) names.add(m.name);
            }
            String joined = SubscriptionManager.mergedSubscriptionName(names);
            return "合集来源：" + (joined.isEmpty() ? sub.members.size() + " 个订阅" : joined);
        }
        if (origin.isEmpty()) return "未保存来源地址（本地或脚本配置）";
        return origin;
    }

    private String metaLine(Sub sub) {
        StringBuilder sb = new StringBuilder();
        sb.append("站点 ").append(sub.rows.size());
        if (!sub.removedIds.isEmpty()) sb.append(" · 已移除 ").append(sub.removedIds.size());
        String stamp = SourceController.formatShortTimestamp(sub.entry.updated);
        sb.append(" · ").append(stamp.isEmpty() ? "更新时间未记录" : "上次更新 " + stamp);
        if (sub.activeMember) sb.append(" · 已并入当前合集");
        if (!sub.error.isEmpty()) sb.append(" · ").append(sub.error);
        return sb.toString();
    }

    // ------------------------------------------------------------------ model

    private static final class Data {
        final List<Sub> subs = new ArrayList<Sub>();
        boolean hasConfig;
        String error = "";
    }

    private static final class Sub {
        final ConfigLibrary.Entry entry;
        final List<Cms.Site> sites = new ArrayList<Cms.Site>();
        final List<SiteRow> rows = new ArrayList<SiteRow>();
        final List<String> removedIds = new ArrayList<String>();
        final Map<String, String> rawNames = new LinkedHashMap<String, String>();
        List<SubscriptionManager.MultiSourceMeta> members = Collections.emptyList();
        boolean merged;
        boolean active;
        boolean activeMember;
        boolean hideFromSites;
        boolean rowsBuilt;
        String error = "";

        Sub(ConfigLibrary.Entry value) {
            entry = value;
        }

        /** True when every visible site is currently excluded from aggregated search. */
        boolean isDisabled() {
            if (rows.isEmpty()) return false;
            for (SiteRow row : rows) {
                if (row.searchable()) return false;
            }
            return true;
        }
    }

    private static final class SiteRow {
        final Cms.Site site;
        Cms.Site active;

        SiteRow(Cms.Site value) {
            site = value;
        }

        boolean searchable() {
            return active != null ? active.searchable : site.searchable;
        }
    }
}
