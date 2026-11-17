package com.HIT.WeisongZhao.utils;

import ij.IJ;
import ij.ImagePlus;

/**
 * Small, user-facing robustness helpers shared by the srFRCJ entry points.
 *
 * These checks intentionally focus on parameters controlled by the user or on
 * algorithm-specific conditions that Fiji/ImageJ does not already report. They
 * do not duplicate generic Fiji errors such as "no image open".
 */
public final class RobustnessUtils {

    private static final double CALIBRATION_MISMATCH_RELATIVE_TOLERANCE = 0.01;
    private static final double STRONG_ANISOTROPY_RATIO = 10.0;

    private RobustnessUtils() {
        // Utility class.
    }

    public static boolean isPositiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static boolean validatePositive(String name, double value) {
        if (isPositiveFinite(value))
            return true;

        IJ.showMessage("Invalid Parameter",
                name + " must be a positive finite value.\n\n"
                + "Current value: " + value);
        return false;
    }

    public static boolean validateSrFRCSettings(
            int blockSize,
            int skip,
            int backgroundIntensity,
            double pixelSizeNm,
            int patchSize) {

        StringBuilder errors = new StringBuilder();

        if (blockSize < 4)
            errors.append("- Block Size must be at least 4 pixels.\n");
        if (skip < 1)
            errors.append("- Skip must be at least 1 pixel.\n");
        if (!isPositiveFinite(pixelSizeNm))
            errors.append("- Pixel Size must be a positive finite value.\n");
        if (backgroundIntensity < 0 || backgroundIntensity > 255)
            errors.append("- Background Intensity must be between 0 and 255.\n");
        if (patchSize < 1)
            errors.append("- Patch Size must be at least 1 pixel.\n");

        if (errors.length() == 0)
            return true;

        IJ.showMessage("Invalid srFRC Settings",
                "Please correct the following settings before continuing:\n\n"
                + errors.toString());
        return false;
    }

    public static boolean validateSFSCSettings(
            double xyPixelSizeNm,
            double zPixelSizeNm,
            double lowAngleDeg,
            double highAngleDeg,
            int numCones) {

        StringBuilder errors = new StringBuilder();

        if (!isPositiveFinite(xyPixelSizeNm))
            errors.append("- XY pixel size must be a positive finite value.\n");
        if (!isPositiveFinite(zPixelSizeNm))
            errors.append("- Z spacing must be a positive finite value.\n");
        if (!(lowAngleDeg >= 0.0 && lowAngleDeg < 90.0))
            errors.append("- Low Angle must be within [0, 90) degrees.\n");
        if (!(highAngleDeg > 0.0 && highAngleDeg <= 90.0))
            errors.append("- High Angle must be within (0, 90] degrees.\n");
        if (!(lowAngleDeg < highAngleDeg))
            errors.append("- Low Angle must be smaller than High Angle.\n");
        if (numCones <= 0)
            errors.append("- Number of Cones must be a positive integer.\n");

        if (errors.length() == 0)
            return true;

        IJ.showMessage("Invalid sFSC Settings",
                "Please correct the following settings before continuing:\n\n"
                + errors.toString());
        return false;
    }

    /**
     * Shows one consolidated warning for suspicious, but still usable, sampling
     * settings. The calculation is not stopped.
     */
    public static void warnSuspiciousSampling(
            ImagePlus imp,
            double enteredXyNm,
            double enteredZNm,
            boolean includeZ) {

        StringBuilder differences = new StringBuilder();
        double storedXyNm = ImagePropertiesUtils.getXYPixelSizeNm(imp);
        if (isPositiveFinite(storedXyNm) && isPositiveFinite(enteredXyNm)
                && differsSubstantially(enteredXyNm, storedXyNm)) {
            differences.append(String.format(java.util.Locale.US,
                    "XY pixel size (nm):\n"
                    + "  Your input: %.3f\n  Image > Properties: %.3f\n\n",
                    enteredXyNm, storedXyNm));
        }
        if (includeZ) {
            double storedZNm = ImagePropertiesUtils.getZPixelSizeNm(imp);
            if (isPositiveFinite(storedZNm) && isPositiveFinite(enteredZNm)
                    && differsSubstantially(enteredZNm, storedZNm)) {
                differences.append(String.format(java.util.Locale.US,
                        "Z spacing (nm):\n"
                        + "  Your input: %.3f\n  Image > Properties: %.3f\n\n",
                        enteredZNm, storedZNm));
            }
        }
        if (includeZ && isPositiveFinite(enteredXyNm) && isPositiveFinite(enteredZNm)) {
            double ratio = Math.max(enteredXyNm, enteredZNm)
                    / Math.min(enteredXyNm, enteredZNm);
            if (ratio >= STRONG_ANISOTROPY_RATIO) {
                differences.append(String.format(java.util.Locale.US,
                        "XY and Z input spacings differ by %.1fx.\n"
                        + "Please verify the voxel dimensions.\n\n", ratio));
            }
        }
        if (differences.length() > 0) {
            String message = "Please verify the following sampling values:\n\n"
                    + differences.toString()
                    + "The entered pixel sizes OVERRIDE Image > Properties.\n"
                    + "All calculations use YOUR INPUT values.\n"
                    + "The original image metadata will not be modified.\n\n"
                    + "If needed, check the units and pixel dimensions via\n"
                    + "Fiji: Image > Properties... before another analysis.";
            IJ.showMessage("Image Properties - sampling check", message);
        }

    }

    /**
     * Retained for source compatibility. Automatic 1/7 extrapolation is now
     * attempted by Correlations whenever a measured curve has no crossing.
     */
    public static void showExtrapolationRequired(String context) {
        StructuredLog.attention(
                context,
                "Automatic curve extrapolation",
                "No threshold crossing was found in the measured range. "
                + "Automatic extrapolation is available for the 1/7 threshold.");
    }

