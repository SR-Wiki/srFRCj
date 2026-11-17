package com.HIT.WeisongZhao.utils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import ij.IJ;
import ij.gui.Plot;

public class DirectionalResolutionInference {
    private INDArray[] fftA;
    private INDArray[] fftB;
    private Params params;
    private Correlations[] sectors;
    private INDArray angles;
    private int depth, height, width;
    private double zResolution = Double.NaN;
    private double fitError = Double.NaN;
    private boolean extrapolationAttempted;
    private double a, b;
    private int validNum;
    private double[] validAngles = new double[0];
    private double[] validCutoffs = new double[0];
    private double[] validResolutions = new double[0];
    private double[] xPoints = new double[0];
    private double[] yPoints = new double[0];

    public DirectionalResolutionInference(
            INDArray[] fftA,
            INDArray[] fftB,
            Params params,
            double xyResolution) {
        this.fftA = fftA;
        this.fftB = fftB;
        this.params = params;
        this.sectors = new Correlations[params.getNumOfCones()];
        this.depth = (int) fftA[0].size(0);
        this.height = (int) fftA[0].size(1);
        this.width = (int) fftA[0].size(2);
        this.a = 1000.0 / xyResolution;
    }

    public double[][] getValidSample() {
        double[][] pairs = new double[2][validNum * 4];
        for (int i = 0; i < validNum; i++) {
            int base = 4 * i;
            pairs[0][base] = this.validAngles[i];
            pairs[1][base] = this.validResolutions[i];
            pairs[0][base + 1] = Math.PI - this.validAngles[i];
            pairs[1][base + 1] = this.validResolutions[i];
            pairs[0][base + 2] = this.validAngles[i] + Math.PI;
            pairs[1][base + 2] = this.validResolutions[i];
            pairs[0][base + 3] = 2 * Math.PI - this.validAngles[i];
            pairs[1][base + 3] = this.validResolutions[i];
        }
        return pairs;
    }

    public double getZResolution() {
        return this.zResolution;
    }

    public double getFitError() { return fitError; }

    public boolean wasExtrapolationAttempted() { return extrapolationAttempted; }

    /**
     * Runs directional FSC and the elliptical resolution fit.
     *
     * @return true when a valid DRI result was produced; false when the
     *         estimation was stopped cleanly because no valid cutoff or fit was found.
     */
    public boolean execute(String thresholdType) {
        zResolution = Double.NaN;
        fitError = Double.NaN;
        extrapolationAttempted = false;
        double[] angleLimits = params.getAngleLimits();
        this.angles = params.getNumOfCones() == 1
                ? Nd4j.create(new double[] {params.getSingleConeAngle()})
                : Nd4j.linspace(angleLimits[0], angleLimits[1], params.getNumOfCones(), DataType.DOUBLE);

        VolumeAngle volumeAngle = new VolumeAngle(depth, height, width);
        double angleStep = params.getNumOfCones() == 1
                ? params.getAngleStep() : angles.getDouble(1) - angles.getDouble(0);
        int extrapolationUsedCount = 0;

        for (int i = 0; i < params.getNumOfCones(); i++) {
            IJ.showProgress(i, params.getNumOfCones());
            double angle = angles.getDouble(i);
            double lower = Math.max(0.0, angle - angleStep / 2.0);
            double upper = angle + angleStep / 2.0;
            volumeAngle.setAngle(lower, upper);
            INDArray mask = volumeAngle.getMask();

            INDArray[] tmpFFTA = new INDArray[2];
            INDArray[] tmpFFTB = new INDArray[2];
            tmpFFTA[0] = fftA[0].mul(mask);
            tmpFFTA[1] = fftA[1].mul(mask);
            tmpFFTB[0] = fftB[0].mul(mask);
            tmpFFTB[1] = fftB[1].mul(mask);

            this.sectors[i] = new Correlations(tmpFFTA, tmpFFTB, params.getPixelSize());
            // Directional sectors can be numerous; suppress one fit-summary row
            // per sector and report automatic extrapolation as an aggregate below.
            this.sectors[i].setExtrapolationFitLoggingEnabled(false);
            this.sectors[i].execute(
                    3,
                    thresholdType,
                    params.isSingleFrame(),
                    params.samplingInZ(),
                    false);


        }

        // Check every measured direction BEFORE extrapolation. One or more
        // measured crossings exclude all unresolved sectors from the fit.
        extrapolationAttempted = extrapolateOnlyIfNoMeasuredCrossings(sectors, thresholdType);
        for (Correlations sector : sectors) {
            if (sector.wasExtrapolationUsed()) extrapolationUsedCount++;
        }

        int finiteCount = 0;
        int unresolvedCrossingCount = 0;
        for (Correlations sector : sectors) {
            double resolution = sector.getResolution();
            if (RobustnessUtils.isPositiveFinite(resolution)) {
                finiteCount++;
            } else if (sector.isExtrapolationRequired()) {
                unresolvedCrossingCount++;
            }
        }

        // The DRI-specific no-valid-crossing warning is
        // shown only when NONE of the directional FSC curves provides a valid
        // threshold crossing.  A few failed sectors are normal and are simply
        // excluded as long as enough valid directions remain for the fit.
        if (finiteCount == 0) {
            RobustnessUtils.showDRINoValidCrossingWarning(
                    params.getNumOfCones(),
                    thresholdType,
                    params.isSingleFrame(),
                    params.samplingInZ());
            return false;
        }

        // Any non-zero number of valid directional crossings is passed to DRI.
        // There is no minimum crossing-count gate here: 1, 2, or more valid
        // directions follow the same fitting path.  Only the zero-valid case
        // above triggers the no-valid-crossing warning.

        if (!directionalResolutionInference())
            return false;

        // Successful automatic/advanced processing notes are intentionally reported
        // only in the structured log table. No success popup is shown.
        if (extrapolationUsedCount > 0) {
            RobustnessUtils.showExtrapolationApplied("directional FSC", extrapolationUsedCount);
        }
        if (params.isSingleFrame() && params.samplingInZ()) {
            RobustnessUtils.logZSamplingApplied("sFSC/DRI directional FSC");
        }
        if (unresolvedCrossingCount > 0) {
            RobustnessUtils.logDRIExcludedCrossings(
                    unresolvedCrossingCount,
                    params.getNumOfCones(),
                    params.isSingleFrame() && params.samplingInZ());
        }
        return true;
    }

