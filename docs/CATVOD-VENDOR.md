# catvod 基座（vendor 自 FongMi/TV）

## 这是什么

`android-tv/src/main/java/com/github/catvod/**` 与 `android-tv/src/main/java/com/fongmi/quickjs/host/**`
是 **FongMi/TV 上游源码的 vendor 副本**，不是本项目代码。CSP/JAR/PY/JS 插件的类（`com.github.catvod.spider.*`）
在运行时通过父类加载器解析到这里的 `com.github.catvod.crawler.Spider`、`net.*`、`utils.*`，
所以这些类的签名就是「插件 ABI」。

| 项 | 值 |
| :--- | :--- |
| 上游仓库 | https://github.com/FongMi/TV |
| 上游提交 | `e7e7dd06250ca53da3c594ca4f6befc02e56b1d1`（2026-11-22，"Align subtitle layout and font selection"） |
| 取用路径 | `tv/catvod/src/main/java/com/github/catvod/**`、`tv/catvod/src/main/java/com/fongmi/quickjs/host/Host.java` |
| 许可 | GPL-3.0（与本项目 GPL-3.0-only 同向兼容），见 `NOTICE` / `THIRD_PARTY_NOTICES.md` |
| 依赖 | 与上游对齐：OkHttp 5.5.1（`okhttp-jvm`）、Okio 3.16.0、Gson 2.15.0、kotlin-stdlib 2.2.0、`logging-interceptor`、`okhttp-dnsohttps`；见 `android-tv/dependencies.json` |

## 怎么同步上游

1. 拉上游到同版本，`cp -a` 覆盖 `com/github/catvod/` 与 `com/fongmi/quickjs/host/` 两个目录；
2. 按下表重新施加补丁；
3. `bash 14-master/agent-use/tmp/20261124/build-tv.sh` 全量构建；
4. 上机验证：App 启动、聚合墙出图、任一 `csp_` 站点能取到首页、能起播。

## 对上游的改动（全部在这里登记，改动越少越好）

| # | 位置 | 改动 | 原因 |
| :--- | :--- | :--- | :--- |
| 1 | `crawler/Spider.java` | 补 `public static OkHttpClient client()`（3 行，带 `Marco patch` 注释） | 上游这一版没有该方法，但插件生态仍有 `invoke-static Spider.client` 调用点（本机 `dongli-spider.jar` 有 1 处），缺了抛 `NoSuchMethodError` |
| 2 | `utils/Prefers.java` | **删除** | 唯一用到 `androidx.preference` 的文件，且全树无人引用；它还使用 Java 21 模式 switch 预览特性，`--release 17` 编不过 |

## 宿主侧适配（不是上游文件，跟着本项目走）

| 文件 | 作用 |
| :--- | :--- |
| `com/orhanobut/logger/Logger.java` | 上游 `SpiderDebug`/`Path`/`Shell` 用 `Logger.t(tag).d(...)`；这里用 30 行转发到 `android.util.Log`，避免为一个日志库加依赖，也不必改上游调用点 |
| `com/github/catvod/crawler/R.java` | 上游是独立 library 模块，自带 `com.github.catvod.crawler.R`；vendor 进 App 后 aapt 只生成 `com.pilipala.marcomovie.R`，这里把 `array.doh_url/doh_name` 桥过去（资源在 `res/values/catvod_arrays.xml`） |
| `com/pilipala/marcomovie/CatvodHost.java` | 装配上游静态入口：`Runtime.set(context)`、`Runtime.setActivity/ setToast`、`Proxy.set(port)`。没有它插件拿不到 Context / 本地代理地址 |

## 为什么必须升 OkHttp 5（实测）

用 OkHttp 3.14.9 编译 vendor 树 → 100 个错误，全是上游新 API：
`RequestBody.create(String,MediaType)`、`Interceptor.Chain.withProxySelector`、`Chain.getProxySelector`、
`ResponseBody.EMPTY`、`okhttp3.dnsoverhttps.DnsOverHttps`、`gson.Strictness`。

产品层（272 个源文件）对 OkHttp 5.5.1 + Okio 3.16 + kotlin 2.2 + Gson 2.15 **零改动编译通过**，
所以这次是「换依赖 + vendor」，没有为了升级去改业务代码。

## 已知风险

- OkHttp 3 → 5 是运行时行为变化（TLS、连接池、HTTP/2、Kotlin 运行时）。产品层编译零改动，但仍需上机回归。
- `min-api 23`：OkHttp 5 的部分实现依赖 API 26+ 的 `java.time` 等。开发盒子是 Android 11（API 30）没问题；
  API 23–25 设备**未验证**。
- d8（build-tools 35 / R8 8.6）读不懂 Kotlin 2.1 metadata，构建时有 `Warning: An error occurred when parsing kotlin metadata`；
  dex 仍正常产出（只影响 Kotlin 反射元数据，运行时不用）。
