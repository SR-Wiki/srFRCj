package com.HIT.WeisongZhao;

import javax.swing.JDialog;

import com.HIT.WeisongZhao.FRC.FRCCurve;
import com.HIT.WeisongZhao.utils.Correction;
import com.HIT.WeisongZhao.utils.Correlations;
import com.HIT.WeisongZhao.utils.CurveExtrapolator;
import com.HIT.WeisongZhao.utils.ImagePropertiesUtils;
import com.HIT.WeisongZhao.utils.RobustnessUtils;
import com.HIT.WeisongZhao.utils.StructuredLog;
import com.HIT.WeisongZhao.utils.AnalysisResults;
import com.HIT.WeisongZhao.utils.StackFrames;
import com.HIT.WeisongZhao.utils.InputImageUtils;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import com.HIT.WeisongZhao.utils.PluginDialogStyle;
import ij.gui.Plot;
import ij.plugin.PlugIn;

/**
 * Single-frame FRC plugin.
 *
 * <p>The preferred calculation backend is {@link Correlations}, which keeps the
 * sFRC implementation aligned with the sFSC/FSC code path.  If the ND4J native
 * backend cannot be loaded because of a platform/runtime configuration problem,
 * the plugin falls back to the legacy {@link FRC}/{@link FRCCurve} implementation
 * for the current run.  Numerical/algorithmic failures are not silently routed to
 * the legacy backend.</p>
 */
public class sFRC extends JDialog implements PlugIn {

    private static final long serialVersionUID = 1L;
    private final FRC myFRC = new FRC();
    private static float pixelSize = 20;
    // Internal current-frame curve gate, not a dialog option.
    private boolean plotCurrentFrame = true;
    private boolean extrapolationTriggered;
    private double frameResolution = Double.NaN;
    private String curveTitle = "sFRC - correlation curve";

    @Override
    public void run(String args) {
        if (IJ.versionLessThan("1.46j"))
            return;

        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            IJ.noImage();
            return;
        }

        StructuredLog.reset();
        final int currentFrame = imp.getCurrentSlice();

        String[] trueFalse = {"true", "false"};
        String[] thresholds = {"1/7 hard threshold", "3-sigma"};
        float dialogPixelSize = ImagePropertiesUtils.getXYPixelSizeNm(imp, pixelSize);

        GenericDialog gd = new GenericDialog("sFRC");
        gd.addNumericField("Pixel Size", dialogPixelSize, 2, 5, "(nm)");
        gd.addChoice("If SingleFrame", trueFalse, "true");
        gd.addChoice("Threshold", thresholds, "1/7 hard threshold");
        gd.addChoice("Advanced Settings", trueFalse, "false");
        gd.addHelp("https://github.com/SR-Wiki/srFRCJ");
        PluginDialogStyle.apply(gd, false);
        gd.showDialog();

        if (gd.wasCanceled())
            return;

        // Keep the remembered pixel size in the original image sampling.
        pixelSize = (float) gd.getNextNumber();
        boolean singleFrame = Boolean.valueOf(trueFalse[gd.getNextChoiceIndex()]);
        String thresholdType = thresholds[gd.getNextChoiceIndex()];
        boolean advancedSettings = Boolean.valueOf(trueFalse[gd.getNextChoiceIndex()]);

        boolean batchProcessing = true;
        boolean applyCorrection = true;
        boolean showResampled = false;

        if (advancedSettings) {
            GenericDialog gd2 = new GenericDialog("sFRC - advanced settings");
            gd2.addChoice("Stack Batch Processing", trueFalse, "true");
            gd2.addMessage("Enabled: analyze all stack frames.\n"
                    + "Disabled: analyze only the current frame (or current + next in paired mode).");
            gd2.addChoice("Apply Correction", trueFalse, "true");
            gd2.addChoice("Show Resampled images", trueFalse, "false");
            PluginDialogStyle.apply(gd2, true);
            gd2.showDialog();
            if (gd2.wasCanceled())
                return;

            batchProcessing = Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
            applyCorrection = Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
            showResampled = Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
        }

