package com.github.catvod.crawler;

/**
 * 宿主侧的资源 id 桥。
 *
 * <p>上游 `catvod` 是独立 Android library 模块，自带 `com.github.catvod.crawler.R`（由 aapt 按该模块
 * 命名空间生成）。我们把源码 vendor 进 App 后，aapt 只为 App 包生成 `com.pilipala.marcomovie.R`，
 * 所以在这里把上游用到的那两个数组映射到宿主资源上（值见 `res/values/catvod_arrays.xml`）。
 *
 * <p>目前上游只有 `com.github.catvod.bean.Doh#get` 用 R。上游以后新增 R 引用时，这里会编译报错，
 * 提醒补映射，不会静默拿到错误 id。
 */
public final class R {

    private R() {
    }

    public static final class array {
        public static final int doh_url = com.pilipala.marcomovie.R.array.doh_url;
        public static final int doh_name = com.pilipala.marcomovie.R.array.doh_name;
    }
}
