package com.orhanobut.logger;

import android.util.Log;

/**
 * 宿主侧的 {@code com.orhanobut:logger} 替代品。
 *
 * <p>vendor 进来的 catvod 有三处 `Logger.t(tag).d(...)` 调用。为一个 30 KB 的日志库再加一个依赖
 * 不划算，而直接改 vendor 里的调用又会给「整目录覆盖上游」添堵，所以这里按它用到的表面实现一个
 * 最小适配类（只转发到 android.util.Log）。若将来上游用到更多方法，编译期就会在这里报错，不会静默丢日志。
 */
public final class Logger {

    private final String tag;

    private Logger(String tag) {
        this.tag = tag == null || tag.isEmpty() ? "catvod" : tag;
    }

    public static Logger t(String tag) {
        return new Logger(tag);
    }

    public void d(String message) {
        Log.d(tag, message == null ? "null" : message);
    }

    public void d(String message, Object... args) {
        Log.d(tag, args == null || args.length == 0 ? String.valueOf(message) : String.format(message, args));
    }

    public void i(String message, Object... args) {
        Log.i(tag, args == null || args.length == 0 ? String.valueOf(message) : String.format(message, args));
    }

    public void w(String message, Object... args) {
        Log.w(tag, args == null || args.length == 0 ? String.valueOf(message) : String.format(message, args));
    }

    public void e(String message, Object... args) {
        Log.e(tag, args == null || args.length == 0 ? String.valueOf(message) : String.format(message, args));
    }

    public void e(Throwable throwable, String message, Object... args) {
        Log.e(tag, args == null || args.length == 0 ? String.valueOf(message) : String.format(message, args), throwable);
    }
}
