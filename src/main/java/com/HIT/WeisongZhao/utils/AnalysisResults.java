package com.HIT.WeisongZhao.utils;

import ij.measure.ResultsTable;

/** Compact per-analysis output. Each batch element contributes exactly one row. */
public final class AnalysisResults {
    private final ResultsTable table = new ResultsTable();
    private final String title;

    public AnalysisResults(String title) {
        this.title = title;
        table.setPrecision(3);
    }

    private void start() {
        table.incrementCounter();
    }

    private void settings(String frame, boolean singleFrame, String threshold) {
        if (frame != null) table.addValue("frame / pair", frame);
        table.addValue("singleframe", String.valueOf(singleFrame));
        table.addValue("threshold", threshold);
    }

    public void addSFRC(String frame, boolean singleFrame, String threshold,
                        boolean extrapolation, double resolution) {
        start();
        table.addValue("resolution (nm)", resolution);
        settings(frame, singleFrame, threshold);
        table.addValue("extrapolation triggered", String.valueOf(extrapolation));
        table.show(title);
    }

    public void addSFSC(boolean singleFrame, String threshold, boolean zSampling, boolean lateralExtrapolation,
                       boolean axialExtrapolation, double lateral, double axial, double error) {
        start();
        table.addValue("lateral resolution (nm)", lateral);
        table.addValue("axial resolution (nm)", axial);
        table.addValue("E_DRI (%)", 100.0 * error);
        settings(null, singleFrame, threshold);
        table.addValue("zsampling", String.valueOf(zSampling));
        table.addValue("extrapolation triggered", String.valueOf(lateralExtrapolation || axialExtrapolation));
        table.addValue("extrapolation stage", lateralExtrapolation
                ? (axialExtrapolation ? "lateral and axial" : "lateral")
                : (axialExtrapolation ? "axial" : "none"));
        table.show(title);
    }

    public void addGlobalFSC(boolean singleFrame, String threshold, boolean extrapolation, double resolution) {
        start();
        table.addValue("global resolution (nm)", resolution);
        settings(null, singleFrame, threshold);
        table.addValue("zsampling", "false");
        table.addValue("extrapolation triggered", String.valueOf(extrapolation));
        table.show("sFSC - global FSC results");
    }

    public void addSrFRC(String frame, boolean singleFrame, String threshold,
                        double mean, double min, double max, double rFRC, double background) {
        start();
        table.addValue("mean resolution (nm)", mean);
        table.addValue("min resolution (nm)", min);
        table.addValue("max resolution (nm)", max);
        table.addValue("rFRC value", rFRC);
        table.addValue("background threshold (0-255)", background);
        settings(frame, singleFrame, threshold);
        table.show(title);
    }

}
