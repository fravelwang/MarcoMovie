package com.pilipala.marcomovie;

/** Browse position is independent of the last episode actually played. */
final class EpisodeSelection {
    String line = "";
    int page;
    boolean selected;
    /**
     * 用户在选择器里**明确点过**的那条线路（不是默认线路、也不是自动挑的）。只在一页详情里有效：
     * {@link MainActivity.Screen} 换页就跟着换，所以不需要手动清。
     *
     * <p>为什么不能拿 {@link #selected} 当判据：详情页一渲染就会 {@link #select} 一次（默认/记忆线路），
     * 于是每次播放都是 selected，判不出"这条是 Boss 点的"。
     */
    String pickedLine = "";

    void select(String name, int group, int count) {
        line = name;
        page = boundPage(group, count);
        selected = true;
    }

    /** 记下"这条线路是用户点的"；只由线路按钮的点击回调调用。 */
    void pick(String name) {
        pickedLine = name == null ? "" : name;
    }

    /** 用户是不是明确点过这条线路（名字为空一律不算）。 */
    boolean isPicked(String name) {
        String wanted = name == null ? "" : name.trim();
        return !wanted.isEmpty() && wanted.equals(pickedLine == null ? "" : pickedLine.trim());
    }

    static int boundPage(int page, int count) {
        return Math.max(0, Math.min(page, Math.max(0, (count - 1) / 50)));
    }

    static int pageFor(int originalIndex, int count, boolean descending) {
        int index = Math.max(0, Math.min(originalIndex, Math.max(0, count - 1)));
        return (descending ? Math.max(0, count - 1 - index) : index) / 50;
    }
}
