package com.HIT.WeisongZhao.utils;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import java.util.logging.Logger;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import ij.process.FloatProcessor;


import Jfft.FloatFFT_3D;

public class FFTProcessor {
    private static final Logger logger = Logger.getLogger(FFTProcessor.class.getName());
    
    public static INDArray[] computeFFTToArray(ImagePlus image, double downsamplingFactor) {
    	int width = image.getWidth();
        int height = image.getHeight();
        int depth = image.getStackSize();
        int[] shape;
        if (depth > 1)
        	shape = new int[] {depth, height, width};
        else
        	shape = new int[] {height, width};
    	float[][] fftResult = computeFFT(image, downsamplingFactor, shape);
    	return postAssembly(fftResult, shape);
    }
    
    /**
     * Compute 3D FFT of ImagePlus object
     * @param image Input ImagePlus object
     * @return INDArray array containing FFT results
     */
    public static INDArray[] computeFFTToArray(ImagePlus image) {
		return computeFFTToArray(image, true);
    }
    
    public static INDArray[] computeFFTToArray(ImagePlus image, boolean applyWindow) {
    	return computeFFTToArray(image, applyWindow, true);
    }
    
    public static INDArray[] computeFFTToArray(ImagePlus image, boolean applyWindow, boolean shift) {
    	return computeFFTToArray(image, applyWindow, shift, true);
    }
    public static INDArray[] computeFFTToArray(ImagePlus image, boolean applyWindow, 
    		boolean shift, boolean pad) {
    	int width = image.getWidth();
        int height = image.getHeight();
        int depth = image.getStackSize();
        int[] shape;
        if (depth > 1)
        	shape = new int[] {depth, height, width};
        else
        	shape = new int[] {height, width};
        float[][] fftResult = computeFFT(image, shape, applyWindow, pad);
		return postAssembly(fftResult, shape, shift);
    }
    
    public static INDArray[] postAssembly(float[][] fftResult, int[] shape) {
    	return postAssembly(fftResult, shape, true);
    }
    
    public static INDArray[] postAssembly(float[][] fftResult, int[] shape, boolean shift) {
    	int depth;
    	if (shape.length > 2)
    		depth = shape[0];
    	else
    		depth = 1;
    	float[] fftRealTmp = fftResult[0].clone();
		float[] fftImagTmp = fftResult[1].clone();
		INDArray fftReal, fftImag;
		if (depth > 1) {
			if (shift) {
				fftShift3D(fftRealTmp, shape[0], shape[1], shape[2]);
				fftShift3D(fftImagTmp, shape[0], shape[1], shape[2]);
			}
			fftReal = ArrayUtils.convertFloatArrayToND4JArray(fftRealTmp, shape[0], shape[1], shape[2]);
			fftImag = ArrayUtils.convertFloatArrayToND4JArray(fftImagTmp, shape[0], shape[1], shape[2]);
		} else {
			if (shift) {
				fftShift2D(fftRealTmp, shape[0], shape[1]);
				fftShift2D(fftImagTmp, shape[0], shape[1]);
			}
			fftReal = ArrayUtils.convertFloatArrayToND4JArray(fftRealTmp, shape[0], shape[1]);
			fftImag = ArrayUtils.convertFloatArrayToND4JArray(fftImagTmp, shape[0], shape[1]);
		}
		fftResult = null; 
		fftRealTmp = null;
		fftImagTmp = null;
		INDArray[] result = new INDArray[2];
		result[0] = fftReal;
		result[1] = fftImag;
		return result;
    }
    
    // for 3d and 2d
    public static float[][] computeFFT(ImagePlus image, int[] shape) {
    	return computeFFT(image, shape, true);
    }
    
    public static float[][] computeFFT(ImagePlus image, int[] shape, boolean applyWindow) {
    	return computeFFT(image, shape, true, true);
    }
    
