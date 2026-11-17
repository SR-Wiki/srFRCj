package com.HIT.WeisongZhao.utils;

import ij.IJ;

public class DRIGoodnessOfFit {
    public static final String GUIDANCE = "E_DRI (relative fit error): <10% reliable; 10-20% use with caution; >20% unacceptable.";
    public static String interpretation(double error) {
        if (!Double.isFinite(error)) return "unavailable";
        if (error < 0.10) return "reliable";
        if (error > 0.20) return "unacceptable";
        return "use with caution";
    }


    public static class Result {
        public final double error;
        public final double[] kFit;
        public final boolean valid;

        public Result(double error, double[] kFit, boolean valid) {
            this.error = error;
            this.kFit = kFit;
            this.valid = valid;
        }
    }
    
    public static Result validate(
            double[] kMeasured,
            double[] thetaRad,
            double kXY,
            double kZ,
            double threshold) {
        Result result = calculate(
                kMeasured,
                thetaRad,
                kXY,
                kZ,
                threshold
        );
        StructuredLog.info(
                "sFSC/DRI",
                "Goodness of fit",
                "E_DRI = " + String.format("%.2f", 100.0 * result.error) + " %; " + interpretation(result.error) + "; " + GUIDANCE);
        if (!Double.isFinite(result.error) || result.error >= 0.10) {
            RobustnessUtils.warnPoorDRIFit(result.error, threshold);
        }
        return result;
    }

    /**
     * Calculate DRI goodness-of-fit.
     *
     * E_DRI =
     * sqrt(
     *   sum_i w_i * ((kMeasured_i - kFit_i) / kFit_i)^2
     *   -------------------------------------------------
     *                     sum_i w_i
     * )
     *
     * where
     *   w_i = cos(theta_i)
     *
     * and
     *
     * kFit(theta) =
     * 1 / sqrt(
     *     cos(theta)^2 / kXY^2
     *   + sin(theta)^2 / kZ^2
     * )
     *
     * theta is measured in degrees relative to the xy plane.
     */
    public static Result calculate(
            double[] kMeasured,
            double[] thetaRad,
            double kXY,
            double kZ,
            double threshold) {

        if (kMeasured.length != thetaRad.length) {
            throw new IllegalArgumentException(
                    "kMeasured and thetaRad must have the same length."
            );
        }

        if (kXY <= 0 || kZ <= 0) {
            throw new IllegalArgumentException(
                    "kXY and kZ must be positive."
            );
        }

        int n = kMeasured.length;

        double[] kFit = new double[n];

        double weightedErrorSum = 0.0;
        double weightSum = 0.0;

        for (int i = 0; i < n; i++) {

            double theta = thetaRad[i];

            double cosTheta = Math.cos(theta);
            double sinTheta = Math.sin(theta);

            // DRI elliptical model
            kFit[i] = 1.0 / Math.sqrt(
                    (cosTheta * cosTheta) / (kXY * kXY)
                    +
                    (sinTheta * sinTheta) / (kZ * kZ)
            );

            // omega(theta) = cos(theta)
            double weight = cosTheta;

            // Relative residual
            double relativeResidual =
                    (kMeasured[i] - kFit[i]) / kFit[i];

            weightedErrorSum +=
                    weight * relativeResidual * relativeResidual;

            weightSum += weight;
        }

        if (weightSum <= 0) {
            throw new IllegalArgumentException(
                    "Sum of angular weights must be positive."
            );
        }

        double E_DRI =
                Math.sqrt(weightedErrorSum / weightSum);

        boolean valid = Double.isFinite(E_DRI) && E_DRI < threshold;

        return new Result(E_DRI, kFit, valid);
    }


    public static void main(String[] args) {

        double[] thetaRad = {
                19.5, 28.5, 37.5, 46.5, 55.5
        };

        double kXY = 1.0 / 120.0;
        double kZ = 1.0 / 360.0;

        double[] kMeasured = {
                0.0080,
                0.0069,
                0.0056,
                0.0045,
                0.0037
        };

        double threshold = 0.10;

        Result result = calculate(
                kMeasured,
                thetaRad,
                kXY,
                kZ,
                threshold
        );

        System.out.printf(
                "E_DRI = %.4f%n",
                result.error
        );

        System.out.printf(
                "Relative fitting error = %.2f%%%n",
                result.error * 100.0
        );

        System.out.println(
                "Valid DRI estimate: " + result.valid
        );

        System.out.println("k_fit:");

        for (double k : result.kFit) {
            System.out.printf("%.6f%n", k);
        }
    }
}
