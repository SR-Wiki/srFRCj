package com.HIT.WeisongZhao;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JDialog;

import com.HIT.WeisongZhao.utils.GrayLevelClass;
import com.HIT.WeisongZhao.utils.ResolutionMapDisplay;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import com.HIT.WeisongZhao.utils.PluginDialogStyle;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;

import com.HIT.WeisongZhao.FRC.FRCCurve;
import com.HIT.WeisongZhao.utils.Correction;
import com.HIT.WeisongZhao.utils.Correlations;
import com.HIT.WeisongZhao.utils.ImagePropertiesUtils;
import com.HIT.WeisongZhao.utils.RobustnessUtils;
import com.HIT.WeisongZhao.utils.StructuredLog;
import com.HIT.WeisongZhao.utils.AnalysisResults;
import com.HIT.WeisongZhao.utils.StackFrames;
import com.HIT.WeisongZhao.utils.InputImageUtils;
import com.HIT.WeisongZhao.utils.ResolutionMapStatistics;
import com.HIT.WeisongZhao.utils.ResolutionMapResampler;

public class srFRCConcurrent extends JDialog implements PlugIn {
    private static int blockSize = 64;
    private static int backgroundIntensity = 30;
    private static int skip = 2;
    private static float pixelSize = 20;
    private static int patchSize = 64; // Parallel block size
    
    // Thread pool
    private ExecutorService executor;
    private static final List<ImagePlus> lastResolutionMaps = new ArrayList<ImagePlus>();

    /** Final numerical maps remain accessible when automatic display is disabled. */
    public static synchronized ImagePlus[] getLastResolutionMaps() {
        return lastResolutionMaps.toArray(new ImagePlus[lastResolutionMaps.size()]);
    }

    private static synchronized void resetResolutionMaps() { lastResolutionMaps.clear(); }
    private static synchronized void retainResolutionMap(ImagePlus image) { lastResolutionMaps.add(image); }

