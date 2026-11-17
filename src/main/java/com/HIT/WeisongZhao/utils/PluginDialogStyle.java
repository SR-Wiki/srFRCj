package com.HIT.WeisongZhao.utils;

import java.awt.Choice;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.TextField;

import ij.gui.GenericDialog;

/**
 * Keep Fiji parameter dialogs legible without imposing oversized windows.
 * AWT/GenericDialog measures each dialog from its own text and controls.
 * This class adjusts font and control height only, then lets pack() determine
 * the appropriate width and height for that specific dialog.
 */
public final class PluginDialogStyle {
    private static final int LABEL_FONT_SIZE = 15;
    private static final int FIELD_MIN_HEIGHT = 28;
    private static final int NUMBER_MIN_WIDTH = 86;
    private static final int CHOICE_MIN_WIDTH = 96;
    private static final int CHOICE_MAX_WIDTH = 520; // Avoid extremely long TIFF names widening the dialog indefinitely.

    private PluginDialogStyle() { }

    /**
     * @param advanced Retained for source compatibility with existing callers;
     *                 sizing is content-based for both basic and advanced dialogs.
     */
    public static void apply(GenericDialog dialog, boolean advanced) {
        styleComponents(dialog);
        // Clear any explicit size constraints; each dialog now uses the actual
        // preferred dimensions of its labels, explanatory text and controls.
        dialog.setMinimumSize(null);
        dialog.setPreferredSize(null);
        dialog.invalidate();
        dialog.pack();
    }

    private static void styleComponents(Component component) {
        Font original = component.getFont();
        if (original != null) {
            component.setFont(original.deriveFont((float) Math.max(
                    original.getSize(), LABEL_FONT_SIZE)));
        } else {
            component.setFont(new Font("SansSerif", Font.PLAIN, LABEL_FONT_SIZE));
        }
        if (component instanceof TextField) {
            // Re-measure after changing the font, not from the old font metrics.
            component.setPreferredSize(null);
            Dimension measured = component.getPreferredSize();
            component.setPreferredSize(new Dimension(
                    Math.max(measured.width, NUMBER_MIN_WIDTH),
                    Math.max(measured.height, FIELD_MIN_HEIGHT)));
        } else if (component instanceof Choice) {
            component.setPreferredSize(null);
            Dimension measured = component.getPreferredSize();
            component.setPreferredSize(new Dimension(
                    Math.min(CHOICE_MAX_WIDTH, Math.max(measured.width, CHOICE_MIN_WIDTH)),
                    Math.max(measured.height, FIELD_MIN_HEIGHT)));
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                styleComponents(child);
            }
        }
    }
}
