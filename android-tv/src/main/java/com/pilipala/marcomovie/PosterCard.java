package com.pilipala.marcomovie;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The parts every poster card writes the same way: the artwork frame with its Douban badge and
 * detail prefetch, the "N 源" corner pill, the title line and the TV focus styling.
 *
 * <p>Layout stays with the callers on purpose: card size, grid cell, title font/padding, the search
 * page's origin line and the phone focus variants differ per screen, so this class only owns the
 * pieces that were duplicated verbatim. Every visual value a caller passes here is the literal that
 * screen used before the extraction.
 *
 * <p>Presentation only, like {@link TvV17PosterCard}: callers own sizing, image loading, identity
 * and actions.
 */
final class PosterCard {
    private PosterCard() {}

    /**
     * The poster frame: TV/phone artwork styling, the Douban badge and the detail prefetch. The
     * caller sizes and adds the returned frame, then loads the placeholder/real artwork itself.
     *
     * @param video metadata video whose Douban score the badge binds (the card's own object)
     * @param site site to prefetch from; null means no prefetch
     * @param prefetchVideo the listing to prefetch — the site's own object, which can differ from
     *     {@code video} on the wall when the ledger's default site is not the sampled one
     * @param phoneBackground phone-only artwork background (the two grids use different values)
     * @param phoneRadius phone-only artwork corner radius
     */
    static FrameLayout artwork(
            MainActivity a,
            ImageView poster,
            Cms.Video video,
            Cms.Site site,
            Cms.Video prefetchVideo,
            int phoneBackground,
            int phoneRadius) {
        if (a == null || poster == null) return null;
        if (a.isTvDevice()) {
            TvV17PosterCard.artwork(a, poster);
        } else {
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(a.bg(phoneBackground, phoneRadius));
            poster.setClipToOutline(true);
        }
        boolean showDoubanBadge = !SourceController.isConfigOrNetdiskActionCard(site, prefetchVideo);
        FrameLayout wrap =
                TvV17PosterCard.wrapArtworkWithDoubanBadge(a, poster, video, showDoubanBadge);
        // Warm this card's detail while the user is still browsing, so pressing OK is instant.
        SourceController.prefetchVisible(a, site, prefetchVideo);
        return wrap;
    }

    /**
     * The bottom-left "N 源" pill: updates the existing one when the card already carries it,
     * otherwise attaches it. No-op for a zero count (the wall never draws a "0 源" badge).
     */
    static void attachCount(MainActivity a, FrameLayout wrap, int count) {
        if (a == null || wrap == null || count <= 0) return;
        View existing = wrap.findViewWithTag("card-source-count");
        if (existing instanceof TextView) {
            ((TextView) existing).setText(count + " 源");
            return;
        }
        TextView badge = a.text(count + " 源", 10.5f, 0xffe8eef6);
        badge.setTag("card-source-count");
        badge.setSingleLine(true);
        badge.setIncludeFontPadding(false);
        badge.setPadding(a.dp(6), a.dp(2.5f), a.dp(6), a.dp(2.5f));
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(0xd010141c);
        background.setCornerRadius(a.dp(6));
        background.setStroke(Math.max(1, a.dp(0.8f)), 0x443b82f6);
        badge.setBackground(background);
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        params.leftMargin = a.dp(6);
        params.bottomMargin = a.dp(6);
        wrap.addView(badge, params);
    }

    /**
     * The title line under the artwork. Font size, colour and padding are the caller's literals;
     * {@code heightDp > 0} adds it with a fixed height (wall), {@code 0} adds it with the parent's
     * default parameters (search results), matching what each screen did before.
     */
    static TextView title(
            MainActivity a,
            LinearLayout card,
            String text,
            float size,
            int color,
            int leftDp,
            int topDp,
            int rightDp,
            int heightDp) {
        TextView title = a.text(text, size, color);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(a.dp(leftDp), a.dp(topDp), a.dp(rightDp), 0);
        if (heightDp > 0) {
            card.addView(title, new LinearLayout.LayoutParams(-1, a.dp(heightDp)));
        } else {
            card.addView(title);
        }
        return title;
    }

    /**
     * Focus styling for a poster card. On TV this is the focus-ring group plus
     * {@link TvV17PosterCard#focus}; the group assignment is idempotent, so the build-time attach
     * and this call never fight each other.
     *
     * <p>Phones keep their own {@code FocusMotion} variant at each call site: the wall and the
     * search grid use different radii, focus fills and title colours, and this axis must not change
     * any of those values.
     */
    static void focus(
            MainActivity a, View card, TextView title, boolean focused, String section) {
        if (a == null || card == null || title == null || !a.isTvDevice()) return;
        TvFocusFrame.attachCard(card, section);
        TvV17PosterCard.focus(a, card, title, focused);
    }
}