    @Override
    public void run(String arg) {
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
        String[] calculationBackends = {"ND4J", "Legacy"};
        String[] thresholds = {"1/7 hard threshold", "3-sigma"};
        float dialogPixelSize = ImagePropertiesUtils.getXYPixelSizeNm(imp, pixelSize);

        GenericDialog gd = new GenericDialog("srFRC");
        gd.addNumericField("Block Size", blockSize, 0, 5, "pixels (default: 64)");
        gd.addNumericField("Skip", skip, 0, 3, "pixels (2-4 for faster calculation)");
        gd.addNumericField("Pixel Size", dialogPixelSize, 2, 5, "(nm)");
        gd.addChoice("If SingleFrame", trueFalse, "true");
        gd.addChoice("Threshold", thresholds, "3-sigma");
        gd.addMessage("The background threshold is estimated automatically by default.");
        gd.addChoice("Advanced Settings", trueFalse, "false");
        gd.addHelp("https://github.com/SR-Wiki/srFRCJ");
        PluginDialogStyle.apply(gd, false);
        gd.showDialog();

        if (gd.wasCanceled())
            return;

        blockSize = (int) gd.getNextNumber();
        skip = (int) gd.getNextNumber();
        pixelSize = (float) gd.getNextNumber();
        boolean singleFrame = Boolean.valueOf(trueFalse[gd.getNextChoiceIndex()]);
        String thresholdLabel = thresholds[gd.getNextChoiceIndex()];
        String correlationThreshold = "1/7 hard threshold".equals(thresholdLabel) ? "1/7" : "3-sigma";
        boolean advancedSettings = Boolean.valueOf(trueFalse[gd.getNextChoiceIndex()]);

        boolean batchProcessing = true;
        boolean adaptiveBackground = true;
        boolean monitorChanges = false;
        String calculationBackend = "ND4J";

        if (advancedSettings) {
            GenericDialog gd2 = new GenericDialog("srFRC - advanced settings");
            gd2.addChoice("Stack Batch Processing", trueFalse, "true");
            gd2.addMessage("Enabled: analyze all stack frames.\n"
                    + "Disabled: analyze only the current frame (or current + next in paired mode).");
            gd2.addMessage(
                    "Automatic background uses 1.0 times Otsu by default.\n"
                    + "Monitor Changes compares 0.8, 1.0 and 1.2 times Otsu\n"
                    + "and turns Automatic Background OFF.\n"
                    + "Background Intensity is used only when both options are OFF.");
            gd2.addChoice("Use Automatic Background", trueFalse, "true");
            gd2.addChoice("Monitor Changes (with varying backgrounds)", trueFalse, "false");
            gd2.addNumericField(
                    "Background Intensity", backgroundIntensity, 0, 5,
                    "0~255 (used only for manual background)");
            gd2.addNumericField(
                    "Patch Size", patchSize, 0, 5,
                    "pixels per parallel patch; smaller patches use less memory");
            gd2.addChoice("Calculation Backend", calculationBackends, "ND4J");
            gd2.addDialogListener((dialog, event) -> {
                java.awt.Choice automatic = (java.awt.Choice) dialog.getChoices().get(1);
                java.awt.Choice monitoring = (java.awt.Choice) dialog.getChoices().get(2);
                if ("true".equals(monitoring.getSelectedItem())) {
                    automatic.select("false");
                    ((java.awt.Choice) dialog.getChoices().get(0)).select("false");
                }
                return true;
            });
            PluginDialogStyle.apply(gd2, true);
            gd2.showDialog();

            if (gd2.wasCanceled())
                return;

            batchProcessing = Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
            adaptiveBackground =
                    Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
            monitorChanges =
                    Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
            backgroundIntensity = (int) gd2.getNextNumber();
            patchSize = (int) gd2.getNextNumber();
            calculationBackend =
                    calculationBackends[gd2.getNextChoiceIndex()];
        }

        if (monitorChanges) {
            batchProcessing = false; // Monitor exactly the active frame/pair.
            adaptiveBackground = false;
            StructuredLog.info("srFRC", "Background mode",
                    "Monitor Changes forces batch processing OFF and analyzes only the current frame/pair. "
                    + "Each result reports its absolute background threshold; manual threshold is ignored.");
        }
        int previewIndex = StackFrames.previewIndex(imp.getImageStackSize(), currentFrame,
                singleFrame, batchProcessing);
        if (previewIndex < 0)
            IJ.log("srFRC - no current-frame preview: paired mode needs a following frame. All computed batch maps are retained.");

        if (!RobustnessUtils.validateSrFRCSettings(
                blockSize, skip, backgroundIntensity, pixelSize, patchSize))
            return;

        RobustnessUtils.warnSuspiciousSampling(
                imp, pixelSize, Double.NaN, false);

        ImagePlus pairBatch = buildBatchPairStack(imp, singleFrame, batchProcessing);
        if (pairBatch == null)
            return;

        ImageStack pairStack = pairBatch.getStack();
        for (int i = 1; i <= pairStack.getSize(); i++) {
            pairStack.setProcessor(
                    InputImageUtils.normalizedImage(pairStack.getProcessor(i)), i);
        }

        float analysisPixelSize = singleFrame ? pixelSize * 2.0f : pixelSize;

        double[] ratios;
        if (monitorChanges) {
            ratios = new double[] {0.8, 1.0, 1.2};
        } else if (adaptiveBackground) {
            ratios = new double[] {1.0};
        } else {
            ratios = new double[] {0.0};
        }

        int nThreads = Runtime.getRuntime().availableProcessors();
        resetResolutionMaps();
        executor = Executors.newFixedThreadPool(nThreads);

        long stime = System.currentTimeMillis();
        StructuredLog.info("srFRC", "Backend", calculationBackend);
        StructuredLog.info("srFRC", "Quantitative map restoration",
                (singleFrame ? "cubic_spline" : "none") + "; original sampling = "
                + pixelSize + " nm; final positive lower bound = " + (2.3 * pixelSize) + " nm.");
        if ("Legacy".equals(calculationBackend)) {
            // Cite once per run, outside the frame, background-ratio and patch loops.
            IJ.log("srFRC - Legacy FRC reference: " + FRC.LEGACY_REFERENCE_URL);
        }
        StructuredLog.info(
                "srFRC",
                "Batch mode",
                batchProcessing
                        ? (singleFrame ? "All stack frames are processed independently."
                                       : "All consecutive frame pairs are processed.")
                        : "Only " + StackFrames.label(imp.getCurrentSlice(), singleFrame) + " is processed.");

        try {
            for (int groupIndex = 0; groupIndex < ratios.length; groupIndex++) {
                double ratio = ratios[groupIndex];
                rFRC(
                        pairBatch,
                        blockSize,
                        backgroundIntensity,
                        skip,
                        analysisPixelSize,
                        singleFrame,
                        adaptiveBackground,
                        monitorChanges,
                        ratio, groupIndex + 1,
                        calculationBackend, correlationThreshold, thresholdLabel, imp, pixelSize, previewIndex, batchProcessing);
            }
        } finally {
            if (executor != null)
                executor.shutdown();
        }

        long etime = System.currentTimeMillis();
        StructuredLog.info("srFRC", "Background results",
                getLastResolutionMaps().length + " background group(s) retained. Each results-table row shows the absolute background threshold (0-255).");
        float time = ((float) (etime - stime)) / 1000;
        StructuredLog.info(
                "srFRC", "Time", String.format("%.2f s", time));
    }

    /**
     * Build an interleaved pair stack:
     * A1, B1, A2, B2, ...
     *
     * Batch mode processes every frame/pair; otherwise only the current displayed frame/pair.
     * Double-frame mode creates consecutive temporal pairs:
     * (1,2), (2,3), ..., (N-1,N).
     */
    static ImagePlus buildBatchPairStack(ImagePlus imp, boolean singleFrame, boolean batchProcessing) {
        ImageStack input = InputImageUtils.normalizedStack(imp.getStack());
        int[] selected;
        try {
            selected = StackFrames.select(input.getSize(), imp.getCurrentSlice(), singleFrame, batchProcessing);
        } catch (IllegalArgumentException error) {
            IJ.showMessage("srFRC - stack input", error.getMessage());
            return null;
        }
        ImageStack batch = null;
        for (int frame : selected) {
            String label = StackFrames.label(frame, singleFrame);
            if (singleFrame) {
                ImagePlus pair = PreGenerator.getResampledImage(input.getProcessor(frame));
                if (batch == null) batch = new ImageStack(pair.getWidth(), pair.getHeight());
                batch.addSlice(label + " - A", pair.getStack().getProcessor(1).duplicate());
                batch.addSlice(label + " - B", pair.getStack().getProcessor(2).duplicate());
            } else {
                if (batch == null) batch = new ImageStack(imp.getWidth(), imp.getHeight());
                batch.addSlice(label + " - A", input.getProcessor(frame).duplicate());
                batch.addSlice(label + " - B", input.getProcessor(frame + 1).duplicate());
            }
        }
        return new ImagePlus("srFRC - input pairs", batch);
    }

