package com.HIT.WeisongZhao.utils;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;

import org.nd4j.linalg.indexing.INDArrayIndex;

public class Pad2Cube {
    
    /**
     * Pad a 3D image to a cube (all dimensions equal), using symmetric padding
     * @param im Input 3D INDArray
     * @return Object containing padded array and padding lengths
     */
    public static PadResult pad2cube(INDArray im, float pad_value) {
        // Get shape of input array
        long depth = im.size(0);
        long height = im.size(1);
        long width = im.size(2);
        
        // Calculate remainder for each dimension
        long[] comple = {
            depth % 2,
            height % 2,
            width % 2
        };
        
        // Step 1: Use symmetric padding to make each dimension even
        INDArray paddedStep1 = symmetricPad3ToEven(im, comple);
        
        // Get dimensions after padding
        long newDepth = paddedStep1.size(0);
        long newHeight = paddedStep1.size(1);
        long newWidth = paddedStep1.size(2);
        
        // Calculate maximum dimension
        long maxDim = Math.max(Math.max(newDepth, newHeight), newWidth);
        
        // Calculate padding amount for each dimension
        long[] padShape = {
            (maxDim - newDepth) / 2,
            (maxDim - newHeight) / 2,
            (maxDim - newWidth) / 2
        };
        INDArray padded = null;
        // Calculate padding length information
        long[] padLength = {
        	2 * padShape[0] + comple[0],	
        	2 * padShape[1] + comple[1],         
            2 * padShape[2] + comple[2] 
        };
        
        if (pad_value < 1e-4) {
        	INDArray window = Hanning.hanning3D((int)newDepth, (int)newHeight, (int)newWidth);
        	paddedStep1.muli(window);
        	padded = zeroPad(paddedStep1, padShape, 0);
        	
        } else {
        	// Step 2: Use symmetric padding to make all dimensions equal to maxDim
            padded = symmetricPad3(paddedStep1, padShape);
            INDArray window = Hanning.hanning3D((int)maxDim, (int)maxDim, (int)maxDim);
            padded.muli(window);
        }
        return new PadResult(padded, padLength);
        
    }
    
    public static PadResult pad2square(INDArray im, float pad_value) {
        // Get shape of input array
        long height = im.size(0);
        long width = im.size(1);
        
        // Calculate remainder for each dimension
        long[] comple = {
            height % 2,
            width % 2
        };
        
        // Step 1: Use symmetric padding to make each dimension even
        INDArray paddedStep1 = symmetricPad3ToEven(im, comple);
        
        long newHeight = paddedStep1.size(0);
        long newWidth = paddedStep1.size(1);
        
        // Calculate maximum dimension
        long maxDim = Math.max(newHeight, newWidth);
        
        // Calculate padding amount for each dimension
        long[] padShape = {
            (maxDim - newHeight) / 2,
            (maxDim - newWidth) / 2
        };
        INDArray padded = null;
        // Calculate padding length information
        long[] padLength = {
        	2 * padShape[0] + comple[0],	
        	2 * padShape[1] + comple[1],         
        };
        
        if (pad_value < 1e-4) {
        	INDArray window = Hanning.hanning2D((int)newHeight, (int)newWidth);
        	paddedStep1.muli(window);
        	padded = zeroPad(paddedStep1, padShape, 0);
        	
        } else {
        	// Step 2: Use symmetric padding to make all dimensions equal to maxDim
            padded = symmetricPad2(paddedStep1, padShape);
            INDArray window = Hanning.hanning2D((int)maxDim, (int)maxDim);
            padded.muli(window);
        }
        return new PadResult(padded, padLength);
    }
    
