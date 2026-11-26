package com.pilipala.marcomovie;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The 网盘 tab: browse your own drives the way a TV file browser should work.
 *
 * <p>Browsing is the page. Configuration is not printed next to it: the account state lives in one
 * header pill, login is a single card that appears only when the drive is not signed in, and the
 * rest (plugin fallback, ordering, cookie paste) sits behind 更多. Each folder is its own screen, so
 * the remote's back key goes up one level, exactly like a native drive app or FongMi's browser.
 *
 * <p>Only 夸克 has a native browser today; the other drives still say so honestly instead of showing
 * a dead grid. Every 牌面 now logs in the same way: 扫码为主 — {@link NetdiskWebLogin} opens that
 * drive's own web login page, the phone App scans the QR on the TV, and the credential is captured
 * from the WebView and saved. 手工粘贴 is kept as the 兜底 (Boss 2026-11-26: 不要手工粘 Cookie/token).
 * 百度 / 阿里 / UC need the account credential at playback time (their share resolvers) but their
 * browsers are still out of scope; 115 目前只存凭据。
 */
final class NetdiskScreen {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int VIDEO_CARD_W = 210;

    private final MainActivity a;
    private final String driveKey;
    private final String dirFid;
    private final String dirName;

    private LinearLayout gridHost;
    private TextView status;
    private int generation;

    NetdiskScreen(MainActivity a) {
        this(a, QuarkQrLogin.PROVIDER, "0", "");
    }

    private NetdiskScreen(MainActivity a, String driveKey, String dirFid, String dirName) {
        this.a = a;
        this.driveKey = driveKey;
        this.dirFid = dirFid;
        this.dirName = dirName;
    }

