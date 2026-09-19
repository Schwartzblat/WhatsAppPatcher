package com.smali_generator.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Building a row that passes for one of the host app's.
 *
 * Shared by every screen this patch injects a row into. WhatsApp's own row
 * layouts are not ours to inflate, and a layout of ours would arrive carrying
 * the module's theme rather than the host screen's -- so a row is built in
 * code and borrows its typography and metrics from a real row beside it, which
 * is what makes it follow whatever theme, font scale and dark mode are in
 * force.
 *
 * The icon goes in the column the text inset already reserves. Every row
 * around ours draws one there, so the space was being paid for either way, and
 * a blank column is what made an injected row read as a row that had lost its
 * icon rather than one that never had one. The column is measured off the
 * neighbour too, for the same reason its text inset is.
 */
public final class InjectedRow {

    /** What a row's icon is drawn at when its neighbour's cannot be measured. */
    private static final int DEFAULT_ICON_DP = 24;

    /** Sizes outside this are not an icon: too small to see, or an avatar. */
    private static final int MIN_ICON_DP = 12;
    private static final int MAX_ICON_DP = 32;

    /** Below this much clear space, the column is not an icon column at all. */
    private static final int MIN_ICON_GAP_DP = 8;

    private InjectedRow() {
    }

    /**
     * A row with a title, a subtitle and an icon, styled off a real row.
     *
     * {@code template} is the neighbouring row everything is measured and
     * copied from, and may be null; the sp figures and the centred icon column
     * are only reached then. A null {@code icon}, or a {@code startInset} with
     * no room in it, gives the row the text-only shape it had before icons:
     * the alternative is an icon that pushes our label out of line with every
     * label above it, which is worse than no icon.
     */
    public static View build(Context context, String title, String subtitle,
                             View template, Drawable icon,
                             int startInset, int verticalPadding,
                             Object tag, Runnable onClick) {
        TextView titleTemplate = template == null ? null : firstTextView(template);
        TextView subtitleTemplate =
                titleTemplate == null ? null : secondTextView(template, titleTemplate);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tag);
        row.setClickable(true);
        row.setFocusable(true);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applySelectableBackground(context, row);
        row.setOnClickListener(view -> onClick.run());

        int[] column = icon == null ? null : iconColumn(context, template, startInset);
        // With no icon the whole column is padding, exactly as it was before.
        row.setPaddingRelative(column == null ? startInset : 0, verticalPadding,
                dp(context, 16), verticalPadding);
        if (column != null) {
            row.addView(iconView(context, icon, column, startInset));
        }

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(context, title, titleTemplate, 16f, 1f));
        if (subtitle != null && !subtitle.isEmpty()) {
            text.addView(label(context, subtitle,
                    subtitleTemplate != null ? subtitleTemplate : titleTemplate,
                    13f, subtitleTemplate != null ? 1f : 0.7f));
        }
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * The icon, sized and placed by margins rather than by gravity.
     *
     * The margins are what hold the label at {@code startInset}: the icon's
     * own box plus the space either side of it add up to the column, so the
     * text after it starts exactly where the text of every other row does.
     */
    private static View iconView(Context context, Drawable icon, int[] column, int startInset) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(icon);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(column[1], column[1]);
        params.setMarginStart(column[0]);
        params.setMarginEnd(startInset - column[0] - column[1]);
        view.setLayoutParams(params);
        return view;
    }

    /**
     * Where a native row draws its icon, as {@code {start, size}} in pixels
     * from the row's leading edge.
     *
     * Measured rather than assumed, for the same reason the text inset is:
     * this is WhatsApp's column, and an icon a few pixels off the one the rows
     * above it use is more conspicuous than no icon at all. A row with no icon
     * of its own, or one not laid out yet, falls back to the middle of the
     * column; a column too narrow to hold an icon returns null, and the row
     * goes out without one.
     */
    private static int[] iconColumn(Context context, View template, int startInset) {
        ImageView image = template == null ? null : firstImageView(template);
        if (image == null || !image.isShown()
                || image.getWidth() <= 0 || image.getHeight() <= 0
                || template.getWidth() <= 0) {
            return centredColumn(context, startInset);
        }
        int[] rowAt = new int[2];
        int[] iconAt = new int[2];
        template.getLocationOnScreen(rowAt);
        image.getLocationOnScreen(iconAt);
        int start = template.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? (rowAt[0] + template.getWidth()) - (iconAt[0] + image.getWidth())
                : iconAt[0] - rowAt[0];
        int size = Math.min(image.getWidth(), image.getHeight());
        // The last test is what rejects a trailing chevron or a switch: the
        // leading icon is the one that fits inside the text inset.
        if (start < 0 || size < dp(context, MIN_ICON_DP) || size > dp(context, MAX_ICON_DP)
                || start + size > startInset) {
            return centredColumn(context, startInset);
        }
        return new int[]{start, size};
    }

    private static int[] centredColumn(Context context, int startInset) {
        int size = dp(context, DEFAULT_ICON_DP);
        if (startInset < size + dp(context, MIN_ICON_GAP_DP)) {
            return null;
        }
        return new int[]{(startInset - size) / 2, size};
    }

    /**
     * A label that matches a native one.
     *
     * The size and colour are copied in pixels straight off the template, so
     * they are already whatever the current theme, font scale and dark mode
     * made them.
     */
    public static TextView label(Context context, String text, TextView template,
                                 float fallbackSp, float alpha) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setAlpha(alpha);
        if (template == null) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fallbackSp);
            return view;
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, template.getTextSize());
        view.setTextColor(template.getTextColors());
        view.setTypeface(template.getTypeface());
        view.setLetterSpacing(template.getLetterSpacing());
        view.setIncludeFontPadding(template.getIncludeFontPadding());
        return view;
    }

    public static TextView firstTextView(View view) {
        if (view instanceof TextView) {
            return (TextView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = firstTextView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static TextView secondTextView(View view, TextView first) {
        if (view instanceof TextView) {
            return view == first ? null : (TextView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = secondTextView(group.getChildAt(i), first);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ImageView firstImageView(View view) {
        if (view instanceof ImageView) {
            return (ImageView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = firstImageView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static void applySelectableBackground(Context context, View view) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, value, true) && value.resourceId != 0) {
            view.setBackgroundResource(value.resourceId);
        }
    }

    public static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