    /**
     * Use symmetric padding to make each dimension of the array even length
     * @param array Input array
     * @param comple Remainder for each dimension
     * @return Padded array
     */
    private static INDArray symmetricPad3ToEven(INDArray array, long[] comple) {
        if (comple.length == 3) {
        	if (comple[0] == 0 && comple[1] == 0 && comple[2] == 0) {
                return array;
            }
        	 long[] newShape = {
        	            array.size(0) + comple[0],
        	            array.size(1) + comple[1],
        	            array.size(2) + comple[2]
        	        };
        	 INDArray result = Nd4j.create(newShape);
        	 INDArrayIndex[] indices = new INDArrayIndex[] {
        	            NDArrayIndex.interval(0, array.size(0)),
        	            NDArrayIndex.interval(0, array.size(1)),
        	            NDArrayIndex.interval(0, array.size(2))
        	        };
        	 result.put(indices, array);
        	 symmetricFill3ToEven3(result, array, comple);
             return result;
        } else {
        	 long[] newShape = {
        	            array.size(0) + comple[0],
        	            array.size(1) + comple[1],
        	        };
        	 INDArray result = Nd4j.create(newShape);
        	 INDArrayIndex[] indices = new INDArrayIndex[] {
        	            NDArrayIndex.interval(0, array.size(0)),
        	            NDArrayIndex.interval(0, array.size(1)),
        	        };
        	 result.put(indices, array);
        	 symmetricFill3ToEven2(result, array, comple);
             return result;
        }
    }
    
    /**
     * Fill remaining parts of array using symmetric padding
     * @param result Result array
     * @param original Original array
     * @param comple Remainder for each dimension
     */
    private static void symmetricFill3ToEven3(INDArray result, INDArray original, long[] comple) {
        long depth = original.size(0);
        long height = original.size(1);
        long width = original.size(2);
        
        // Pad depth dimension
        if (comple[0] > 0) {
            // Get last depth slice
            INDArray lastDepthSlice = original.get(NDArrayIndex.point(depth - 1));
            // Ensure slice shape is correct
            if (lastDepthSlice.shape().length == 2) {
                // Copy to new position
                for (long y = 0; y < height; y++) {
                    for (long x = 0; x < width; x++) {
                        double value = lastDepthSlice.getDouble(y, x);
                        result.putScalar(new long[]{depth, y, x}, value);
                    }
                }
            }
        }
        
        // Pad height dimension
        if (comple[1] > 0) {
            for (long z = 0; z < result.size(0); z++) {
                for (long x = 0; x < width; x++) {
                    // Get value from last row
                    double lastValue = result.getDouble(z, height - 1, x);
                    // Copy to new position
                    result.putScalar(new long[]{z, height, x}, lastValue);
                }
            }
        }
        
        // Pad width dimension
        if (comple[2] > 0) {
            for (long z = 0; z < result.size(0); z++) {
                for (long y = 0; y < result.size(1); y++) {
                    // Get value from last column
                    double lastValue = result.getDouble(z, y, width - 1);
                    // Copy to new position
                    result.putScalar(new long[]{z, y, width}, lastValue);
                }
            }
        }
    }
    
    private static void symmetricFill3ToEven2(INDArray result, INDArray original, long[] comple) {
        long height = original.size(0);
        long width = original.size(1);     
        if (comple[0] > 0) {
        	for (long x = 0; x < width; x++) {
                double lastValue = result.getDouble(height - 1, x);
                result.putScalar(new long[]{height, x}, lastValue);
            }
        }
        if (comple[1] > 0) {
        	for (long y = 0; y < height; y++) {
                double lastValue = result.getDouble(y, width - 1);
                result.putScalar(new long[]{y, width}, lastValue);
            }
        }
    }
    
