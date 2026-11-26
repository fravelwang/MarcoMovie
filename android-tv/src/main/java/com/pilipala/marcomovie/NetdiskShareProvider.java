package com.pilipala.marcomovie;

import java.util.List;

/**
 * 一个网盘的分享链接 → 可播线路。实现只做协议转换，不做"要不要用"的决策。
 *
 * <p>为什么要有这一层：站点详情页的下载区常常同时挂几条网盘分享（百度 / 夸克 / UC），每家解析
 * 分享所需的协议都不同 —— 夸克要登录 cookie，UC 免登录换 token —— 但"变成一条线路"之后的事情
 * 完全一样：一个线路名 + 每集一个 episode id。所以协议留在 provider 里，"要不要试、几条、叫
 * 什么名、缓存多久"留给 {@link NetdiskShareLines}。
 *
 * <p>本次落地的四个实现：
 * <ul>
 *   <li>{@link QuarkShareProvider}（{@code quark}）：复用本机夸克登录 cookie，造五段 id，播放侧见
 *       {@link QuarkShareResolver}；
 *   <li>{@link UcShareProvider}（{@code uc}）：免登录，造四段 id，播放侧见
 *       {@link UcShareResolver}；
 *   <li>{@link BaiduShareProvider}（{@code baidu}）：列目录免登录（访问者 Cookie + 提取码），造六段
 *       {@code bd} id；播放直链要账号 Cookie，见 {@link BaiduShareResolver}；
 *   <li>{@link AliShareProvider}（{@code ali}）：列目录只要匿名 {@code share_token}，造四段
 *       {@code al} id；播放直链要账号（转存后换直链），见 {@link AliShareResolver}。
 * </ul>
 *
 * <p>还没实现的盘，以及为什么先不写（照"不加假实现"的要求记在这里，而不是交一个空壳类）：
 * <ul>
 *   <li>115：分享快照 {@code webapi.115.com/share/snap} 与下载地址都要登录 Cookie（{@code UID/CID/SEID}），
 *       且风控严格；手里没有可复现的免登录样本，写出来也只能是猜。
 *   <li>天翼云盘（189）：分享页与 {@code api.cloud.189.cn} 接口都要会话票据，同样没有样本。
 *   <li>移动云盘（139）：接口按省分域，签名参数随客户端版本变化，没样本不敢写。
 *   <li>123 云盘：分享直链可匿名，但接口要求 {@code shareKey} 与动态 {@code sign}，且服务端会按 UA 校验，
 *       没有一份实测通过的样本。
 *   <li>迅雷云盘：分享解析要 {@code pan.xunlei.com} 的登录票据与 {@code captcha_token}，同样缺样本。
 *   <li>阿里云盘的播放要账号（转存后取直链），已见 {@link AliShareResolver}；UC 的分享目前免登录，
 *       但"需要登录才能看"的那一类分享（接口 401/403）还没做账号链路。
 * </ul>
 * 这些不是"不支持"，是"没有实测通过的协议可抄"；等有样本时按本接口加一个实现即可，
 * {@link NetdiskShareLines} 只需要多认一条域名正则。
 */
interface NetdiskShareProvider {

    /** 唯一 id，用于日志与去重：{@code quark} / {@code uc} / {@code baidu}… */
    String id();

    /** 这个 http 链接是不是本盘的分享链接（只认自己的域名）。 */
    boolean matches(String url);

    /**
     * 把一条分享链接变成线路；拿不到就返回空表，失败只记日志（不打 UI）。
     *
     * <p>会在后台线程被调用（会打网络），所以允许 {@code throws Exception}；调用方逐条捕获，
     * 一条分享失败不影响别的分享、别的盘。
     *
     * @param shareUrl 页面里抓到的那条分享链接（尾部标点已去掉）
     * @param passcode 链接上带的提取码，通常为空串
     */
    List<Cms.Line> lines(String shareUrl, String passcode) throws Exception;
}
