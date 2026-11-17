package com.HIT.WeisongZhao.utils;

/** Parameter advice only; no automatic classification of optimistic resolution. */
public final class SFSCGuidance {
    private SFSCGuidance() { }
    public static final String ANGLE_GUIDANCE = "Recommended DRI fitting angles: lowAngle > 10 degrees and highAngle < 80 degrees.";
    public static final String FIT_RANGE_ADVICE = "Consider adjusting the DRI fitting angle range, then repeat the analysis. Recommended DRI fitting angles: lowAngle > 10 degrees and highAngle < 80 degrees.";
    public static final String Z_SAMPLING_NOTE = "Z sampling is OFF and can be enabled manually. If the axial resolution appears overly optimistic (too small), enable Z sampling and repeat the analysis to compare the estimates.";
    public static final boolean DEFAULT_Z_SAMPLING = false;
    public static String zSamplingNote(boolean singleFrame, boolean enabled, double axialNm) {
        return singleFrame && !enabled && Double.isFinite(axialNm) ? Z_SAMPLING_NOTE : "";
    }
}
