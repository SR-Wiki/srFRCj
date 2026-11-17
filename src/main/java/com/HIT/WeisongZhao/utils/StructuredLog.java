package com.HIT.WeisongZhao.utils;
import ij.IJ;
/** Diagnostics only. Scientific results are written by AnalysisResults. */
public final class StructuredLog {
    private StructuredLog() { }
    public static void reset() { }
    public static void info(String module, String event, String message) { }
    public static void attention(String module, String event, String message) { }
    public static void warning(String module, String event, String message) {
        add("WARNING", module, event, message);
    }
    /** Print one paragraph on several short ImageJ Log lines (no GUI horizontal scroll). */
    public static void logWrapped(String prefix, String message) {
        if (message == null || message.trim().isEmpty()) return;
        final int maxWidth = 83;
        String line = prefix == null ? "" : prefix;
        for (String token : message.replace('\r', ' ').replace('\n', ' ').trim().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + token.length() > maxWidth) {
                IJ.log(line);
                line = "    " + token;
            } else {
                line = line.isEmpty() ? token : line + " " + token;
            }
        }
        if (!line.isEmpty()) IJ.log(line);
    }
    public static synchronized void add(String level, String module, String event, String message) {
        if ("WARNING".equals(level))
            logWrapped(module + " - " + event + ":", message);
    }
}
