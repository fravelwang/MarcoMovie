package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;

/** Explicit defaults remain separate from a one-time manual source switch. */
final class SourcePreferences {
    static String identity(Cms.Site site) {
        String baseApi =
                site.originalApi != null && !site.originalApi.isEmpty()
                        ? site.originalApi
                        : site.api;
        return site.key + "\n" + baseApi;
    }

    static boolean isPreferred(MainActivity a, Cms.Site site) {
        String pref = a.prefs.getString(Prefs.PREFERRED_SITE, "");
        if (pref.isEmpty()) return false;
        return pref.equals(identity(site)) || pref.equals(site.key + "\n" + site.api);
    }

    static void restoreDefault(MainActivity a) {
        if (a.config == null) return;
        for (Cms.Site site : a.config.sites) {
            if (isPreferred(a, site) && a.available(site)) {
                a.prefs.edit().putString(Prefs.SELECTED_SITE, site.key).apply();
                return;
            }
        }
    }

    static void choose(MainActivity a) {
        if (a.config == null) return;
        List<Cms.Site> sites = new ArrayList<>();
        for (Cms.Site site : a.config.sites) if (a.available(site)) sites.add(site);
        String[] names = new String[sites.size() + 1];
        names[0] = "跟随上次选择";
        for (int i = 0; i < sites.size(); i++)
            names[i + 1] = (isPreferred(a, sites.get(i)) ? "★ " : "") + sites.get(i).name;
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("默认偏好源")
                .setItems(
                        names,
                        (d, index) -> {
                            a.prefs
                                    .edit()
                                    .putString(
                                            Prefs.PREFERRED_SITE,
                                            index == 0 ? "" : identity(sites.get(index - 1)))
                                    .apply();
                            a.toast(index == 0 ? "已取消默认偏好源" : "已保存默认偏好源");
                            SourcePicker.show(a);
                        })
                .show();
    }

    static int lineIndex(MainActivity a, Cms.Video video) {
        return lineIndex(a, null, video);
    }

    /**
     * Same choice, but able to use what {@link LineProbe} already measured for this site: a line the
     * picker marks 可用 (or one with a small first-byte latency) wins over an unknown line of the
     * same class, so plain "play" stops spending its first attempt on a dead address.
     */
    static int lineIndex(MainActivity a, Cms.Site site, Cms.Video video) {
        if (video == null || video.lines == null || video.lines.isEmpty()) return 0;
        String preferred = a.prefs.getString(Prefs.PREFERRED_LINE, "");
        for (int i = 0; i < video.lines.size(); i++) {
            if (video.lines.get(i).name.equals(preferred) && !video.lines.get(i).episodes.isEmpty())
                return i;
        }
        // No explicit preference. Default order (Boss 2026-11-25): netdisk first, site collects
        // supplement. A share line hands the player the original file (4K HEVC, DDP), while a CMS
        // line is usually a re-encode; the user should not have to pick that by hand.
        int share = -1;
        int shareEpisodes = 0;
        // Tie-break inside the same class: the line that carries the whole season. Netdisk sites
        // often list one file per "line", so the first non-empty line is usually a single episode;
        // picking the richest one makes plain "play" advance through the show.
        int richest = -1;
        int richestEpisodes = 0;
        for (int i = 0; i < video.lines.size(); i++) {
            Cms.Line line = video.lines.get(i);
            if (line == null || line.episodes == null || line.episodes.isEmpty()) continue;
            if (isShareLine(line)) {
                if (line.episodes.size() > shareEpisodes) {
                    shareEpisodes = line.episodes.size();
                    share = i;
                }
            } else if (line.episodes.size() > richestEpisodes) {
                richestEpisodes = line.episodes.size();
                richest = i;
            }
        }
        int chosen = share >= 0 ? share : (richest >= 0 ? richest : 0);
        if (site == null) return chosen;
        // Same class only: a proven line beats an unproven one, then the faster first byte wins.
        int best = chosen;
        long bestLatency = LineProbe.latencyMs(site, video.lines.get(chosen));
        boolean bestProven = "可用".equals(LineHealth.badge(a, site, video.lines.get(chosen)));
        for (int i = 0; i < video.lines.size(); i++) {
            if (i == chosen) continue;
            Cms.Line line = video.lines.get(i);
            if (line == null || line.episodes == null || line.episodes.isEmpty()) continue;
            if (isShareLine(line) != isShareLine(video.lines.get(chosen))) continue;
            boolean proven = "可用".equals(LineHealth.badge(a, site, line));
            long latency = LineProbe.latencyMs(site, line);
            if (proven && !bestProven) {
                best = i;
                bestProven = true;
                bestLatency = latency;
            } else if (proven == bestProven
                    && latency > 0
                    && (bestLatency <= 0 || latency < bestLatency)) {
                best = i;
                bestLatency = latency;
            }
        }
        return best;
    }

