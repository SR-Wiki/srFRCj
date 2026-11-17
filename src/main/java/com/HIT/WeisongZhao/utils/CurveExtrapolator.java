package com.HIT.WeisongZhao.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Robust extrapolation of FRC/FSC curves with the model
 *
 *   FRC(f) = A exp(-k x^2) / (A exp(-k x^2) + 1),
 *   x = f / f_max(measured).
 *
 * Design goals:
 *  1) fit the same corrected/smoothed curve used for threshold detection;
 *  2) preserve all measured samples and their frequency coordinates exactly;
 *  3) constrain A > 0 and k > 0 by optimizing log(A) and log(k);
 *  4) emphasize the transition region and down-weight outliers with IRLS;
 *  5) never fabricate fixed fallback parameters when fitting fails;
 *  6) remain threshold-agnostic: this class only fits and extends the curve;
 *     threshold crossing and resolution determination stay in the caller's
 *     existing FRC/FSC resolution code.
 */
public final class CurveExtrapolator {

    private static final double EPS = 1e-6;
    private static final double MIN_K = 1e-10;
    private static final double MID_LOW = 0.05;
    private static final double MID_HIGH = 0.95;
    private static final int MIN_FIT_POINTS = 4;
    private static final int MAX_LM_ITER = 250;
    private static final int MAX_IRLS_ITER = 6;

    private CurveExtrapolator() {}

    public static final class Result {
        public final boolean success;
        public final boolean usedLinearizedFallback;
        public final double A;
        /** k for normalized frequency x=f/fMax. */
        public final double k;
        /** k for physical frequency f: exp(-kPhysical*f^2). */
        public final double kPhysical;
        public final double rSquared;
        public final double rmse;
        public final int originalLength;
        public final double measuredFMax;
        public final double[] frequency;
        public final double[] frc;
        public final String message;

        private Result(boolean success,
                       boolean usedLinearizedFallback,
                       double A,
                       double k,
                       double rSquared,
                       double rmse,
                       int originalLength,
                       double measuredFMax,
                       double[] frequency,
                       double[] frc,
                       String message) {
            this.success = success;
            this.usedLinearizedFallback = usedLinearizedFallback;
            this.A = A;
            this.k = k;
            this.kPhysical = success && measuredFMax > 0
                    ? k / (measuredFMax * measuredFMax)
                    : Double.NaN;
            this.rSquared = rSquared;
            this.rmse = rmse;
            this.originalLength = originalLength;
            this.measuredFMax = measuredFMax;
            this.frequency = frequency;
            this.frc = frc;
            this.message = message;
        }

        public static Result failure(String message) {
            return new Result(false, false,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0, Double.NaN, null, null, message);
        }
    }

    private static final class FitData {
        final double[] x;
        final double[] y;
        final double[] baseWeights;
        final double fMax;

        FitData(double[] x, double[] y, double[] baseWeights, double fMax) {
            this.x = x;
            this.y = y;
            this.baseWeights = baseWeights;
            this.fMax = fMax;
        }
    }

    private static final class FitResult {
        boolean success;
        boolean usedFallback;
        double alpha; // log(A)
        double beta;  // log(k)
        double rSquared;
        double rmse;
        String message;
    }

