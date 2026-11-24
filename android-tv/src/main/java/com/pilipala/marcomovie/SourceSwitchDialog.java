package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;

/**
 * Switches the active source package between the subscriptions saved in {@link ConfigLibrary}.
 *
 * <p>This dialog only switches. Merging, deleting and refreshing subscriptions stays in {@link
 * SourcePicker#showSavedConfigs}, which the "管理全部订阅" footer opens. The switch itself reuses
 * {@link SourceController#saveConfig} so engines, pointers and the home screen reload exactly as
 * they do for a fresh import.
 */
final class SourceSwitchDialog {
    private SourceSwitchDialog() {}

    static void show(final MainActivity a) {
        if (a == null || a.isFinishing() || a.configStore == null) return;
        final Cms.Config previous = a.config;
        final ConfigLibrary library = new ConfigLibrary(a.configStore.directory);
        a.worker.execute(
                () -> {
                    final List<ConfigLibrary.Entry> entries = new ArrayList<>();
                    final List<String> labels = new ArrayList<>();
                    int activeIndex = -1;
                    try {
                        if (previous != null
                                && previous.raw != null
                                && !previous.raw.trim().isEmpty()) {
                            // Keep the active package listed even if it was never indexed.
                            library.rememberIfAbsent(
                                    previous.raw,
                                    a.configOrigin,
                                    SourceController.configName(previous),
                                    a.prefs.getString(Prefs.SELECTED_SITE, ""));
                        }
                        String activeId =
                                previous == null
                                        ? ""
                                        : ConfigLibrary.identity(a.configOrigin, previous.raw);
                        for (ConfigLibrary.Entry entry : library.entries()) {
                            boolean current = entry.id.equals(activeId);
                            if (current) activeIndex = entries.size();
                            entries.add(entry);
                            labels.add(label(library, entry, current));
                        }
                    } catch (Exception error) {
                        a.ui.post(() -> a.notice("无法读取已添加的订阅源，原订阅文件已保留"));
                        return;
                    }
                    final int active = activeIndex;
                    a.ui.post(() -> showList(a, library, previous, entries, labels, active));
                });
    }

    private static void showList(
            final MainActivity a,
            final ConfigLibrary library,
            final Cms.Config previous,
            final List<ConfigLibrary.Entry> entries,
            final List<String> labels,
            final int active) {
        if (a.isFinishing() || a.isDestroyed()) return;
        if (entries.isEmpty()) {
            new AppDialog.Builder(a)
                    .setTitle("切换片源包")
                    .setMessage("还没有添加任何订阅源。先添加一份，再在首页切换。")
                    .setPositiveButton("添加订阅源", (d, w) -> a.importDialog())
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("切换片源包 · 共 " + entries.size() + " 个")
                .setItems(
                        labels.toArray(new String[labels.size()]),
                        (dialog, index) -> {
                            if (index == active) {
                                a.toast("当前已是「" + displayName(entries.get(index)) + "」");
                                return;
                            }
                            switchTo(a, library, previous, entries.get(index));
                        })
                .setNegativeButton("关闭", null)
                .setNeutralButton(
                        "管理全部订阅 ⟩",
                        (d, w) -> {
                            // showSavedConfigs() reads the active config, so an empty install has
                            // to go through the add flow first.
                            if (a.config == null) {
                                a.importDialog();
                                return;
                            }
                            SourcePicker.showSavedConfigs(a);
                        })
                .show();
    }

    /** "当前 · 名称 · N 个源 · 更新于 MM-dd HH:mm"; a broken package still lists with 0 sources. */
    private static String label(ConfigLibrary library, ConfigLibrary.Entry entry, boolean current) {
        String stamp = SourceController.formatShortTimestamp(entry.updated);
        StringBuilder out = new StringBuilder();
        if (current) out.append("当前 · ");
        out.append(displayName(entry))
                .append(" · ")
                .append(siteCount(library, entry))
                .append(" 个源");
        if (!stamp.isEmpty()) out.append(" · 更新于 ").append(stamp);
        return out.toString();
    }

    private static String displayName(ConfigLibrary.Entry entry) {
        return entry.name == null || entry.name.trim().isEmpty() ? "未命名订阅" : entry.name.trim();
    }

    private static int siteCount(ConfigLibrary library, ConfigLibrary.Entry entry) {
        try {
            return Cms.parseConfig(library.raw(entry), entry.origin).sites.size();
        } catch (Throwable ignored) {
            // Keep the row visible: an unreadable package must still be switchable away from.
            return 0;
        }
    }

    /** Single switch path: parse the saved raw, then hand it to SourceController.saveConfig. */
    private static void switchTo(
            final MainActivity a,
            final ConfigLibrary library,
            final Cms.Config previous,
            final ConfigLibrary.Entry entry) {
        final String name = displayName(entry);
        a.toast("正在切换到「" + name + "」…");
        a.worker.execute(
                () -> {
                    Cms.Config picked = null;
                    try {
                        picked = Cms.parseConfig(library.raw(entry), entry.origin);
                    } catch (Exception error) {
                        AppLog.w("SourceSwitchDialog", "Switch source package failed", error);
                    }
                    if (picked == null) {
                        a.ui.post(() -> a.notice("无法读取这份订阅，已保留当前片源"));
                        return;
                    }
                    final Cms.Config ready = picked;
                    a.ui.post(
                            () -> {
                                if (a.isFinishing() || a.isDestroyed() || a.config != previous)
                                    return;
                                try {
                                    SourceController.saveConfig(a, ready, entry.origin);
                                    // saveConfig() reports "已导入 N 个来源" for imports; replace it
                                    // with the switch wording so the toast matches the action.
                                    a.toast("已切换到「" + name + "」");
                                } catch (Exception error) {
                                    a.notice("片源包切换失败，已保留当前片源");
                                }
                            });
                });
    }
}
