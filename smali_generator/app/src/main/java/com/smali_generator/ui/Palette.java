package com.smali_generator.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.CompoundButton;

/**
 * The look shared by the patcher's screens.
 *
 * These screens are built in code, so there is no style resource to hold this;
 * without somewhere shared, a second screen means a second copy of the palette
 * that drifts from the first. Colours are explicit rather than theme
 * attributes: the module's resource table is not WhatsApp's, and the framework
 * theme this runs under reads washed out against the background.
 */
public final class Palette {

    /** WhatsApp's green, so the controls read as part of the app they live in. */
    public static final int ACCENT = 0xFF25D366;

    /** The off state. A mid grey rather than a theme colour: it has to sit on
     *  both the light and the dark background these screens can be shown on. */
    public static final int NEUTRAL = 0xFF9E9E9E;

    /** WhatsApp puts dark text on its own green buttons; so do these. */
    public static final int ON_ACCENT = 0xFF0B141A;

    private static final int TEXT_PRIMARY_DARK = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY_DARK = 0xFFCFD6DB;
    private static final int TEXT_PRIMARY_LIGHT = 0xFF0B141A;
    private static final int TEXT_SECONDARY_LIGHT = 0xFF3B4A54;

    private Palette() {
    }

    public static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int primaryText(Context context) {
        return isNight(context) ? TEXT_PRIMARY_DARK : TEXT_PRIMARY_LIGHT;
    }

    public static int secondaryText(Context context) {
        return isNight(context) ? TEXT_SECONDARY_DARK : TEXT_SECONDARY_LIGHT;
    }

    public static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    public static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Green when on, grey when off, and a dimmed green when on but locked --
     * a mandatory hook should read as switched on, not as switched off.
     */
    public static void paintSwitch(android.widget.Switch toggle) {
        toggle.setThumbTintList(states(ACCENT, NEUTRAL, alpha(ACCENT, 0x8A), alpha(NEUTRAL, 0x61)));
        toggle.setTrackTintList(states(alpha(ACCENT, 0x7A), alpha(NEUTRAL, 0x52),
                alpha(ACCENT, 0x47), alpha(NEUTRAL, 0x33)));
    }

    /** Checkboxes and radio buttons carry the accent in one colour, not two. */
    public static void paintCheckable(CompoundButton button) {
        button.setButtonTintList(states(ACCENT, NEUTRAL, alpha(ACCENT, 0x8A), alpha(NEUTRAL, 0x61)));
    }

    private static ColorStateList states(int on, int off, int onLocked, int offLocked) {
        int[][] specs = {
                {-android.R.attr.state_enabled, android.R.attr.state_checked},
                {-android.R.attr.state_enabled},
                {android.R.attr.state_checked},
                new int[0],
        };
        return new ColorStateList(specs, new int[]{onLocked, offLocked, on, off});
    }

    /** A solid green pill with a press ripple, drawn rather than themed. */
    public static Drawable pill(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(context, 26));
        shape.setColor(ACCENT);
        return new RippleDrawable(ColorStateList.valueOf(alpha(ON_ACCENT, 0x33)), shape, null);
    }

    /**
     * The pill a filter chip is drawn as, filled when it is the one in use.
     *
     * Outlined rather than solid when it is not: a row of solid chips reads as
     * a row of buttons, and only one of them is a state.
     */
    public static Drawable chip(Context context, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(context, 16));
        shape.setColor(selected ? alpha(ACCENT, 0x33) : 0x00000000);
        shape.setStroke(Math.max(1, dp(context, 1)), selected ? ACCENT : alpha(NEUTRAL, 0x80));
        return new RippleDrawable(ColorStateList.valueOf(alpha(ACCENT, 0x33)), shape, null);
    }

    /** The press highlight used by rows that open something. */
    public static Drawable rowRipple(Context context) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(ACCENT, 0x33)), null, null);
    }
}
