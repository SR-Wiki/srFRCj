package com.HIT.WeisongZhao.utils;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;

/**
 * Utilities for reading physical pixel/voxel sizes from the active ImageJ image.
 * Values returned by this class are expressed in nanometres.
 */
public final class ImagePropertiesUtils {

    private static final double XY_MISMATCH_TOLERANCE = 0.01; // 1%

    private ImagePropertiesUtils() {
        // Utility class.
    }

    /**
     * Returns the calibrated XY pixel size in nm, or the supplied fallback when
     * the active image has no usable physical calibration.
     */
    public static float getXYPixelSizeNm(ImagePlus imp, float fallbackNm) {
        double detected = getXYPixelSizeNm(imp);
        return isPositiveFinite(detected) ? (float) detected : fallbackNm;
    }

    /**
     * Returns the calibrated Z spacing in nm, or the supplied fallback when the
     * active image has no usable physical calibration.
     */
    public static float getZPixelSizeNm(ImagePlus imp, float fallbackNm) {
        double detected = getZPixelSizeNm(imp);
        return isPositiveFinite(detected) ? (float) detected : fallbackNm;
    }

    /**
     * Returns the calibrated XY pixel size in nm, or NaN if unavailable.
     * For non-square pixels, the X calibration is used and a warning is logged.
     */
    public static double getXYPixelSizeNm(ImagePlus imp) {
        if (imp == null)
            return Double.NaN;

        Calibration cal = imp.getCalibration();
        if (cal == null)
            return Double.NaN;

        double factor = unitToNanometres(cal.getUnit());
        if (!isPositiveFinite(factor))
            return Double.NaN;

        double xNm = cal.pixelWidth * factor;
        double yNm = cal.pixelHeight * factor;

        if (!isPositiveFinite(xNm) && !isPositiveFinite(yNm))
            return Double.NaN;
        if (!isPositiveFinite(xNm))
            return yNm;
        if (!isPositiveFinite(yNm))
            return xNm;

        double relativeDifference = Math.abs(xNm - yNm) / Math.max(xNm, yNm);
        if (relativeDifference > XY_MISMATCH_TOLERANCE) {
            StructuredLog.warning(
                    "Sampling",
                    "Non-square XY calibration",
                    String.format(
                            "X = %.4f nm, Y = %.4f nm. Using the X pixel size; "
                            + "please verify the value in the dialog.",
                            xNm, yNm));
            return xNm;
        }

        return 0.5 * (xNm + yNm);
    }

    /**
     * Returns the calibrated Z spacing in nm, or NaN if unavailable.
     */
    public static double getZPixelSizeNm(ImagePlus imp) {
        if (imp == null || imp.getStackSize() <= 1)
            return Double.NaN;

        Calibration cal = imp.getCalibration();
        if (cal == null)
            return Double.NaN;

        double factor = unitToNanometres(cal.getUnit());
        if (!isPositiveFinite(factor))
            return Double.NaN;

        double zNm = cal.pixelDepth * factor;
        return isPositiveFinite(zNm) ? zNm : Double.NaN;
    }

    /**
     * Apply the USER-ENTERED lateral pixel size to a derived result without
     * changing the source image or its Image > Properties values.
     *
     * This must be applied after any resolution-map resampling so a restored
     * native-size map is calibrated with the original user-entered sampling.
     */
    public static void applyUserXYPixelSizeNm(ImagePlus result, ImagePlus source,
                                             double enteredPixelSizeNm) {
        if (result == null || source == null || !isPositiveFinite(enteredPixelSizeNm))
            throw new IllegalArgumentException("Output image, source image and user pixel size must be valid.");
        Calibration resultCalibration = source.getCalibration().copy();
        resultCalibration.setUnit("nm");
        resultCalibration.pixelWidth = enteredPixelSizeNm;
        resultCalibration.pixelHeight = enteredPixelSizeNm;
        result.setCalibration(resultCalibration);
    }

    private static double unitToNanometres(String unit) {
        if (unit == null)
            return Double.NaN;

        String normalized = unit.trim().toLowerCase()
                .replace("μ", "u")
                .replace("µ", "u")
                .replace("å", "angstrom")
                .replace(" ", "");

        if (normalized.isEmpty()
                || normalized.equals("pixel")
                || normalized.equals("pixels")
                || normalized.equals("px")) {
            return Double.NaN;
        }

        if (normalized.equals("nm")
                || normalized.equals("nanometer")
                || normalized.equals("nanometers")
                || normalized.equals("nanometre")
                || normalized.equals("nanometres")) {
            return 1.0;
        }

        if (normalized.equals("um")
                || normalized.equals("micron")
                || normalized.equals("microns")
                || normalized.equals("micrometer")
                || normalized.equals("micrometers")
                || normalized.equals("micrometre")
                || normalized.equals("micrometres")) {
            return 1.0e3;
        }

        if (normalized.equals("mm")
                || normalized.equals("millimeter")
                || normalized.equals("millimeters")
                || normalized.equals("millimetre")
                || normalized.equals("millimetres")) {
            return 1.0e6;
        }

        if (normalized.equals("cm")
                || normalized.equals("centimeter")
                || normalized.equals("centimeters")
                || normalized.equals("centimetre")
                || normalized.equals("centimetres")) {
            return 1.0e7;
        }

        if (normalized.equals("m")
                || normalized.equals("meter")
                || normalized.equals("meters")
                || normalized.equals("metre")
                || normalized.equals("metres")) {
            return 1.0e9;
        }

        if (normalized.equals("angstrom")
                || normalized.equals("angstroms")) {
            return 0.1;
        }

        if (normalized.equals("pm")
                || normalized.equals("picometer")
                || normalized.equals("picometers")
                || normalized.equals("picometre")
                || normalized.equals("picometres")) {
            return 1.0e-3;
        }

        return Double.NaN;
    }

    private static boolean isPositiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