    /**
     * Extrapolate an FRC/FSC curve.
     *
     * @param measuredFrc measured, already smoothed/corrected curve used for cutoff detection
     * @param measuredFrequency frequency coordinate for each measured sample
     * @param newLength total output length; must be >= measured length
     */
    public static Result extrapolate(double[] measuredFrc,
                                     double[] measuredFrequency,
                                     int newLength) {
        String error = validateInputs(measuredFrc, measuredFrequency, newLength);
        if (error != null) {
            return Result.failure(error);
        }

        final int n = measuredFrc.length;
        final double fMax = measuredFrequency[n - 1];

        FitData fitData = prepareFitData(measuredFrc, measuredFrequency);
        if (fitData == null || fitData.x.length < MIN_FIT_POINTS) {
            return Result.failure("Too few valid points for curve extrapolation.");
        }

        FitResult fit = fitRobust(fitData);
        if (!fit.success) {
            return Result.failure(fit.message == null ? "Curve fitting failed." : fit.message);
        }

        double A = safeExp(fit.alpha);
        double k = safeExp(fit.beta);
        if (!Double.isFinite(A) || !Double.isFinite(k) || A <= 0 || k <= MIN_K) {
            return Result.failure("Curve fitting produced non-physical parameters.");
        }

        double[] newFrequency = extendFrequencyAxis(measuredFrequency, newLength);
        if (newFrequency == null) {
            return Result.failure("Unable to construct a valid extrapolated frequency axis.");
        }

        double[] extrapolated = new double[newLength];
        // Keep measured data unchanged. Do not replace it with the fitted model.
        System.arraycopy(measuredFrc, 0, extrapolated, 0, n);

        for (int i = n; i < newLength; i++) {
            double x = newFrequency[i] / fMax;
            extrapolated[i] = modelFromLogParams(x, fit.alpha, fit.beta);
            if (!Double.isFinite(extrapolated[i])) {
                return Result.failure("The fitted model became non-finite in the extrapolated region.");
            }
            extrapolated[i] = clamp(extrapolated[i], 0.0, 1.0);
        }

        return new Result(true, fit.usedFallback,
                A, k, fit.rSquared, fit.rmse,
                n, fMax, newFrequency, extrapolated,
                fit.message == null ? "OK" : fit.message);
    }

    /**
     * This class intentionally does not determine threshold crossings or
     * resolution.  Callers should pass the returned extended curve back to
     * their existing FRC/FSC threshold-detection code so that measured and
     * extrapolated data use exactly the same criterion.
     */

    /**
     * Compatibility wrapper for legacy callers that pass log(FRC) as y.
     * Returns {A, kPhysical}; returns null on failure. It never returns fixed
     * fabricated fallback parameters.
     */
    public static double[] stableFit(double[] frequency, double[] logFrc) {
        if (frequency == null || logFrc == null || frequency.length != logFrc.length
                || frequency.length < MIN_FIT_POINTS) {
            return null;
        }
        double[] frc = new double[logFrc.length];
        for (int i = 0; i < logFrc.length; i++) {
            if (!Double.isFinite(logFrc[i])) {
                return null;
            }
            frc[i] = clamp(Math.exp(logFrc[i]), 0.0, 1.0);
        }
        Result r = extrapolate(frc, frequency, frequency.length);
        if (!r.success) {
            return null;
        }
        return new double[] {r.A, r.kPhysical};
    }

    private static String validateInputs(double[] frc, double[] frequency, int newLength) {
        if (frc == null || frequency == null) {
            return "FRC and frequency arrays cannot be null.";
        }
        if (frc.length != frequency.length) {
            return "FRC and frequency arrays must have the same length.";
        }
        if (frc.length < MIN_FIT_POINTS) {
            return "At least " + MIN_FIT_POINTS + " measured points are required.";
        }
        if (newLength < frc.length) {
            return "newLength must be greater than or equal to the measured curve length.";
        }
        if (!Double.isFinite(frequency[0]) || frequency[0] < 0) {
            return "The first frequency must be finite and non-negative.";
        }
        for (int i = 0; i < frc.length; i++) {
            if (!Double.isFinite(frc[i])) {
                return "FRC contains a non-finite value at index " + i + ".";
            }
            if (!Double.isFinite(frequency[i])) {
                return "Frequency contains a non-finite value at index " + i + ".";
            }
            if (i > 0 && frequency[i] <= frequency[i - 1]) {
                return "Frequency must be strictly increasing.";
            }
        }
        if (frequency[frequency.length - 1] <= 0) {
            return "The measured maximum frequency must be greater than zero.";
        }
        return null;
    }