    private static INDArray zeroPad(INDArray array, long[] padShape, double constantValue) {
        // If no padding needed, return original array
        if (padShape[0] == 0 && padShape[1] == 0 && padShape[2] == 0) {
            return array;
        }
        
        // Calculate shape after padding
        long[] newShape = {
            array.size(0) + 2 * padShape[0],
            array.size(1) + 2 * padShape[1],
            array.size(2) + 2 * padShape[2]
        };
        
        // Create new array filled with constant value
        INDArray result = Nd4j.valueArrayOf(newShape, constantValue);
        
        // Copy original data to center
        INDArrayIndex[] indices = new INDArrayIndex[] {
            NDArrayIndex.interval(padShape[0], padShape[0] + array.size(0)),
            NDArrayIndex.interval(padShape[1], padShape[1] + array.size(1)),
            NDArrayIndex.interval(padShape[2], padShape[2] + array.size(2))
        };
        result.put(indices, array);
        
        return result;
    }
    
    /**
     * Pad array using symmetric padding mode
     * @param array Input array
     * @param padShape Padding amount for each dimension
     * @return Padded array
     */
    private static INDArray symmetricPad3(INDArray array, long[] padShape) {
        // If no padding needed, return original array
        if (padShape[0] == 0 && padShape[1] == 0 && padShape[2] == 0) {
            return array;
        }
        
        // Calculate shape after padding
        long[] newShape = {
            array.size(0) + 2 * padShape[0],
            array.size(1) + 2 * padShape[1],
            array.size(2) + 2 * padShape[2]
        };
        
        // Create new array
        INDArray result = Nd4j.create(newShape);
        
        // Copy original data to center
        INDArrayIndex[] indices = new INDArrayIndex[] {
            NDArrayIndex.interval(padShape[0], padShape[0] + array.size(0)),
            NDArrayIndex.interval(padShape[1], padShape[1] + array.size(1)),
            NDArrayIndex.interval(padShape[2], padShape[2] + array.size(2))
        };
        result.put(indices, array);
        
        // Implement symmetric padding
        symmetricFill3(result, padShape);
        
        return result;
    }
    
    private static INDArray symmetricPad2(INDArray array, long[] padShape) {
        // If no padding needed, return original array
        if (padShape[0] == 0 && padShape[1] == 0) {
            return array;
        }
        
        // Calculate shape after padding
        long[] newShape = {
            array.size(0) + 2 * padShape[0],
            array.size(1) + 2 * padShape[1],
        };
        
        // Create new array
        INDArray result = Nd4j.create(newShape);
        
        // Copy original data to center
        INDArrayIndex[] indices = new INDArrayIndex[] {
            NDArrayIndex.interval(padShape[0], padShape[0] + array.size(0)),
            NDArrayIndex.interval(padShape[1], padShape[1] + array.size(1)),
        };
        result.put(indices, array);
        
        // Implement symmetric padding
        symmetricFill2(result, padShape);
        
        return result;
    }
    
    /**
     * Implement symmetric padding logic
     * @param array Array to be padded
     * @param padShape Padding amount for each dimension
     */
    private static void symmetricFill3(INDArray array, long[] padShape) {
        long depth = array.size(0);
        long height = array.size(1);
        long width = array.size(2);
        
        long centerDepth = padShape[0];
        long centerHeight = padShape[1];
        long centerWidth = padShape[2];
        // TODO: Optimize using vector operations in the future
        // Pad depth dimension
        for (long z = 0; z < padShape[0]; z++) {
            for (long y = 0; y < height; y++) {
                for (long x = 0; x < width; x++) {
                    // Front padding (symmetric about center)
                    double sourceValue = array.getDouble(centerDepth + z, y, x);
                    array.putScalar(new long[]{centerDepth - 1 - z, y, x}, sourceValue);
                    
                    // Back padding (symmetric about center)
                    sourceValue = array.getDouble(depth - centerDepth - 1 - z, y, x);
                    array.putScalar(new long[]{depth - centerDepth + z, y, x}, sourceValue);
                }
            }
        }
        
        // Pad height dimension
        for (long y = 0; y < padShape[1]; y++) {
            for (long z = 0; z < depth; z++) {
                for (long x = 0; x < width; x++) {
                    // Front padding (symmetric about center)
                    double sourceValue = array.getDouble(z, centerHeight + y, x);
                    array.putScalar(new long[]{z, centerHeight - 1 - y, x}, sourceValue);
                    
                    // Back padding (symmetric about center)
                    sourceValue = array.getDouble(z, height - centerHeight - 1 - y, x);
                    array.putScalar(new long[]{z, height - centerHeight + y, x}, sourceValue);
                }
            }
        }
        
        // Pad width dimension
        for (long x = 0; x < padShape[2]; x++) {
            for (long z = 0; z < depth; z++) {
                for (long y = 0; y < height; y++) {
                    // Front padding (symmetric about center)
                    double sourceValue = array.getDouble(z, y, centerWidth + x);
                    array.putScalar(new long[]{z, y, centerWidth - 1 - x}, sourceValue);
                    
                    // Back padding (symmetric about center)
                    sourceValue = array.getDouble(z, y, width - centerWidth - 1 - x);
                    array.putScalar(new long[]{z, y, width - centerWidth + x}, sourceValue);
                }
            }
        }
    }
    
