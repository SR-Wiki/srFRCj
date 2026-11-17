package com.HIT.WeisongZhao.utils;

/** Statistics of the final resolution map, excluding zero-valued background. */
public final class ResolutionMapStatistics {
    public final double mean, min, max, rFRC;
    public final int count;

    private ResolutionMapStatistics(double mean, double min, double max, int count) {
        this.mean = mean;
        this.min = min;
        this.max = max;
        this.count = count;
        // A non-positive cutoff map cannot define the physical rFRC ratio.
        this.rFRC = count > 0 && min > 0 ? mean / min - 1 : Double.NaN;
    }

    /** Treat non-positive interpolation values as background in the output map. */
    public static void setNonPositiveToBackground(float[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] <= 0) pixels[i] = 0;
        }
    }

    /** Apply the lower bound to final map pixels, using original sampling in nm. */
    public static void finalizeMap(float[] pixels, double originalPixelSizeNm) {
        if (!Double.isFinite(originalPixelSizeNm) || originalPixelSizeNm <= 0)
            throw new IllegalArgumentException("Enter a positive, finite original pixel size in nm.");
        double minimum = 2.3 * originalPixelSizeNm;
        float floor = (float) minimum;
        if (!Float.isFinite(floor))
            throw new IllegalArgumentException("The original pixel size is too large for a float resolution map.");
        if (floor < minimum) floor = Math.nextUp(floor);
        setNonPositiveToBackground(pixels);
        for (int i = 0; i < pixels.length; i++) {
            if (Float.isFinite(pixels[i]) && pixels[i] > 0 && pixels[i] < floor)
                pixels[i] = floor;
        }
    }

    public static ResolutionMapStatistics calculate(float[] pixels) {
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (float value : pixels) {
            if (Float.isFinite(value) && value > 0) {
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
                count++;
            }
        }
        if (count == 0) return new ResolutionMapStatistics(Double.NaN, Double.NaN, Double.NaN, 0);
        return new ResolutionMapStatistics(sum / count, min, max, count);
    }
}
