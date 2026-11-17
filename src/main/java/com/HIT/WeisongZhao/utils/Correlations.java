package com.HIT.WeisongZhao.utils;

import java.awt.Color;
import java.util.Arrays;
import java.util.function.BiFunction;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.indexing.conditions.Conditions;
import org.nd4j.linalg.ops.transforms.Transforms;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Plot;

public class Correlations {
	private INDArray[] fftA;
	private INDArray[] fftB;
	private double pixelSize  = 32.5 / 1000;
	private INDArray frequency = null;
	private INDArray correlations = null;
	private INDArray extrapolatedCorrelations = null;
	private INDArray numberOfPixels = null;
	private INDArray fixedThreshold = null;
	private INDArray threeSigma = null;
	private INDArray twoSigma = null;
	private INDArray fiveSigma = null;
	private double resolution = 0;
	private int rMax = 512;
	private int index = -1;
	private int depth, height, width;
	private INDArray extrapolatedFrequency, extraPolatedCurve, extraPolatedFixedThreshold;
	private boolean extrapolationRequired = false;
	private boolean extrapolationUsed = false;
	private boolean extrapolationAttempted = false;
	private boolean extrapolationFitLoggingEnabled = true;
	private String extrapolationFitSummary = null;
	private String lastThresholdType = "fixedThreshold";
	
	public Correlations(INDArray[] fftA, INDArray[] fftB, double pixelSize) {
		this.fftA = fftA;
		this.fftB = fftB;
		this.pixelSize = pixelSize;
	}
	
	public Correlations(ImagePlus im1, ImagePlus im2, double pixelSize) {
		this.fftA = FFTProcessor.computeFFTToArray(im1);
		this.fftB = FFTProcessor.computeFFTToArray(im2);
		this.pixelSize = pixelSize;
	}
	
	public double getResolution() {
		return this.resolution;
	}
	
	public int getIndex() {
		return this.index;
	}

	public boolean isExtrapolationRequired() {
		return extrapolationRequired;
	}

	public boolean wasExtrapolationAttempted() { return extrapolationAttempted; }

	public boolean wasExtrapolationUsed() {
		return extrapolationUsed;
	}

    /**
     * Controls internal extrapolation diagnostic rows. Numerical calculation is
     * unchanged. Candidate scans can disable these rows and report only the
     * selected or aggregate result at the caller level.
     */
    public void setExtrapolationFitLoggingEnabled(boolean enabled) {
        this.extrapolationFitLoggingEnabled = enabled;
    }

    /** Returns the most recent successful extrapolation-fit summary, or null. */
    public String getExtrapolationFitSummary() {
        return this.extrapolationFitSummary;
    }


	public static boolean isExtrapolationSupportedThreshold(String thresholdType) {
		return "1/7".equals(thresholdType) || "fixedThreshold".equals(thresholdType);
	}
	
	public void execute(int dim) {execute(dim, "fixedThreshold", false, false, true);}
	
	public void execute(int dim, Boolean singleFrame) {execute(dim, "fixedThreshold", singleFrame, false, true);}

	public void execute(int dim, Boolean singleFrame, Boolean zSampling) {
		execute(dim, "fixedThreshold", singleFrame, zSampling, true);
	}
	
	public void execute(int dim, String thresholdType, Boolean singleFrame) {
		execute(dim, thresholdType, singleFrame, false, true);
	}
	
	public void execute(int dim, String thresholdType, Boolean singleFrame, Boolean zSampling) {
		execute(dim, thresholdType, singleFrame, zSampling, true);
	}

	public void execute(int dim, String thresholdType, Boolean singleFrame, Boolean zSampling, Boolean allowExtrapolation) {
		extrapolationRequired = false;
		extrapolationUsed = false;
		extrapolationAttempted = false;
		extrapolationFitSummary = null;
		extrapolatedCorrelations = null;
		extrapolatedFrequency = null;
		index = -1;
		resolution = Double.NaN;

		if (dim == 2) {
			computeFRC();
			getNumberOfPixels();
		} else {
			computeFSC();
			getNumberOfVoxels();
		}
		if (singleFrame)
			correct(zSampling);
		refine();
		getFrequency();
		calculateResolution(thresholdType, Boolean.TRUE.equals(allowExtrapolation));
	}
	