    public static float[][] computeFFT(ImagePlus image, int[] shape, boolean applyWindow, boolean pad) {
        // Get image dimensions
    	int depth = 0, height, width;
    	if (shape.length == 2) {
    		height = shape[0];
    		width = shape[1];
    	} else {
    		depth = shape[0];
    		height = shape[1];
    		width = shape[2];
    	}
        float[] imageData = ArrayUtils.convertImagePlusToFloatArray(image);
        INDArray imageArray;
        if (depth < 1)
        	imageArray = ArrayUtils.convertFloatArrayToND4JArray(imageData, height, width);
        else
        	imageArray = ArrayUtils.convertFloatArrayToND4JArray(imageData, depth, height, width);
        imageData = null;
        INDArray padded;
        if (shape.length == 2) {
        	if (pad)
        		padded = Pad2Cube.pad2square(imageArray, 3).getPadded();
        	else
        		padded = imageArray;
        	if (applyWindow) {
        		int newHeight = (int) padded.size(0);
        		int newWidth = (int) padded.size(1);
        		padded = padded.mul(Hanning.hanning2D(newHeight, newWidth));
        	}
        	shape[0] = (int) padded.size(0);
        	shape[1] = (int) padded.size(1);
        }
        else {
        	if (pad)
        		padded = Pad2Cube.pad2cube(imageArray, 3).getPadded();
        	else {
        		if (applyWindow)
            		padded = imageArray.mul(Hanning.hanning3D(depth, height, width));
            	else
            		padded = imageArray;
        	}
        	shape[0] = (int) padded.size(0);
        	shape[1] = (int) padded.size(1);
        	shape[2] = (int) padded.size(2);
        }	
        imageArray = null;
        
        imageData = ArrayUtils.convertND4JArrayToFloatArray(padded);
        padded = null;
        
        // Create FFT processor
        if (shape.length == 2) {
        	FloatFFT_2D fft = new FloatFFT_2D(shape[0], shape[1]);
        	float[] complexData = new float[shape[0] * shape[1] * 2];
        	for (int i = 0, j = 0; i < imageData.length; i++, j += 2) {
                complexData[j] = imageData[i];
                complexData[j + 1] = 0; // Initialize imaginary part to 0
            }
        	fft.complexForward(complexData);
        	return createFFTArray(complexData, shape[0], shape[1]);
        } else {
        	FloatFFT_3D fft = new FloatFFT_3D(shape[0], shape[1], shape[2]);
        	float[] complexData = new float[shape[0] * shape[1] * shape[2] * 2];
        	for (int i = 0, j = 0; i < imageData.length; i++, j += 2) {
                complexData[j] = imageData[i];
                complexData[j + 1] = 0; // Initialize imaginary part to 0
            }
        	fft.complexForward(complexData);
        	return createFFTArray(complexData, shape[0], shape[1], shape[2]);
        }
    }
    
    // for 3d only
    public static float[][] computeFFT(ImagePlus image, double downsamplingFactor, int[] shape) {
    	INDArray imageArray = ArrayUtils.convertImagePlusToND4JArray(image);
    	if (downsamplingFactor < 1) {
			imageArray = ArrayUtils.samplingZ(imageArray, 1 / downsamplingFactor);
		} else {
			imageArray = ArrayUtils.samplingXY(imageArray, downsamplingFactor);
		}
        INDArray padded;
        padded = Pad2Cube.pad2cube(imageArray, 3).getPadded();
    	shape[0] = (int) padded.size(0);
    	shape[1] = (int) padded.size(1);
    	shape[2] = (int) padded.size(2);	
        imageArray = null;
        
        float[] imageData = ArrayUtils.convertND4JArrayToFloatArray(padded);
        padded = null;
        FloatFFT_3D fft = new FloatFFT_3D(shape[0], shape[1], shape[2]);
    	float[] complexData = new float[shape[0] * shape[1] * shape[2] * 2];
    	for (int i = 0, j = 0; i < imageData.length; i++, j += 2) {
            complexData[j] = imageData[i];
            complexData[j + 1] = 0; // Initialize imaginary part to 0
        }
    	fft.complexForward(complexData);
    	return createFFTArray(complexData, shape[0], shape[1], shape[2]);
    }

    /**
     * Create real and imaginary arrays from FFT data
     * @param fftData FFT complex data (real and imaginary interleaved)
     * @param width Image width
     * @param height Image height
     * @param depth Image depth
     * @return Array containing FFT magnitude spectrum as ImagePlus object
     */
    public static float[][] createFFTArray(float[] fftData, int height, int width) {
        float[] real = new float[width * height];
        float[] imag = new float[width * height];
        
        for (int i = 0, j = 0; i < real.length; i++, j += 2) {
            real[i] = fftData[j];
            imag[i] = fftData[j + 1];
        }
        
        float[][] result = new float[2][width * height];
        result[0] = real;
        result[1] = imag;
        return result;
    }
    
    private static float[][] createFFTArray(float[] fftData, int depth, int height, int width) {
        float[] real = new float[width * height * depth];
        float[] imag = new float[width * height * depth];
        
        for (int i = 0, j = 0; i < real.length; i++, j += 2) {
            real[i] = fftData[j];
            imag[i] = fftData[j + 1];
        }
        
        float[][] result = new float[2][width * height * depth];
        result[0] = real;
        result[1] = imag;
        return result;
    }
    
    /**
     * 3D FFTShift operation - move zero frequency component to center of spectrum
     * @param data Complex data array (real and imaginary interleaved)
     * @param width Image width
     * @param height Image height
     * @param depth Image depth
     */
    public static void fftShift2D(float[] data, int height, int width) {
        // Calculate offset for each dimension
        int widthShift = (width + 1) / 2;
        int heightShift = (height + 1) / 2;
        
        // Create temporary array to store shifted data
        float[] shiftedData = new float[data.length];
        for (int y = 0; y < height; y++) {
            int newY = (y + heightShift) % height;
            for (int x = 0; x < width; x++) {
                int newX = (x + widthShift) % width;
                
                int oldIndex = (y * width + x);
                int newIndex = (newY * width + newX);
                shiftedData[newIndex] = data[oldIndex];
            }
        }
        System.arraycopy(shiftedData, 0, data, 0, data.length);
    }
    
