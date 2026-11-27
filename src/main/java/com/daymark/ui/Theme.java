package com.daymark.ui;

import javafx.scene.Parent;
import javafx.scene.control.Dialog;

import java.util.prefs.Preferences;

/**
 * Light / dark theme. Both themes live in daymark.css - dark mode is just the "dark"
 * style class on the top node of a window, which swaps the color variables.
 *
 * The choice is saved with java.util.prefs so the app opens the way you left it.
 */
public class Theme {

    private static final String STYLESHEET = "/com/daymark/ui/daymark.css";
    private static final String DARK_CLASS = "dark";
    private static final String DARK_KEY = "darkMode";

    private static final Preferences PREFS = openPreferences();
    private static volatile boolean sessionDark;

    private static Preferences openPreferences() {
        try {
            return Preferences.userNodeForPackage(Theme.class);
        } catch (SecurityException denied) {
            // The theme still works for this session on accounts without preference access.
            return null;
        }
    }

    public static boolean isDark() {
        return PREFS == null ? sessionDark : PREFS.getBoolean(DARK_KEY, false);
    }

    public static void setDark(boolean dark) {
        sessionDark = dark;
        if (PREFS != null) {
            PREFS.putBoolean(DARK_KEY, dark);
        }
    }

    // adds or removes the dark class so the node matches the saved choice
    public static void apply(Parent root) {
        root.getStyleClass().remove(DARK_CLASS);
        if (isDark()) {
            root.getStyleClass().add(DARK_CLASS);
        }
    }

    // dialogs are separate windows, so they need the stylesheet and the theme themselves
    public static void apply(Dialog<?> dialog) {
        String css = Theme.class.getResource(STYLESHEET).toExternalForm();
        if (!dialog.getDialogPane().getStylesheets().contains(css)) {
            dialog.getDialogPane().getStylesheets().add(css);
        }
        apply(dialog.getDialogPane());
    }
}