	public void executeRFRC(Boolean singleFrame, double padFactor, Boolean hardThreshold) {
		executeRFRC(singleFrame, padFactor, Boolean.TRUE.equals(hardThreshold) ? "1/7" : "3-sigma");
	}

	public void executeRFRC(Boolean singleFrame, double padFactor) {
		executeRFRC(singleFrame, padFactor, "3-sigma");
	}

	/** Evaluate the selected primary criterion, retaining calibrated sigma fallbacks. */
	public void executeRFRC(Boolean singleFrame, double padFactor, String thresholdType) {
		if (!"1/7".equals(thresholdType) && !"3-sigma".equals(thresholdType))
			throw new IllegalArgumentException("Choose 1/7 hard threshold or 3-sigma.");
		computeFRC();
		getNumberOfPixels();
		if (singleFrame) {
			correct();
			setRFRC(padFactor);
		}
		refine();
		getFrequency();
		calculateResolution(thresholdType, false);
		if (!Double.isFinite(resolution) || resolution <= 0) {
			calculateResolution("2-sigma", false);
			resolution *= 1.5;
		}
		if (!Double.isFinite(resolution) || resolution <= 0) {
			calculateResolution("5-sigma", false);
			resolution *= 0.6;
		}
	}
	
	public void correct() {
		correct(false);
	}
	
	public void setRFRC(double padFactor) {
//		System.out.println("Before: " + this.threeSigma);
		this.threeSigma.muli(padFactor);
		this.twoSigma.muli(padFactor);
		this.fiveSigma.muli(padFactor);
//		System.out.println("After: " + this.threeSigma);
	}
	
	public void correct(Boolean zSampling) {
		float[] temp_correlations = ArrayUtils.convertND4JArrayToFloatArray(this.correlations);
		double[] currentCorrelations = Correction.applyCorrection(ArrayUtils.float2Double(temp_correlations));
		if (zSampling)
			currentCorrelations = Correction.zCorrection(currentCorrelations);
		this.correlations = ArrayUtils.convertFloatArrayToND4JArray(ArrayUtils.double2Float(currentCorrelations), this.rMax);
	}
	
	public void computeFRC() {
		INDArray fftAReal = fftA[0];
		INDArray fftAImag = fftA[1];
		INDArray fftBReal = fftB[0];
		INDArray fftBImag = fftB[1];
		this.height = (int) fftAReal.size(0);
		this.width = (int) fftAReal.size(1);
		this.correlations = computeFRC(fftAReal, fftAImag, fftBReal, fftBImag);
//		System.out.println(correlations.maxNumber().doubleValue());
//		System.out.println(correlations.minNumber().doubleValue());
	}
	
	public INDArray computeFRC(INDArray fftAReal, INDArray fftAImag, 
			INDArray fftBReal, INDArray fftBImag) {
		INDArray[] top = computeTop2D(fftAReal, fftAImag, fftBReal, fftBImag);
		INDArray bot = computeBot2D(fftAReal, fftAImag, fftBReal, fftBImag);
		INDArray topReal = top[0];
		INDArray topImag = top[1];
		INDArray real = topReal.div(bot.add(1e-8));
		INDArray imag = topImag.div(bot.add(1e-8));
		INDArray correlations = computeAbs(real, imag);
		int rMax = (int) fftAReal.size(0) / 2;
		this.rMax = rMax;
//		System.out.println("rMAX = " + rMax);
//		System.out.println("correlations size = " + java.util.Arrays.toString(correlations.shape()));
		INDArrayIndex indices = NDArrayIndex.interval(0, rMax);
		return correlations.get(indices);
	}
	
	public void computeFSC() {
		INDArray fftAReal = fftA[0];
		INDArray fftAImag = fftA[1];
		INDArray fftBReal = fftB[0];
		INDArray fftBImag = fftB[1];
		this.depth = (int) fftAReal.size(0);
		this.height = (int) fftAReal.size(1);
		this.width = (int) fftAReal.size(2);
		this.correlations = computeFSC(fftAReal, fftAImag, fftBReal, fftBImag);
	}
	
