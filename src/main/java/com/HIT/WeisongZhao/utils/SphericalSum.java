package com.HIT.WeisongZhao.utils;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import java.util.LinkedHashMap;
import java.util.Map;

/** 3D spherical shell accumulation without broadcasted ND4J coordinate volumes. */
public final class SphericalSum {
    private SphericalSum() {}
    private static final int MAX_CACHE = 2;
    private static final int MAX_CACHED_VOXELS = 2_000_000;
    private static final Map<String, int[]> BIN_CACHE =
        new LinkedHashMap<String, int[]>(MAX_CACHE, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
                return size() > MAX_CACHE;
            }
        };

    private static int[] buildBins(int d, int h, int w, double step) {
        int[] indices = new int[Math.multiplyExact(Math.multiplyExact(d, h), w)];
        double[] x2 = new double[w], y2 = new double[h], z2 = new double[d];
        for (int x = 0; x < w; x++) { double t = x + 1 - w / 2.0; x2[x] = t*t; }
        for (int y = 0; y < h; y++) { double t = y + 1 - h / 2.0; y2[y] = t*t; }
        for (int z = 0; z < d; z++) { double t = z + 1 - d / 2.0; z2[z] = t*t; }
        int i = 0;
        for (int z = 0; z < d; z++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    indices[i++] = (int) Math.rint(Math.sqrt(z2[z]+y2[y]+x2[x])/step);
        return indices;
    }

    private static synchronized int[] bins(int d, int h, int w, double step) {
        if ((long) d * h * w > MAX_CACHED_VOXELS) return buildBins(d, h, w, step);
        String key = d+":"+h+":"+w+":"+Double.doubleToLongBits(step);
        int[] result = BIN_CACHE.get(key);
        if (result == null) {
            result = buildBins(d, h, w, step);
            BIN_CACHE.put(key, result);
        }
        return result;
    }

    public static INDArray sphericalSum(INDArray data, double radialStep) {
        if (!(radialStep > 0) || !Double.isFinite(radialStep))
            throw new IllegalArgumentException("radialStep must be positive and finite");
        int d = (int) data.size(0), h = (int) data.size(1), w = (int) data.size(2);
        int[] indices = bins(d, h, w, radialStep);
        int max = 0;
        for (int idx : indices) if (idx > max) max = idx;
        double[] sums = new double[max + 1];
        int i = 0;
        for (int z = 0; z < d; z++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    sums[indices[i++]] += data.getDouble(z, y, x);
        INDArray result = Nd4j.zeros(max + 1);
        for (int k = 0; k <= max; k++) result.putScalar(k, sums[k]);
        return result;
    }
}
