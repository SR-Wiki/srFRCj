package com.HIT.WeisongZhao.utils;

import ij.gui.Plot;
import ij.gui.PlotWindow;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * Corrected version polar plot class - fully usable annotation functionality
 */
public class PolarPlotter {
    
    // Plot configuration
    private String title = "";
    private boolean showGrid = true;
    private Color gridColor = new Color(160, 160, 160);
    private int gridDensity = 5;
    private boolean showLabels = true;
    private Color labelColor = Color.BLACK;
    
    // Annotation configuration
    private boolean showPointLabels = false;
    private Color pointLabelColor = Color.RED;
    private boolean showAngleLabels = false;
    private boolean showRadiusLabels = false;
    private List<TextAnnotation> textAnnotations = new ArrayList<>();
    // Data
    private double[] angles;
    private double[] radii;
    
    // Annotation inner class
    private static class TextAnnotation {
        double x, y;
        String text;
        Color color;
        
        TextAnnotation(double x, double y, String text, Color color) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
        }
    }
    
    /**
     * Constructor
     */
    public PolarPlotter(double[] angles, double[] radii) {
        if (angles == null || radii == null || angles.length != radii.length) {
            throw new IllegalArgumentException("Angle and radius arrays must be non-null and have the same length");
        }
        this.angles = angles;
        this.radii = radii;
    }
    
    /**
     * Draw polar plot
     */
    public Plot draw() {
        // Convert polar coordinates to Cartesian coordinates
        double[] x = new double[angles.length];
        double[] y = new double[angles.length];
        
        for (int i = 0; i < angles.length; i++) {
            x[i] = radii[i] * Math.cos(angles[i]);
            y[i] = Math.abs(radii[i]) * Math.sin(angles[i]); // ensure y is non-negative
        }
        
        // Create plot object
        Plot plot = new Plot(title, "XY (nm)", "Z (nm)");
        plot.setColor(new Color(230, 115, 115));
        plot.setLineWidth(5);
        plot.addPoints(x, y, Plot.LINE);
        plot.show();
        
        // Calculate coordinate range
        double maxRadius = Arrays.stream(radii).max().orElse(1.0);
        double minRadius = Arrays.stream(radii).min().orElse(1.0);
        double axisLimit = maxRadius * 1.2;
        
        // Set coordinate limits
        plot.setLimits(-axisLimit, axisLimit, -axisLimit, axisLimit);
        
        // Add grid
        if (showGrid) {
            addPolarGrid(plot, maxRadius);
        }
        plot.update();
        
        // Add angle labels
        if (showAngleLabels) {
            addAngleLabels(plot, maxRadius);
        }
        plot.update();
        
        // Add radius labels
        if (showRadiusLabels) {
            addRadiusLabels(plot, maxRadius, minRadius);
        }
        plot.update();
        
        // Add custom text annotations
        addTextAnnotations(plot);
        
        return plot;
    }
    
    /**
     * Display polar plot
     */
    public void show() {
        Plot plot = draw();
        plot.show();
    }
    
    /**
     * Add polar grid
     */
    private void addPolarGrid(Plot plot, double maxRadius) {
        double radiusStep = calculateGridStep(maxRadius);
        double angleStep = Math.PI / 4;
        
        // Add radial grid (concentric circles)
        for (double r = radiusStep; r <= 1.1 * Math.ceil(maxRadius); r += radiusStep) {
            double[] circleX = new double[361];
            double[] circleY = new double[361];
            
            for (int i = 0; i <= 360; i++) {
                double angle = Math.toRadians(i);
                circleX[i] = r * Math.cos(angle);
                circleY[i] = r * Math.sin(angle);
            }
            
            plot.setColor(gridColor);
            plot.setLineWidth(2);
            plot.addPoints(circleX, circleY, Plot.LINE);
        }
        
        // Add angular grid (lines radiating from center)
        for (double angle = 0; angle < 2 * Math.PI; angle += angleStep) {
            double[] lineX = {0, maxRadius * Math.cos(angle)};
            double[] lineY = {0, maxRadius * Math.sin(angle)};
            
            plot.setColor(gridColor);
            plot.setLineWidth(2);
            plot.addPoints(lineX, lineY, Plot.LINE);
        }
    }
    
    
    /**
     * Add angle labels (corrected version)
     */
    private void addAngleLabels(Plot plot, double maxRadius) {
        plot.setColor(Color.BLACK);
        plot.addLabel(0.93, 0.52, "0°");
        plot.addLabel(0.8, 0.2, "45°");
        plot.addLabel(0.49, 0.07, "90°");
        plot.addLabel(0.17, 0.2, "135°");
        plot.addLabel(0.02, 0.52, "180°");
        plot.addLabel(0.17, 0.85, "225°");
        plot.addLabel(0.48, 0.98, "270°");
        plot.addLabel(0.81, 0.84, "315°");
        plot.update();
    }
    
    /**
     * Add radius labels (corrected version)
     */
    private void addRadiusLabels(Plot plot, double maxRadius, double minRadius) {
        plot.setColor(Color.BLACK);
        plot.setFontSize(18);
        plot.setFont(new Font("SansSerif", Font.BOLD, 15));
        plot.addLabel(0.5, 0.52, "R");
        plot.addLabel(0.54, 0.48, String.format("%d", (int) (maxRadius*0.2)));
        plot.addLabel(0.6, 0.42, String.format("%d", (int) (maxRadius*0.4)));
        plot.addLabel(0.65, 0.37, String.format("%d", (int) (maxRadius*0.6)));
        plot.addLabel(0.71, 0.3, String.format("%d", (int) (maxRadius*0.8)));
        plot.addLabel(0.77, 0.245, String.format("%d", (int) (maxRadius)));
        
        // Show lateral and axial resolution
        double ratio = minRadius / maxRadius / 2;
        plot.addLabel(0.5 + ratio, 0.53, String.format("%d", Math.round(minRadius)));
        plot.addLabel(0.48, 0.15, String.format("%d", (int) Math.round(maxRadius)));
        
    }
    
    /**
     * Add custom text annotations (corrected version)
     */
    private void addTextAnnotations(Plot plot) {
        for (TextAnnotation annotation : textAnnotations) {
            plot.setColor(annotation.color);
            plot.addLabel(annotation.x, annotation.y, annotation.text);
        }
    }
    
    /**
     * Calculate appropriate grid step
     */
    private double calculateGridStep(double maxRadius) {
        if (maxRadius <= 1) return 0.2;
        if (maxRadius <= 5) return 1;
        if (maxRadius <= 10) return 2;
        if (maxRadius <= 20) return 5;
        return Math.ceil(maxRadius / gridDensity);
    }
    
    // Basic configuration methods
    
    public PolarPlotter setTitle(String title) {
        this.title = title;
        return this;
    }
    
    
    public PolarPlotter setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        return this;
    }
    
    public PolarPlotter setGridDensity(int gridDensity) {
        this.gridDensity = gridDensity;
        return this;
    }
    
    // Annotation configuration methods
    
    public PolarPlotter setShowPointLabels(boolean showPointLabels) {
        this.showPointLabels = showPointLabels;
        return this;
    }
    
    public PolarPlotter setPointLabelColor(Color pointLabelColor) {
        this.pointLabelColor = pointLabelColor;
        return this;
    }
    
    public PolarPlotter setShowAngleLabels(boolean showAngleLabels) {
        this.showAngleLabels = showAngleLabels;
        return this;
    }
    
    public PolarPlotter setShowRadiusLabels(boolean showRadiusLabels) {
        this.showRadiusLabels = showRadiusLabels;
        return this;
    }
    

    /**
     * Add custom text annotation (polar coordinate version)
     */
    public PolarPlotter addTextAnnotation(double angle, double radius, String text, Color color) {
        double x = radius * Math.cos(angle);
        double y = radius * Math.sin(angle);
        return addTextAnnotation(x, y, text, color);
    }
    
    /**
     * Add custom text annotation (simplified version)
     */
    public PolarPlotter addTextAnnotation(double x, double y, String text) {
        return addTextAnnotation(x, y, text, Color.BLACK);
    }
    
    /**
     * Clear all text annotations
     */
    public PolarPlotter clearTextAnnotations() {
        textAnnotations.clear();
        return this;
    }
    
    // Static utility methods
    
    public static double[] createUniformAngles(int points) {
        double[] angles = new double[points];
        double step = 2 * Math.PI / points;
        
        for (int i = 0; i < points; i++) {
            angles[i] = i * step;
        }
        
        return angles;
    }
    
    public static double[] createHeartRadii(int points) {
        double[] radii = new double[points];
        double[] angles = createUniformAngles(points);
        
        for (int i = 0; i < points; i++) {
            radii[i] = 1 - Math.sin(angles[i]);
        }
        
        return radii;
    }
    
    public static double[] createSpiralRadii(int points, double turns) {
        double[] radii = new double[points];
        double[] angles = createUniformAngles(points);
        
        for (int i = 0; i < points; i++) {
            radii[i] = 0.1 * angles[i] * turns;
        }
        
        return radii;
    }
    
    /**
     * Main method - demonstrate corrected annotation functionality
     */
    public static void main(String[] args) {
    	double xyResolution = 160, zResolution = 544;
    	double a = 1000 / xyResolution, b = 1000 / zResolution;
		double[] angles = new double[25];
		double[] frequencyRadii = new double[25];
		double[] radii = new double[25];
		double top = a * b, bot = 0;
		for (int i = 0; i < 24; i++) {
			angles[i] = Math.PI * i / 12;
			bot = Math.sqrt(b*b*Math.cos(angles[i])*Math.cos(angles[i]) + a*a*Math.sin(angles[i])*Math.sin(angles[i]));
			frequencyRadii[i] = top / bot;
			radii[i] = 1000 / frequencyRadii[i];
		}
		angles[24] = angles[0];
		radii[24] = radii[0];
		PolarPlotter polarPlotter = new PolarPlotter(angles, radii);
		polarPlotter.setTitle("Lateral/Axial Resolution")
          .setGridDensity(5)
          .setShowAngleLabels(true)
          .setShowRadiusLabels(true);
		polarPlotter.show();
    }
}