        if (!RobustnessUtils.validatePositive("Pixel Size", pixelSize))
            return;
        RobustnessUtils.warnSuspiciousSampling(imp, pixelSize, Double.NaN, false);

        final float analysisPixelSize = singleFrame ? pixelSize * 2.0f : pixelSize;

        ImageStack input = InputImageUtils.normalizedStack(imp.getStack());
        int frames = input.getSize();
        if (!singleFrame && frames < 2) {
            IJ.showMessage("sFRC - stack input", "Double-frame mode requires at least two frames/slices.");
            return;
        }
        if (singleFrame && (imp.getWidth() < 2 || imp.getHeight() < 2)) {
            IJ.showMessage("sFRC - input", "Single-frame input must be at least 2 x 2 pixels.");
            return;
        }
        int[] selectedFrames;
        try {
            selectedFrames = StackFrames.select(frames, currentFrame, singleFrame, batchProcessing);
        } catch (IllegalArgumentException error) {
            IJ.showMessage("sFRC - stack input", error.getMessage());
            return;
        }
        int pairCount = selectedFrames.length;
        int previewIndex = StackFrames.previewIndex(frames, currentFrame, singleFrame, batchProcessing);
        if (previewIndex < 0)
            IJ.log("sFRC - no current-frame preview: paired mode needs a following frame. All computed batch results are retained.");
        boolean legacyBackend = false;
        AnalysisResults results = new AnalysisResults("sFRC - results");
        for (int index = 0; index < pairCount; index++) {
            plotCurrentFrame = index == previewIndex;
            int frame = selectedFrames[index];
            String label = StackFrames.label(frame, singleFrame);
            curveTitle = "sFRC - correlation curve - " + label;
            extrapolationTriggered = false;
            frameResolution = Double.NaN;
            ImagePlus analysisPair;
            if (singleFrame) {
                analysisPair = PreGenerator.getResampledImage(input.getProcessor(frame));
                analysisPair.setTitle("sFRC - resampled pair - " + label);
                if (showResampled && index == previewIndex) analysisPair.show();
            } else {
                ImageStack pair = new ImageStack(imp.getWidth(), imp.getHeight());
                pair.addSlice(input.getProcessor(frame));
                pair.addSlice(input.getProcessor(frame + 1));
                analysisPair = new ImagePlus("sFRC - input pair - " + label, pair);
            }
            ImagePlus im1 = new ImagePlus("sFRC - sample A", analysisPair.getStack().getProcessor(1));
            ImagePlus im2 = new ImagePlus("sFRC - sample B", analysisPair.getStack().getProcessor(2));
            try {
                if (!legacyBackend) {
                    try {
                        runWithCorrelations(im1, im2, analysisPixelSize, singleFrame,
                                applyCorrection, thresholdType);
                    } catch (LinkageError err) {
                        if (!isNd4jBackendFailure(err)) throw err;
                        notifyNd4jFallback(err);
                        legacyBackend = true;
                    } catch (RuntimeException ex) {
                        if (!isNd4jBackendFailure(ex)) throw ex;
                        notifyNd4jFallback(ex);
                        legacyBackend = true;
                    }
                }
                if (legacyBackend) {
                    runWithLegacyFRC(analysisPair.getStack(), analysisPixelSize,
                            singleFrame, applyCorrection, thresholdType);
                }
            } finally {
                results.addSFRC(label, singleFrame, thresholdType,
                        extrapolationTriggered, frameResolution);
                IJ.showProgress(index + 1, pairCount);
            }
        }
    }

    /**
     * Preferred sFRC calculation path using utils.Correlations.
     *
     * @return true when a result was produced; false when the backend completed
     *         but no valid result should be reported (warning already shown).
     */
    private boolean runWithCorrelations(ImagePlus im1,
                                        ImagePlus im2,
                                        float analysisPixelSizeNm,
                                        boolean singleFrame,
                                        boolean applyCorrection,
                                        String thresholdType) {
        final String correlationThreshold = toCorrelationThreshold(thresholdType);

        // Correlations expects pixel size in micrometres and returns resolution in nm.
        Correlations correlations = new Correlations(
                im1, im2, analysisPixelSizeNm / 1000.0);

        // In Correlations, the singleFrame argument controls application of the
        // single-frame correction.  Pair generation has already happened above.
        final boolean useCorrection = singleFrame && applyCorrection;
        correlations.execute(2, correlationThreshold, useCorrection, false, true);

        extrapolationTriggered = correlations.wasExtrapolationAttempted();
        double resolution = correlations.getResolution();
        if (!Double.isFinite(resolution) || resolution <= 0) {
            if (correlations.isExtrapolationRequired()
                    && !Correlations.isExtrapolationSupportedThreshold(correlationThreshold)) {
                RobustnessUtils.showExtrapolationUnsupportedThreshold(
                        "sFRC", thresholdType);
            } else {
                RobustnessUtils.showExtrapolationFailed("sFRC");
            }
            return false;
        }

        if (correlations.wasExtrapolationUsed())
            RobustnessUtils.showExtrapolationApplied("sFRC");

        frameResolution = resolution;
        if (plotCurrentFrame) correlations.plotFRC(curveTitle);
        StructuredLog.info("sFRC", "Backend", "Correlations (ND4J)");
        StructuredLog.info("sFRC", "Resolution", String.format("%.1f nm", resolution));
        return true;
    }

    /**
     * Legacy fallback based on FRC/FRCCurve.  This is intentionally the same
     * calculation route used by the previous sFRC implementation and is entered
     * only when the ND4J backend cannot be loaded/configured.
     *
     * @see FRC#LEGACY_REFERENCE_URL
     */
    private void runWithLegacyFRC(ImageStack ims,
                                  float analysisPixelSize,
                                  boolean singleFrame,
                                  boolean applyCorrection,
                                  String thresholdType) {
        StructuredLog.info("sFRC", "Backend", "Legacy FRC");

        FRCCurve frcCurve = myFRC.calculateFrcCurve(
                ims.getProcessor(1), ims.getProcessor(2));
        FRCCurve smoothedFRCCurve;
        if (applyCorrection && singleFrame) {
            smoothedFRCCurve = Correction.applyCorrection(
                    FRC.getSmoothedCurve(frcCurve, false));
        } else {
            smoothedFRCCurve = FRC.getSmoothedCurve(frcCurve, false);
        }

        double resolution;
        double[] thresholdCurve;
        switch (thresholdType) {
            case "1/7 hard threshold":
                resolution = FRC.calculateFireNumber(
                        smoothedFRCCurve, FRC.ThresholdMethod.FIXED_1_OVER_7);
                thresholdCurve = FRC.calculateThresholdCurve(
                        frcCurve, FRC.ThresholdMethod.FIXED_1_OVER_7);
                break;
            case "3-sigma":
                resolution = FRC.calculateFireNumber(
                        smoothedFRCCurve, FRC.ThresholdMethod.THREE_SIGMA);
                thresholdCurve = FRC.calculateThresholdCurve(
                        frcCurve, FRC.ThresholdMethod.THREE_SIGMA);
                break;
            default:
                throw new IllegalArgumentException("Unknown threshold type: " + thresholdType);
        }

        resolution *= analysisPixelSize;

        if (!Double.isFinite(resolution) || resolution <= 0) {
            if (!"1/7 hard threshold".equals(thresholdType)) {
                RobustnessUtils.showExtrapolationUnsupportedThreshold(
                        "sFRC (FRCCurve fallback)", thresholdType);
                return;
            }
            // Extrapolate exactly the same corrected/smoothed curve used for
            // threshold detection; never switch back to raw FRC values.
            double[] correlations = smoothedFRCCurve.getCorrelationValues();
            double[] radius = smoothedFRCCurve.getRadiusValues();
            int measuredLength = smoothedFRCCurve.getSize();
            if (measuredLength < 4 || radius.length != measuredLength
                    || correlations.length != measuredLength) {
                RobustnessUtils.showExtrapolationFailed(
                        "sFRC (FRCCurve fallback)");
                return;
            }

            double maxRadius = radius[measuredLength - 1];
            if (!(maxRadius > 0)) {
                RobustnessUtils.showExtrapolationFailed(
                        "sFRC (FRCCurve fallback)");
                return;
            }

            double[] frequency = new double[measuredLength];
            frequency[0] = 0.0;
            for (int i = 1; i < measuredLength; i++) {
                frequency[i] = radius[i]
                        / (2.0 * analysisPixelSize * maxRadius) * 1000.0;
            }

            extrapolationTriggered = true;
            CurveExtrapolator.Result ext = CurveExtrapolator.extrapolate(
                    correlations, frequency, measuredLength * 2);
            if (!ext.success) {
                StructuredLog.warning(
                        "sFRC",
                        "Automatic extrapolation failed",
                        ext.message);
                RobustnessUtils.showExtrapolationFailed(
                        "sFRC (FRCCurve fallback)");
                return;
            }

            // CurveExtrapolator only extends the curve.  Reuse the legacy FRC
            // threshold/resolution implementation for the extrapolated curve so
            // the cutoff criterion is identical to the non-extrapolated path.
            FRC.FRCCurveResult[] extendedResults =
                    new FRC.FRCCurveResult[ext.frc.length];
            int lastMeasuredRadius = (int) Math.round(radius[measuredLength - 1]);
            for (int i = 0; i < ext.frc.length; i++) {
                int r;
                if (i < measuredLength) {
                    r = (int) Math.round(radius[i]);
                } else {
                    r = lastMeasuredRadius + (i - measuredLength + 1);
                }
                extendedResults[i] = new FRC.FRCCurveResult(r, 1, ext.frc[i]);
            }
            FRCCurve extendedCurve = new FRCCurve(
                    smoothedFRCCurve.fieldOfView, extendedResults);

            double extendedFireNumber = FRC.calculateFireNumber(
                    extendedCurve, FRC.ThresholdMethod.FIXED_1_OVER_7);
            if (!Double.isFinite(extendedFireNumber) || extendedFireNumber <= 0) {
                StructuredLog.warning(
                        "sFRC",
                        "Automatic extrapolation failed",
                        "The extrapolated curve does not reach the selected threshold using "
                        + "the existing legacy resolution rule.");
                RobustnessUtils.showExtrapolationFailed(
                        "sFRC (FRCCurve fallback)");
                return;
            }

            resolution = extendedFireNumber * analysisPixelSize;
            double[] newThreshold = FRC.calculateThresholdCurve(
                    extendedCurve, FRC.ThresholdMethod.FIXED_1_OVER_7);

            StructuredLog.info(
                    "sFRC",
                    "Extrapolation fit",
                    String.format(
                            "A=%.6g, k(norm)=%.6g, R^2=%.4f, RMSE=%.4g%s",
                            ext.A, ext.k, ext.rSquared, ext.rmse,
                            ext.usedLinearizedFallback
                                    ? " [linearized fallback]" : ""));
            RobustnessUtils.showExtrapolationApplied(
                    "sFRC (FRCCurve fallback)");
            if (plotCurrentFrame) plotExtrapolatedFRC(
                    ext.frequency, ext.frc, newThreshold, resolution);
        } else {
            if (plotCurrentFrame) FRC.plotFRC(
                    smoothedFRCCurve, analysisPixelSize,
                    thresholdCurve, resolution, curveTitle);
        }

        frameResolution = resolution;
        StructuredLog.info("sFRC", "Resolution", String.format("%.1f nm", resolution));
    }

    private static String toCorrelationThreshold(String thresholdType) {
        if ("1/7 hard threshold".equals(thresholdType))
            return "1/7";
        if ("3-sigma".equals(thresholdType))
            return "3-sigma";
        throw new IllegalArgumentException("Unknown threshold type: " + thresholdType);
    }

    /**
     * Decide whether a failure is specifically attributable to the ND4J native
     * stack rather than the correlation algorithm itself.
     */
    private static boolean isNd4jBackendFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            String className = current.getClass().getName().toLowerCase();
            String message = current.getMessage() == null
                    ? "" : current.getMessage().toLowerCase();

            if (className.startsWith("org.nd4j.")
                    || className.startsWith("org.bytedeco."))
                return true;

            boolean nativeLinkFailure = current instanceof UnsatisfiedLinkError;
            boolean missingClass = current instanceof NoClassDefFoundError;
            boolean initializerFailure = current instanceof ExceptionInInitializerError;

            boolean nd4jText = containsAny(message,
                    "nd4j", "org/nd4j", "javacpp", "org/bytedeco",
                    "openblas", "jniopenblas", "native backend",
                    "no available backend", "blas backend");

            if ((nativeLinkFailure || missingClass || initializerFailure)
                    && (nd4jText || nativeLinkFailure))
                return true;

            if (current instanceof RuntimeException && nd4jText)
                return true;

            current = current.getCause();
        }
        return false;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle))
                return true;
        }
        return false;
    }

    private static void notifyNd4jFallback(Throwable error) {
        String reason = backendFailureReason(error);
        String message =
                "WARNING: ND4J backend could not be initialized.\n"
                + "REASON: " + reason + "\n"
                + "PLAUSIBLE CAUSE: The existing JavaCPP dependency may require replacement. "
                + "Please see the manual for details.\n\n"
                + "sFRC will use the Legacy FRC backend instead. "
                + "The calculation remains valid but uses an alternative implementation.\n\n"
                + "Legacy FRC reference: " + FRC.LEGACY_REFERENCE_URL;

        StructuredLog.warning(
                "sFRC",
                "ND4J backend fallback",
                message);
        IJ.showMessage("sFRC backend warning", message);
    }

    private static String backendFailureReason(Throwable error) {
        Throwable current = error;
        Throwable root = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof ExceptionInInitializerError)
                return "ExceptionInInitializerError";
            root = current;
            current = current.getCause();
        }
        return root == null ? "Unknown backend initialization error"
                : root.getClass().getSimpleName();
    }

    private void plotExtrapolatedFRC(double[] frequency,
                                     double[] correlations,
                                     double[] thresholdCurve,
                                     double resolution) {
        Plot frcPlot = new Plot(
                curveTitle + " - extrapolated", "Spatial Frequency (1/μm)", "Correlation");
        frcPlot.setColor(new java.awt.Color(230, 115, 115));
        frcPlot.addPoints(frequency, correlations, Plot.LINE);
        frcPlot.setColor(java.awt.Color.BLACK);
        frcPlot.addPoints(frequency, thresholdCurve, Plot.LINE);

        double cutoffFrequency = 1000.0 / resolution;
        double[] x0 = {cutoffFrequency, cutoffFrequency};
        double[] y0 = {0.0, 1.0};
        frcPlot.addLabel(0.1, 0.5,
                "Resolution:" + String.format("%.1f", resolution) + " nm");
        frcPlot.setColor(java.awt.Color.BLUE);
        frcPlot.addPoints(x0, y0, Plot.LINE);
        frcPlot.setLimits(
                0, 1.1 * frequency[frequency.length - 1], -0.1, 1.1);
        frcPlot.show();
    }

    public static void main(String[] args) {
        Class<?> clazz = sFRC.class;
        String url = clazz.getResource(
                "/" + clazz.getName().replace('.', '/') + ".class").toString();
        String pluginsDir = url.substring(
                "file:".length(),
                url.length() - clazz.getName().length() - ".class".length());
        System.setProperty("plugins.dir", pluginsDir);
        new ImageJ();
        ImagePlus image = IJ.openImage();
        image.show();
        IJ.runPlugIn(clazz.getName(), "");
    }
}