    public static void showExtrapolationUnsupportedThreshold(String context, String threshold) {
        String message = context + ": no threshold crossing was found in the measured range.\n\n"
                + "Automatic extrapolation is available only for 1/7.\n"
                + "Selected threshold: " + threshold + "\n\n"
                + "Resolution is unavailable (NaN). Check the threshold and pixel size.";
        IJ.showMessage("sFRC/sFSC - extrapolation unavailable", message);
        StructuredLog.warning(
                context,
                "Extrapolation not applied",
                "Automatic curve extrapolation is available for the 1/7 threshold only. "
                + "Selected threshold: " + threshold + ".");
    }

    /** Successful automatic extrapolation is informational only. */
    public static void showExtrapolationApplied(String context) {
        StructuredLog.attention(
                context,
                "Automatic curve extrapolation",
                "No threshold crossing was found in the measured range. "
                + "Automatic 1/7 extrapolation provided the resolution estimate.");
    }

    /** Informational variant for multiple automatically extrapolated curves. */
    public static void showExtrapolationApplied(String context, int numberOfCurves) {
        StructuredLog.attention(
                context,
                "Automatic curve extrapolation",
                "Automatic 1/7 extrapolation provided a crossing for " + numberOfCurves + " " + context + " curve"
                + (numberOfCurves == 1 ? "" : "s")
                + ". The reported result includes extrapolated estimates.");
    }

    public static void showExtrapolationFailed(String context) {
        String message = context + ": automatic extrapolation could not provide a usable threshold crossing.\n\n"
                + "Resolution is unavailable (NaN). Check the pixel size and threshold.";
        IJ.showMessage("sFRC/sFSC - extrapolation failed", message);
        StructuredLog.warning(
                context,
                "Automatic extrapolation failed",
                message);
    }

    /**
     * DRI-specific warning shown ONLY when zero directional FSC curves provide
     * a usable threshold crossing after the normal calculation path, including
     * automatic 1/7 extrapolation when applicable.
     */
    public static void showDRINoValidCrossingWarning(
            int totalDirections,
            String threshold,
            boolean singleFrame,
            boolean zSamplingEnabled) {

        StringBuilder message = new StringBuilder();
        message.append("No usable threshold crossing was found in any of the ")
                .append(totalDirections).append(" directions.\n\n");

        if ("1/7".equals(threshold) || "fixedThreshold".equals(threshold)) {
            message.append("Automatic 1/7 extrapolation was attempted after all measured directions had no crossing.\n\n");
        } else {
            message.append("Automatic extrapolation was not applied: it is available only for 1/7.\n\n");
        }

        if (singleFrame) {
            message.append("Current Z Sampling: ")
                    .append(zSamplingEnabled ? "ON" : "OFF").append("\n")
                    .append("Threshold: ").append(threshold).append("\n\n");
        } else {
            message.append("Threshold: ").append(threshold).append("\n\n");
        }

        message.append("Axial resolution and E_DRI are unavailable.\n")
                .append("Check the voxel calibration, threshold, and fitting angles.\n")
                .append(SFSCGuidance.ANGLE_GUIDANCE).append("\n\n")
                .append(DRIGoodnessOfFit.GUIDANCE);

        IJ.showMessage("sFSC - no valid DRI crossing", message.toString());
        StructuredLog.warning(
                "sFSC/DRI",
                "No valid directional crossing",
                "No valid directional FSC threshold crossing was obtained (0/"
                + totalDirections + "). Threshold=" + threshold
                + (singleFrame ? ", Z Sampling=" + (zSamplingEnabled ? "ON" : "OFF") : "")
                + ".");
    }

    /** Successful Z Sampling is reported in the structured log only. */
    public static void logZSamplingApplied(String context) {
        StructuredLog.attention(
                context,
                "Z Sampling",
                "Z sampling was applied using odd/even axial splitting to reduce "
                + "spurious correlation between adjacent Z planes.");
    }

    /**
     * Structured-log note when a valid DRI fit is obtained even though some
     * directional curves have no usable threshold crossing.
     */
    public static void logDRIExcludedCrossings(
            int excluded,
            int total,
            boolean zSamplingEnabled) {
        StructuredLog.attention(
                "sFSC/DRI",
                "Excluded directional crossings",
                excluded + "/" + total
                + " directional FSC curve" + (excluded == 1 ? "" : "s")
                + " remained without a valid threshold crossing and were excluded from "
                + "the DRI fit. Z Sampling=" + (zSamplingEnabled ? "ON" : "OFF") + "."
                + (!zSamplingEnabled
                        ? " Consider Z Sampling if inter-plane discontinuity is suspected."
                        : ""));
    }

    public static void warnPoorDRIFit(double relativeError, double threshold) {
        if (Double.isFinite(relativeError) && relativeError < 0.10) return;
        String message = String.format(
                "E_DRI = %.2f%% (%s)\n\n%s\n\n"
                + SFSCGuidance.FIT_RANGE_ADVICE + "\n\n"
                + "The numerical estimate is retained for inspection. An E_DRI above 20%% "
                + "means the axial estimate should not be accepted.",
                100.0 * relativeError, DRIGoodnessOfFit.interpretation(relativeError),
                DRIGoodnessOfFit.GUIDANCE);
        IJ.showMessage("sFSC - DRI goodness of fit", message);
        StructuredLog.warning("sFSC/DRI", "Goodness of fit", message);
    }

    private static boolean differsSubstantially(double a, double b) {
        return Math.abs(a - b) / Math.max(a, b) > CALIBRATION_MISMATCH_RELATIVE_TOLERANCE;
    }
}