    public static void fftShift3D(float[] data, int depth, int height, int width) {
        // Calculate offset for each dimension
        int widthShift = (width + 1) / 2;
        int heightShift = (height + 1) / 2;
        int depthShift = (depth + 1) / 2;
        
        // Create temporary array to store shifted data
        float[] shiftedData = new float[data.length];
        
        // Perform 3D shift
        for (int z = 0; z < depth; z++) {
            int newZ = (z + depthShift) % depth;
            for (int y = 0; y < height; y++) {
                int newY = (y + heightShift) % height;
                for (int x = 0; x < width; x++) {
                    int newX = (x + widthShift) % width;
                    // Calculate indices of old and new positions
                    int oldIndex = (z * height * width + y * width + x);
                    int newIndex = (newZ * height * width + newY * width + newX);
                    shiftedData[newIndex] = data[oldIndex];
                }
            }
        }
        // Copy shifted data back to original array
        System.arraycopy(shiftedData, 0, data, 0, data.length);
    }
    
    public static float[] ifft2D(INDArray[] ffta) {
    	INDArray fftReal = ffta[0];
        INDArray fftImag = ffta[1];
        int height = (int) fftReal.shape()[0];
        int width = (int) fftReal.shape()[1];
        float[] realData = ArrayUtils.convertND4JArrayToFloatArray(fftReal);
        float[] imagData = ArrayUtils.convertND4JArrayToFloatArray(fftImag);
        float[] complexData = new float[height * width * 2];
        for (int i = 0, j = 0; i < realData.length; i++, j += 2) {
            complexData[j] = realData[i];
            complexData[j + 1] = imagData[i];
        }
        FloatFFT_2D fft = new FloatFFT_2D(height, width);
        fft.complexInverse(complexData, false); // false indicates no normalization
        return complexData;
//        float[][] fftResult = createFFTArray(complexData, height, width);
//        int[] shape = new int[] {height, width};
//        return postAssembly(fftResult, shape, false);
    }
    
   
    // assume ffts without fftshift
    public static INDArray crossCorrelation(INDArray[] fftA, INDArray[] fftB) {
    	INDArray fftAReal = fftA[0];
		INDArray fftAImag = fftA[1];
		INDArray fftBReal = fftB[0];
		INDArray fftBImag = fftB[1];
		INDArray real = fftAReal.mul(fftBReal).add(fftAImag.mul(fftBImag));
    	INDArray imag = fftAImag.mul(fftBReal).sub(fftAReal.mul(fftBImag));
    	INDArray[] result = new INDArray[2];
    	result[0] = real;
    	result[1] = imag;
//    	ImagePlus t = ArrayUtils.convertND4JArrayToImagePlus(result, "Product");
//    	t.show();
    	int height = (int) real.shape()[0], width = (int) real.shape()[1];
    	int[] shape = new int[] {height, width};
    	float[] complexData = ifft2D(result);
    	float[][] floats = createFFTArray(complexData, height, width);
//    	result = postAssembly(floats, shape, false);
//    	ImagePlus imp1 = ArrayUtils.convertND4JArrayToImagePlus(result, "Inverse");
//    	imp1.show();
    	fftShift2D(floats[0], height, width);
    	fftShift2D(floats[1], height, width);
    	result = postAssembly(floats, shape, false);
//		ImagePlus imp2 = ArrayUtils.convertND4JArrayToImagePlus(result, "Shifted Inverse");
//    	imp2.show();
		result[0] = ArrayUtils.convertFloatArrayToND4JArray(floats[0], height, width);
		result[1] = ArrayUtils.convertFloatArrayToND4JArray(floats[1], height, width);
    	return result[0];
    }
    
    /**
     * Check if a number is a power of two
     * @param n Number to check
     * @return true if n is a power of two, false otherwise
     */
    private static boolean isPowerOfTwo(int n) {
        return (n & (n - 1)) == 0;
    }
    
    public static void main(String[] args) {
    	// Load image
        INDArray b = Nd4j.create(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        System.out.println("Original array b:");
        System.out.println("Shape: " + java.util.Arrays.toString(b.shape()));
        System.out.println("Data: " + b);
        
        // Perform reshape operation
        INDArray a = b.reshape(3, 4);
        System.out.println("\nReshaped array a:");
        System.out.println("Shape: " + java.util.Arrays.toString(a.shape()));
        System.out.println("Data: " + a);
        
        // Modify reshaped array
        a.putScalar(0, 0, 100);
        System.out.println("\nAfter modifying a[0,0] to 100:");
        System.out.println("Array a: " + a);
        System.out.println("Array b: " + b);
        
        // Check if data buffer is shared
        System.out.println("\nIs data buffer shared: " + (a.data() == b.data()));
        
    	new ImageJ();
		ImagePlus image = IJ.openImage();
		image.show();
//    	ImagePlus image = IJ.openImage("D:/LYZ/miplib-public/Tubulin_STED_8bit_0_a.tif");
    	// Compute FFT
    	INDArray[] fftResult = FFTProcessor.computeFFTToArray(image);
    	ImagePlus FFT = ArrayUtils.convertND4JArrayToImagePlus(fftResult);
    	FFT.show();
    }
}