package com.pilipala.marcomovie;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native 夸克网盘 QR login: one tap opens this, the app owns the whole exchange.
 *
 * <p>The legacy flow asked the spider config center to open its own login dialog, mirrored that
 * dialog's pixels into a bubble, and then guessed success from "did any file change on disk". That
 * is what produced "提示成功但播放不能用". Here the state machine is explicit —
 * {@code 生成中 / 等待扫码 / 已确认 / 已过期 / 失败} — and the only success signal is a credential
 * blob that {@link QuarkQrLogin} verified against the real account endpoint.
 *
 * <p>Runs on: TV and mobile shells. The dialog is D-pad friendly (buttons are focusable) and closes
 * itself on success so the caller can refresh the account panel.
 */
final class QuarkLoginDialog {

    /** One background thread for all QR work; requests are short and must not block the UI. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static final long POLL_INTERVAL_MS = 3000L;
    private static final long SUCCESS_DISMISS_MS = 1200L;

    private QuarkLoginDialog() {}

    /** Opens the sheet. {@code onChanged} runs on the UI thread after login/logout succeeds. */
    static void show(final MainActivity a, final Runnable onChanged) {
        if (a == null || a.isFinishing()) return;
        final QuarkQrLogin login = new QuarkQrLogin();

        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout panel = a.vertical();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(a.dp(26), a.dp(22), a.dp(26), a.dp(18));
        panel.setBackground(a.bg(0xff14141a, 22));

        TextView title = a.text("夸克网盘 · 扫码登录", 20, MainActivity.WHITE);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint =
                a.text("手机夸克 App → 右上角扫一扫。扫完在手机上点确认即可，这里会自动刷新。", 13,
                        MainActivity.MUTED);
        hint.setLineSpacing(a.dp(4), 1f);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        a.gap(panel, 14);

        final FrameLayout qrHolder = new FrameLayout(a);
        qrHolder.setBackground(a.bg(0xffffffff, 16));
        final int qrPx = a.dp(252);
        panel.addView(qrHolder, new LinearLayout.LayoutParams(qrPx, qrPx));

        a.gap(panel, 12);

        final TextView status = a.text("正在生成二维码…", 14.5f, 0xffe6e6ee);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        final TextView account =
                a.text(QuarkQrLogin.describe(QuarkQrLogin.loadSaved()), 12, MainActivity.MUTED);
        account.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(account, new LinearLayout.LayoutParams(-1, -2));

        a.gap(panel, 14);

        final LinearLayout actions = a.row();
        actions.setGravity(Gravity.CENTER);
        final Button refresh = a.navButton("刷新二维码", "quark-qr-refresh", () -> {});
        final Button logout = a.navButton("退出登录", "quark-qr-logout", () -> {});
        final Button close = a.navButton("完成", "quark-qr-close", dialog::dismiss);
        actions.addView(refresh, new LinearLayout.LayoutParams(a.dp(126), a.dp(40)));
        actions.addView(new android.view.View(a), new LinearLayout.LayoutParams(a.dp(10), 1));
        actions.addView(logout, new LinearLayout.LayoutParams(a.dp(126), a.dp(40)));
        actions.addView(new android.view.View(a), new LinearLayout.LayoutParams(a.dp(10), 1));
        actions.addView(close, new LinearLayout.LayoutParams(a.dp(126), a.dp(40)));
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        logout.setEnabled(QuarkQrLogin.loadSaved() != null);
        logout.setAlpha(logout.isEnabled() ? 1f : .4f);

        final int[] attempt = {0};
        final String[] token = {""};
        final String[] payload = {""};
        final Runnable[] pollTask = new Runnable[1];

        final Runnable stopPolling =
                () -> {
                    attempt[0]++;
                    if (pollTask[0] != null) a.ui.removeCallbacks(pollTask[0]);
                };

        final Runnable paintQr =
                () -> {
                    qrHolder.removeAllViews();
                    if (payload[0].isEmpty()) return;
                    try {
                        Bitmap bmp = qrBitmap(payload[0], qrPx);
                        if (bmp == null) return;
                        ImageView view = new ImageView(a);
                        view.setTag("quark-qr");
                        view.setContentDescription("夸克网盘扫码登录二维码");
                        view.setImageBitmap(bmp);
                        int pad = a.dp(10);
                        view.setPadding(pad, pad, pad, pad);
                        qrHolder.addView(
                                view, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                    } catch (Throwable t) {
                        AppLog.w("QuarkLogin", "qr render failed", t);
                    }
                };

        final Runnable begin =
                () -> {
                    stopPolling.run();
                    final int mine = attempt[0];
                    token[0] = "";
                    payload[0] = "";
                    qrHolder.removeAllViews();
                    status.setText("正在生成二维码…");
                    refresh.setEnabled(false);
                    WORKER.execute(
                            () -> {
                                String error = null;
                                QuarkQrLogin.QrSession session = null;
                                try {
                                    session = login.start();
                                } catch (Throwable t) {
                                    error = t.getMessage() == null ? "二维码生成失败" : t.getMessage();
                                }
                                final QuarkQrLogin.QrSession done = session;
                                final String err = error;
                                a.ui.post(
                                        () -> {
                                            if (!dialog.isShowing() || attempt[0] != mine) return;
                                            refresh.setEnabled(true);
                                            if (done == null) {
                                                status.setText(err);
                                                return;
                                            }
                                            token[0] = done.token;
                                            payload[0] = done.contentUrl;
                                            paintQr.run();
                                            status.setText("等待使用手机「夸克 App」扫码并确认…");
                                            a.ui.postDelayed(pollTask[0], POLL_INTERVAL_MS);
                                        });
                            });
                };

        pollTask[0] =
                () -> {
                    final int mine = attempt[0];
                    final String current = token[0];
                    if (current.isEmpty() || !dialog.isShowing()) return;
                    WORKER.execute(
                            () -> {
                                QuarkQrLogin.PollResult result;
                                try {
                                    result = login.poll(current);
                                } catch (Throwable t) {
                                    result =
                                            new QuarkQrLogin.PollResult(
                                                    QuarkQrLogin.Poll.ERROR, "网络异常，请重试", null);
                                }
                                final QuarkQrLogin.PollResult done = result;
                                a.ui.post(
                                        () -> {
                                            if (!dialog.isShowing() || attempt[0] != mine) return;
                                            status.setText(done.message);
                                            switch (done.state) {
                                                case CONFIRMED:
                                                    stopPolling.run();
                                                    boolean saved =
                                                            done.credential != null
                                                                    && QuarkQrLogin.saveSaved(
                                                                            done.credential);
                                                    if (!saved) {
                                                        status.setText("登录成功，但凭据写入失败");
                                                        refresh.setEnabled(true);
                                                        return;
                                                    }
                                                    account.setText(
                                                            QuarkQrLogin.describe(
                                                                    QuarkQrLogin.loadSaved()));
                                                    logout.setEnabled(true);
                                                    logout.setAlpha(1f);
                                                    AccountPanel.markDriveLoggedIn(
                                                            a, QuarkQrLogin.PROVIDER, true);
                                                    if (onChanged != null) onChanged.run();
                                                    a.ui.postDelayed(
                                                            () -> {
                                                                if (dialog.isShowing()) {
                                                                    dialog.dismiss();
                                                                }
                                                            },
                                                            SUCCESS_DISMISS_MS);
                                                    break;
                                                case WAITING:
                                                    a.ui.postDelayed(pollTask[0], POLL_INTERVAL_MS);
                                                    break;
                                                case EXPIRED:
                                                case ERROR:
                                                default:
                                                    stopPolling.run();
                                                    refresh.setEnabled(true);
                                                    break;
                                            }
                                        });
                            });
                };

        refresh.setOnClickListener(v -> begin.run());
        logout.setOnClickListener(
                v -> {
                    stopPolling.run();
                    // The QR on screen belonged to the previous session: clear it so nobody scans a
                    // code the app has stopped polling.
                    token[0] = "";
                    payload[0] = "";
                    qrHolder.removeAllViews();
                    QuarkQrLogin.clearSaved();
                    AccountPanel.markDriveLoggedIn(a, QuarkQrLogin.PROVIDER, false);
                    account.setText(QuarkQrLogin.describe(null));
                    logout.setEnabled(false);
                    logout.setAlpha(.4f);
                    refresh.setEnabled(true);
                    status.setText("已退出登录，点「刷新二维码」重新扫码");
                    if (onChanged != null) onChanged.run();
                });

        dialog.setContentView(panel);
        dialog.setOnDismissListener(
                d -> {
                    stopPolling.run();
                    token[0] = "";
                });
        dialog.show();
        final Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setTag("app-dialog");
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.min(a.dp(420), Math.max(a.dp(320), a.windowWidth() - a.dp(48)));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = .6f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(lp);
        }
        close.requestFocus();
        begin.run();
    }

    /** Renders the su.quark.cn login URL the phone will open. */
    private static Bitmap qrBitmap(String content, int px) {
        try {
            com.google.zxing.common.BitMatrix matrix =
                    new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px);
            int[] pixels = new int[px * px];
            for (int y = 0; y < px; y++) {
                for (int x = 0; x < px; x++) {
                    pixels[y * px + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Kept for callers that want the dp helper without pulling in MainActivity directly. */
    static int dp(MainActivity a, float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, a.getResources().getDisplayMetrics());
    }
}
