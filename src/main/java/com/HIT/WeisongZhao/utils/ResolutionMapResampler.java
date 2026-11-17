package com.HIT.WeisongZhao.utils;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;

/** Restore quantitative map dimensions without treating background as evidence. */
public final class ResolutionMapResampler {
    private ResolutionMapResampler() { }

    public static ImagePlus restore(ImagePlus input, int targetWidth, int targetHeight) {
        int width = input.getWidth(), height = input.getHeight();
        boolean unchanged = width == targetWidth && height == targetHeight;
        if (targetWidth < 1 || targetHeight < 1 || (!unchanged
                && (width != targetWidth / 2 || height != targetHeight / 2)))
            throw new IllegalArgumentException("Restoration expects a half-size map or an unchanged paired map.");
        ImageStack output = new ImageStack(targetWidth, targetHeight);
        for (int slice = 1; slice <= input.getStackSize(); slice++) {
            float[] source = (float[]) input.getStack().getProcessor(slice)
                    .convertToFloatProcessor().getPixels();
            float[] clean = source.clone();
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < clean.length; i++) {
                if (!Float.isFinite(clean[i]) || clean[i] <= 0) clean[i] = 0;
                else { min = Math.min(min, clean[i]); max = Math.max(max, clean[i]); }
            }
            float[] values = new float[targetWidth * targetHeight];
            if (unchanged) values = clean;
            else if (Float.isFinite(min)) {
                values = cubicSpline2D(clean, width, height, targetWidth, targetHeight);
            }
            if (!unchanged) {
                for (int y = 0; y < targetHeight; y++) {
                    int sy = Math.min(y / 2, height - 1);
                    for (int x = 0; x < targetWidth; x++) {
                        float original = clean[sy * width + Math.min(x / 2, width - 1)];
                        int i = y * targetWidth + x;
                        if (original <= 0 || !Float.isFinite(values[i]) || values[i] <= 0)
                            values[i] = 0;
                        else values[i] = Math.max(min, Math.min(max, values[i]));
                    }
                }
            }
            output.addSlice(input.getStack().getSliceLabel(slice),
                    new FloatProcessor(targetWidth, targetHeight, values));
        }
        ImagePlus result = new ImagePlus(input.getTitle(), output);
        result.setCalibration(input.getCalibration().copy());
        return result;
    }

    /** Tensor-product not-a-knot spline: X first, then Y, on the half grid. */
    private static float[] cubicSpline2D(float[] source, int width, int height,
                                         int targetWidth, int targetHeight) {
        double[] temporary = new double[height * targetWidth];
        for (int y = 0; y < height; y++) {
            double[] line = new double[width];
            for (int x = 0; x < width; x++) line[x] = source[y * width + x];
            double[] restored = splineLine(line, targetWidth);
            System.arraycopy(restored, 0, temporary, y * targetWidth, targetWidth);
        }
        float[] output = new float[targetWidth * targetHeight];
        for (int x = 0; x < targetWidth; x++) {
            double[] line = new double[height];
            for (int y = 0; y < height; y++) line[y] = temporary[y * targetWidth + x];
            double[] restored = splineLine(line, targetHeight);
            for (int y = 0; y < targetHeight; y++) output[y * targetWidth + x] = (float) restored[y];
        }
        return output;
    }

    /** Interpolate uniform samples at j/2, with constant extension at the last edge. */
    private static double[] splineLine(double[] values, int targetLength) {
        int n = values.length;
        double[] output = new double[targetLength];
        if (n == 1) {
            java.util.Arrays.fill(output, values[0]);
            return output;
        }
        double[] second = new double[n];
        if (n == 3) {
            // The not-a-knot spline through three samples is a single parabola.
            java.util.Arrays.fill(second, values[0] - 2 * values[1] + values[2]);
        } else if (n >= 4) {
            // Uniform-grid not-a-knot conditions eliminate the first/last unknowns.
            second[1] = values[0] - 2 * values[1] + values[2];
            second[n - 2] = values[n - 3] - 2 * values[n - 2] + values[n - 1];
            int unknowns = n - 4;
            double[] upper = new double[unknowns];
            double[] rhs = new double[unknowns];
            for (int k = 0; k < unknowns; k++) {
                int i = k + 2;
                double value = 6 * (values[i - 1] - 2 * values[i] + values[i + 1]);
                if (k == 0) value -= second[1];
                if (k == unknowns - 1) value -= second[n - 2];
                double denominator = 4 - (k > 0 ? upper[k - 1] : 0);
                upper[k] = k < unknowns - 1 ? 1 / denominator : 0;
                rhs[k] = (value - (k > 0 ? rhs[k - 1] : 0)) / denominator;
            }
            for (int k = unknowns - 1; k >= 0; k--)
                second[k + 2] = rhs[k] - (k < unknowns - 1 ? upper[k] * second[k + 3] : 0);
            second[0] = 2 * second[1] - second[2];
            second[n - 1] = 2 * second[n - 2] - second[n - 3];
        }
        // With two samples, the zero second derivatives give linear interpolation.
        for (int j = 0; j < targetLength; j++) {
            double coordinate = Math.min(j / 2.0, n - 1);
            int i = Math.min((int) Math.floor(coordinate), n - 2);
            double t = coordinate - i, a = 1 - t;
            output[j] = a * values[i] + t * values[i + 1]
                    + ((a * a * a - a) * second[i]
                    + (t * t * t - t) * second[i + 1]) / 6;
        }
        return output;
    }

    /** Fill exactly one rolling block; never overwrite its right-hand neighbour. */
    public static void fillBlock(float[] map, int width, int height,
                                 int x, int y, int skip, float value) {
        for (int yy = y; yy < Math.min(y + skip, height); yy++)
            for (int xx = x; xx < Math.min(x + skip, width); xx++)
                map[yy * width + xx] = value;
    }
}