    /** Measured crossings take priority over all extrapolated estimates. */
    static boolean extrapolateOnlyIfNoMeasuredCrossings(Correlations[] sectors, String thresholdType) {
        for (Correlations sector : sectors) {
            if (RobustnessUtils.isPositiveFinite(sector.getResolution())) return false;
        }
        boolean attempted = false;
        for (Correlations sector : sectors) {
            sector.extrapolateIfRequired(thresholdType);
            attempted |= sector.wasExtrapolationAttempted();
        }
        return attempted;
    }

    private boolean directionalResolutionInference() {
        List<Double> cutoffList = new ArrayList<Double>();
        List<Integer> validIndices = new ArrayList<Integer>();

        for (int i = 0; i < params.getNumOfCones(); i++) {
            double resolution = sectors[i].getResolution();
            double angle = angles.getDouble(i);
            double angleInDegree = 180.0 * angle / Math.PI;
            StructuredLog.info(
                    "sFSC/DRI",
                    "Directional crossing",
                    "Angle = " + String.format("%d", (int) angleInDegree)
                    + " deg; Resolution = " + String.valueOf(resolution) + " nm");

            if (RobustnessUtils.isPositiveFinite(resolution)) {
                cutoffList.add(1000.0 / resolution);
                validIndices.add(i);
            }
        }

        this.validNum = cutoffList.size();
        // execute() has already handled the zero-valid case.  Keep DRI fitting
        // independent of an arbitrary minimum number of valid directions.

        this.validCutoffs = new double[validNum];
        this.xPoints = new double[validNum];
        this.yPoints = new double[validNum];
        this.validAngles = new double[validNum];
        this.validResolutions = new double[validNum];

        for (int i = 0; i < validNum; i++) {
            validCutoffs[i] = cutoffList.get(i);
            validResolutions[i] = 1000.0 / validCutoffs[i];
            double curAngle = angles.getDouble(validIndices.get(i));
            validAngles[i] = curAngle;
            xPoints[i] = validCutoffs[i] * Math.cos(curAngle);
            yPoints[i] = validCutoffs[i] * Math.sin(curAngle);
        }

        double topSum = 0.0;
        double botSum = 0.0;
        for (int i = 0; i < validNum; i++) {
            double x = xPoints[i];
            double y = yPoints[i];
            double radial = Math.sqrt(x * x + y * y) + 1e-4;
            double weight = x / radial;
            topSum += weight * y * y * (1.0 - x * x / (a * a));
            botSum += weight * Math.pow(y, 4);
        }

        if (!(botSum > 0.0) || Double.isNaN(botSum) || Double.isInfinite(botSum)) {
            IJ.showMessage("sFSC - DRI fit warning",
                    "The directional samples could not produce a stable elliptical fit.\n\n"
                    + "Please review the angular range, sampling and threshold settings.");
            return false;
        }

        double k = topSum / (botSum + 1e-4);
        if (!(k > 0.0) || Double.isNaN(k) || Double.isInfinite(k)) {
            IJ.showMessage("sFSC - DRI fit warning",
                    "The fitted axial cutoff is not physically valid.\n\n"
                    + "Please review the directional samples and analysis settings.");
            return false;
        }

        this.zResolution = 1000.0 * Math.sqrt(k);
        if (!RobustnessUtils.isPositiveFinite(zResolution)) {
            String message =
                    "A finite axial resolution could not be obtained from the directional fit.";
            IJ.showMessage("sFSC - DRI fit warning", message);
            StructuredLog.warning("sFSC/DRI", "Elliptical fit", message);
            return false;
        }

        this.b = 1000.0 / this.zResolution;
        fitError = DRIGoodnessOfFit.validate(validCutoffs, validAngles, a, b, 0.10).error;
        if (params.plotFittingCurve()) plotFitting();
        return true;
    }

