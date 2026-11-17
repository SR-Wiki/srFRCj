package com.HIT.WeisongZhao;

import java.util.Arrays;

import javax.swing.JDialog;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

public class Percentile implements PlugIn{
	public static void applyPercentileClipping(ImageStack ims, double lowerPercentile,  double upperPercentile) {
		ImageProcessor ip = ims.getProcessor(1);
		double preMax = calculatePercentile(ip, 100);
		applyPercentileClipping(ip, lowerPercentile, upperPercentile);
		double afterMax = calculatePercentile(ip, 100);
		ims.deleteSlice(1);
		ims.addSlice(ip);
	}
	public static void applyPercentileClipping(ImageProcessor ip, double lowerPercentile,  double upperPercentile) {
		// Calculate percentile values
		double minVal = calculatePercentile(ip, lowerPercentile);
		double maxVal = calculatePercentile(ip, upperPercentile);

		// Apply clipping and stretching
		int width = ip.getWidth();
		int height = ip.getHeight();
		
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				double value = ip.getPixelValue(x, y);
		
				// Clip
				if (value < minVal) value = minVal;
				if (value > maxVal) value = maxVal;
		
				ip.putPixelValue(x, y, value);
			}
		}
	}
	
	public static void applyPercentileClipping(float[] pixels, double lowerPercentile,  double upperPercentile) {
		// Calculate percentile values
		float minVal = (float) calculatePercentile(pixels, lowerPercentile);
		float maxVal = (float) calculatePercentile(pixels, upperPercentile);
				
		for (int i = 0; i < pixels.length; i++) {
			if (pixels[i] < minVal)
				pixels[i] = minVal;
			if (pixels[i] > maxVal)
				pixels[i] = maxVal;
					
		}			
	}
	
	public static double calculatePercentile(ImageProcessor ip, double percentile) {
        // Get the pixel-value array
        float[] pixels = (float[]) ip.convertToFloatProcessor().getPixels();
        
        // Sort the array
        Arrays.sort(pixels);
        
        // Calculate the percentile index
        double pos = (pixels.length - 1) * percentile;
        int index = (int) Math.ceil(pos);
        // Linear interpolation
        return pixels[index];
    }
	
	public static double calculatePercentile(float[] pixels, double percentile) {
		float[] pixelsCopy = pixels.clone();
        // Sort the array
        Arrays.sort(pixelsCopy);
        
        // Calculate the percentile index
        double pos = (pixelsCopy.length - 1) * percentile;
        int index = (int) Math.ceil(pos);
        // Linear interpolation
        return pixelsCopy[index];
    }
	
	public void run(String args) {
		// TODO Auto-generated method stub
		if (IJ.versionLessThan("1.46j"))
			return;
		ImagePlus imp = WindowManager.getCurrentImage();
		ImageStack ims = imp.getImageStack();
		applyPercentileClipping(ims, 0,  0.95);
		imp.setStack(ims);
		imp.show();

	}
	
	public static void main(String[] args) {
		Class<?> clazz = Percentile.class;
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
