package com.smali_generator.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.smali_generator.db.Avatars;

import java.util.Locale;

/**
 * The circle a person is drawn as, wherever the patcher lists people.
 *
 * Shared between the screens rather than copied into each, because the
 * fallback is the interesting half and it has rules: WhatsApp holds a photo
 * for a small minority of the people a big group contains -- 160 files against
 * 5,778 contacts on the test device -- so the coloured disc is the common case
 * and not a fallback nobody sees.
 */
public final class Avatar {

    /**
     * The discs a person with no photo is drawn as.
     *
     * Muted enough to carry white text, and picked by the jid rather than by
     * the row's position, so somebody keeps their colour when the list is
     * filtered, unfolded or crossed with another group.
     */
    private static final int[] DISC_COLOURS = {
            0xFF5B7C99, 0xFF7E6B8F, 0xFF4F7A5B, 0xFF9C6B4E,
            0xFF7A5C5C, 0xFF3F6E7A, 0xFF8A7A4E, 0xFF6B5E8C,
    };

    private static final int MARGIN_END_DP = 12;

    private Avatar() {
    }

    /**
     * Somebody's photo, or a coloured disc bearing their initial.
     *
     * A null {@code key} means this device's owner, whose photo is kept under
     * a name of its own.
     */
    public static View of(Context context, Avatars avatars, String key, String name, int sizeDp) {
        Bitmap photo = avatars == null ? null : (key == null ? avatars.mine() : avatars.photo(key));
        View view;
        if (photo != null) {
            ImageView image = new ImageView(context);
            image.setImageBitmap(photo);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view = image;
        } else {
            TextView initial = new TextView(context);
            initial.setText(initialOf(name));
            initial.setTextColor(Color.WHITE);
            initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f);
            initial.setGravity(Gravity.CENTER);
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(discColour(key == null ? name : key));
            initial.setBackground(disc);
            view = initial;
        }
        // Clipped to an oval rather than masked into the bitmap: it costs
        // nothing, and the same three lines round off the drawn disc too.
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View clipped, Outline outline) {
                outline.setOval(0, 0, clipped.getWidth(), clipped.getHeight());
            }
        });
        view.setClipToOutline(true);
        int size = Palette.dp(context, sizeDp);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginEnd(Palette.dp(context, MARGIN_END_DP));
        view.setLayoutParams(params);
        return view;
    }

    /**
     * The first letter of a name, or nothing at all.
     *
     * Names here are "number - pushname", so the first character is a digit
     * for everybody and the first *letter* is the first character of what the
     * person calls themselves. Somebody WhatsApp has no name for gets a bare
     * disc: a digit lifted out of a phone number would look like an initial
     * and mean nothing.
     */
    static String initialOf(String name) {
        if (name == null) {
            return "";
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetter(name.charAt(i))) {
                return name.substring(i, i + 1).toUpperCase(Locale.getDefault());
            }
        }
        return "";
    }

    private static int discColour(String key) {
        // Masked rather than Math.abs: abs(Integer.MIN_VALUE) is still negative.
        return DISC_COLOURS[(key == null ? 0 : key.hashCode() & 0x7FFFFFFF) % DISC_COLOURS.length];
    }
}