	public INDArray computeFSC(INDArray fftAReal, INDArray fftAImag, 
			INDArray fftBReal, INDArray fftBImag) {
		INDArray[] top = computeTop3D(fftAReal, fftAImag, fftBReal, fftBImag);
		INDArray bot = computeBot3D(fftAReal, fftAImag, fftBReal, fftBImag);
		INDArray topReal = top[0];
		INDArray topImag = top[1];
		INDArray real = topReal.div(bot.add(1e-8));
		INDArray imag = topImag.div(bot.add(1e-8));
		INDArray correlations = computeAbs(real, imag);
		int rMax = (int) fftAReal.size(0) / 2;
		this.rMax = rMax;
		INDArrayIndex indices = NDArrayIndex.interval(0, rMax);
		return correlations.get(indices);
	}
	
	private void getFrequency() {
		int rMax = (int) correlations.size(0);
		// Correlation index 0 is the DC bin and must map to frequency 0.
		// Keep the same convention as the Python/MATLAB implementations.
		INDArray R = Nd4j.linspace(0, rMax - 1, rMax);
		R.divi(pixelSize * 2.0 * rMax);
		this.frequency = R;
	}
    
    private void getNumberOfPixels() {
    	INDArray X = ArrayUtils.meshGrid2D(height, width)[0];
    	INDArray temp = Nd4j.onesLike(X);
    	int rMax = (int) height / 2;
    	INDArrayIndex indices = NDArrayIndex.interval(0, rMax);
		numberOfPixels = RadialSum.radialSum(temp, 1.0).get(indices);
		INDArray bot = Transforms.sqrt(numberOfPixels.div(2));
		threeSigma = Nd4j.onesLike(numberOfPixels).muli(3).divi(bot);
		threeSigma.replaceWhere(Nd4j.onesLike(threeSigma), Conditions.greaterThan(1));
		twoSigma = Nd4j.onesLike(numberOfPixels).muli(2).divi(bot);
		twoSigma.replaceWhere(Nd4j.onesLike(twoSigma), Conditions.greaterThan(1));
		fiveSigma = Nd4j.onesLike(numberOfPixels).muli(5).divi(bot);
		fiveSigma.replaceWhere(Nd4j.onesLike(fiveSigma), Conditions.greaterThan(1));
		fixedThreshold = Nd4j.onesLike(numberOfPixels).muli(1.0 / 7);
    }
    
    /** Retry a measured-only sector without recomputing FFT or correction. */
    public void extrapolateIfRequired(String thresholdType) {
        if (extrapolationRequired && !Double.isFinite(resolution))
            calculateResolution(thresholdType, true);
    }

    private void getNumberOfVoxels() {
    	INDArray X = ArrayUtils.meshGrid3D(depth, height, width)[0];
    	INDArray temp = Nd4j.onesLike(X);
    	int rMax = (int) depth / 2;
		INDArrayIndex indices = NDArrayIndex.interval(0, rMax);
		this.numberOfPixels = RadialSum.radialSum(temp, 1.0).get(indices);
		INDArray bot = Transforms.sqrt(numberOfPixels.div(2));
		this.threeSigma = Nd4j.onesLike(numberOfPixels).muli(3).divi(bot);
		this.threeSigma.replaceWhere(Nd4j.onesLike(threeSigma), Conditions.greaterThan(1));
		this.twoSigma = Nd4j.onesLike(numberOfPixels).muli(2).divi(bot);
		this.twoSigma.replaceWhere(Nd4j.onesLike(twoSigma), Conditions.greaterThan(1));
		this.fiveSigma = Nd4j.onesLike(numberOfPixels).muli(5).divi(bot);
		this.fiveSigma.replaceWhere(Nd4j.onesLike(fiveSigma), Conditions.greaterThan(1));
		this.fixedThreshold = Nd4j.onesLike(numberOfPixels).muli(1.0 / 7);
    }

