package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 选源决策（领域层）：只回答"该先探谁、该选谁"，不碰网络、不碰 Android、不碰厂商类型。
 *
 * <p>三个问题分开答，调用方（{@code SourceAutoSwitch}）只负责派发探测、把答复换算成
 * {@link Capability}、执行换站：
 *
 * <ol>
 *   <li>{@link #probeOrder}：这次该探哪几个站、先探谁；
 *   <li>{@link #pick}：答完之后选谁；
 *   <li>{@link #settled}：探测还在飞的时候，现在的答案还会不会被后面的站推翻。
 * </ol>
 *
 * <p>纯 Java 的代价是"名字里带不带网盘"这类判据进不来（站点命名是配置层的事），所以由调用方
 * 算好、以 {@link Candidate#netdiskNamed} 传进来。好处是这些规则可以脱离真机直接读、直接测。
 */
final class SourcePlan {
    private SourcePlan() {}

    /**
     * 一个站对"这部剧"的答复能力，探测阶段由防腐层给出。
     *
     * <p>注意这三个值不是按"能不能播"排的：{@link #SHARE} 也能播，只是要多走网盘解析；
     * 把它和 {@link #DIRECT} 分开，才能表达"直链优先"这条规则。
     */
    enum Capability {
        /** 有 http(s) 直链：采集站给的 m3u8/mp4，一次解析就能起播。 */
        DIRECT,
        /** 只有分享式 id（{@code fid++…++stoken++size}）：还要转存、换链才能播。 */
        SHARE,
        /** 站点认了这部剧，却没有一条可播线路。 */
        NONE
    }

    /**
     * 一个候选站，外加调用方算好的"名字里带不带网盘"判据。
     *
     * <p>领域层不猜站点命名：同样的名字，在不同配置里可能指不同东西，判断属于配置/体验层。
     */
    static final class Candidate {
        final SiteRef site;
        final boolean netdiskNamed;

        Candidate(SiteRef site, boolean netdiskNamed) {
            this.site = site;
            this.netdiskNamed = netdiskNamed;
        }
    }

    /**
     * 探测顺序：普通站（{@code netdiskNamed == false}）优先，同档保持传入顺序（也就是账本序），
     * 最多 {@code limit} 个；当前站和 {@code skip} 里的站不探。
     *
     * <p>为什么网盘站排后面：账本为了播放把网盘优先（原文件、CDN 直出），但"名字里带网盘"的站
     * 恰好是错贴/失效分享最容易出现的地方；探测预算花在三个网盘站上，就永远走不到后面那些给
     * 直链的采集站。所以这里把账本的播放序和探测序分开：播放序不动，探测先普通站。
     *
     * <p>为什么同档保持传入顺序：账本序本身就是"上次能播的站优先 + 其余按播放偏好"，同档内
     * 它是有意义的信号，不能在这里重排。
     *
     * <p>{@code skip} 是调用方的 60 秒记忆（刚探过的站）：本方法不关心它从哪来、何时过期，
     * 只当"这次不要探"。
     *
     * @param known 账本给出的候选（顺序即账本序），可含 null
     * @param currentKey 当前正在看的站，跳过；null 表示不跳过任何站
     * @param skip 这次不要再探的站 key 集合，可 null
     * @param limit 最多返回几个；{@code <= 0} 返回空表
     * @return 按探测顺序排好的候选，不含 null，长度不超过 {@code limit}
     */
    static List<Candidate> probeOrder(
            List<Candidate> known, String currentKey, Set<String> skip, int limit) {
        List<Candidate> out = new ArrayList<>();
        if (known == null || known.isEmpty() || limit <= 0) return out;
        List<Candidate> ordinary = new ArrayList<>();
        List<Candidate> netdisk = new ArrayList<>();
        for (Candidate candidate : known) {
            if (candidate == null || candidate.site == null) continue;
            String key = candidate.site.key;
            if (key.isEmpty()) continue;
            if (currentKey != null && currentKey.equals(key)) continue;
            if (skip != null && skip.contains(key)) continue;
            (candidate.netdiskNamed ? netdisk : ordinary).add(candidate);
        }
        for (Candidate candidate : ordinary) {
            if (out.size() >= limit) break;
            out.add(candidate);
        }
        for (Candidate candidate : netdisk) {
            if (out.size() >= limit) break;
            out.add(candidate);
        }
        return out;
    }

    /**
     * 选择：先取"能播且 {@link Capability#DIRECT}"里顺序最靠前的；没有才取"能播（
     * {@link Capability#SHARE}）"里最靠前的；都没有返回 null。
     *
     * <p>为什么直链优先：直链一次解析就能起播；分享式 id 要转存 + 换链，多两步、多两个会失败的
     * 地方。同一部片两个站都能播时，先把用户送到不用等的那一个。
     *
     * <p>为什么分享式还留着：能播的分享站也好过"没线路"。只有连一个能播的都没有时才什么都不做，
     * 让调用方保持它自己的"暂无线路"状态。
     *
     * <p>为什么按 {@code probed} 的顺序而不是 {@code answers} 的遍历顺序：{@code probed} 就是
     * {@link #probeOrder} 给出的探测序，同档里它等于账本序；{@code answers} 是 HashMap，遍历序
     * 没有意义。
     *
     * @param probed 探测顺序，即 {@link #probeOrder} 的结果
     * @param answers 已答复的站：site key → 能力；缺项按"还没答"处理
     * @return 选中的站，或 null
     */
    static SiteRef pick(List<SiteRef> probed, Map<String, Capability> answers) {
        SiteRef direct = first(probed, answers, Capability.DIRECT);
        if (direct != null) return direct;
        return first(probed, answers, Capability.SHARE);
    }

    /**
     * 探测还在飞的时候，现在的答案定了吗（{@link #pick} 的结果还会不会被后面的站推翻）。
     *
     * <p>为什么需要它：探测是并发的，全都等完再换站会让用户多等最慢的那个站（可能几十秒）。
     * 两条规则决定"不用再等"：
     *
     * <ul>
     *   <li>按探测序扫到第一个"还没答"的站：它可能答出 {@link Capability#DIRECT}，也可能就是
     *       账本里顺序更靠前的直链，所以从它往后一律不能定；
     *   <li>在它之前已经出了 {@link Capability#DIRECT}：序更靠前的站都答了、且都不是直链，后面
     *       的站最多也只是"能播"，翻不了盘；
     *   <li>一路扫到末尾（所有站都答完）：没有新信息了，再等也不会变。
     * </ul>
     *
     * <p>注意"后面的站先答出直链"不能立刻定：探测序就是账本序，前面那个站可能也是直链，按顺序
     * 该选前面那个。所以这里的判据是"前缀答完 + 前缀里出了直链"，不是"任何一个直链"。
     *
     * @param probed 探测顺序
     * @param answers 已答复的站；调用方只放"已经答完"的站，不要预填未答的站
     * @return true 表示现在可以按 {@link #pick} 的结果执行
     */
    static boolean settled(List<SiteRef> probed, Map<String, Capability> answers) {
        if (probed == null || probed.isEmpty()) return true;
        if (answers == null) return false;
        for (SiteRef site : probed) {
            if (site == null) continue;
            if (!answers.containsKey(site.key)) return false;
            if (answers.get(site.key) == Capability.DIRECT) return true;
        }
        return true;
    }

    /** 按探测顺序找第一个该能力的站。 */
    private static SiteRef first(
            List<SiteRef> probed, Map<String, Capability> answers, Capability want) {
        if (probed == null || answers == null) return null;
        for (SiteRef site : probed) {
            if (site == null) continue;
            if (answers.get(site.key) == want) return site;
        }
        return null;
    }
}
