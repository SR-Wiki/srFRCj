package com.HIT.WeisongZhao.utils;

import java.util.Locale;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;

import ij.ImageStack;
import ij.process.ColorProcessor;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageListener;
import ij.process.ImageProcessor;

/**
 * Display a quantitative srFRC map with ImageJ's native Calibration Bar,
 * following the style used by PANELJ: lower-right, black background,
 * white labels, four major values and bold type.
 *
 * The supplied image is an independent Float32 preview of the retained
 * resolution map. The calibration bar is an Overlay: neither the underlying
 * pixels nor the separately retained numerical map are modified. In
 * particular, unlike the old RGB-canvas approach, no wider synthetic image
 * and no second colorbar-only window are created.
 */
public final class ResolutionMapDisplay {
    private ResolutionMapDisplay() { }

    /** Apply the 0..max display scale to the preview without changing pixels. */
    public static double setResolutionDisplayRange(ImagePlus image) {
        double maximum = 0.0;
        for (int slice = 1; slice <= image.getStackSize(); slice++) {
            ImageProcessor ip = image.getStack().getProcessor(slice);
            for (int y = 0; y < ip.getHeight(); y++) {
                for (int x = 0; x < ip.getWidth(); x++) {
                    double value = ip.getf(x, y);
                    if (Double.isFinite(value) && value > maximum) maximum = value;
                }
            }
        }
        if (maximum <= 0.0) maximum = 1.0;
        image.setDisplayRange(0.0, maximum);
        return maximum;
    }

    /**
     * Show a single floating-point preview with a PANELJ-style colorbar.
     * Call this only for the display copy, not for the retained result map.
     * "overlay" is essential: ImageJ's Calibration Bar can otherwise flatten
     * an RGB image, replacing a quantitative preview with presentation pixels.
     */
    public static void showWithColorBar(ImagePlus preview) {
        double maximum = setResolutionDisplayRange(preview);
        preview.setProperty("srFRC.visualizationColorbar", "ImageJ Calibration Bar (PANELJ style)");
        preview.setProperty("srFRC.colorbarMinimumNm", 0.0);
        preview.setProperty("srFRC.colorbarMaximumNm", maximum);

        // The map has already been restored to its final pixel dimensions.
        // ImageJ scales overlay text together with the image in a zoomed-out window;
        // compensate using a larger native overlay for large 2048-pixel maps.
        // No interpolation/resize is performed on any letter or bar pixels.
        int width = preview.getWidth();
        int height = preview.getHeight();
        double zoom = Math.max(1.0,
                Math.min(3.5, Math.max(width, height) / 680.0));
        String settings = "location=[Lower Right] fill=Black label=White "
                + "number=4 decimal=0 font=12 zoom="
                + String.format(Locale.US, "%.2f", zoom)
                + " bold overlay show";
        preview.show();
        try {
            IJ.run(preview, "Calibration Bar...", settings);
        } catch (RuntimeException ex) {
            // The map must still be accessible if ImageJ's optional display
            // command is unavailable or incompatible with the active GUI.
            IJ.log("srFRC: unable to add Calibration Bar: " + ex.getMessage());
        }
    }

    /** Maximum finite positive value in a SINGLE slice; zero is the lower limit. */
    public static double sliceMaximum(ImageProcessor ip) {
        double maximum = 0.0;
        for (int y = 0; y < ip.getHeight(); y++)
            for (int x = 0; x < ip.getWidth(); x++) {
                double value = ip.getf(x, y);
                if (Double.isFinite(value) && value > maximum) maximum = value;
            }
        return maximum > 0.0 ? maximum : 1.0;
    }

    /**
     * Scale each displayed Float32 slice independently, to match the matching
     * per-frame RGB colorbar image. Only the display range changes; the Float32
     * pixel array and quantitative results are not modified.
     *
     * ImageJ keeps a shared display range for a regular stack. Listen for
     * slice changes and update that range using precomputed slice maxima.
     */
    public static void enablePerFrameDisplayRange(final ImagePlus image) {
        final int count = image.getStackSize();
        if (count < 1) return;

        final double[] maxima = new double[count];
        for (int slice = 1; slice <= count; slice++) {
            maxima[slice - 1] = sliceMaximum(image.getStack().getProcessor(slice));
        }

        int initialSlice = Math.max(1, Math.min(count, image.getCurrentSlice()));
        image.setDisplayRange(0.0, maxima[initialSlice - 1]);
        if (count == 1) return;

        ImagePlus.addImageListener(new ImageListener() {
            private int lastSlice = initialSlice;
            private boolean updating = false;

            @Override
            public void imageOpened(ImagePlus imp) { }

            @Override
            public void imageUpdated(ImagePlus imp) {
                if (imp != image || updating) return;
                int slice = imp.getCurrentSlice();
                if (slice == lastSlice || slice < 1 || slice > count) return;

                updating = true;
                try {
                    lastSlice = slice;
                    imp.setDisplayRange(0.0, maxima[slice - 1]);
                    imp.updateAndDraw();
                } finally {
                    updating = false;
                }
            }

            @Override
            public void imageClosed(ImagePlus imp) {
                if (imp == image) ImagePlus.removeImageListener(this);
            }
        });
    }

