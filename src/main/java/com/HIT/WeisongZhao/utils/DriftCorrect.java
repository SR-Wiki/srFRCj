package com.HIT.WeisongZhao.utils;


import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.ops.transforms.Transforms;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;

import java.util.Arrays;

public class DriftCorrect {
	
	public static ImagePlus driftCorrect3D(ImagePlus image1, ImagePlus image2, boolean verbose) {
		int depth = image1.getStackSize();
		int height = image1.getHeight();
		int width = image2.getWidth();
		ImageStack correctedImageStack2 = new ImageStack(width, height);
		for (int i = 0; i < depth; i++) {
			IJ.showStatus("Correcting Motion...");
			IJ.showProgress(i, depth);
			ImagePlus im1 = new ImagePlus("", image1.getStack().getProcessor(i + 1));
			ImagePlus im2 = new ImagePlus("", image2.getStack().getProcessor(i + 1));
			correctedImageStack2.addSlice(driftCorrect2D(im1, im2, verbose).getStack().getProcessor(1));
		}
		return new ImagePlus("", correctedImageStack2);
	}
	
	// shift in the Fourier domain
	public static ImagePlus driftCorrect2D(ImagePlus image1, ImagePlus image2, boolean verbose) {
		int[] shifts = driftDetect(image1, image2, verbose);
		int height = image1.getHeight();
		int width = image2.getWidth();
		INDArray[] fftB = FFTProcessor.computeFFTToArray(image2, false, true, false);
		shift(fftB, shifts, height, width);
		float[] tempReal = ArrayUtils.convertND4JArrayToFloatArray(fftB[0]);
		float[] tempImag = ArrayUtils.convertND4JArrayToFloatArray(fftB[1]);
		FFTProcessor.fftShift2D(tempReal, height, width);
		FFTProcessor.fftShift2D(tempImag, height, width);
		fftB[0] = ArrayUtils.convertFloatArrayToND4JArray(tempReal, height, width);
		fftB[1] = ArrayUtils.convertFloatArrayToND4JArray(tempImag, height, width);
		float[] ifft = FFTProcessor.ifft2D(fftB);
		float[][] floats = FFTProcessor.createFFTArray(ifft, height, width);
		INDArray temp = ArrayUtils.convertFloatArrayToND4JArray(floats[0], height, width);
		return ArrayUtils.convertND4JArrayToImagePlus(temp);
	}
	
	private static void shift(INDArray[] fft, int[] shifts, int height, int width) {
		INDArray[] meshGrid = ArrayUtils.meshGrid2D(height, width);
		INDArray X = meshGrid[0].mul(-shifts[1]).div(width).mul(2 * Math.PI);
		INDArray Y = meshGrid[1].mul(-shifts[0]).div(height).mul(2 * Math.PI);
		INDArray phase = X.add(Y);
		INDArray phaseReal = Transforms.cos(phase);
		INDArray phaseImag = Transforms.sin(phase);
		
		INDArray fftReal = fft[0];
		INDArray fftImag = fft[1];
		
		INDArray real = fftReal.mul(phaseReal).add(fftImag.mul(phaseImag));
    	INDArray imag = fftImag.mul(phaseReal).sub(fftReal.mul(phaseImag));
		fft[0] = real;
		fft[1] = imag;
	}
	
    public static int[] driftDetect(INDArray image1, INDArray image2,  boolean verbose) {
        ImagePlus im1 = ArrayUtils.convertND4JArrayToImagePlus(image1);
        ImagePlus im2 = ArrayUtils.convertND4JArrayToImagePlus(image2);
        return driftDetect(im1, im2, verbose);
    }
    
    public static int[] driftDetect(ImagePlus im1, ImagePlus im2, boolean verbose) {
    	int height = im1.getHeight();
    	int width = im1.getWidth();
    	if (im1.getHeight() != im2.getHeight() || im1.getWidth() != im2.getWidth() || im1.getStackSize() > 1) {
            throw new IllegalArgumentException("This code is only for 2D images with same shapes!");
        }
    	INDArray[] fftA = FFTProcessor.computeFFTToArray(im1, false, false);
        INDArray[] fftB = FFTProcessor.computeFFTToArray(im2, false, false);
    	INDArray corr = FFTProcessor.crossCorrelation(fftA, fftB);
//    	ImagePlus testCorr = ArrayUtils.convertND4JArrayToImagePlus(corr);
//    	testCorr.show();

        // Use the cross-correlation peak position as the initial guess
    	double maxNumber = corr.maxNumber().doubleValue();
    	long index = corr.argMax().getLong(0);
        int peakY = (int) index / width;
        int peakX = (int) index % width;
        int centerY = (int) Math.ceil((height + 1) / 2);
        int centerX = (int) Math.ceil((width + 1) / 2);
        int[] drift = new int[]{centerY - peakY, centerX - peakX};
        if (verbose) {
            StructuredLog.info(
                    "Motion correction",
                    "Detected drift",
                    "Peak=" + String.format("%.6g", maxNumber)
                    + "; peak position=(" + peakY + ", " + peakX + ")"
                    + "; shift Y=" + drift[0] + " px; shift X=" + drift[1] + " px.");
        }
        return drift;
	}
    
    
    /**
     * Example usage
     */
    public static void main(String[] args) {
        // Create example images
    	new ImageJ();
    	String folder = "D:/LYZ/single FRC/FRC/data/3D STED/STED_image_quality_comparison_images/";
    	ImagePlus im1 = IJ.openImage(folder + "Detector_1_STED_60perc.tif");
    	ImagePlus im2 = IJ.openImage(folder + "Detector_2_STED_60perc.tif");
//    	im1.show();
//    	im2.show();
    	ImagePlus im2Corrected = driftCorrect2D(im1, im2, true);
//    	im2Corrected.show();
    	
    	INDArray[] fftA = FFTProcessor.computeFFTToArray(im1);
		INDArray[] fftB = FFTProcessor.computeFFTToArray(im2Corrected);
		Correlations correlations = new Correlations(fftA, fftB, 30.0 / 1000);
		correlations.execute(2);
		correlations.plotFRC();

    }
}