    private INDArray getThresholdCurve(String thresholdType) {
        if ("1/7".equals(thresholdType) || "fixedThreshold".equals(thresholdType))
            return fixedThreshold;
        if ("3-sigma".equals(thresholdType))
            return threeSigma;
        if ("2-sigma".equals(thresholdType))
            return twoSigma;
        if ("5-sigma".equals(thresholdType))
            return fiveSigma;
        throw new IllegalArgumentException("Unknown threshold type: " + thresholdType);
    }

    private void calculateResolution(String thresholdType, boolean allowExtrapolation) {
        int rMax = (int) correlations.size(0);
        this.lastThresholdType = thresholdType;
        INDArray threshold = getThresholdCurve(thresholdType);

        if (threshold == null)
            throw new IllegalStateException("Threshold curve is unavailable for: " + thresholdType);

        // First use the measured curve and the existing Correlations crossing rule.
        if (detectResolutionWithExistingRule(
                correlations, frequency, threshold, 4, rMax - 5)) {
            return;
        }

        this.resolution = Double.NaN;
        this.index = -1;
        this.extrapolationRequired = true;

        // Internal gate for measured-only DRI and rolling FRC calculations.
        if (!allowExtrapolation || !isExtrapolationSupportedThreshold(thresholdType))
            return;

        this.extrapolationAttempted = true;
        try {
            int preLength = (int) frequency.length();
            int newLength = preLength * 2;
            double[] frequencyData = frequency.data().asDouble();
            double[] correlationData = correlations.data().asDouble();

            CurveExtrapolator.Result ext = CurveExtrapolator.extrapolate(
                    correlationData, frequencyData, newLength);
            if (!ext.success) {
                if (extrapolationFitLoggingEnabled) {
                    StructuredLog.warning(
                            "Correlation",
                            "Automatic extrapolation failed",
                            ext.message);
                }
                return;
            }

            this.extrapolatedCorrelations = Nd4j.create(ext.frc);
            this.extrapolatedFrequency = Nd4j.create(ext.frequency);

            // Reuse exactly the same threshold-crossing rule as the measured path.
            INDArray extrapolatedThreshold = Nd4j.onesLike(extrapolatedCorrelations)
                    .muli(1.0 / 7.0);
            boolean found = detectResolutionWithExistingRule(
                    extrapolatedCorrelations,
                    extrapolatedFrequency,
                    extrapolatedThreshold,
                    preLength,
                    (int) extrapolatedCorrelations.size(0) - 5);

            if (!found) {
                this.resolution = Double.NaN;
                this.index = -1;
                if (extrapolationFitLoggingEnabled) {
                    StructuredLog.warning(
                            "Correlation",
                            "Automatic extrapolation failed",
                            "The extrapolated curve did not reach the selected threshold using "
                            + "the existing resolution rule.");
                }
                return;
            }

            this.extrapolationUsed = true;
            this.extrapolationFitSummary = String.format(
                    "A=%.6g, k(norm)=%.6g, R^2=%.4f, RMSE=%.4g%s",
                    ext.A, ext.k, ext.rSquared, ext.rmse,
                    ext.usedLinearizedFallback ? " [linearized fallback]" : "");
            if (extrapolationFitLoggingEnabled) {
                StructuredLog.info(
                        "Correlation",
                        "Extrapolation fit",
                        extrapolationFitSummary);
            }
            return;
        } catch (RuntimeException ex) {
            if (extrapolationFitLoggingEnabled) {
                StructuredLog.warning(
                        "Correlation",
                        "Automatic extrapolation failed",
                        String.valueOf(ex.getMessage()));
            }
            this.resolution = Double.NaN;
            this.index = -1;
        }
    }

