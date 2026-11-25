package com.pilipala.marcomovie;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * TV dock panel that groups the three source layers in one place: subscription package, browsing
 * site and the current series line. The panel only opens existing pickers; it never reimplements
 * switching logic.
 */
final class SourceControlPanel {
    private static final int ROW_FILL = 0x1fffffff;
    private static final int ROW_FOCUS = 0x38ffffff;
    private static final int CHIP_FILL = 0x33ffffff;
    private static final int MUTED_TEXT = 0x99cbd5e1;

    private SourceControlPanel() {}

    static void show(final MainActivity a) {
        if (a == null || a.isFinishing()) return;

        final Cms.Site seriesSite =
                a.recentSeriesConfig == a.config ? findSite(a, a.recentSeriesSite) : null;
        final Cms.Video seriesVideo = seriesSite == null ? null : a.recentSeriesVideo;
        final boolean seriesReady = seriesSite != null && seriesVideo != null;

        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(2));

        TextView lead =
                a.text(
                        "两层：片源包决定能搜到哪些站，站点偏好决定默认从哪些站取片（网盘在前、采集站补充）。"
                                + "具体线路在详情页里换。",
                        12.5f,
                        MUTED_TEXT);
        lead.setLineSpacing(a.dp(3), 1.05f);
        panel.addView(lead);
        a.gap(panel, 10);

        addLayer(
                a,
                panel,
                "source-control:package",
                "第一层",
                "片源包",
                configName(a),
                "切换",
                "影响片库、分类与搜索能使用的片源集合。",
                true,
                () -> SourceSwitchDialog.show(a));

        a.gap(panel, 8);
        addLayer(
                a,
                panel,
                "source-control:site",
                "第二层",
                "站点偏好",
                selectedSiteName(a),
                "调整偏好",
                "决定片库、分类与搜索默认用哪个站点；同一部剧有多个来源时，详情页按这里的偏好顺序挑网盘在前的那条。点按可置顶站点。",
                true,
                () ->
                        new AppDialog.Builder(a)
                                .setTitle("站点偏好")
                                .setMessage("选择默认浏览的站点，或调整多来源时的优先顺序。")
                                .setNegativeButton("选择站点", (d, i) -> a.chooseSources())
                                .setPositiveButton("调整优先级", (d, i) -> SourcePreferences.editOrder(a))
                                .show());

        new AppDialog.Builder(a)
                .setTitle("源 · 两层切换")
                .setView(panel)
                .setWide(true)
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void addLayer(
            MainActivity a,
            LinearLayout panel,
            String tag,
            String layer,
            String title,
            String value,
            String actionLabel,
            String noteText,
            boolean enabled,
            final Runnable action) {
        LinearLayout row = a.row();
        row.setTag(tag);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(11), a.dp(12), a.dp(11));
        row.setBackground(a.bg(ROW_FILL, 16));
        row.setFocusable(enabled);
        row.setFocusableInTouchMode(enabled);
        row.setClickable(enabled);
        row.setEnabled(enabled);
        if (!enabled) row.setAlpha(0.45f);
        row.setContentDescription(
                enabled
                        ? title + "：" + value + "，按确认键" + actionLabel
                        : title + "：" + value);

        LinearLayout labels = a.vertical();
        TextView layerView = a.text(layer + " · " + title, 11.5f, MUTED_TEXT);
        layerView.setSingleLine(true);
        labels.addView(layerView);

        TextView valueView = a.text(value, 17f, MainActivity.WHITE);
        valueView.setTypeface(null, Typeface.BOLD);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(-1, -2);
        valueLp.topMargin = a.dp(3);
        labels.addView(valueView, valueLp);

        TextView chip = a.text(actionLabel, 12.5f, 0xffdceef4);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setIncludeFontPadding(false);
        chip.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        chip.setBackground(a.bg(CHIP_FILL, 999));

        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.leftMargin = a.dp(12);
        row.addView(chip, chipLp);

        if (enabled) {
            UiCompat.setDefaultFocusHighlightEnabled(row, false);
            row.setOnClickListener(
                    v -> {
                        AppDialog open = a.appDialog;
                        if (open != null) open.dismiss();
                        a.ui.post(action);
                    });
            row.setOnFocusChangeListener(
                    (v, focused) -> {
                        FocusMotion.style(
                                a,
                                row,
                                focused,
                                ROW_FILL,
                                ROW_FOCUS,
                                16,
                                MainActivity.WHITE,
                                MainActivity.WHITE,
                                true,
                                1.008f);
                        chip.setBackground(a.bg(focused ? 0xffffffff : CHIP_FILL, 999));
                        chip.setTextColor(focused ? MainActivity.DARK : 0xffdceef4);
                    });
            a.pressFeedback(row);
        }

        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));

        TextView note = a.text(noteText, 11.5f, 0x88cbd5e1);
        note.setLineSpacing(a.dp(2), 1.05f);
        note.setPadding(a.dp(14), a.dp(5), a.dp(14), 0);
        panel.addView(note);
    }

    private static String configName(MainActivity a) {
        try {
            String name = SourceController.configName(a.config);
            return name == null || name.trim().isEmpty() ? "我的订阅" : name.trim();
        } catch (Throwable ignored) {
            return "我的订阅";
        }
    }

    private static String selectedSiteName(MainActivity a) {
        String key = a.prefs == null ? "" : a.prefs.getString(Prefs.SELECTED_SITE, "");
        if (key == null) key = "";
        if (a.config != null) {
            for (Cms.Site site : a.config.sites) {
                if (site != null && key.equals(site.key)) {
                    String name = site.name == null ? "" : site.name.trim();
                    return name.isEmpty() ? "未选择" : name;
                }
            }
        }
        return "未选择";
    }

    private static String seriesValue(MainActivity a, Cms.Site site, Cms.Video video) {
        if (site == null || video == null) return "未进入剧集";
        String title =
                video.title == null || video.title.trim().isEmpty()
                        ? "当前剧集"
                        : "《" + video.title.trim() + "》";
        Cms.Line active = a.activeLine;
        if (active == null) return title + " · 未选线路";
        if (video.lines == null || video.lines.isEmpty()) {
            String name = active.name == null ? "" : active.name.trim();
            return name.isEmpty() ? title : title + " · " + name;
        }
        if (!hasLine(video, active)) return title + " · 未选线路";
        String name = active.name == null ? "" : active.name.trim();
        return name.isEmpty() ? title : title + " · " + name;
    }

    private static boolean hasLine(Cms.Video video, Cms.Line line) {
        if (video == null || video.lines == null || line == null) return false;
        for (Cms.Line candidate : video.lines) {
            if (candidate == line) return true;
            if (candidate != null
                    && candidate.name != null
                    && line.name != null
                    && candidate.name.equals(line.name)) return true;
        }
        return false;
    }

    private static Cms.Site findSite(MainActivity a, Cms.Site wanted) {
        if (a == null || wanted == null || a.config == null) return null;
        for (Cms.Site site : a.config.sites) {
            if (site != null && wanted.key != null && wanted.key.equals(site.key)) return site;
        }
        return null;
    }

    /** Reuses the detail page itself, landing focus on its existing line/episode selector. */
    private static void openLinePicker(MainActivity a, Cms.Site site, Cms.Video video) {
        if (site == null || video == null) return;
        a.open(new MainActivity.Screen(() -> a.detail(site, video)), true);
        if (a.current != null) a.current.focus = "detail-episodes-trigger";
    }
}