    private String getBatchLabel(ImageStack stack, int pairIndex) {
        String label = stack.getSliceLabel(2 * pairIndex + 1);
        if (label == null || !label.endsWith(" - A"))
            return "Frame/Pair " + (pairIndex + 1);
        return label.substring(0, label.length() - " - A".length());
    }

    private boolean showDialog() {
        return true;
    }
    
    private void rFRC(ImagePlus stack, int blockSize, int backgroundIntensity, int skip,
            float pixelSize, boolean singleFrame, boolean adaptiveBackground, boolean monitorChanges, double ratio, int groupNumber,
            String calculationBackend, String correlationThreshold, String thresholdLabel,
            ImagePlus originalInput, float originalPixelSizeNm, int previewIndex, boolean batchProcessing) {

        final int finalBlockSize =
                (blockSize % 2 != 0) ? blockSize + 1 : blockSize;
        final int finalSkip = min(skip, finalBlockSize / 2);
        final float finalPixelSize = pixelSize;
        final boolean finalSingleFrame = singleFrame;
        final boolean useOtsuBackground = adaptiveBackground || monitorChanges;
        final double finalRatio = ratio;
        final int finalBackgroundIntensity = backgroundIntensity;
        final int finalpatchSize = patchSize;
        final String finalCalculationBackend = calculationBackend;

        int w = stack.getWidth();
        int h = stack.getHeight();
        int l = stack.getImageStackSize();
        int pairCount = l / 2;
        int padw = w + finalBlockSize;
        int padh = h + finalBlockSize;

        ImageStack ims = stack.getStack();
        ImageStack result = new ImageStack(w, h);
        ImageStack resultmask = new ImageStack(w, h);
        // A separate Otsu cutoff is computed for every acquired frame/pair.
        // Preserve its absolute value in preview order, not only its ratio.
        List<Float> backgroundThresholds = new ArrayList<Float>();

        // The per-frame absolute threshold is a ResultsTable column. Use a
        // neutral group index only to distinguish simultaneous monitor windows.
        String resultsTitle = "srFRC - results" + (monitorChanges ? " - group " + groupNumber : "");
        AnalysisResults results = new AnalysisResults(resultsTitle);

        AtomicInteger progressCounter = new AtomicInteger(0);
        int xpatchs = (h + finalpatchSize - 1) / finalpatchSize;
        int ypatchs = (w + finalpatchSize - 1) / finalpatchSize;
        final int totalpatchs = xpatchs * ypatchs;
        final int totalTasks = Math.max(1, totalpatchs * pairCount);

        int pairIndex = 0;
        for (int t = 1; t < l; t += 2, pairIndex++) {
            String batchLabel = getBatchLabel(ims, pairIndex);

            ImageProcessor ip1s = ims.getProcessor(t);
            ImageProcessor ip2s = ims.getProcessor(t + 1);
            FloatProcessor ip1 =
                    createPaddedImage(ip1s, padw, padh).convertToFloatProcessor();
            FloatProcessor ip2 =
                    createPaddedImage(ip2s, padw, padh).convertToFloatProcessor();

            final float max1 = (float) ip1.getMax();
            final float max2 = (float) ip2.getMax();

            final float[] biContainer = new float[1];
            if (useOtsuBackground) {
                biContainer[0] = (float) (finalRatio * grayThreshold(ip1));
                StructuredLog.info(
                        "srFRC",
                        monitorChanges ? "Background comparison" : "Automatic background",
                        batchLabel + ": Background threshold = "
                                + String.format(java.util.Locale.US, "%.3f", biContainer[0])
                                + " (0-255).");
            } else {
                biContainer[0] = finalBackgroundIntensity;
                StructuredLog.info(
                        "srFRC",
                        "Manual background",
                        batchLabel + ": Background threshold = "
                                + String.format(java.util.Locale.US, "%.3f", biContainer[0])
                                + " (0-255).");
            }

            final float[] pixels1 = (float[]) ip1.getPixels();
            final float[] pixels2 = (float[]) ip2.getPixels();
            final int finalPadw = padw;
            final int finalPadh = padh;

            List<Callable<List<double[]>>> tasks = new ArrayList<>();

            for (int patchX = 0; patchX < xpatchs; patchX++) {
                for (int patchY = 0; patchY < ypatchs; patchY++) {
                    final int startX = patchX * finalpatchSize;
                    final int startY = patchY * finalpatchSize;
                    final int endX = Math.min(startX + finalpatchSize, h);
                    final int endY = Math.min(startY + finalpatchSize, w);

                    tasks.add(() -> {
                        List<double[]> patchResults = new ArrayList<>();

                        for (int x = ((startX + finalSkip - 1) / finalSkip) * finalSkip; x < endX; x += finalSkip) {
                            for (int y = ((startY + finalSkip - 1) / finalSkip) * finalSkip; y < endY; y += finalSkip) {
                                float[] roiPixels1 = extractROI(
                                        pixels1, finalPadw, finalPadh,
                                        y, x, finalBlockSize, finalBlockSize);
                                float[] roiPixels2 = extractROI(
                                        pixels2, finalPadw, finalPadh,
                                        y, x, finalBlockSize, finalBlockSize);

                                float suming = 0;
                                int flage = 0;
                                for (int xsum = 0; xsum < finalSkip; xsum++) {
                                    for (int ysum = 0; ysum < finalSkip; ysum++) {
                                        int centerX =
                                                finalBlockSize / 2 - 1 + xsum;
                                        int centerY =
                                                finalBlockSize / 2 - 1 + ysum;
                                        int idx =
                                                centerY * finalBlockSize + centerX;
                                        float value1 = roiPixels1[idx];
                                        float value2 = roiPixels2[idx];
                                        if (max1 > 0)
                                            suming += value1 / max1;
                                        if (max2 > 0)
                                            suming += value2 / max2;
                                        flage += 2;
                                    }
                                }
                                if (flage > 0)
                                    suming /= flage;

                                if ((suming * 255) > biContainer[0]) {
                                    FloatProcessor preIpROI1 =
                                            new FloatProcessor(
                                                    finalBlockSize,
                                                    finalBlockSize,
                                                    roiPixels1);
                                    FloatProcessor preIpROI2 =
                                            new FloatProcessor(
                                                    finalBlockSize,
                                                    finalBlockSize,
                                                    roiPixels2);

                                    double padFactor = 1;
                                    int padSize = Math.max(128, finalBlockSize);
                                    FloatProcessor ipROI1;
                                    FloatProcessor ipROI2;

                                    if (finalSingleFrame) {
                                        ipROI1 = createPaddedImage(
                                                preIpROI1, padSize, padSize)
                                                .convertToFloatProcessor();
                                        ipROI2 = createPaddedImage(
                                                preIpROI2, padSize, padSize)
                                                .convertToFloatProcessor();
                                        padFactor =
                                                Math.sqrt(
                                                        (double) padSize
                                                        / finalBlockSize);
                                    } else {
                                        ipROI1 = preIpROI1;
                                        ipROI2 = preIpROI2;
                                    }

                                    double resolutionNm;
                                    if ("ND4J".equals(finalCalculationBackend)) {
                                        resolutionNm = calculateND4JResolutionNm(
                                                ipROI1,
                                                ipROI2,
                                                finalSingleFrame,
                                                padFactor,
                                                finalPixelSize,
                                                correlationThreshold);
                                    } else {
                                        resolutionNm = calculateLegacyResolutionNm(
                                                ipROI1,
                                                ipROI2,
                                                finalSingleFrame,
                                                padFactor,
                                                finalPixelSize,
                                                correlationThreshold);
                                    }

                                    if (Double.isFinite(resolutionNm)
                                            && resolutionNm > 0) {
                                        patchResults.add(
                                                new double[] {y, x, resolutionNm});
                                    }
                                }
                            }
                        }

                        int progress = progressCounter.incrementAndGet();
                        IJ.showProgress((double) progress / totalTasks);
                        IJ.showStatus(
                                "Processing srFRC patch: "
                                + progress + "/" + totalTasks);
                        return patchResults;
                    });
                }
            }

            List<double[]> vectors = new ArrayList<>();
            try {
                List<Future<List<double[]>>> futures = executor.invokeAll(tasks);
                for (Future<List<double[]>> future : futures) {
                    try {
                        vectors.addAll(future.get());
                    } catch (ExecutionException e) {
                        Throwable cause =
                                (e.getCause() != null) ? e.getCause() : e;
                        StructuredLog.warning(
                                "srFRC",
                                finalCalculationBackend + " backend",
                                batchLabel + ": "
                                + cause.getClass().getSimpleName() + ": "
                                + String.valueOf(cause.getMessage()));
                        IJ.handleException(e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IJ.handleException(e);
            }

            IJ.showStatus("Ensemble srFRC results");

            int nValues = vectors.size();
            float[] xPositions = new float[nValues];
            float[] yPositions = new float[nValues];
            float[] values = new float[nValues];

            float preliminaryMin = Float.POSITIVE_INFINITY;
            int vectorCounter = 0;
            for (double[] vector : vectors) {
                xPositions[vectorCounter] = (int) vector[0];
                yPositions[vectorCounter] = (int) vector[1];
                values[vectorCounter] = (float) vector[2];
                preliminaryMin = Math.min(values[vectorCounter], preliminaryMin);
                vectorCounter++;
            }

            float[] rFRCMAP = new float[w * h];
            float[] rFRCMASK = new float[w * h];

            if (nValues > 0 && Float.isFinite(preliminaryMin)
                    && preliminaryMin > 0) {
                for (int p = 0; p < nValues; p++) {
                    ResolutionMapResampler.fillBlock(rFRCMAP, w, h,
                            (int) xPositions[p], (int) yPositions[p], finalSkip, values[p]);
                    ResolutionMapResampler.fillBlock(rFRCMASK, w, h,
                            (int) xPositions[p], (int) yPositions[p], finalSkip,
                            max(values[p] / preliminaryMin - 1.4f, 0));
                }
            }

            if (finalSkip == 1)
                rFRCMAP = AMF(rFRCMAP, w, h);

            Percentile.applyPercentileClipping(rFRCMAP, 0, 1);

            // Scientific summaries use the cleaned native rolling grid, before
            // any spline interpolation changes sample values or their weights.
            ResolutionMapStatistics.finalizeMap(rFRCMAP, originalPixelSizeNm);
            ResolutionMapStatistics statistics = ResolutionMapStatistics.calculate(rFRCMAP);
            if (statistics.count == 0) {
                StructuredLog.warning("srFRC", "No valid local FRC values",
                        batchLabel + ": No valid foreground resolution was obtained. Summary values are NaN; check the background threshold and pixel size.");
            }
            results.addSrFRC(batchLabel, finalSingleFrame, thresholdLabel,
                    statistics.mean, statistics.min, statistics.max, statistics.rFRC,
                    biContainer[0]);

            result.addSlice(batchLabel, rFRCMAP);
            resultmask.addSlice(batchLabel, rFRCMASK);
            backgroundThresholds.add(biContainer[0]);
        }

        ImagePlus image = new ImagePlus("srFRC - resolution map", result);
        if (finalSingleFrame) {
            image = ResolutionMapResampler.restore(
                    image, originalInput.getWidth(), originalInput.getHeight());
        }
        // The restored map is for display/export; retain the pre-upsampling summary.
        // The analysis uses the pixel size entered in the dialog, which takes
        // precedence over Image > Properties. Match the exported map axes to it.
        ImagePropertiesUtils.applyUserXYPixelSizeNm(image, originalInput, originalPixelSizeNm);
        image.getCalibration().disableDensityCalibration();
        image.getCalibration().setValueUnit("nm");
        image.setProperty("srFRC.inputPixelSizeNm", originalPixelSizeNm);
        image.setProperty("srFRC.mapInterpolation", finalSingleFrame ? "cubic_spline" : "none");
        image.setProperty("srFRC.statisticsStage", "pre_upsampling");
        image.setProperty("srFRC.statisticsMapSize", w + "x" + h);
        image.setProperty("srFRC.backgroundThresholds",
                backgroundThresholds.toArray(new Float[backgroundThresholds.size()]));
        image.setProperty("srFRC.backgroundRatio", finalRatio);
        image.setProperty("srFRC.backgroundMode", monitorChanges ? "monitor"
                : (adaptiveBackground ? "automatic" : "manual"));
        NJ_LUT.applyLUT_PANEL_rFRC(image);
        retainResolutionMap(image);
        if (batchProcessing && !monitorChanges) {
            // Both windows show the FULL stack. Neither is the retained numerical result.
            ImagePlus rawStack = copyDisplayStack(image, "srFRC - resolution map stack");
            NJ_LUT.applyLUT_PANEL_rFRC(rawStack);
            ResolutionMapDisplay.enablePerFrameDisplayRange(rawStack);
            rawStack.show();
            if (previewIndex >= 0) rawStack.setSlice(previewIndex + 1);
            // Render a separate RGB visualization per frame, each with its own
            // absolute 0..sliceMax scale and final-size colorbar labels.
            // The original Float32 stack and stored results stay unchanged.
            ImagePlus barStack = ResolutionMapDisplay.perFrameColorBarStack(
                    image, "srFRC - per-frame colorbar stack (RGB preview)");
            barStack.show();
            if (previewIndex >= 0) barStack.setSlice(previewIndex + 1);
        } else if (previewIndex >= 0) {
            ImagePlus preview = currentFramePreview(image, previewIndex);
            float actualThreshold = backgroundThresholds.get(previewIndex);
            preview.setTitle(preview.getTitle() + " - Background threshold = "
                    + String.format(java.util.Locale.US, "%.1f", actualThreshold));
            preview.setProperty("srFRC.backgroundThreshold", actualThreshold);
            NJ_LUT.applyLUT_PANEL_rFRC(preview);
            if (monitorChanges) preview.show(); // three independent groups, current frame only
            else ResolutionMapDisplay.showWithColorBar(preview);
        }

        if (finalRatio < 0.1 && !batchProcessing && previewIndex >= 0) {
            ImagePlus mask = new ImagePlus("", resultmask);
            ImageConverter ic = new ImageConverter(mask);
            ic.convertToGray8();

            ImageStack msstack = new ImageStack(w, h);
            for (int slice = 1; slice <= mask.getImageStackSize(); slice++) {
                ImageStack msslice = ostu(mask.getStack().getProcessor(slice));
                msstack.addSlice(
                        resultmask.getSliceLabel(slice),
                        msslice.getProcessor(1));
            }

            ImagePlus maskshow =
                    new ImagePlus("srFRC - resolution mask", msstack);
            if (finalSingleFrame) {
                ImageStack originalSizeMask = new ImageStack(originalInput.getWidth(), originalInput.getHeight());
                for (int slice = 1; slice <= msstack.getSize(); slice++) {
                    ImageProcessor processor = msstack.getProcessor(slice).duplicate();
                    processor.setInterpolationMethod(ImageProcessor.NONE);
                    originalSizeMask.addSlice(msstack.getSliceLabel(slice),
                            processor.resize(originalInput.getWidth(), originalInput.getHeight()));
                }
                maskshow = new ImagePlus("srFRC - resolution mask", originalSizeMask);
            }
            ImagePropertiesUtils.applyUserXYPixelSizeNm(maskshow, originalInput, originalPixelSizeNm);
            NJ_LUT.applyLUT_PANEL_rFRCmask(maskshow);
            ImagePlus maskPreview = currentFramePreview(maskshow, previewIndex);
            float actualThreshold = backgroundThresholds.get(previewIndex);
            maskPreview.setTitle(maskPreview.getTitle() + " - Background threshold = "
                    + String.format(java.util.Locale.US, "%.1f", actualThreshold));
            maskPreview.setProperty("srFRC.backgroundThreshold", actualThreshold);
            maskPreview.show();
        }


    }

    /** Final native-resolution Float32 stack copy; the colorbar is applied only to this copy. */
    private static ImagePlus copyDisplayStack(ImagePlus image, String title) {
        ImageStack source = image.getStack();
        ImageStack copy = new ImageStack(source.getWidth(), source.getHeight());
        for (int slice = 1; slice <= source.getSize(); slice++) {
            copy.addSlice(source.getSliceLabel(slice), source.getProcessor(slice).duplicate());
        }
        ImagePlus shown = new ImagePlus(title, copy);
        shown.setCalibration(image.getCalibration().copy());
        return shown;
    }

    private static ImagePlus currentFramePreview(ImagePlus image, int index) {
        String label = image.getStack().getSliceLabel(index + 1);
        ImagePlus preview = new ImagePlus(image.getTitle() + " - " + label,
                image.getStack().getProcessor(index + 1).duplicate());
        preview.setCalibration(image.getCalibration().copy());
        for (String key : new String[] {"srFRC.mapInterpolation", "srFRC.statisticsStage", "srFRC.statisticsMapSize",
                "srFRC.backgroundRatio", "srFRC.backgroundMode"}) {
            Object value = image.getProperty(key);
            if (value != null) preview.setProperty(key, value);
        }
        return preview;
    }

    /**
     * Local srFRC calculation using the ND4J-based Correlations backend.
     *
     * Correlations expects pixel size in micrometres and reports resolution
     * in nanometres. executeRFRC() applies the single-frame correction and
     * selected primary criterion followed by the existing calibrated sigma fallbacks.
     */
    private double calculateND4JResolutionNm(FloatProcessor ipROI1,
                                             FloatProcessor ipROI2,
                                             boolean singleFrame,
                                             double padFactor,
                                             float pixelSizeNm,
                                             String correlationThreshold) {
        ImagePlus im1 = new ImagePlus("srFRC-ND4J-A", ipROI1);
        ImagePlus im2 = new ImagePlus("srFRC-ND4J-B", ipROI2);

        Correlations correlations = new Correlations(
                im1, im2, pixelSizeNm / 1000.0);
        correlations.executeRFRC(singleFrame, padFactor, correlationThreshold);

        double resolutionNm = correlations.getResolution();
        return (Double.isFinite(resolutionNm) && resolutionNm > 0)
                ? resolutionNm : Double.NaN;
    }

    /**
     * Local srFRC calculation using the previous FRC/FRCCurve implementation.
     *
     * Evaluate the selected 1/7 or 3-sigma criterion on the same smoothed curve.
     * Retain the legacy 5-sigma * 0.6 then 2-sigma * 1.5 fallback order.
     *
     * @see FRC#LEGACY_REFERENCE_URL
     */
    private double calculateLegacyResolutionNm(FloatProcessor ipROI1,
                                               FloatProcessor ipROI2,
                                               boolean singleFrame,
                                               double padFactor,
                                               float pixelSizeNm,
                                               String correlationThreshold) {
        FRC myFRC = new FRC();
        FRCCurve frcCurve = myFRC.calculateFrcCurve(ipROI1, ipROI2);
        FRCCurve smoothedFRCCurve;

        FRC.ThresholdMethod method = "1/7".equals(correlationThreshold)
                ? FRC.ThresholdMethod.FIXED_1_OVER_7 : FRC.ThresholdMethod.THREE_SIGMA;
        if (singleFrame) {
            smoothedFRCCurve = Correction.applyCorrection(
                    FRC.getSmoothedCurve(frcCurve, false));
        } else {
            smoothedFRCCurve = FRC.getSmoothedCurve(frcCurve, false);
        }
        // Padding calibration applies to sigma curves; the hard cutoff stays 1/7.
        double thresholdFactor = singleFrame && method == FRC.ThresholdMethod.THREE_SIGMA
                ? padFactor : 1.0;
        double resolutionPixels = FRC.calculateFireNumber(
                smoothedFRCCurve, method, thresholdFactor);

        if (!Double.isFinite(resolutionPixels) || resolutionPixels <= 0)
            resolutionPixels = myFRC.calculateFireNumber(
                    ipROI1, ipROI2, FRC.ThresholdMethod.FIVE_SIGMA, padFactor) * 0.6;
        if (!Double.isFinite(resolutionPixels) || resolutionPixels <= 0)
            resolutionPixels = myFRC.calculateFireNumber(
                    ipROI1, ipROI2, FRC.ThresholdMethod.TWO_SIGMA, padFactor) * 1.5;

        if (!Double.isFinite(resolutionPixels) || resolutionPixels <= 0)
            return Double.NaN;

        return resolutionPixels * pixelSizeNm;
    }

    // Extract the ROI using direct array access
    private float[] extractROI(float[] sourcePixels, int sourceWidth, int sourceHeight, 
                               int x, int y, int roiWidth, int roiHeight) {
        float[] roiPixels = new float[roiWidth * roiHeight];
        
        for (int j = 0; j < roiHeight; j++) {
            int sourceY = y + j;
            if (sourceY >= sourceHeight) sourceY = sourceHeight - 1;
            
            for (int i = 0; i < roiWidth; i++) {
                int sourceX = x + i;
                if (sourceX >= sourceWidth) sourceX = sourceWidth - 1;
                
                int sourceIndex = sourceY * sourceWidth + sourceX;
                int roiIndex = j * roiWidth + i;
                roiPixels[roiIndex] = sourcePixels[sourceIndex];
            }
        }
        
        return roiPixels;
    }

    private float[] AMF(float[] fp, int w, int h) {

		int width = w;
		int height = h;
		int window = 3;
		float threshold = 2;
		float[] pixels = fp;
		float[] temp = new float[window * window];
		float[] newpix = pixels;
		int win = (3 - 1) / 2;

		for (int y = win; y < height - win; y++) {
			for (int x = win; x < width - win; x++) {
				int flage = 0;

				for (int i = -win; i <= win; i++) {
					for (int j = -win; j <= win; j++) {
						temp[flage] = newpix[((x + i + (y + j) * width))];
						flage = flage + 1;
					}
				}
				Arrays.sort(temp);
				if (threshold * temp[(flage) / 2] < newpix[(x + y * width)]) {
					pixels[(x + y * width)] = temp[flage / 2];
				}
			}
		}
		return pixels;
	}

    private ImageStack ostu(ImageProcessor ip) {
		int width = ip.getWidth();
		int height = ip.getHeight();

		GrayLevelClass.N = width * height;
		GrayLevelClass.probabilityHistogramDone = false;
		GrayLevelClass C1 = new GrayLevelClass((ByteProcessor) ip, true);
		GrayLevelClass C2 = new GrayLevelClass((ByteProcessor) ip, false);

		float fullMu = C1.getOmega() * C1.getMu() + C2.getOmega() * C2.getMu();

		double sigmaMax = 0;
		int threshold = 0;

		/** Start **/
		for (int i = 0; i < 255; i++) {
			double sigma = C1.getOmega() * (Math.pow(C1.getMu() - fullMu, 2))
					+ C2.getOmega() * (Math.pow(C2.getMu() - fullMu, 2));

			if (sigma > sigmaMax) {
				sigmaMax = sigma;
				threshold = C1.getThreshold();
			}

			C1.addToEnd();
			C2.removeFromBeginning();
		}

//		ImagePlus imp = NewImage.createByteImage("Threshold", width, height, 1, NewImage.FILL_WHITE);
		ImagePlus imp = new ImagePlus("", ip);
		ImageProcessor nip = imp.getProcessor();

		byte[] pixels = (byte[]) ((ByteProcessor) ip).getPixels();

		int offset = 0;

		for (int y = 0; y < height; y++) {
			offset = y * width;
			for (int x = 0; x < width; x++) {
				if ((pixels[offset + x] & 0xff) <= threshold)
					nip.putPixel(x, y, 0);
			}
		}
		return imp.getStack();
	}
    
    private int grayThreshold(ImageProcessor ip) {
        int width = ip.getWidth();
        int height = ip.getHeight();

        GrayLevelClass.N = width * height;
        GrayLevelClass.probabilityHistogramDone = false;
        GrayLevelClass C1 = new GrayLevelClass(ip.convertToByteProcessor(), true);
        GrayLevelClass C2 = new GrayLevelClass(ip.convertToByteProcessor(), false);

        float fullMu = C1.getOmega() * C1.getMu() + C2.getOmega() * C2.getMu();

        double sigmaMax = 0;
        int threshold = 0;

        /** Start **/
        for (int i = 0; i < 255; i++) {
            double sigma = C1.getOmega() * (Math.pow(C1.getMu() - fullMu, 2))
                    + C2.getOmega() * (Math.pow(C2.getMu() - fullMu, 2));

            if (sigma > sigmaMax) {
                sigmaMax = sigma;
                threshold = C1.getThreshold();
            }
            C1.addToEnd();
            C2.removeFromBeginning();
        }
        return threshold;
    }
    
    private FloatProcessor getROI(FloatProcessor ip, int x, int y, int w, int h) {
        FloatProcessor ipCrop = new FloatProcessor(w, h);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                ipCrop.setf(i, j, ip.getf(x + i, y + j));
            }
        }
        return ipCrop;
    }

    class FRCData {
        public FloatProcessor FRCMap;
        public FloatProcessor FRCMask;
        public double meanFRC;
        public double stdDevFRC;
        public double minFRC;
        public double maxFRC;
        public double rFRC;
        public int nValues;
    }

    /**
     * createPaddedImage creates an image with added border to decrease border
     * problematic with filters.
     * 
     * @param ip           - original image processor
     * @param paddedWidth  - The final padded image width
     * @param paddedHeight - The final padded image height
     * @param paddedOption - 0 , 1 - asymmetric or symmetric
     * @return padded imageProcessor
     */
    private static ImageProcessor createPaddedImage(ImageProcessor ip, int paddedWidth, int paddedHeight) {
		ImageProcessor paddedProcessor;
		int imageWidth = ip.getWidth();
		int extraWidth = paddedWidth - imageWidth;
		int imageHeight = ip.getHeight();
		int extraHeight = paddedHeight - imageHeight;
//		System.out.printf("%d",paddedWidth);
		// ImagePlus paddedImg;//For debugging -> possible to show padded image.

		// Cast image in 8bits/16bits in 32 bits images.
		float[] pixArray = (float[]) ip.convertToFloatProcessor().getPixels();
		paddedProcessor = new FloatProcessor(paddedWidth, paddedHeight);
		float[] paddedArray = new float[paddedWidth * paddedHeight];
		// Start by copying the interior of the image + first padded rows and columns.
		for (int row = 0; row < imageHeight; row++) {
			int imageOffset = row * imageWidth;
			int paddedOffset = row * paddedWidth + ((extraHeight / 2) * paddedWidth);

			for (int col = 0; col < imageWidth; col++) {
				// COPY INTERIOR
				paddedArray[col + paddedOffset + extraWidth / 2] = pixArray[col + imageOffset];
			}
		}

		// Fill top and bottom border
		for (int row = 0; row < extraHeight / 2; row++) {
			// Calculate the different index for the different offSets
			int topOffset = paddedWidth * (extraHeight / 2 - 1 - row);
			int firstTopElementOffset = (extraHeight / 2 + row) * paddedWidth;
			int secondTopElementOffset = firstTopElementOffset + paddedWidth;

			int botOffset = paddedWidth * (paddedHeight - extraHeight / 2 + row);
			int firstBotElementOffset = paddedWidth * (paddedHeight - extraHeight / 2 - 1 - row);
			int secondBotElementOffset = firstBotElementOffset - paddedWidth;

			int colOffset = (extraWidth / 2) - row;

			for (int col = 0; col < paddedWidth - (extraWidth / 2 - row); col++) {

				// FILL TOP
				int firstTopIdx = col + colOffset + firstTopElementOffset;
				int secondTopIdx = col + colOffset + secondTopElementOffset;
				int topIdx = col + colOffset + topOffset;
				float diff = paddedArray[secondTopIdx] - paddedArray[firstTopIdx];
				float value = 0;

				value = (paddedArray[topIdx + paddedWidth]) + diff;
				paddedArray[topIdx] = value;

				// FILL BOT
				int firstBotIdx = col + colOffset + firstBotElementOffset;
				int secondBotIdx = col + colOffset + secondBotElementOffset;
				int botIdx = col + colOffset + botOffset;

				diff = paddedArray[secondBotIdx] - paddedArray[firstBotIdx];

				value = paddedArray[botIdx - paddedWidth] + diff;
				paddedArray[botIdx] = value;

			}

		}

		// Fill left and right
		for (int col = 0; col < extraWidth / 2; col++) {
			int colOffset = extraWidth / 2 - col;
			int rightColOffset = paddedWidth - 1 - extraWidth / 2 + col;
			for (int row = 0; row < paddedHeight; row++) {
				int rowOffset = row * paddedWidth;

				int currentLeftValueIdx = rowOffset + colOffset;
				int leftIdx = currentLeftValueIdx - 1;
				int firstLeftElementIdx = rowOffset + colOffset + col * 2;
				int secondLeftElementIdx = firstLeftElementIdx + 1;

				int currentRightValueIdx = rowOffset + rightColOffset;
				int rightIdx = currentRightValueIdx + 1;
				int firstRightElementIdx = rowOffset + rightColOffset - col * 2;
				int secondRightElementIdx = firstRightElementIdx - 1;

				float value = 0;
				// LEFT
				float diff = paddedArray[secondLeftElementIdx] - paddedArray[firstLeftElementIdx];

				value = paddedArray[currentLeftValueIdx] + diff;
				paddedArray[leftIdx] = value;
				// RIGHT
				diff = paddedArray[secondRightElementIdx] - paddedArray[firstRightElementIdx];

				value = paddedArray[currentRightValueIdx] + diff;
				paddedArray[rightIdx] = value;
			}
		}
		paddedProcessor.setPixels(paddedArray);
		return paddedProcessor;
	}
    
    public static void main(String[] args) {
        Class<?> clazz = srFRCConcurrent.class;
        String url = clazz.getResource("/" + clazz.getName().replace('.', '/') + ".class").toString();
        String pluginsDir = url.substring("file:".length(),
                url.length() - clazz.getName().length() - ".class".length());
        System.setProperty("plugins.dir", pluginsDir);
        new ImageJ();
        ImagePlus image = IJ.openImage();
        image.show();
        IJ.runPlugIn(clazz.getName(), "");
    }
}