    private static void symmetricFill2(INDArray array, long[] padShape) {
        long height = array.size(0);
        long width = array.size(1);
        
        long centerHeight = padShape[0];
        long centerWidth = padShape[1];

        // Pad height dimension
        for (long y = 0; y < padShape[0]; y++) {
        	for (long x = 0; x < width; x++) {
                // Front padding (symmetric about center)
                double sourceValue = array.getDouble(centerHeight + y, x);
                array.putScalar(new long[]{centerHeight - 1 - y, x}, sourceValue);
                // Back padding (symmetric about center)
                sourceValue = array.getDouble(height - centerHeight - 1 - y, x);
                array.putScalar(new long[]{height - centerHeight + y, x}, sourceValue);
            }
        }
        
        // Pad width dimension
        for (long x = 0; x < padShape[1]; x++) {
        	for (long y = 0; y < height; y++) {
                // Front padding (symmetric about center)
                double sourceValue = array.getDouble(y, centerWidth + x);
                array.putScalar(new long[]{y, centerWidth - 1 - x}, sourceValue);
                
                // Back padding (symmetric about center)
                sourceValue = array.getDouble(y, width - centerWidth - 1 - x);
                array.putScalar(new long[]{y, width - centerWidth + x}, sourceValue);
            }
        }
    }
    
    
    /**
     * Container class for padding result
     */
    public static class PadResult {
        private final INDArray padded;
        private final long[] padLength;
        
        public PadResult(INDArray padded, long[] padLength) {
            this.padded = padded;
            this.padLength = padLength;
        }
        
        public INDArray getPadded() {
            return padded;
        }
        
        public long[] getPadLength() {
            return padLength;
        }
    }
    
    /**
     * Test method
     */
    public static void main(String[] args) {
        // Create a test array with shape [31, 511, 512]
        // Load image
    	new ImageJ();
		ImagePlus image = IJ.openImage();
		image.show();
		INDArray im = ArrayUtils.convertImagePlusToND4JArray(image);
		INDArray padded1 = pad2cube(im, 0).getPadded();
		ImagePlus imp = ArrayUtils.convertND4JArrayToImagePlus(padded1);
		imp.show();
		
        INDArray testArray = Nd4j.ones(31, 253, 256);
        System.out.println("Original array shape: " + java.util.Arrays.toString(testArray.shape()));
        
        try {
            // Try manually implemented symmetric padding
            PadResult result = pad2cube(testArray, 3);
            INDArray padded = result.getPadded();
            long[] padLength = result.getPadLength();
            
            System.out.println("Manually symmetrically padded array shape: " + java.util.Arrays.toString(padded.shape()));
            System.out.println("Padding length: " + java.util.Arrays.toString(padLength));
        } catch (Exception e) {
            System.out.println("Manual symmetric padding failed: " + e.getMessage());
            e.printStackTrace();
        }
       
    }
}