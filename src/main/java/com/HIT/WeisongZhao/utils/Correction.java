package com.HIT.WeisongZhao.utils;

import java.util.Arrays;

import com.HIT.WeisongZhao.FRC.FRCCurve;
import com.HIT.WeisongZhao.FRC.FRCCurveResult;

public class Correction {
    static final double eps = 1e-10;

    public static class Result {
        double[] corrected;
        double[] ad;

        public Result(double[] corrected, double[] ad) {
            this.corrected = corrected;
            this.ad = ad;
        }
    }

    public static double[] zCorrection(double[] single_frc) {
        double[] corrected = new double[single_frc.length];
        Arrays.fill(corrected, 0);
        for (int i = 0; i < single_frc.length; i++) {
        	corrected[i] = 2 * single_frc[i] / (1 + single_frc[i]);
        }
        return corrected;
    }
    
    
    /**
     * MATLAB_SQUARED matches MATLAB apply_correction.m's selection:
     * round(X.^2 + Y.^2) == r.^2 (integer-grid exact circles).
     * ROUNDED_RADIUS is the common default across Python, Java, and MATLAB:
     * round(sqrt(X.^2 + Y.^2)) == r (unit-width annuli).
     */
    public enum Geometry { MATLAB_SQUARED, ROUNDED_RADIUS }
    // Ordinary Fiji calls use the shared rounded-sqrt-radius definition.
    // The optional JVM property permits explicit legacy comparison only.
    private static final Geometry DEFAULT_GEOMETRY =
        "matlab_squared".equalsIgnoreCase(
            System.getProperty("srfrc.correction.geometry", "rounded_radius"))
            ? Geometry.MATLAB_SQUARED : Geometry.ROUNDED_RADIUS;