    /**
     * Return a display-only RGB stack whose every slice has its OWN [0, maximum]
     * scale and labeled colorbar. This is intentionally RGB, unlike the raw
     * Float32 stack retained for analysis and further processing.
     *
     * Color is assigned after map restoration: map pixels are never interpolated,
     * and the bar and its labels are drawn at the final pixel dimensions.
     * Unlike a single ImageJ Overlay attached to an entire stack, this cannot
     * accidentally display one frame's numbers when browsing another frame.
     */
    public static ImagePlus perFrameColorBarStack(ImagePlus quantitativeStack, String title) {
        ImageStack input = quantitativeStack.getStack();
        ImageStack visual = new ImageStack(input.getWidth(), input.getHeight());
        // NJ_LUT.applyLUT_PANEL_rFRC() has already applied sJet to the source.
        ColorModel lut = quantitativeStack.getProcessor().getColorModel();
        double[] maxima = new double[input.getSize()];
        for (int slice = 1; slice <= input.getSize(); slice++) {
            ImageProcessor src = input.getProcessor(slice);
            double max = sliceMaximum(src);
            maxima[slice - 1] = max;
            BufferedImage canvas = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    double val = src.getf(x, y);
                    int colorIndex = (Double.isFinite(val) && val > 0.0)
                            ? (int) Math.round(Math.min(255.0, 255.0 * val / max)) : 0;
                    canvas.setRGB(x, y, lut.getRGB(colorIndex));
                }
            }
            paintFinalSizeColorbar(canvas, lut, max);
            int w = canvas.getWidth(), h = canvas.getHeight();
            int[] rgb = new int[w * h];
            canvas.getRGB(0, 0, w, h, rgb, 0, w);
            visual.addSlice(input.getSliceLabel(slice), new ColorProcessor(w, h, rgb));
        }
        ImagePlus result = new ImagePlus(title, visual);
        result.setCalibration(quantitativeStack.getCalibration().copy());
        result.setProperty("srFRC.colorbarScope", "per-slice (0 to finite positive maximum)");
        result.setProperty("srFRC.colorbarMaximumNmBySlice", maxima);
        result.setProperty("srFRC.visualizationOnly", "RGB, do not use for quantitative measurements");
        return result;
    }

    private static void paintFinalSizeColorbar(BufferedImage canvas, ColorModel lut, double max) {
        int w = canvas.getWidth(), h = canvas.getHeight();
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Scale everything with final resolution, avoiding tiny raster labels on 2K maps.
            int fontPx = Math.max(11, Math.min(34, Math.min(w, h) / 60));
            Font labels = new Font(Font.SANS_SERIF, Font.BOLD, fontPx);
            g.setFont(labels);
            FontMetrics fm = g.getFontMetrics();
            String title = "Resolution (nm)";
            String[] levels = new String[4];
            for (int tick = 0; tick < 4; tick++)
                levels[tick] = String.format(Locale.US, "%.0f", max * (3 - tick) / 3.0);
            int longest = fm.stringWidth(title);
            for (String level : levels) longest = Math.max(longest, fm.stringWidth(level));
            int padding = Math.max(8, fontPx / 2);
            int stripeWidth = Math.max(7, fontPx);
            int barHeight = Math.max(20, Math.min((int) (h * 0.34), fontPx * 13));
            int width = longest + stripeWidth + padding * 4;
            int height = barHeight + fontPx * 3 + padding * 3;
            // For very small images use an unobtrusive miniature legend.
            if (width > w - 4 || height > h - 4) {
                fontPx = Math.max(8, Math.min(12, Math.min(w, h) / 16));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontPx));
                fm = g.getFontMetrics();
                stripeWidth = Math.max(5, fontPx / 2);
                barHeight = Math.max(12, h / 4);
                longest = fm.stringWidth("nm");
                for (String level : levels) longest = Math.max(longest, fm.stringWidth(level));
                padding = 4;
                width = longest + stripeWidth + padding * 3;
                height = barHeight + fontPx * 2 + padding * 2;
                title = "nm";
            }
            int left = Math.max(0, w - width - Math.max(4, w / 70));
            int top = Math.max(0, h - height - Math.max(4, h / 70));
            g.setComposite(AlphaComposite.SrcOver.derive(0.88f));
            g.setColor(Color.BLACK);
            g.fillRoundRect(left, top, Math.min(width, w - left), Math.min(height, h - top), padding, padding);
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(Color.WHITE);
            g.drawString(title, left + padding, top + padding + fm.getAscent());
            int stripeX = left + padding;
            int stripeY = top + padding + fontPx + padding;
            for (int i = 0; i < barHeight; i++) {
                int idx = (int) Math.round(255.0 * (barHeight - 1 - i) / Math.max(1, barHeight - 1));
                g.setColor(new Color(lut.getRGB(idx), true));
                g.fillRect(stripeX, stripeY + i, stripeWidth, 1);
            }
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(Math.max(1f, fontPx / 12f)));
            g.drawRect(stripeX, stripeY, stripeWidth, barHeight);
            int labelX = stripeX + stripeWidth + padding;
            for (int tick = 0; tick < 4; tick++) {
                int y = stripeY + (int) Math.round(barHeight * tick / 3.0);
                g.drawLine(stripeX + stripeWidth, y, labelX - 2, y);
                g.drawString(levels[tick], labelX, Math.max(fm.getAscent(), y + fm.getAscent() / 3));
            }
        } finally { g.dispose(); }
    }

}