    private static FitData prepareFitData(double[] frc, double[] frequency) {
        final int n = frc.length;
        final double fMax = frequency[n - 1];

        List<Integer> midrange = new ArrayList<Integer>();
        List<Integer> allValid = new ArrayList<Integer>();

        for (int i = 0; i < n; i++) {
            double y = frc[i];
            double f = frequency[i];
            if (!Double.isFinite(y) || !Double.isFinite(f)) {
                continue;
            }
            // The model itself is defined on (0,1). Values outside that range
            // are retained in the measured output but excluded/clipped for fitting.
            allValid.add(i);
            if (y >= MID_LOW && y <= MID_HIGH) {
                midrange.add(i);
            }
        }

        int desiredMidrange = Math.max(6, (int) Math.round(0.20 * n));
        List<Integer> selected = midrange.size() >= desiredMidrange ? midrange : allValid;
        if (selected.size() < MIN_FIT_POINTS) {
            return null;
        }

        // When falling back to all points, omit endpoints if there is enough data.
        int start = 0;
        int end = selected.size();
        if (selected == allValid && selected.size() >= MIN_FIT_POINTS + 2) {
            start = 1;
            end = selected.size() - 1;
        }
        int m = end - start;
        if (m < MIN_FIT_POINTS) {
            start = 0;
            end = selected.size();
            m = end;
        }

        double[] x = new double[m];
        double[] y = new double[m];
        double[] w = new double[m];
        for (int j = 0; j < m; j++) {
            int i = selected.get(start + j);
            x[j] = frequency[i] / fMax;
            y[j] = clamp(frc[i], EPS, 1.0 - EPS);
            // Same principle as the MATLAB implementation: emphasize the
            // transition region, where threshold prediction is most informative.
            w[j] = Math.max(y[j] * (1.0 - y[j]), 0.02);
        }
        return new FitData(x, y, w, fMax);
    }

    private static FitResult fitRobust(FitData data) {
        FitResult init = linearizedInitialization(data.x, data.y, data.baseWeights);
        if (!init.success) {
            return init;
        }

        double alpha = init.alpha;
        double beta = init.beta;
        double[] robustWeights = new double[data.x.length];
        Arrays.fill(robustWeights, 1.0);
        boolean acceptedAny = false;

        for (int outer = 0; outer < MAX_IRLS_ITER; outer++) {
            double[] optimized = optimizeLM(data.x, data.y, data.baseWeights,
                    robustWeights, alpha, beta);
            if (optimized == null) {
                break;
            }
            alpha = optimized[0];
            beta = optimized[1];
            acceptedAny = true;

            double[] residuals = residuals(data.x, data.y, alpha, beta);
            double scale = robustScale(residuals);
            if (!Double.isFinite(scale) || scale < 1e-8) {
                break;
            }
            double huber = 1.345 * scale;
            for (int i = 0; i < residuals.length; i++) {
                double a = Math.abs(residuals[i]);
                robustWeights[i] = a <= huber ? 1.0 : huber / Math.max(a, 1e-15);
            }
        }

        FitResult out = new FitResult();
        if (!acceptedAny) {
            // Data-derived fallback only. No fixed {A,k} is ever fabricated.
            out.success = true;
            out.usedFallback = true;
            out.alpha = init.alpha;
            out.beta = init.beta;
            out.message = "Nonlinear fit did not converge; using data-derived logit-linearized parameters.";
        } else {
            out.success = true;
            out.usedFallback = false;
            out.alpha = alpha;
            out.beta = beta;
            out.message = "OK";
        }

        double[] quality = fitQuality(data.x, data.y, out.alpha, out.beta);
        out.rSquared = quality[0];
        out.rmse = quality[1];

        double A = safeExp(out.alpha);
        double k = safeExp(out.beta);
        if (!Double.isFinite(A) || !Double.isFinite(k) || A <= 0 || k <= MIN_K) {
            out.success = false;
            out.message = "Fitted A/k parameters are non-physical.";
        }
        return out;
    }