    private void plotFitting() {
        INDArray x1 = Nd4j.linspace(0, a, 222, DataType.DOUBLE);
        INDArray y1 = Transforms.sqrt(x1.mul(x1).div(a * a).negi().add(1)).mul(b);
        Plot fitPlot = new Plot(
                "sFSC - DRI elliptical fit",
                "Spatial Frequency (1/um) (XY)",
                "Spatial Frequency (1/um) (Z)");
        fitPlot.setColor(new Color(230, 115, 115));
        fitPlot.addPoints(
                ArrayUtils.convertND4JArrayToFloatArray(x1),
                ArrayUtils.convertND4JArrayToFloatArray(y1),
                Plot.LINE);
        fitPlot.setColor(new Color(0, 0, 255));
        fitPlot.setLineWidth(4);
        fitPlot.addPoints(xPoints, yPoints, Plot.CIRCLE);
        fitPlot.setLimits(0, 1.05 * a, 0, 1.05 * b);
        fitPlot.setColor(Color.BLACK);
        fitPlot.addLabel(0.03, 0.90, "E_DRI=" + String.format("%.2f%%", 100.0 * fitError));
        fitPlot.show();
    }

    public static class Params {
        private double lowAngle = 0;
        private double highAngle = 60;
        private double pixelSize = 32.5 / 1000;
        private int numCones = 5;
        private Boolean zSampling = false;
        private Boolean singleFrame = false;
        private Boolean plotFittingCurve = false;

        public Params(
                float pixelSize,
                float lowAngle,
                float highAngle,
                int numCones,
                Boolean zSampling,
                Boolean singleFrame,
                Boolean plotFittingCurve) {
            this.pixelSize = pixelSize;
            this.lowAngle = Math.toRadians(lowAngle);
            this.highAngle = Math.toRadians(highAngle);
            if (numCones <= 0) throw new IllegalArgumentException("Number of Cones must be a positive integer.");
            this.numCones = numCones;
            this.zSampling = zSampling;
            this.singleFrame = singleFrame;
            this.plotFittingCurve = plotFittingCurve;
        }

        public double getPixelSize() {
            return this.pixelSize;
        }

        public double[] getAngleLimits() {
            return new double[] {this.lowAngle, this.highAngle};
        }

        /** A single cone covers the requested angular range at its midpoint. */
        public double getSingleConeAngle() { return (lowAngle + highAngle) / 2.0; }

        public double getAngleStep() {
            return (highAngle - lowAngle) / Math.max(1, numCones - 1);
        }

        public int getNumOfCones() {
            return this.numCones;
        }

        public Boolean samplingInZ() {
            return this.zSampling;
        }

        public Boolean isSingleFrame() {
            return this.singleFrame;
        }

        public Boolean plotFittingCurve() {
            return this.plotFittingCurve;
        }


    }

    public static void main(String[] args) {
        // Utility class test entry point intentionally left empty.
    }
}