    /**
     * True when this line's episodes are cloud-drive share addresses rather than site links.
     *
     * <p>Two shapes are in the wild: a plain share page URL, and the packed id the netdisk plugins
     * hand to the native resolver ({@code fid++pwdId++stoken++size++…}). Either way the player gets
     * the original file instead of a transcode, which is what "netdisk first" means.
     */
    static boolean isShareLine(Cms.Line line) {
        if (line == null || line.episodes == null) return false;
        for (Cms.Episode ep : line.episodes) {
            if (ep == null || ep.url == null) continue;
            String url = ep.url.trim().toLowerCase(java.util.Locale.ROOT);
            if (url.isEmpty()) continue;
            if (url.contains("pan.quark.cn")
                    || url.contains("alipan.com")
                    || url.contains("aliyundrive.com")
                    || url.contains("pan.baidu.com")
                    || url.contains("115.com")
                    || url.contains("cloud.189.cn")
                    || url.contains("drive.uc.cn")) return true;
            if (url.matches("^[0-9a-f]{32}\\+\\+.*")) return true;
        }
        return false;
    }

    /** Site keys the user pinned, front = highest priority. */
    static List<String> order(MainActivity a) {
        List<String> keys = new ArrayList<>();
        if (a == null || a.prefs == null) return keys;
        String raw = a.prefs.getString(Prefs.SITE_ORDER, "");
        if (raw == null || raw.isEmpty()) return keys;
        for (String part : raw.split("\n")) {
            String key = part == null ? "" : part.trim();
            if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    /** Moves a site to the front of the queue, or removes it when it is already pinned. */
    static void togglePin(MainActivity a, Cms.Site site) {
        if (a == null || site == null || site.key == null) return;
        List<String> keys = order(a);
        if (keys.remove(site.key)) {
            // Un-pinning keeps the rest of the order.
        } else {
            keys.add(0, site.key);
        }
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) out.append('\n');
            out.append(key);
        }
        a.prefs.edit().putString(Prefs.SITE_ORDER, out.toString()).apply();
    }

    /** Small editor: tap a site to pin it to the front, tap again to release it. */
    static void editOrder(MainActivity a) {
        if (a == null || a.config == null) return;
        List<Cms.Site> sites = new ArrayList<>();
        for (Cms.Site site : a.config.sites) if (a.available(site)) sites.add(site);
        List<String> pinned = order(a);
        String[] names = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            String key = sites.get(i).key;
            int at = pinned.indexOf(key);
            names[i] = (at >= 0 ? "★" + (at + 1) + " " : "　 ") + sites.get(i).name;
        }
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("站点优先级（点击置顶，再点取消）")
                .setItems(
                        names,
                        (d, index) -> {
                            togglePin(a, sites.get(index));
                            editOrder(a);
                        })
                .setNegativeButton("完成", null)
                .show();
    }

    static List<Cms.Site> prioritized(MainActivity a, List<Cms.Site> sites) {
        List<String> pinned = order(a);
        List<Cms.Site> ordered = new ArrayList<>();
        for (String key : pinned) {
            for (Cms.Site site : sites) {
                if (site != null && key.equals(site.key) && !ordered.contains(site)) ordered.add(site);
            }
        }
        for (Cms.Site site : sites) if (isPreferred(a, site) && !ordered.contains(site)) ordered.add(site);
        for (Cms.Site site : sites) if (!ordered.contains(site)) ordered.add(site);
        return ordered;
    }

    static void chooseLine(MainActivity a, Cms.Video video) {
        String[] names = new String[video.lines.size() + 1];
        names[0] = "自动选择";
        String preferred = a.prefs.getString(Prefs.PREFERRED_LINE, "");
        for (int i = 0; i < video.lines.size(); i++) {
            String name = video.lines.get(i).name;
            names[i + 1] = (name.equals(preferred) ? "★ " : "") + name;
        }
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("默认播放线路")
                .setItems(
                        names,
                        (d, index) -> {
                            a.prefs
                                    .edit()
                                    .putString(
                                            Prefs.PREFERRED_LINE,
                                            index == 0 ? "" : video.lines.get(index - 1).name)
                                    .apply();
                            a.toast("已保存，下次打开影片时优先选择；观看记录仍优先续播");
                        })
                .show();
    }
}