    /**
     * Existing Correlations cutoff rule, extracted so the same rule is used for
     * both measured and extrapolated curves.  No threshold logic lives in
     * CurveExtrapolator.
     */
    private boolean detectResolutionWithExistingRule(INDArray curve,
                                                     INDArray curveFrequency,
                                                     INDArray threshold,
                                                     int startSample,
                                                     int endExclusive) {
        int n = (int) Math.min(curve.size(0),
                Math.min(curveFrequency.size(0), threshold.size(0)));
        int start = Math.max(4, startSample);
        int end = Math.min(n, endExclusive);

        for (int sample = start; sample < end; sample++) {
            double curThreshold = threshold.getDouble(sample);
            double maxPrevData = Math.max(Math.max(
                    curve.getDouble(sample - 1),
                    curve.getDouble(sample - 2)),
                    curve.getDouble(sample - 3));
            double curData = curve.getDouble(sample);
            if (curData < curThreshold && maxPrevData > curThreshold) {
                this.index = sample;
                this.resolution = 1000.0 / curveFrequency.getDouble(sample);
                return true;
            }
        }
        return false;
    }

    public static INDArray[] computeTop2D(INDArray fftAReal, INDArray fftAImag, 
    		INDArray fftBReal, INDArray fftBImag) {
    	INDArray real = fftAReal.mul(fftBReal).add(fftAImag.mul(fftBImag));
    	INDArray imag = fftAImag.mul(fftBReal).sub(fftAReal.mul(fftBImag));
    	INDArray[] result = new INDArray[2];
    	result[0] = RadialSum.radialSum(real, 1.0);
    	result[1] = RadialSum.radialSum(imag, 1.0);
    	return result;
    }
    
    public static INDArray computeBot2D(INDArray fftAReal, INDArray fftAImag, 
    		INDArray fftBReal, INDArray fftBImag) {
    	INDArray de1 = computeAbs(fftAReal, fftAImag);
    	INDArray de2 = computeAbs(fftBReal, fftBImag);
    	return RadialSum.radialSum(de1.mul(de2), 1.0);
    }
    
	public static INDArray[] computeTop3D(INDArray fftAReal, INDArray fftAImag, 
    		INDArray fftBReal, INDArray fftBImag) {
    	INDArray real = fftAReal.mul(fftBReal).add(fftAImag.mul(fftBImag));
    	INDArray imag = fftAImag.mul(fftBReal).sub(fftAReal.mul(fftBImag));
    	INDArray[] result = new INDArray[2];
    	result[0] = SphericalSum.sphericalSum(real, 1.0);
    	result[1] = SphericalSum.sphericalSum(imag, 1.0);
    	return result;
    }
    
    public static INDArray computeBot3D(INDArray fftAReal, INDArray fftAImag, 
    		INDArray fftBReal, INDArray fftBImag) {
    	INDArray de1 = computeAbs(fftAReal, fftAImag);
    	INDArray de2 = computeAbs(fftBReal, fftBImag);
    	return SphericalSum.sphericalSum(de1.mul(de2), 1.0);
    }
    
    public static INDArray computeAbs(INDArray real, INDArray imag) {
    	INDArray temp1 = real.mul(real);
    	INDArray temp2 = imag.mul(imag);
    	INDArray temp = Transforms.sqrt(temp1.add(temp2));
    	return temp;
//    	return Transforms.log(temp.addi(1));
    }
    
    public static INDArray computeLogAbs(INDArray real, INDArray imag) {
    	return Transforms.log(computeAbs(real, imag).addi(1));
    }
    
    public static float[] meanFilter(float[] data, int meanWidth) {
        int d = data.length;
        float[] smoothData = new float[d];
        
        if (d == 0 || meanWidth == 0) {
            return Arrays.copyOf(data, d);
        }
        for (int i = 0; i < d; i++) {
            if (i < meanWidth) {
                smoothData[i] = calculateMean(data, 0, i + meanWidth);
            } else if (i >= d - meanWidth) {
                smoothData[i] = calculateMean(data, i - meanWidth, d - 1);
            } else {
                smoothData[i] = calculateMean(data, i - meanWidth, i + meanWidth);
            }
        }
        return smoothData;
    }
    
    private static float calculateMean(float[] data, int start, int end) {
        if (start > end) {
            return 0;
        }
        float sum = 0;
        int count = 0;
        
        for (int i = start; i <= end; i++) {
            sum += data[i];
            count++;
        }
        return sum / count;
    }
    
