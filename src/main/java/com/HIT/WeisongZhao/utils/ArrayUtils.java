package com.HIT.WeisongZhao.utils;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

public class ArrayUtils {
	
	public static INDArray convertImagePlusToND4JArray(ImagePlus image) {
		float[] data = convertImagePlusToFloatArray(image);
		int width = image.getWidth();
        int height = image.getHeight();
        int depth = image.getStackSize();
        // for 3D and 2D
        if (depth > 1)
        	return convertFloatArrayToND4JArray(data, depth, height, width);
        else
        	return convertFloatArrayToND4JArray(data, height, width);
	}
	/**
     * Creates a 1D INDArray from a one-dimensional float array.
     * @param data One-dimensional float array
     * @param width Size of the array
     * @return 1D INDArray with shape (width)
     */
	public static INDArray convertFloatArrayToND4JArray(float[] data, int width) {
        // Check if data length matches
        if (data.length != width) {
            throw new IllegalArgumentException("Data length does not match specified dimensions. " +
                    "Expected: " + (width) + ", Actual: " + data.length);
        }
        
        INDArray result = Nd4j.create(new int[]{width});
        int index = 0;
        for (int w = 0; w < width; w++) {
            result.putScalar(new int[]{w}, data[index++]);
        }   
        return result;
    }
	