    private static final int MAX_FACTOR_ENTRIES = 12;
    private static final java.util.Map<Integer, double[]> FACTORS =
        new java.util.LinkedHashMap<Integer, double[]>(MAX_FACTOR_ENTRIES, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer,double[]> e) {
                return size() > MAX_FACTOR_ENTRIES;
            }
        };

    // Most srFRC windows share one FFT size. A volatile immutable entry
    // avoids contending on a synchronized cache lookup for every window.
    private static final class MostRecent {
        final int key;
        final double[] values;
        MostRecent(int key, double[] values) {
            this.key = key;
            this.values = values;
        }
    }
    private static volatile MostRecent mostRecent;

    private static double[] factors(int length) {
        return factors(length, DEFAULT_GEOMETRY);
    }

    private static double[] factors(int length, Geometry geometry) {
        if (geometry == null) throw new IllegalArgumentException("geometry cannot be null");
        // Cache by both length and geometry; a cache hit must never reuse
        // correction factors computed for another radial selection rule.
        int key = length * 2 + geometry.ordinal();
        MostRecent hit = mostRecent;
        if (hit != null && hit.key == key) return hit.values;
        return factorsLocked(length, geometry, key);
    }

    private static synchronized double[] factorsLocked(int length, Geometry geometry, int key) {
        double[] existing = FACTORS.get(key);
        if (existing != null) {
            mostRecent = new MostRecent(key, existing);
            return existing;
        }
        if (length == 0) return new double[0];

        // Preserve the original Fourier response and >= 4.0 rejection rule.
        // Geometry is selectable: exact squared radii or legacy rounded radii.
        // Only compute trigonometry needed by a selected bin.
        int w = 2 * length;
        double[] coordinate = new double[w];
        double[] theta = new double[w];
        double[] cosTheta = new double[w];
        double[] cosNegativeTheta = new double[w];
        double[] sinNegativeTheta = new double[w];
        for (int i = 0; i < w; i++) {
            coordinate[i] = -w / 2.0 + 1 + i;
            theta[i] = coordinate[i] * Math.PI / w;
            cosTheta[i] = Math.cos(theta[i]);
            cosNegativeTheta[i] = Math.cos(-theta[i]);
            sinNegativeTheta[i] = Math.sin(-theta[i]);
        }

        double[] sum = new double[length];
        double[] count = new double[length];
        // Index exact squared radii directly, avoiding both sqrt and
        // a fresh full-image Boolean mask for each radius. MATLAB's round()
        // is an identity because X and Y are integers here.
        final int[] squaredToBin;
        if (geometry == Geometry.MATLAB_SQUARED) {
            squaredToBin = new int[length * length + 1];
            Arrays.fill(squaredToBin, -1);
            for (int r = 1; r <= length; r++) squaredToBin[r * r] = r - 1;
        } else {
            squaredToBin = null;
        }
        for (int i = 0; i < w; i++) {
            double yi = coordinate[i];
            double t2 = theta[i];
            double cos2 = cosNegativeTheta[i];
            double sin2 = sinNegativeTheta[i];
            for (int j = 0; j < w; j++) {
                double xj = coordinate[j];
                int bin;
                if (geometry == Geometry.MATLAB_SQUARED) {
                    int r2 = (int) (yi * yi + xj * xj);
                    if (r2 > length * length) continue;
                    bin = squaredToBin[r2];
                    if (bin < 0) continue;
                } else {
                    int roundedRadius = (int) Math.round(Math.sqrt(yi * yi + xj * xj));
                    if (roundedRadius < 1 || roundedRadius > length) continue;
                    bin = roundedRadius - 1;
                }

                double both = -(theta[j] + t2);
                double cb = Math.cos(both);
                double sb = Math.sin(both);
                double cos1 = cosNegativeTheta[j];
                double sin1 = sinNegativeTheta[j];
                // Keep the *same* sequence of squared terms and products as
                // before, instead of replacing the numerator by its ideal
                // constant 4. This matters for div >= 4.0 at the boundary.
                // A square is an ordinary multiplication; calculate shared
                // terms only once per Fourier pixel. The four-term numerator
                // is intentionally left unchanged to preserve its rounding
                // and the existing div >= 4.0 rejection rule.
                double cp = cb + 1, cm = cb - 1;
                double sb2 = sb * sb;
                double yp = cos1 + cos2, ym = cos1 - cos2;
                double zp = sin1 + sin2, zm = sin1 - sin2;
                double x1 = cp * cp + sb2;
                double x2 = cm * cm + sb2;
                double y1 = yp * yp + zp * zp;
                double y2 = ym * ym + zm * zm;
                double a = Math.sqrt(x1 * y1 + x2 * y2 + x1 * y2 + x2 * y1);
                double re = cosTheta[j] + cosTheta[i];
                double div = re != 0 ? a / re : 0;
                if (!Double.isFinite(div) || div >= 4.0) div = 0;
                sum[bin] += div;
                count[bin]++;
            }
        }
        double[] ad = new double[length];
        for (int r = 0; r < length; r++) ad[r] = sum[r] / count[r];
        FACTORS.put(key, ad);
        mostRecent = new MostRecent(key, ad);
        return ad;
    }

    public static double[] applyCorrection(double[] single_frc) {
        return applyCorrection(single_frc, DEFAULT_GEOMETRY);
    }

    /** Specify MATLAB_SQUARED to opt into MATLAB squared-radius selection. */
    public static double[] applyCorrection(double[] single_frc, Geometry geometry) {
        int length = single_frc.length;
        double[] corrected = new double[length];
        double[] ad = factors(length, geometry);
        int firstCrossing = -1;
        for (int r = 0; r < length; r++) {
            double tmp = single_frc[r];
            corrected[r] = (ad[r] * tmp + eps) / (1 + (ad[r] - 1) * tmp + eps);
            if (corrected[r] < 1.0 / 7.0) {
                firstCrossing = r;
                break;
            }
        }
        if (firstCrossing >= 0) {
            // The old implementation retains five corrected samples counting
            // the first crossing, then restores the unchanged input tail.
            int tailStart = Math.min(length, firstCrossing + 5);
            for (int r = firstCrossing + 1; r < tailStart; r++) {
                double tmp = single_frc[r];
                corrected[r] = (ad[r] * tmp + eps) / (1 + (ad[r] - 1) * tmp + eps);
            }
            if (tailStart < length)
                System.arraycopy(single_frc, tailStart, corrected, tailStart, length - tailStart);
        }
        for (int i = 0; i < length; i++) {
            if (corrected[i] > 1) corrected[i] = 1;
            if (corrected[i] < 0) corrected[i] = 0;
        }
        return corrected;
    }

    public static  FRCCurve applyCorrection(FRCCurve frccurve) {
        return applyCorrection(frccurve, DEFAULT_GEOMETRY);
    }

    public static FRCCurve applyCorrection(FRCCurve frccurve, Geometry geometry) {
    	double[] single_frc = frccurve.getCorrelationValues();
        int h = 2 * single_frc.length;
        int w = 2 * single_frc.length;
        double[] corrected = applyCorrection(single_frc, geometry);
        FRCCurveResult[] results = new FRCCurveResult[corrected.length];
        results[0] = new FRCCurveResult(0, 1, 1, 1, 1);
        for (int i = 1; i < corrected.length; i++) {
        	results[i] = new FRCCurveResult(frccurve.get(i).getRadius(), frccurve.get(i).getNumberOfSamples(), corrected[i]);
        }
        return new FRCCurve(frccurve.fieldOfView, results);
        
    }
    

}
