package com.smali_generator;

/**
 * A section of the settings screen.
 *
 * Declaration order is display order, and a section with no hooks in it is not
 * drawn, so a category can be added here before anything uses it. The locked
 * hooks come last: the ones worth reading are the ones you can act on.
 *
 * Adding a feature means adding a constant here and returning it from
 * {@link Hook#category()} -- the screen needs no change.
 */
public enum HookCategory {
    PRIVACY("Privacy", "What others can see about you."),
    MESSAGES("Messages", "How messages are shown."),
    SEARCH("Search", "How search works."),
    INTERFACE("Interface", "What's on screen."),
    UNLOCKS("Unlocks", "Features the app charges for."),
    OTHER("Other", ""),
    MANDATORY("Mandatory", "Always on. The patched app needs these.");

    private final String title;
    private final String caption;

    HookCategory(String title, String caption) {
        this.title = title;
        this.caption = caption;
    }

    public String title() {
        return title;
    }

    public String caption() {
        return caption;
    }
}