	// 2D
	public static INDArray convertFloatArrayToND4JArray(float[] data, int height, int width) {
        // Check if data length matches
        if (data.length != height * width) {
            throw new IllegalArgumentException("Data length does not match specified dimensions. " +
                    "Expected: " + (height * width) + ", Actual: " + data.length);
        }
        
        INDArray result = Nd4j.create(new int[]{height, width});
        int index = 0;
        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                result.putScalar(new int[]{h, w}, data[index++]);
            }
        }
        return result;
    }
	
	// 3D
    public static INDArray convertFloatArrayToND4JArray(float[] data, int depth, int height, int width) {
        // Check if data length matches
        if (data.length != depth * height * width) {
            throw new IllegalArgumentException("Data length does not match specified dimensions. " +
                    "Expected: " + (depth * height * width) + ", Actual: " + data.length);
        }
        
        INDArray result = Nd4j.create(new int[]{depth, height, width});
        int index = 0;
        for (int d = 0; d < depth; d++) {
            for (int h = 0; h < height; h++) {
                for (int w = 0; w < width; w++) {
                    result.putScalar(new int[]{d, h, w}, data[index++]);
                }
            }
        }
        return result;
    }
    
    // for 3D and 2D
    public static float[] convertImagePlusToFloatArray(ImagePlus image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = image.getStackSize();
        
        float[] data = new float[width * height * depth];
        
        for (int z = 0; z < depth; z++) {
            ImageProcessor ip = image.getStack().getProcessor(z + 1); // ImageJ stack is 1-indexed
            float[] sliceData = (float[]) ip.convertToFloatProcessor().getPixels();
            
            System.arraycopy(sliceData, 0, data, z * width * height, width * height);
        }
        
        return data;
    }
    
    public static ImagePlus convertND4JArrayToImagePlus(INDArray[] fft) {
    	return convertND4JArrayToImagePlus(fft, "");
    }
    
    public static ImagePlus convertND4JArrayToImagePlus(INDArray[] fft, String title) {
    	INDArray abs = Correlations.computeLogAbs(fft[0], fft[1]);
    	if (abs.rank() < 3) {
    		abs = abs.reshape(new int[] {1, (int) abs.size(0), (int) abs.size(1)});
    	}
    	int depth = (int) abs.size(0);
    	int height = (int) abs.size(1);
    	int width = (int) abs.size(2);
    	ImageStack stack = new ImageStack(width, height);
    	// Convert each slice to FloatProcessor and add to stack
        for (int z = 0; z < depth; z++) {
            // Extract current slice
            INDArray slice = abs.get(NDArrayIndex.point(z), NDArrayIndex.all(), NDArrayIndex.all());
            // Convert INDArray slice to float array
            float[] sliceData = convertSliceToFloatArray(slice, height, width);
       
            // Create FloatProcessor
            FloatProcessor fp = new FloatProcessor(width, height, sliceData);
            // Add to stack
            stack.addSlice("Slice " + (z + 1), fp);
        }
        // Create ImagePlus
        ImagePlus imagePlus = new ImagePlus(title, stack);
        return imagePlus;
    	
    }
    
    public static float[] convertSliceToFloatArray(INDArray slice, int height, int width) {
        float[] data = new float[height * width];
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = slice.getFloat(y, x);
                data[y * width + x] = value;
            }
        }
        
        return data;
    }
    
    // for 3D and 2D
    public static ImagePlus convertND4JArrayToImagePlus(INDArray imageArray) {
    	return convertND4JArrayToImagePlus(imageArray, "");
    }
    
    public static ImagePlus convertND4JArrayToImagePlus(INDArray imageArray, String title) {
    	if (imageArray.rank() < 3) {
    		imageArray = imageArray.reshape(new int[] {1, (int) imageArray.size(0), (int) imageArray.size(1)});
    	}
    	int depth = (int) imageArray.size(0);
    	int height = (int) imageArray.size(1);
    	int width = (int) imageArray.size(2);
    	ImageStack stack = new ImageStack(width, height);
    	for (int z = 0; z < depth; z++) {
    		INDArray slice = imageArray.slice(z);
    		float[] pixels = convertSliceToFloatArray(slice, height, width);
    		stack.addSlice(new FloatProcessor(width, height, pixels));
    	}
    	return new ImagePlus(title, stack);
	}
    
    public static float[] convertND4JArrayToFloatArray(INDArray imageArray) {
    	int depth = 1, height = 1, width;
    	INDArray temp;
    	if (imageArray.shape().length == 2) {
    		height = (int) imageArray.size(0);
    		width = (int) imageArray.size(1);
    		temp = imageArray.reshape(1, height, width);
    	} else if (imageArray.shape().length == 1) {
    		width = (int) imageArray.size(0);
    		temp = imageArray.reshape(1, 1, width);
    	} else {
    		depth = (int) imageArray.size(0);
    		height = (int) imageArray.size(1);
    		width = (int) imageArray.size(2);
    		temp = imageArray;
    	}
    	long totalElements = depth * height * width;
    	float[] result = new float[(int) totalElements];
    	int index = 0;
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    result[index++] = temp.getFloat(z, y, x);
                }
            }
        }
        return result;
	}
    
    public static INDArray[] meshGrid2D(int height, int width) {
    	// Create coordinate grid
        INDArray x = Nd4j.linspace(1, width, width).subi(width / 2.0);
        INDArray y = Nd4j.linspace(1, height, height).subi(height / 2.0);
        
        // Use broadcasting to create mesh grid
        INDArray X = x.reshape(1, (int)width);
        INDArray Y = y.reshape((int)height, 1);
        
        // Broadcast to same shape
        X = X.broadcast(height, width);
        Y = Y.broadcast(height, width);
        
        INDArray[] meshGrid = new INDArray[2];
        meshGrid[0] = X;
        meshGrid[1] = Y;
        return meshGrid;
    }
    
    public static INDArray[] meshGrid3D(int depth, int height, int width) {
    	// Create coordinate grid
        INDArray x = Nd4j.linspace(1, width, width).subi(width / 2.0);
        INDArray y = Nd4j.linspace(1, height, height).subi(height / 2.0);
        INDArray z = Nd4j.linspace(1, depth, depth).subi(depth / 2.0);
        
        // Use broadcasting to create 3D mesh grid
        INDArray X = x.reshape(1, 1, (int)width);
        INDArray Y = y.reshape(1, (int)height, 1);
        INDArray Z = z.reshape((int)depth, 1, 1);
        
        // Broadcast to same shape
        X = X.broadcast(depth, height, width);
        Y = Y.broadcast(depth, height, width);
        Z = Z.broadcast(depth, height, width);
        
        INDArray[] meshGrid = new INDArray[3];
        meshGrid[0] = X;
        meshGrid[1] = Y;
        meshGrid[2] = Z;
        return meshGrid;
    }
    
    public static INDArray samplingXY(INDArray imageArray, double factor) {
    	// Get dimensions of input array
        long depth = imageArray.size(0);
        long height = imageArray.size(1);
        long width = imageArray.size(2);
        // Calculate new height and width
        int newHeight = (int) Math.ceil(height / factor);
        int newWidth = (int) Math.ceil(width / factor);
        // Ensure new dimensions are even
        if (newHeight % 2 != 0) {
            newHeight--;
        }
        if (newWidth % 2 != 0) {
            newWidth--;
        }
        // Create downsampled array
        INDArray downsampled = Nd4j.create(new int[]{(int)depth, newHeight, newWidth});
        // Perform downsampling
        for (int i = 0; i < newHeight; i++) {
            for (int j = 0; j < newWidth; j++) {
                // Calculate original position
                int origI = (int) (factor * i);
                int origJ = (int) (factor * j);
                
                // Copy entire depth dimension data
                INDArray slice = imageArray.get(
                	NDArrayIndex.all(),
                    NDArrayIndex.point(origI),
                    NDArrayIndex.point(origJ)
                );
                INDArrayIndex[] indices = new INDArrayIndex[] {
                		NDArrayIndex.all(),
                		NDArrayIndex.point(i),
                        NDArrayIndex.point(j),
                    };
                downsampled.put(indices, slice);
            }
        }
        return downsampled;
    }
    
    public static INDArray samplingZ(INDArray imageArray, double factor) {
    	// Get dimensions of input array
    	long depth = imageArray.size(0);
        int height = (int) imageArray.size(1);
        int width = (int) imageArray.size(2);
        
        // Calculate new depth
        int newDepth = (int) Math.ceil(depth / factor);
        
        // Ensure new depth is even
        if (newDepth % 2 != 0) {
            newDepth--;
        }

        // Create downsampled array
        INDArray downsampled = Nd4j.create(new int[]{newDepth, height, width});
        
        // Perform downsampling
        for (int i = 0; i < newDepth; i++) {
                // Calculate original position
                int origI = (int) (factor * i);
                
                // Copy entire slice data
                INDArray slice = imageArray.slice(origI);
//                INDArrayIndex[] indices = new INDArrayIndex[] {
//                		NDArrayIndex.point(i),
//                		NDArrayIndex.all(),
//                        NDArrayIndex.all(),
//                    };
                downsampled.putSlice(i, slice);
            }
        return downsampled;
    }
    
    public static double[] float2Double(float[] array) {
    	if (array == null) {
            return new double[0];
        }
        
        double[] doubleArray = new double[array.length];
        for (int i = 0; i < array.length; i++) {
            doubleArray[i] = array[i];
        }
        return doubleArray;
    }
    
    public static float[] double2Float(double[] array) {
    	if (array == null) {
            return new float[0];
        }
        
        float[] floatArray = new float[array.length];
        for (int i = 0; i < array.length; i++) {
        	floatArray[i] = (float) array[i];
        }
        return floatArray;
    }

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		new ImageJ();
		ImagePlus image = IJ.openImage();
		image.show();
		INDArray array = convertImagePlusToND4JArray(image);
		INDArray downsampled = samplingZ(array, 2);
		ImagePlus newImp = convertND4JArrayToImagePlus(downsampled);
		newImp.show();
		
	}

}