    void show() {
        a.renderPage("网盘", rootPathLabel());
        if (a.body != null) {
            a.body.setPadding(a.dp(48), a.dp(18), a.dp(48), a.dp(40));
        }
        renderDriveStrip();
        renderPathBar();
        status = a.text("", 12f, TvV17Theme.MUTED);
        status.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(10));
        a.body.addView(status, new LinearLayout.LayoutParams(-1, -2));
        gridHost = a.vertical();
        gridHost.setClipChildren(false);
        gridHost.setClipToPadding(false);
        a.body.addView(gridHost, new LinearLayout.LayoutParams(-1, -2));
        load();
    }

    private String rootPathLabel() {
        return dirName == null || dirName.isEmpty() ? "我的网盘" : dirName;
    }

    // ------------------------------------------------------------------ header

    /** One pill per drive: the account status and the way to change it, in a single line. */
    private void renderDriveStrip() {
        LinearLayout strip = a.row();
        strip.setClipChildren(false);
        strip.setPadding(0, a.dp(4), 0, a.dp(4));
        String[][] drives = {
            {QuarkQrLogin.PROVIDER, "夸克"},
            {UcLogin.PROVIDER, "UC"},
            {BaiduLogin.PROVIDER, "百度"},
            {AliLogin.PROVIDER, "阿里"},
            {NetdiskWebLogin.PROVIDER_115, "115"},
        };
        for (String[] drive : drives) {
            final String key = drive[0];
            boolean current = key.equals(driveKey);
            // 五家登录态都是本机凭据文件说了算（UC 2026-11-26 起也有了自己的 uc.json；
            // 115 的凭据由 NetdiskWebLogin 写 netdisk/115.json）。
            boolean logged =
                    (QuarkQrLogin.PROVIDER.equals(key) && QuarkQrLogin.loadSaved() != null)
                            || (UcLogin.PROVIDER.equals(key) && UcLogin.loadSaved() != null)
                            || (BaiduLogin.PROVIDER.equals(key) && BaiduLogin.loadSaved() != null)
                            || (AliLogin.PROVIDER.equals(key) && AliLogin.loadSaved() != null)
                            || (NetdiskWebLogin.PROVIDER_115.equals(key)
                                    && a != null
                                    && NetdiskWebLogin.load115(a.getFilesDir()) != null);
            boolean nativeLogin =
                    QuarkQrLogin.PROVIDER.equals(key) || NetdiskWebLogin.supports(key);
            String label = drive[1] + (nativeLogin ? (logged ? " · 已登录" : " · 未登录") : "");
            Button pill = a.navButton(label, "netdisk-drive:" + key, () -> onDrivePill(key));
            pill.setTextSize(13f);
            if (!current) pill.setAlpha(.62f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(36));
            lp.rightMargin = a.dp(10);
            strip.addView(pill, lp);
        }
        View spacer = new View(a);
        strip.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        Button more = a.navButton("更多", "netdisk-more", this::openMore);
        strip.addView(more, new LinearLayout.LayoutParams(a.dp(96), a.dp(36)));
        a.body.addView(strip, new LinearLayout.LayoutParams(-1, -2));
    }

    private void onDrivePill(String key) {
        if (QuarkQrLogin.PROVIDER.equals(key)) {
            if (QuarkQrLogin.loadSaved() == null) {
                QuarkLoginDialog.show(a, this::reloadSelf);
                return;
            }
            QuarkQrLogin.Credential credential = QuarkQrLogin.loadSaved();
            a.notice("已登录 · " + QuarkQrLogin.describeShort(credential));
            return;
        }
        if (!NetdiskWebLogin.supports(key)) {
            // 剩下的都是没接过的盘：没有登录入口也没有原生浏览。
            a.toast("这个网盘还没有原生登录，暂时只能用「更多 → 插件配置中心」里的旧登录");
            return;
        }
        showLoginChoice(key);
    }

    /**
     * 非夸克牌面的入口：先弹一个二选一。**扫码是主路**（Boss 2026-11-26 定的口径：不要手工粘
     * Cookie/token），粘贴只在扫码不好使时当兜底；115 还没有粘贴入口，只有扫码。
     *
     * <p>百度多一项「开放平台授权（取直链）」：扫码登录拿到的是账号 Cookie（转存、删除够用），
     * 取自己文件的直链还要一份 OAuth 令牌（{@link BaiduOAuth}），所以登录之后点一次它，
     * 播放才会走「先转存到 /MarcoMovie、播前清理、再取直链」那条主路。
     */
    private void showLoginChoice(final String key) {
        boolean paste = NetdiskWebLogin.supportsPaste(key);
        boolean authorize = BaiduLogin.PROVIDER.equals(key);
        final List<String> options = new ArrayList<>();
        options.add("扫码登录（推荐）");
        if (authorize) options.add("开放平台授权（取直链）");
        if (paste) options.add("手工粘贴凭据（兜底）");
        final String[] choices = options.toArray(new String[0]);
        StringBuilder message =
                new StringBuilder(
                        "扫码登录：电视上会打开这家的网页登录页，用手机 App 扫屏幕上的二维码，"
                                + "登录成功后凭据自动保存，不用手工粘贴。");
        if (authorize) {
            message.append(
                    "「开放平台授权」是在扫码登录之后再做一次：换一份取直链的令牌，"
                            + "之后百度分享会先把这一集转存到网盘的 /MarcoMovie、播前清理旧副本，"
                            + "再用自己文件的直链播放。");
        }
        new AppDialog.Builder(a)
                .setTitle(NetdiskWebLogin.label(key) + " · 登录")
                .setMessage(message.toString())
                .setItems(
                        choices,
                        (dialog, which) -> {
                            String picked = choices[which];
                            if (picked.startsWith("开放平台")) {
                                BaiduOAuth.showAuthorize(a, this::reloadSelf);
                            } else if (picked.startsWith("扫码")) {
                                scanLogin(key);
                            } else {
                                pasteLogin(key);
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void scanLogin(String key) {
        NetdiskWebLogin.show(a, key, this::reloadSelf);
    }

    private void pasteLogin(String key) {
        if (UcLogin.PROVIDER.equals(key)) {
            // 兜底：游客接口被限流时也能靠账号 Cookie 换直链。
            UcLogin.showPasteDialog(a, this::reloadSelf);
            return;
        }
        if (BaiduLogin.PROVIDER.equals(key)) {
            BaiduLogin.showPasteDialog(a, this::reloadSelf);
            return;
        }
        if (AliLogin.PROVIDER.equals(key)) {
            AliLogin.showPasteDialog(a, this::reloadSelf);
            return;
        }
        a.toast("这家暂时只有扫码登录");
    }

    private void reloadSelf() {
        if (a.body != null) {
            a.open(new MainActivity.Screen(() -> new NetdiskScreen(a).show()), true);
        }
    }

    /** Breadcrumb + the only two verbs a browser needs: up, refresh. */
    private void renderPathBar() {
        LinearLayout bar = a.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView path = a.text(rootPathLabel(), 15f, TvV17Theme.TEXT);
        path.setTypeface(null, Typeface.BOLD);
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.START);
        bar.addView(path, new LinearLayout.LayoutParams(0, -2, 1f));
        Button refresh =
                a.navButton(
                        "刷新", "netdisk-refresh", () -> load());
        bar.addView(refresh, new LinearLayout.LayoutParams(a.dp(88), a.dp(34)));
        a.body.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void openMore() {
        LinearLayout box = a.vertical();
        box.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        TextView hint =
                a.text("网盘的高级设置。日常浏览用不到这里。", 12f, TvV17Theme.MUTED);
        hint.setPadding(a.dp(4), 0, a.dp(4), a.dp(10));
        box.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        box.addView(
                moreRow("插件配置中心（兜底登录 / 已导入的线路）", "netdisk-more-plugin", () -> AccountPanel.show(a, null)),
                new LinearLayout.LayoutParams(-1, a.dp(42)));
        box.addView(
                moreRow("网盘优先顺序", "netdisk-more-order", () -> accountAction("panSortShow")),
                new LinearLayout.LayoutParams(-1, a.dp(42)));
        box.addView(
                moreRow("网盘搜索顺序", "netdisk-more-search", () -> accountAction("panSourceSortShow")),
                new LinearLayout.LayoutParams(-1, a.dp(42)));
        box.addView(
                moreRow("粘贴 Cookie 凭据", "netdisk-more-cookie", () -> accountAction("pushCkShow")),
                new LinearLayout.LayoutParams(-1, a.dp(42)));
        if (QuarkQrLogin.loadSaved() != null) {
            box.addView(
                    moreRow("退出夸克登录", "netdisk-more-logout", this::logoutQuark),
                    new LinearLayout.LayoutParams(-1, a.dp(42)));
        }
        new AppDialog.Builder(a)
                .setTitle("更多")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private Button moreRow(String label, String tag, Runnable action) {
        Button b = a.navButton(label, tag, action);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setTextSize(13.5f);
        b.setPadding(a.dp(14), 0, a.dp(14), 0);
        return b;
    }

    private void accountAction(String action) {
        try {
            a.ensureStoragePermissionForNetdisk(
                    () -> {
                        PluginDialogStyler.clearDirectDriveForward();
                        PluginDialogStyler.watch(a);
                        new SettingsScreen(a).accountAction(action, null);
                    });
        } catch (Throwable t) {
            a.notice(t.getMessage() == null ? "这个入口暂时不可用" : t.getMessage());
        }
    }

    private void logoutQuark() {
        QuarkQrLogin.clearSaved();
        AccountPanel.markDriveLoggedIn(a, QuarkQrLogin.PROVIDER, false);
        a.toast("已退出夸克登录");
        reloadSelf();
    }

    // ------------------------------------------------------------------ grid

    private void load() {
        final int mine = ++generation;
        gridHost.removeAllViews();
        if (BaiduLogin.PROVIDER.equals(driveKey)) {
            // 百度的原生浏览不在本次范围：这里只把登录状态与去哪播说清楚，不摆一个死格子。
            BaiduLogin.Credential saved = BaiduLogin.loadSaved();
            String authorize = BaiduOAuth.describeShort();
            status.setText(
                    saved == null
                            ? "百度网盘还没登录：点上面的「百度」牌面选「扫码登录」，用手机百度网盘 App 扫电视上的二维码。"
                                    + "登录之后再做一次「开放平台授权（取直链）」，播放才会先转存到 /MarcoMovie 再播。"
                                    + "原生浏览还没做。"
                            : "百度网盘已登录（"
                                    + BaiduLogin.describe(saved)
                                    + "）· "
                                    + authorize
                                    + "。播放走详情页的「百度分享」线路：先转存到网盘的 /MarcoMovie、播前清理旧副本，"
                                    + "再用自己文件的直链播放；没有授权时退回网页直链。原生浏览还没做。");
            return;
        }
        if (AliLogin.PROVIDER.equals(driveKey)) {
            // 阿里的原生浏览不在本次范围：这里只把登录状态与去哪播说清楚，不摆一个死格子。
            AliLogin.Credential saved = AliLogin.loadSaved();
            status.setText(
                    saved == null
                            ? "阿里云盘还没登录：点上面的「阿里」牌面选「扫码登录」，用手机阿里云盘 App 扫电视上的二维码。这次只做登录与分享线路，原生浏览还没做。"
                            : "阿里云盘已登录（"
                                    + AliLogin.describe(saved)
                                    + "）。这次只做登录与分享线路：播放走详情页的「阿里分享」线路，原生浏览还没做。");
            return;
        }
        if (UcLogin.PROVIDER.equals(driveKey)) {
            // UC 的原生浏览不在本次范围：这里只把登录状态与去哪播说清楚，不摆一个死格子。
            UcLogin.Credential saved = UcLogin.loadSaved();
            status.setText(
                    saved == null
                            ? "UC 网盘还没登录：点上面的「UC」牌面选「扫码登录」，用手机 UC 扫码。这次只做登录与分享线路，原生浏览还没做。"
                            : "UC 网盘已登录（"
                                    + UcLogin.describe(saved)
                                    + "）。这次只做登录与分享线路：播放走详情页的「UC」线路，游客接口被限流时自动改用账号 Cookie；原生浏览还没做。");
            return;
        }
        if (NetdiskWebLogin.PROVIDER_115.equals(driveKey)) {
            // 115 本卡只存凭据、不接播放，所以这一页只说清楚状态。
            NetdiskWebLogin.Cookie115 saved = NetdiskWebLogin.load115(a.getFilesDir());
            status.setText(
                    saved == null
                            ? "115 网盘还没登录：点上面的「115」牌面选「扫码登录」，用手机 115 App 扫电视上的二维码。这次只存凭据，原生浏览与播放都还没接。"
                            : "115 网盘已登录（"
                                    + NetdiskWebLogin.describe115(saved)
                                    + "）。这次只存凭据，原生浏览与播放都还没接。");
            return;
        }
        if (!QuarkQrLogin.PROVIDER.equals(driveKey)) {
            status.setText("这个网盘还没有原生浏览");
            return;
        }
        QuarkQrLogin.Credential credential = QuarkQrLogin.loadSaved();
        if (credential == null) {
            status.setText("");
            renderLoginCard();
            return;
        }
        status.setText("正在读取…");
        final QuarkDriveClient client = new QuarkDriveClient(credential.cookie);
        WORKER.execute(
                () -> {
                    List<QuarkDriveClient.Entry> rows = null;
                    String error = null;
                    try {
                        rows = client.list(dirFid);
                    } catch (Throwable t) {
                        error = t.getMessage() == null ? "读取失败" : t.getMessage();
                    }
                    final List<QuarkDriveClient.Entry> done = rows;
                    final String err = error;
                    a.ui.post(
                            () -> {
                                if (generation != mine) return;
                                renderGrid(client, done, err);
                            });
                });
    }

    /**未登录时整页只有一张能按的卡：扫码。配置不进主位。 */
    private void renderLoginCard() {
        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(28));
        card.setBackground(a.bg(0x1affffff, 20));
        TextView title = a.text("先登录夸克网盘", 20, TvV17Theme.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-2, -2));
        a.gap(card, 8);
        TextView sub =
                a.text(
                        "点下面的按钮，屏幕上会出现二维码；用手机夸克 App 扫一下，确认后这里就会列出你的文件和视频。",
                        13f,
                        TvV17Theme.MUTED);
        sub.setLineSpacing(a.dp(4), 1f);
        card.addView(sub, new LinearLayout.LayoutParams(a.dp(520), -2));
        a.gap(card, 16);
        Button login =
                a.navButton("扫码登录夸克", "netdisk-login", () -> QuarkLoginDialog.show(a, this::reloadSelf));
        card.addView(login, new LinearLayout.LayoutParams(a.dp(200), a.dp(44)));
        a.tvInitialFocus = login;
        LinearLayout wrap = a.row();
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(card, new LinearLayout.LayoutParams(-2, -2));
        gridHost.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderGrid(
            QuarkDriveClient client, List<QuarkDriveClient.Entry> rows, String error) {
        gridHost.removeAllViews();
        if (error != null) {
            status.setText(error);
            return;
        }
        if (rows == null || rows.isEmpty()) {
            status.setText("这个文件夹是空的");
            return;
        }
        List<QuarkDriveClient.Entry> folders = new ArrayList<>();
        List<QuarkDriveClient.Entry> videos = new ArrayList<>();
        List<QuarkDriveClient.Entry> others = new ArrayList<>();
        for (QuarkDriveClient.Entry row : rows) {
            if (row.dir) folders.add(row);
            else if (client.isVideoName(row.name)) videos.add(row);
            else others.add(row);
        }
        status.setText(
                "文件夹 " + folders.size() + " · 视频 " + videos.size()
                        + (others.isEmpty() ? "" : " · 其它 " + others.size()));
        float density = Math.max(1f, a.getResources().getDisplayMetrics().density);
        int cols = Math.max(3, Math.min(6, (int) (a.windowWidth() / density / 220)));
        int gap = a.dp(16);
        int cardW = Math.max(a.dp(150), (a.windowWidth() - a.dp(96) - gap * (cols - 1)) / cols);

        View first = null;
        List<QuarkDriveClient.Entry> all = new ArrayList<>();
        all.addAll(folders);
        all.addAll(videos);
        all.addAll(others);
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % cols == 0) {
                row = a.row();
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = a.dp(16);
                gridHost.addView(row, rlp);
            }
            QuarkDriveClient.Entry entry = all.get(i);
            boolean playable = !entry.dir && client.isVideoName(entry.name);
            View card = buildCard(entry, playable, cardW);
            if (first == null) first = card;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % cols < cols - 1) clp.rightMargin = gap;
            row.addView(card, clp);
        }
        if (row != null) {
            int rem = all.size() % cols;
            for (int k = rem; rem > 0 && k < cols; k++) {
                row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        if (first != null) a.tvInitialFocus = first;
    }

    private View buildCard(QuarkDriveClient.Entry entry, boolean playable, int width) {
        LinearLayout card = a.vertical();
        card.setClipChildren(false);
        LinearLayout face = a.vertical();
        face.setGravity(Gravity.CENTER);
        int faceH = Math.round(width * (playable ? 0.62f : 0.5f));
        String badge = entry.dir ? "文件夹" : playable ? "视频" : "文件";
        TextView icon = a.text(badge, 15, 0xccffffff);
        icon.setTypeface(null, Typeface.BOLD);
        face.addView(icon, new LinearLayout.LayoutParams(-2, -2));
        if (entry.size > 0) {
            TextView size = a.text(size(entry.size), 11.5f, 0xd0ffffff);
            size.setPadding(0, a.dp(2), 0, 0);
            face.addView(size, new LinearLayout.LayoutParams(-2, -2));
        }
        face.setBackground(a.bg(entry.dir ? 0x2e3b82f6 : playable ? 0x2e34d399 : 0x1affffff, 14));
        card.addView(face, new LinearLayout.LayoutParams(-1, faceH));
        TextView name = a.text(entry.name, 12f, TvV17Theme.TEXT);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(a.dp(2), a.dp(7), a.dp(2), 0);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));

        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(
                v -> {
                    if (entry.dir) {
                        a.open(
                                new MainActivity.Screen(
                                        () ->
                                                new NetdiskScreen(a, driveKey, entry.fid, entry.name)
                                                        .show()),
                                true);
                    } else if (playable) {
                        play(entry);
                    } else {
                        a.toast("这个文件不是视频，暂时只能在夸克 App 里打开");
                    }
                });
        card.setOnFocusChangeListener(
                (v, focused) -> {
                    float scale = focused ? 1.05f : 1f;
                    card.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                    face.setBackground(
                            a.bg(
                                    focused
                                            ? (entry.dir ? 0x553b82f6 : playable ? 0x5534d399 : 0x33ffffff)
                                            : (entry.dir
                                                    ? 0x2e3b82f6
                                                    : playable ? 0x2e34d399 : 0x1affffff),
                                    14));
                });
        return card;
    }

    private void play(final QuarkDriveClient.Entry entry) {
        // -1: no manual tier choice. NetdiskQualityGuard then skips tiers this device already
        // failed to decode (4K first unless the device memory says otherwise).
        play(entry, -1);
    }

    /**
     * Native netdisk playback entry point.
     *
     * @param manualRank 1..6 when the user explicitly picked a tier for this file. A manual 4K
     *     choice (6) wins over the guard's device memory; -1 means automatic. The browser has no
     *     quality picker yet, so today only -1 is passed.
     */
    private void play(final QuarkDriveClient.Entry entry, final int manualRank) {
        QuarkQrLogin.Credential credential = QuarkQrLogin.loadSaved();
        if (credential == null) {
            a.toast("先登录夸克");
            return;
        }
        // A decision queued for the previous netdisk playback must not reopen it after this click.
        NetdiskQualityGuard.disarm();
        a.toast("正在取播放地址…");
        final QuarkDriveClient client = new QuarkDriveClient(credential.cookie);
        final int startRank = NetdiskQualityGuard.preferredStartRank(a, manualRank);
        // Only an automatic start may relax upward when the remembered memory leaves no tier;
        // a manual pick must not silently turn 2K into 4K.
        startNetdiskPlayback(entry, credential.cookie, client, startRank, 0, 0, manualRank < 0);
    }

    /**
     * Resolves one URL and (re)opens the player. The first play and the guard's automatic downgrade
     * share this path so headers, episode flags and the quality memory stay in one place.
     *
     * @param requestedRank highest tier to accept (4k=6 ... normal=1)
     * @param downgrades automatic downgrades already spent on this playback, 0 on the first play
     * @param fromRank tier being left when this call is a downgrade, 0 on the first play
     * @param relaxMissingTier true only for an automatic start: when no tier at or below
     *     {@code requestedRank} exists, play the only offered tier instead of failing
     */
    private void startNetdiskPlayback(
            final QuarkDriveClient.Entry entry,
            final String cookie,
            final QuarkDriveClient client,
            final int requestedRank,
            final int downgrades,
            final int fromRank,
            final boolean relaxMissingTier) {
        WORKER.execute(
                () -> {
                    QuarkDriveClient.PlayVariant variant = null;
                    String error = null;
                    try {
                        variant = client.playVariant(entry.fid, requestedRank);
                        if (variant == null
                                && relaxMissingTier
                                && requestedRank < NetdiskQualityGuard.RANK_4K) {
                            // The remembered tier left no lower variant (the file only has 4K), so
                            // play what exists instead of refusing to play. The guard re-reads the
                            // real rank and only reports later misses; it does not loop.
                            variant = client.playVariant(entry.fid, NetdiskQualityGuard.RANK_4K);
                        }
                    } catch (Throwable t) {
                        error = t.getMessage() == null ? "取播放地址失败" : t.getMessage();
                    }
                    final QuarkDriveClient.PlayVariant done = variant;
                    final String err = error;
                    a.ui.post(
                            () ->
                                    onPlaybackResolved(
                                            entry,
                                            cookie,
                                            client,
                                            done,
                                            err,
                                            downgrades,
                                            fromRank,
                                            relaxMissingTier));
                });
    }

    /** Runs on the UI thread after the URL (or its error) is known. */
    private void onPlaybackResolved(
            final QuarkDriveClient.Entry entry,
            final String cookie,
            final QuarkDriveClient client,
            final QuarkDriveClient.PlayVariant variant,
            final String error,
            final int downgrades,
            final int fromRank,
            final boolean relaxMissingTier) {
        if (error != null) {
            // Keep the user's current playback untouched when an automatic switch fails.
            if (fromRank > 0) a.toast("切换画质失败：" + error);
            else a.notice(error);
            return;
        }
        if (variant == null || variant.url == null || variant.url.isEmpty()) {
            if (fromRank > 0) a.toast("没有更低一档可切，继续用当前画质");
            else if (relaxMissingTier) a.notice("没拿到播放地址");
            else a.toast("这个文件没有所选画质，换一个档位试试");
            return;
        }
        if (fromRank > 0 && (variant.rank <= 0 || variant.rank >= fromRank)) {
            // The service has no lower tier for this file: keep playing the current one.
            a.toast("没有更低一档可切，继续用当前画质");
            return;
        }
        Cms.Site site = new Cms.Site();
        site.key = "netdisk-native";
        site.name = "我的夸克网盘";
        Cms.Video video = new Cms.Video();
        video.id = entry.fid;
        video.title = entry.name;
        Cms.Episode episode = new Cms.Episode();
        episode.name = entry.name;
        episode.url = variant.url;
        try {
            episode.media.put("_noCache", true);
            // MPV handles high-bitrate 4K on this box far better than the Media3/MediaCodec path;
            // netdisk files opt in. When MPV is unavailable this episode falls back to MoviePlayer,
            // whose PlayStats listener feeds NetdiskQualityGuard's drop-rate detection.
            // 网盘直链不再强制 mpv：盒子上的 mpv 打开这类签名直链会 audio=eof 卡住；
                // media3 + 默认优先 AAC 音轨已经能播（12:42、15:29 实测 4K 出画面）。
                episode.media.put("_forceMpv", false);
            // Only native netdisk playback carries this marker. NetdiskQualityGuard.arm refuses
            // every episode without it, so ordinary web lines are never silently downgraded.
            episode.media.put("_netdiskNative", true);
        } catch (org.json.JSONException ignored) {
            // org.json's checked exception; the flag is optional.
        }
        episode.headers.put("Cookie", cookie);
        episode.headers.put("User-Agent", "Mozilla/5.0");
        episode.headers.put("Referer", "https://pan.quark.cn/");
        a.openPlayer(site, video, episode);
        if (fromRank > 0 && variant.rank > 0) {
            a.toast(
                    "设备跑不动"
                            + NetdiskQualityGuard.labelOfRank(fromRank)
                            + "，已切换到"
                            + NetdiskQualityGuard.labelOfRank(variant.rank));
        }
        NetdiskQualityGuard.arm(
                a,
                episode,
                variant.rank,
                variant.resolution,
                downgrades,
                new NetdiskQualityGuard.Hooks() {
                    @Override
                    public void restart(int maxRank, int nextDowngrades, int from) {
                        if (a.isFinishing()) return;
                        startNetdiskPlayback(
                                entry, cookie, client, maxRank, nextDowngrades, from, false);
                    }

                    @Override
                    public void hint(String message) {
                        a.toast(message);
                    }
                });
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1073741824L) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824d);
        }
        if (bytes >= 1048576L) {
            return String.format(Locale.ROOT, "%.0f MB", bytes / 1048576d);
        }
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024d);
    }
}
