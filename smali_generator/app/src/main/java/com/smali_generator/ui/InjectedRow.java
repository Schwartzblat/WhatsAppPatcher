package com.smali_generator.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Building a row that passes for one of the host app's.
 *
 * Shared by every screen this patch injects a row into. WhatsApp's own rows
 * cannot be inflated -- the module's resource table is not merged into the
 * host's -- so a row is built in code and borrows its typography and metrics
 * from a real row beside it, which is what makes it follow whatever theme,
 * font scale and dark mode are in force.
 */
public final class InjectedRow {

    private InjectedRow() {
    }

    /**
     * A row with a title and a subtitle, styled off the templates given.
     *
     * Both templates may be null; the sp figures are only reached then.
     */
    public static View build(Context context, String title, String subtitle,
                             TextView titleTemplate, TextView subtitleTemplate,
                             int startInset, int verticalPadding,
                             Object tag, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tag);
        row.setClickable(true);
        row.setFocusable(true);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPaddingRelative(startInset, verticalPadding,
                dp(context, 16), verticalPadding);
        applySelectableBackground(context, row);

        row.addView(label(context, title, titleTemplate, 16f, 1f));
        if (subtitle != null && !subtitle.isEmpty()) {
            row.addView(label(context, subtitle,
                    subtitleTemplate != null ? subtitleTemplate : titleTemplate,
                    13f, subtitleTemplate != null ? 1f : 0.7f));
        }
        row.setOnClickListener(view -> onClick.run());
        return row;
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
