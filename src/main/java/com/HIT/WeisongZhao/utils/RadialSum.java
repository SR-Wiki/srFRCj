package com.HIT.WeisongZhao.utils;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cached 2D Fourier-ring bins and primitive-array accumulation. */
public final class RadialSum {
    private RadialSum() {}
    private static final int MAX_CACHE = 16;
    private static final Map<String, int[]> BIN_CACHE =
        new LinkedHashMap<String, int[]>(MAX_CACHE, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
                return size() > MAX_CACHE;
            }
        };

    private static synchronized int[] bins(int h, int w, double step) {
        String key = h + ":" + w + ":" + Double.doubleToLongBits(step);
        int[] indices = BIN_CACHE.get(key);
        if (indices != null) return indices;
        indices = new int[h * w];
        double[] x2 = new double[w];
        for (int x = 0; x < w; x++) {
            double t = x + 1 - w / 2.0;
            x2[x] = t * t;
        }
        int n = 0;
        for (int y = 0; y < h; y++) {
            double t = y + 1 - h / 2.0;
            double yy = t * t;
            for (int x = 0; x < w; x++)
                indices[n++] = (int) Math.rint(Math.sqrt(yy + x2[x]) / step);
        }
        BIN_CACHE.put(key, indices);
        return indices;
    }

    public static INDArray radialSum(INDArray data, double radialStep) {
        if (!(radialStep > 0) || !Double.isFinite(radialStep))
            throw new IllegalArgumentException("radialStep must be positive and finite");
        int h = (int) data.size(0), w = (int) data.size(1);
        int[] indices = bins(h, w, radialStep);
        int max = 0;
        for (int index : indices) if (index > max) max = index;
        double[] sums = new double[max + 1];
        int i = 0;
        // Use logical indexing for views/column-major ND4J arrays as well.
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                sums[indices[i++]] += data.getDouble(y, x);
        INDArray result = Nd4j.zeros(max + 1);
        for (int k = 0; k <= max; k++) result.putScalar(k, sums[k]);
        return result;
    }
}