    private void refine() {
    	double maxValue = correlations.maxNumber().doubleValue();
    	int index = 0;
    	for (int i = 0; i < rMax; i++) {
    		if (Math.abs(maxValue - correlations.getDouble(i)) < 1e-9) {
    			index = i;
    			break;
    		}		
    	}
    	if (index < 1)
    		return;
    	double k = (1 - maxValue) / index;
    	INDArray indices = Nd4j.arange(0, index, 1.0);
    	INDArray refined = indices.mul(-k).add(1);
//    	System.out.println(indices);
    	for (int i = 0; i < index; i++)
    		this.correlations.put(i, refined.getScalar(i));
    	for (int i = 1; i < correlations.length() - 1; i++) {
    		if (correlations.getDouble(i) < 1e-4)
    			if (correlations.getDouble(i - 1) > 0.9 || correlations.getDouble(i + 1) > 0.9)
    				this.correlations.putScalar(i, 1.0);
    	}
    }
    
    
    public void plotFRC() { plotFRC("sFRC - correlation curve"); }

    public void plotFRC(String title) {
        Plot frcPlot = new Plot(title, "Spatial Frequency (1/μm)", "Correlation");

        INDArray plotFrequency = this.frequency;
        INDArray plotCorrelations = this.correlations;
        INDArray plotThreshold = getThresholdCurve(this.lastThresholdType);

        if (extrapolationUsed && extrapolatedFrequency != null && extrapolatedCorrelations != null) {
            plotFrequency = extrapolatedFrequency;
            plotCorrelations = extrapolatedCorrelations;
            plotThreshold = Nd4j.onesLike(plotFrequency).mul(1.0 / 7.0);
        }

        int length = (int) plotCorrelations.size(0);
        float[] correlationsData = ArrayUtils.convertND4JArrayToFloatArray(plotCorrelations);
        correlationsData = meanFilter(correlationsData, 3);
        float[] frequencyData = ArrayUtils.convertND4JArrayToFloatArray(plotFrequency);
        float[] thresholdCurve = ArrayUtils.convertND4JArrayToFloatArray(plotThreshold);

        frcPlot.setColor(new Color(230, 115, 115));
        frcPlot.addPoints(frequencyData, correlationsData, Plot.LINE);
        frcPlot.setColor(new Color(0, 0, 0));
        frcPlot.addPoints(frequencyData, thresholdCurve, Plot.LINE);

        double[] x0 = new double[2];
        double[] y0 = new double[2];
        if (index > 1 && index < frequencyData.length) {
            x0[0] = frequencyData[index];
            x0[1] = frequencyData[index];
            y0[0] = 0;
            y0[1] = 1;
            frcPlot.setColor(new Color(0, 0, 255));
            frcPlot.addPoints(x0, y0, Plot.LINE);
        }

        frcPlot.setColor(new Color(0, 0, 0));
        frcPlot.addLabel(0.1, 0.5, "Resolution:" + String.format("%.1f", resolution) + " nm");
        frcPlot.setLimits(0, 1.1 * frequencyData[length - 1], -0.1, 1.1);
        frcPlot.show();
    }
    
    public static double[] linspace(double start, double end, int num) {
    	double[] result = new double[num];
		for (int i = 0; i < num; i++) {
		    result[i] = start + i * (end - start) / (num - 1);
		}
		return result;
    }
    
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		ImagePlus image = IJ.openImage("D:/LYZ/single FRC/FRC/data/QD525-LIVESR/Substack (1-2).tif");
		ImagePlus im1 = new ImagePlus("IM1", image.getStack().getProcessor(1));
		ImagePlus im2 = new ImagePlus("IM2", image.getStack().getProcessor(2));
		INDArray[] fftA = FFTProcessor.computeFFTToArray(im1);
		INDArray[] fftB = FFTProcessor.computeFFTToArray(im2);
		Correlations correlations = new Correlations(fftA, fftB, 38.2 / 1000);
		correlations.execute(2);
		correlations.plotFRC();
		System.out.println(correlations.getResolution());
		System.out.println("Successfully executed.");
	}

}
