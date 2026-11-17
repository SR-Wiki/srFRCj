package com.HIT.WeisongZhao.utils;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/** Floating-point input copies normalized by one maximum per image or stack. */
public final class InputImageUtils {
    private InputImageUtils() { }

    public static FloatProcessor normalizedImage(ImageProcessor input) {
        ImageStack stack = new ImageStack(input.getWidth(), input.getHeight());
        stack.addSlice(input);
        return (FloatProcessor) normalizedStack(stack).getProcessor(1);
    }

    public static ImagePlus normalizedVolume(ImagePlus input) {
        ImagePlus result = new ImagePlus(input.getTitle(), normalizedStack(input.getStack()));
        result.setCalibration(input.getCalibration().copy());
        result.setDimensions(input.getNChannels(), input.getNSlices(), input.getNFrames());
        return result;
    }

    public static ImageStack normalizedStack(ImageStack input) {
        if (input.getSize() == 0)
            throw new IllegalArgumentException("Provide a nonempty image stack.");
        ImageStack result = new ImageStack(input.getWidth(), input.getHeight());
        float maximum = Float.NEGATIVE_INFINITY;
        for (int slice = 1; slice <= input.getSize(); slice++) {
            FloatProcessor image = (FloatProcessor) input.getProcessor(slice)
                    .convertToFloatProcessor().duplicate();
            float[] pixels = (float[]) image.getPixels();
            for (float value : pixels) {
                if (!Float.isFinite(value))
                    throw new IllegalArgumentException(
                            "Image intensities must be finite; remove NaN or infinite values before analysis.");
                maximum = Math.max(maximum, value);
            }
            result.addSlice(input.getSliceLabel(slice), image);
        }
        if (maximum != 0.0f) {
            for (int slice = 1; slice <= result.getSize(); slice++) {
                float[] pixels = (float[]) result.getProcessor(slice).getPixels();
                for (int i = 0; i < pixels.length; i++) pixels[i] /= maximum;
                result.getProcessor(slice).resetMinAndMax();
            }
        }
        return result;
    }
}