    /** Initial estimate from logit(y) = log(A) - k*x^2. */
    private static FitResult linearizedInitialization(double[] x, double[] y, double[] w) {
        double sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0;
        int count = 0;
        for (int i = 0; i < x.length; i++) {
            double x2 = x[i] * x[i];
            double z = Math.log(y[i] / (1.0 - y[i]));
            double wi = w[i];
            if (!Double.isFinite(x2) || !Double.isFinite(z) || !Double.isFinite(wi) || wi <= 0) {
                continue;
            }
            sw += wi;
            sx += wi * x2;
            sy += wi * z;
            sxx += wi * x2 * x2;
            sxy += wi * x2 * z;
            count++;
        }

        FitResult result = new FitResult();
        double denom = sw * sxx - sx * sx;
        if (count < MIN_FIT_POINTS || sw <= 0 || Math.abs(denom) < 1e-14) {
            result.success = false;
            result.message = "Unable to initialize extrapolation parameters from the measured curve.";
            return result;
        }

        double slope = (sw * sxy - sx * sy) / denom;
        double intercept = (sy - slope * sx) / sw;
        double k0 = -slope;
        if (!Double.isFinite(intercept) || !Double.isFinite(k0) || k0 <= MIN_K) {
            result.success = false;
            result.message = "The measured curve does not provide a positive decay estimate for extrapolation.";
            return result;
        }

        result.success = true;
        result.alpha = clamp(intercept, -30.0, 30.0);
        result.beta = clamp(Math.log(k0), -25.0, 25.0);
        return result;
    }

    private static double[] optimizeLM(double[] x,
                                       double[] y,
                                       double[] baseWeights,
                                       double[] robustWeights,
                                       double initialAlpha,
                                       double initialBeta) {
        double alpha = initialAlpha;
        double beta = initialBeta;
        double lambda = 1e-3;
        double current = objective(x, y, baseWeights, robustWeights, alpha, beta);
        if (!Double.isFinite(current)) {
            return null;
        }

        boolean accepted = false;
        for (int iter = 0; iter < MAX_LM_ITER; iter++) {
            double h00 = 0, h01 = 0, h11 = 0;
            double g0 = 0, g1 = 0;
            double k = safeExp(beta);
            if (!Double.isFinite(k) || k <= MIN_K) {
                return null;
            }

            for (int i = 0; i < x.length; i++) {
                double pred = modelFromLogParams(x[i], alpha, beta);
                double r = y[i] - pred;
                double common = pred * (1.0 - pred);
                double j0 = common;                  // dF/dalpha
                double j1 = -common * k * x[i] * x[i]; // dF/dbeta
                double wi = baseWeights[i] * robustWeights[i];

                h00 += wi * j0 * j0;
                h01 += wi * j0 * j1;
                h11 += wi * j1 * j1;
                g0 += wi * j0 * r;
                g1 += wi * j1 * r;
            }

            double a00 = h00 + lambda * (h00 + 1e-12);
            double a11 = h11 + lambda * (h11 + 1e-12);
            double det = a00 * a11 - h01 * h01;
            if (!Double.isFinite(det) || Math.abs(det) < 1e-20) {
                lambda *= 10.0;
                if (lambda > 1e12) break;
                continue;
            }

            double dAlpha = (g0 * a11 - g1 * h01) / det;
            double dBeta = (a00 * g1 - h01 * g0) / det;
            if (!Double.isFinite(dAlpha) || !Double.isFinite(dBeta)) {
                return null;
            }

            double candidateAlpha = clamp(alpha + dAlpha, -30.0, 30.0);
            double candidateBeta = clamp(beta + dBeta, -25.0, 25.0);
            double candidate = objective(x, y, baseWeights, robustWeights,
                    candidateAlpha, candidateBeta);

            if (Double.isFinite(candidate) && candidate < current) {
                alpha = candidateAlpha;
                beta = candidateBeta;
                double improvement = current - candidate;
                current = candidate;
                lambda = Math.max(1e-12, lambda / 3.0);
                accepted = true;
                if (improvement < 1e-12 || (Math.abs(dAlpha) + Math.abs(dBeta)) < 1e-9) {
                    break;
                }
            } else {
                lambda *= 10.0;
                if (lambda > 1e12) break;
            }
        }
        return accepted ? new double[] {alpha, beta} : null;
    }

