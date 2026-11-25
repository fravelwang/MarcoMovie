package com.pilipala.marcomovie;

import android.content.Context;

/**
 * 宿主侧装配 vendor 进来的 catvod 基座（`com.github.catvod.*`）。
 *
 * <p>上游的基座假定自己跑在 FongMi 的 App 里，靠 {@code com.github.catvod.Runtime} 拿 Context / Activity /
 * Toast，靠 {@code com.github.catvod.Proxy} 拼本地代理地址。我们把源码 vendor 进来以后，由这个类在宿主
 * 启动时把这几个静态入口接上；插件 jar 里的类通过父加载器引用的就是这里装好的对象。
 *
 * <p>为什么单独一个类：装配点会跟着基座升级变（上游加一个入口这里就得补一个），集中在一处，
 * 不散在 CspEngine / LocalProxy / MainActivity 里，回滚和排查都只看这一个文件。
 */
final class CatvodHost {

    private CatvodHost() {
    }

    /** 只要有 Context 就先把基座的 Runtime 接上（处理自动化 / 网关这类没有 Activity 的入口）。 */
    static void ensure(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        com.github.catvod.Runtime.set(app == null ? context : app);
        syncProxyPort(CspEngine.proxyPort());
    }

    /** 前台就绪后把 Activity / Toast 入口也接上，插件的 dialog、toast、WebView 才有宿主可用。 */
    static void bindActivity() {
        com.github.catvod.Runtime.setActivity(() -> MainActivity.activeActivity);
        com.github.catvod.Runtime.setToast(
                text -> {
                    if (text == null) return;
                    MainActivity current = MainActivity.activeActivity;
                    if (current == null || current.isFinishing() || current.isDestroyed()) return;
                    current.ui.post(() -> current.toast(text));
                });
    }

    /** 控件的本地代理端口可能随监听器启动而变，变了就同步给基座，插件拼出的 proxyUrl 才是通的。 */
    static void syncProxyPort(int port) {
        if (port <= 0) return;
        com.github.catvod.Proxy.set(port);
    }
}