    private static double objective(double[] x,
                                    double[] y,
                                    double[] baseWeights,
                                    double[] robustWeights,
                                    double alpha,
                                    double beta) {
        double sse = 0.0;
        for (int i = 0; i < x.length; i++) {
            double pred = modelFromLogParams(x[i], alpha, beta);
            if (!Double.isFinite(pred)) return Double.NaN;
            double r = y[i] - pred;
            sse += baseWeights[i] * robustWeights[i] * r * r;
        }
        return sse;
    }

    private static double[] residuals(double[] x, double[] y, double alpha, double beta) {
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            r[i] = y[i] - modelFromLogParams(x[i], alpha, beta);
        }
        return r;
    }

    private static double robustScale(double[] residuals) {
        double[] copy = residuals.clone();
        Arrays.sort(copy);
        double med = medianSorted(copy);
        double[] dev = new double[copy.length];
        for (int i = 0; i < residuals.length; i++) {
            dev[i] = Math.abs(residuals[i] - med);
        }
        Arrays.sort(dev);
        double mad = medianSorted(dev);
        return 1.4826 * mad;
    }

    private static double medianSorted(double[] sorted) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        if ((n & 1) == 1) return sorted[n / 2];
        return 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    private static double[] fitQuality(double[] x, double[] y, double alpha, double beta) {
        double mean = 0.0;
        for (double v : y) mean += v;
        mean /= y.length;

        double sse = 0.0;
        double sst = 0.0;
        for (int i = 0; i < y.length; i++) {
            double pred = modelFromLogParams(x[i], alpha, beta);
            double r = y[i] - pred;
            sse += r * r;
            double d = y[i] - mean;
            sst += d * d;
        }
        double r2 = sst > 0 ? 1.0 - sse / sst : Double.NaN;
        double rmse = Math.sqrt(sse / y.length);
        return new double[] {r2, rmse};
    }

    private static double[] extendFrequencyAxis(double[] frequency, int newLength) {
        int n = frequency.length;
        double[] out = new double[newLength];
        System.arraycopy(frequency, 0, out, 0, n);
        if (newLength == n) {
            return out;
        }

        double[] diffs = new double[n - 1];
        int count = 0;
        for (int i = 1; i < n; i++) {
            double d = frequency[i] - frequency[i - 1];
            if (Double.isFinite(d) && d > 0) {
                diffs[count++] = d;
            }
        }
        if (count == 0) return null;
        diffs = Arrays.copyOf(diffs, count);
        Arrays.sort(diffs);
        double df = medianSorted(diffs);
        if (!Double.isFinite(df) || df <= 0) return null;

        for (int i = n; i < newLength; i++) {
            out[i] = out[i - 1] + df;
        }
        return out;
    }

    private static double modelFromLogParams(double x, double alpha, double beta) {
        double k = safeExp(beta);
        double z = alpha - k * x * x;
        // Stable logistic(z).
        if (z >= 0) {
            double e = Math.exp(-Math.min(z, 745.0));
            return 1.0 / (1.0 + e);
        } else {
            double e = Math.exp(Math.max(z, -745.0));
            return e / (1.0 + e);
        }
    }

    private static double safeExp(double x) {
        if (x > 700) return Double.POSITIVE_INFINITY;
        if (x < -745) return 0.0;
        return Math.exp(x);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
