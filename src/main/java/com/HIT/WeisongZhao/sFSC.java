package com.HIT.WeisongZhao;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.swing.JDialog;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import com.HIT.WeisongZhao.utils.*;
import ij.plugin.PlugIn;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import com.HIT.WeisongZhao.utils.PluginDialogStyle;
import ij.plugin.ChannelSplitter;
import com.HIT.WeisongZhao.utils.DirectionalResolutionInference.Params;


public class sFSC extends JDialog implements PlugIn{
	
	private static float xyPixelSize = 30;
	private static float zPixelSize = 100;
	private static float lowAngle = 15;
	private static float highAngle = 60;
	private static int numCones = 5;
//	private static int meanFilterWidth = 5;
	
	@Override
	public void run(String args) {
		if (IJ.versionLessThan("1.46j"))
			return;

		int[] wList = WindowManager.getIDList();
		if (wList == null) {
			IJ.noImage();
			return;
		}

		ImagePlus imp = WindowManager.getCurrentImage();
		if (imp == null) {
			IJ.noImage();
			return;
		}

		StructuredLog.reset();

		float dialogXyPixelSize = ImagePropertiesUtils.getXYPixelSizeNm(imp, sFSC.xyPixelSize);
		float dialogZPixelSize = ImagePropertiesUtils.getZPixelSizeNm(imp, sFSC.zPixelSize);

		String[] trueFalse = {"true", "false"};
		String[] modes = {"slice", "3D"};
		String[] thresholds = {"1/7 hard threshold", "3-sigma"};

        // Snapshot open image IDs instead of relying on titles (which may repeat).
        // Both selectors are ignored when If SingleFrame = true.
        String[] volumeNames = new String[wList.length];
        int firstVolumeIndex = 0;
        int secondVolumeIndex = 0;
        for (int i = 0; i < wList.length; i++) {
            ImagePlus candidate = WindowManager.getImage(wList[i]);
            volumeNames[i] = candidate == null ? "[Closed image " + wList[i] + "]"
                    : candidate.getTitle() + " [ID " + wList[i] + "]";
            if (candidate == imp) firstVolumeIndex = i;
        }
        for (int i = 0; i < wList.length; i++) {
            if (i != firstVolumeIndex) { secondVolumeIndex = i; break; }
        }

		GenericDialog gd = new GenericDialog("sFSC");
		gd.addChoice("If SingleFrame", trueFalse, "true");
        gd.addChoice("3D Stack 1 (paired mode)", volumeNames,
                volumeNames[firstVolumeIndex]);
        gd.addChoice("3D Stack 2 (paired mode)", volumeNames,
                volumeNames[secondVolumeIndex]);
		gd.addNumericField("Pixel Size (XY)", dialogXyPixelSize, 2, 5, "(nm)");
		gd.addNumericField("Pixel Size (Z)", dialogZPixelSize, 2, 5, "(nm)");
		gd.addChoice("Threshold", thresholds, "1/7 hard threshold");
		gd.addChoice("Advanced Settings", trueFalse, "false");
        gd.addHelp("https://github.com/SR-Wiki/srFRCJ");
        // GenericDialog#getChoices() may return a raw Vector in ImageJ 1.x.
        // Explicitly use AWT Choice so this also compiles against older APIs.
        final java.awt.Choice singleFrameChoice =
                (java.awt.Choice) gd.getChoices().get(0);
        final java.awt.Choice firstVolumeChoice =
                (java.awt.Choice) gd.getChoices().get(1);
        final java.awt.Choice secondVolumeChoice =
                (java.awt.Choice) gd.getChoices().get(2);
        // Paired input fields are ignored until the user selects double-frame mode.
        firstVolumeChoice.setEnabled(false);
        secondVolumeChoice.setEnabled(false);
        gd.addDialogListener((dialog, event) -> {
            boolean paired = "false".equals(singleFrameChoice.getSelectedItem());
            firstVolumeChoice.setEnabled(paired);
            secondVolumeChoice.setEnabled(paired);
            return true;
        });
        PluginDialogStyle.apply(gd, false);
        gd.showDialog();

		if (gd.wasCanceled())
			return;

		Boolean singleFrame = Boolean.valueOf(trueFalse[gd.getNextChoiceIndex()]);
        final int volumeAId = wList[gd.getNextChoiceIndex()];
        final int volumeBId = wList[gd.getNextChoiceIndex()];
		float rawXyPixelSize = (float) gd.getNextNumber();
		float rawZPixelSize = (float) gd.getNextNumber();
		String thresholdLabel = thresholds[gd.getNextChoiceIndex()];
		String threshold = "1/7 hard threshold".equals(thresholdLabel) ? "1/7" : "3-sigma";
		Boolean advancedSettings = Boolean.valueOf(trueFalse[gd.getNextChoiceIndex()]);

		sFSC.xyPixelSize = rawXyPixelSize;
		sFSC.zPixelSize = rawZPixelSize;

		Boolean zSampling = SFSCGuidance.DEFAULT_Z_SAMPLING;
		String xyMode = "slice";
		float lowAngle = sFSC.lowAngle;
		float highAngle = sFSC.highAngle;
		int numCones = sFSC.numCones;
		Boolean plotFittingCurve = false;
		final Boolean useValid = false; // UI removed; existing code path remains silently disabled.
		Boolean motionCorrection = false;


		if (advancedSettings) {
			GenericDialog gd2 = new GenericDialog("sFSC - advanced settings");
			gd2.addMessage("Advanced Parameter Settings:");
			gd2.addChoice("Z Sampling", trueFalse, zSampling ? "true" : "false");
            gd2.addMessage("If axial resolution appears overly optimistic (too small),\n"
                    + "consider enabling Z Sampling and rerun the analysis.");
			gd2.addNumericField("Low Angle", lowAngle, 2, 5, "degrees; recommended > 10");
			gd2.addNumericField("High Angle", highAngle, 2, 5, "degrees; recommended < 80");
			gd2.addNumericField("Number of Cones", numCones, 0, 2, "positive integer; recommended >= 5");
			gd2.addChoice("Plot Fitting Curve", trueFalse, "false");
			gd2.addChoice("xyMode", modes, "slice");
            gd2.addMessage("For low-SNR data, xyMode can optionally be set to 3D.");
			gd2.addChoice("Motion Correction (optional for double-frame)", trueFalse, "false");
			PluginDialogStyle.apply(gd2, true);
            gd2.showDialog();

			if (gd2.wasCanceled())
				return;

			zSampling = Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
			lowAngle = (float) gd2.getNextNumber();
			highAngle = (float) gd2.getNextNumber();
            double coneInput = gd2.getNextNumber();
            if (!RobustnessUtils.isPositiveFinite(coneInput) || coneInput != Math.rint(coneInput)
                    || coneInput > Integer.MAX_VALUE) {
                IJ.showMessage("Invalid sFSC Settings", "Number of Cones must be a positive integer.");
                return;
            }
            numCones = (int) coneInput;
			plotFittingCurve = Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
			xyMode = modes[gd2.getNextChoiceIndex()];
			motionCorrection = Boolean.valueOf(trueFalse[gd2.getNextChoiceIndex()]);
		}

		if (!RobustnessUtils.validateSFSCSettings(
				rawXyPixelSize, rawZPixelSize, lowAngle, highAngle, numCones))
			return;

        if (lowAngle <= 10 || highAngle >= 80)
            StructuredLog.warning("sFSC", "Fitting angle range", SFSCGuidance.ANGLE_GUIDANCE);
		sFSC.lowAngle = lowAngle;
		sFSC.highAngle = highAngle;
		sFSC.numCones = numCones;
        // Resolve paired stacks only in paired mode; single-frame mode ignores
        // both selectors, even if a selected window has since been closed.
        ImagePlus selectedA = null, selectedB = null;
        if (!singleFrame) {
            selectedA = WindowManager.getImage(volumeAId);
            selectedB = WindowManager.getImage(volumeBId);
            if (selectedA == null || selectedB == null) {
                IJ.showMessage("Invalid paired-volume input",
                        "Both selected 3D stacks must remain open in Fiji.");
                return;
            }
            if (!validatePairedVolumes(selectedA, selectedB)) return;
        }
        RobustnessUtils.warnSuspiciousSampling(
                singleFrame ? imp : selectedA, rawXyPixelSize, rawZPixelSize, true);

		// Correlation code uses micrometres internally. Keep these as local
		// analysis values so single-frame resampling never changes remembered values.
		float xyPixelSize = rawXyPixelSize / 1000.0f;
		float zPixelSize = rawZPixelSize / 1000.0f;

        AnalysisResults results = new AnalysisResults("sFSC - results");
        double xyResolution = Double.NaN;
        double zResolution = Double.NaN;
        double driError = Double.NaN;
        boolean lateralExtrapolation = false;
        boolean axialExtrapolation = false;
        boolean zSamplingApplied = false;
        double globalResolution = Double.NaN;
        boolean globalExtrapolation = false;
        try {
			ImagePlus im1;
			ImagePlus im2;

			if (singleFrame) {
				StructuredLog.info("sFSC", "Preprocessing", "Generate resampled images.");
				ImageStack tempStack = InputImageUtils.normalizedStack(imp.getStack());
				ImagePlus[] tmp = PreGenerator.getResampledImage(tempStack);
				im1 = tmp[0];
				im2 = tmp[1];
				xyPixelSize *= 2;
			} else {
                if (selectedA == selectedB) {
                    // Backwards compatibility for the legacy two-channel input.
                    ImagePlus[] channels = ChannelSplitter.split(selectedA);
                    im1 = InputImageUtils.normalizedVolume(channels[0]);
                    im2 = InputImageUtils.normalizedVolume(channels[1]);
                } else {
                    // Native paired mode: separate ImageJ windows / TIFF stacks.
                    im1 = InputImageUtils.normalizedVolume(selectedA);
                    im2 = InputImageUtils.normalizedVolume(selectedB);
                }
				if (motionCorrection) {
					StructuredLog.info("sFSC", "Motion correction", "Correcting motion.");
					im2 = DriftCorrect.driftCorrect3D(im1, im2, false);
				}
			}

			long stime = System.currentTimeMillis();
			int depth = im1.getStackSize();

			StructuredLog.info("sFSC", "Stage", "Compute XY resolution.");
			IJ.showStatus("Compute XY Resolution...");


			if (highAngle < (90 - 1e-3)) {
				if (xyMode.equals("slice")) {
					double minResolution = Double.POSITIVE_INFINITY;
					boolean selectedXYExtrapolated = false;
					boolean xyExtrapolationRequired = false;
					String selectedXYFitSummary = null;
					int numSlices = Math.min(20, depth);
					int selectPlane = Math.max(1, depth / 2);
					int[] planes = selectPlanes(im1, numSlices);

					for (int i = 0; i < numSlices; i++) {
						IJ.showStatus("Compute XY Resolution...");
						IJ.showProgress(i, numSlices);

						int index = planes[i];
						ImagePlus tmpIm1 = new ImagePlus(
								"", im1.getStack().getProcessor(index));
						ImagePlus tmpIm2 = new ImagePlus(
								"", im2.getStack().getProcessor(index));

						INDArray[] fftA = FFTProcessor.computeFFTToArray(tmpIm1);
						INDArray[] fftB = FFTProcessor.computeFFTToArray(tmpIm2);
						Correlations correlations = new Correlations(
								fftA, fftB, xyPixelSize);

						// Candidate scans can include many planes. Suppress per-candidate
						// extrapolation-fit rows and report only the selected plane.
						correlations.setExtrapolationFitLoggingEnabled(false);
						correlations.execute(2, threshold, singleFrame, false);
                        lateralExtrapolation |= correlations.wasExtrapolationAttempted();

						xyExtrapolationRequired |= correlations.isExtrapolationRequired();
						double resolution = correlations.getResolution();

						if (Double.isFinite(resolution) && resolution < minResolution) {
							minResolution = resolution;
							selectPlane = index;
							selectedXYExtrapolated = correlations.wasExtrapolationUsed();
							selectedXYFitSummary = correlations.getExtrapolationFitSummary();
						}
					}

					if (!Double.isFinite(minResolution)) {
						if (xyExtrapolationRequired
								&& !Correlations.isExtrapolationSupportedThreshold(threshold)) {
							RobustnessUtils.showExtrapolationUnsupportedThreshold(
									"lateral FRC", threshold);
						} else {
							RobustnessUtils.showExtrapolationFailed("lateral FRC");
						}
						return;
					}

					xyResolution = minResolution;
					if (selectedXYExtrapolated) {
						RobustnessUtils.showExtrapolationApplied("lateral FRC");
						if (selectedXYFitSummary != null) {
							StructuredLog.info(
									"sFSC",
									"Selected lateral extrapolation fit",
									selectedXYFitSummary);
						}
					}

					StructuredLog.info(
							"sFSC", "XY Resolution", String.format("%.1f nm", xyResolution));
					StructuredLog.info(
							"sFSC", "Selected XY plane", String.valueOf(selectPlane));

				} else {
					int zc = (int) Math.ceil((depth + 1) / 2.0);
					INDArray[] tempFftA =
							FFTProcessor.computeFFTToArray(im1, true, true, false);
					INDArray[] tempFftB =
							FFTProcessor.computeFFTToArray(im2, true, true, false);

					INDArray[] fftA = new INDArray[2];
					fftA[0] = tempFftA[0].slice(zc - 1);
					fftA[1] = tempFftA[1].slice(zc - 1);
					INDArray[] fftB = new INDArray[2];
					fftB[0] = tempFftB[0].slice(zc - 1);
					fftB[1] = tempFftB[1].slice(zc - 1);

					Correlations correlations = new Correlations(fftA, fftB, xyPixelSize);
					correlations.execute(2, threshold, singleFrame, false);
                    lateralExtrapolation |= correlations.wasExtrapolationAttempted();
					xyResolution = correlations.getResolution();

					if (!Double.isFinite(xyResolution)) {
						if (correlations.isExtrapolationRequired()
								&& !Correlations.isExtrapolationSupportedThreshold(threshold)) {
							RobustnessUtils.showExtrapolationUnsupportedThreshold(
									"lateral FRC", threshold);
						} else {
							RobustnessUtils.showExtrapolationFailed("lateral FRC");
						}
						return;
					}

					if (correlations.wasExtrapolationUsed())
						RobustnessUtils.showExtrapolationApplied("lateral FRC");

					StructuredLog.info(
							"sFSC", "XY Resolution", String.format("%.1f nm", xyResolution));
				}

				long etime = System.currentTimeMillis();
				float time = ((float) (etime - stime)) / 1000;
				StructuredLog.info("sFSC", "XY stage time", String.format("%.2f s", time));

				stime = System.currentTimeMillis();
				StructuredLog.info("sFSC", "Stage", "Compute Z resolution.");
				IJ.showStatus("Compute Z Resolution...");

				if (zSampling) {
					ImagePlus[] temp = oddEvenSplit(im1, im2);
					im1 = temp[0];
					im2 = temp[1];
					zPixelSize *= 2;
                    zSamplingApplied = true;
				}

				double downsamplingFactor = zPixelSize / xyPixelSize;
				INDArray[] fftA = FFTProcessor.computeFFTToArray(im1, downsamplingFactor);
				im1 = null;
				INDArray[] fftB = FFTProcessor.computeFFTToArray(im2, downsamplingFactor);
				im2 = null;

				xyPixelSize = Math.max(xyPixelSize, zPixelSize);
				zPixelSize = Math.max(xyPixelSize, zPixelSize);

				Params params = new Params(
						xyPixelSize,
						lowAngle,
						highAngle,
						numCones,
						zSampling,
						singleFrame,
						plotFittingCurve);

				DirectionalResolutionInference dri =
						new DirectionalResolutionInference(fftA, fftB, params, xyResolution);

				// Warning is emitted only when zero directional crossings are usable.
				// One or more usable values follow the normal fitting path.
                boolean driCompleted = dri.execute(threshold);
                axialExtrapolation = dri.wasExtrapolationAttempted();
                driError = dri.getFitError();
                if (!driCompleted) return;

                zResolution = dri.getZResolution();
                String axialNote = SFSCGuidance.zSamplingNote(singleFrame, zSampling, zResolution);
                if (!axialNote.isEmpty()) StructuredLog.logWrapped("sFSC - axial:", axialNote);
				StructuredLog.info(
						"sFSC", "Z Resolution", String.format("%.1f nm", zResolution));

				double a = 1000 / xyResolution;
				double b = 1000 / zResolution;
				double[] validAngles = dri.getValidSample()[0];
				double[] validResolutions = dri.getValidSample()[1];
				int numValids = validAngles.length;

				double[] theoryAngles = new double[24];
				double[] frequencyRadii = new double[24];
				double[] theoryRadii = new double[24];
				double top = a * b;

				for (int i = 0; i < 24; i++) {
					double curAngle = Math.PI * i / 12;
					theoryAngles[i] = curAngle;
					double bot = Math.sqrt(
							b * b * Math.cos(curAngle) * Math.cos(curAngle)
							+ a * a * Math.sin(curAngle) * Math.sin(curAngle));
					frequencyRadii[i] = top / bot;
					theoryRadii[i] = 1000 / frequencyRadii[i];
				}

				List<double[]> pointList = new ArrayList<>();
				for (int i = 0; i < 24; i++)
					pointList.add(new double[] {theoryAngles[i], theoryRadii[i]});

				// Retained intentionally, but useValid is permanently false and no
				// longer exposed in the UI.
				if (useValid) {
					for (int i = 0; i < numValids; i++)
						pointList.add(new double[] {validAngles[i], validResolutions[i]});
				}

				pointList.sort(Comparator.comparingDouble(point -> point[0]));
				int totalPoints = pointList.size();
				double[] angles = new double[totalPoints + 1];
				double[] radii = new double[totalPoints + 1];

				for (int i = 0; i < totalPoints; i++) {
					double[] point = pointList.get(i);
					angles[i] = point[0];
					radii[i] = point[1];
				}
				angles[totalPoints] = pointList.get(0)[0];
				radii[totalPoints] = pointList.get(0)[1];

				PolarPlotter polarPlotter = new PolarPlotter(angles, radii);
				polarPlotter.setTitle("sFSC - lateral/axial resolution; E_DRI="
                        + String.format("%.2f%%", 100.0 * driError))
						.setGridDensity(5)
						.setShowAngleLabels(true)
						.setShowRadiusLabels(true);
				polarPlotter.show();

				etime = System.currentTimeMillis();
				time = ((float) (etime - stime)) / 1000;
				StructuredLog.info("sFSC", "Z stage time", String.format("%.2f s", time));

			} else {
				StructuredLog.info("sFSC", "Stage", "Perform global FSC.");

				double downsamplingFactor = zPixelSize / xyPixelSize;
				INDArray[] fftA = FFTProcessor.computeFFTToArray(im1, downsamplingFactor);
				im1 = null;
				INDArray[] fftB = FFTProcessor.computeFFTToArray(im2, downsamplingFactor);
				im2 = null;

				xyPixelSize = Math.max(xyPixelSize, zPixelSize);
				zPixelSize = Math.max(xyPixelSize, zPixelSize);

				Correlations correlations = new Correlations(fftA, fftB, xyPixelSize);
				correlations.execute(3, threshold, singleFrame, false);
                // Global FSC is a scalar shell estimate, not a directional XY/Z pair.
                globalExtrapolation = correlations.wasExtrapolationAttempted();
                globalResolution = correlations.getResolution();

				if (!Double.isFinite(correlations.getResolution())) {
					if (correlations.isExtrapolationRequired()
							&& !Correlations.isExtrapolationSupportedThreshold(threshold)) {
						RobustnessUtils.showExtrapolationUnsupportedThreshold(
								"global FSC", threshold);
					} else {
						RobustnessUtils.showExtrapolationFailed("global FSC");
					}
					return;
				}

				if (correlations.wasExtrapolationUsed())
					RobustnessUtils.showExtrapolationApplied("global FSC");

				StructuredLog.info(
						"sFSC",
						"Global FSC Resolution",
						String.format("%.1f nm", correlations.getResolution()));
				correlations.plotFRC("sFSC - global correlation curve");
			}
        } finally {
            if (Double.isFinite(driError))
                IJ.log("sFSC - E_DRI = " + String.format("%.2f%%", 100.0 * driError)
                        + " (" + DRIGoodnessOfFit.interpretation(driError) + ")");
            StructuredLog.logWrapped("sFSC - E_DRI guidance:", DRIGoodnessOfFit.GUIDANCE);
            if (driError >= 0.10)
                StructuredLog.logWrapped("sFSC - DRI:", "Try adjusting the DRI fitting angle range and rerun the analysis.");
            if (highAngle >= 90 - 1e-3) {
                results.addGlobalFSC(singleFrame, thresholdLabel, globalExtrapolation, globalResolution);
            } else {
                results.addSFSC(singleFrame, thresholdLabel, zSamplingApplied, lateralExtrapolation,
                        axialExtrapolation, xyResolution, zResolution, driError);
            }
        }
	}
	
    /** Validate two independent stacks or the legacy two-channel hyperstack. */
    private static boolean validatePairedVolumes(ImagePlus a, ImagePlus b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            IJ.showMessage("Invalid paired-volume input",
                    "Volume A and Volume B must have identical XY dimensions.");
            return false;
        }
        if (a == b) {
            if (a.getNChannels() < 2 || a.getNFrames() != 1 || a.getNSlices() < 2) {
                IJ.showMessage("Invalid paired-volume input",
                        "Select two distinct single-channel 3D stacks, or select the same "
                        + "two-channel 3D hyperstack in both fields.");
                return false;
            }
            return true;
        }
        if (a.getNChannels() != 1 || b.getNChannels() != 1
                || a.getNFrames() != 1 || b.getNFrames() != 1
                || a.getNSlices() < 2 || b.getNSlices() < 2) {
            IJ.showMessage("Invalid paired-volume input",
                    "Each selected image must be a single-channel 3D stack "
                    + "with at least two Z slices and one time point.");
            return false;
        }
        if (a.getNSlices() != b.getNSlices() || a.getStackSize() != b.getStackSize()) {
            IJ.showMessage("Invalid paired-volume input",
                    "Volume A and Volume B must have the same Z slice count.");
            return false;
        }
        return true;
    }

	public static ImagePlus[] oddEvenSplit(ImagePlus im1, ImagePlus im2) {
		ImagePlus[] result = new ImagePlus[2];
		int height = im1.getHeight();
		int width = im1.getWidth();
		ImageStack ims1 = new ImageStack(width, height);
		ImageStack ims2 = new ImageStack(width, height);
		int depth = im1.getStackSize();
		if (depth % 2 > 0)
			depth--;
		for (int i = 0; i < depth / 2; i+=2) {
			ims1.addSlice(im1.getStack().getProcessor(i+1));
			ims2.addSlice(im2.getStack().getProcessor(i+2));
		}
		result[0] = new ImagePlus("", ims1);
		result[1] = new ImagePlus("", ims2);
		return result;
	}
	
	public int[] selectPlanes(ImagePlus imp, int numSlices) {
		int[] planes = new int[numSlices];
		int depth = imp.getStackSize();
		ImageStack ims = imp.getStack();
		PlaneStatistics[] planeMeans = new PlaneStatistics[depth];
		for (int i = 0; i < depth; i++) {
			planeMeans[i] = new PlaneStatistics(i+1, ims.getProcessor(i+1).getStatistics().mean);
		}
		// descend order
        Arrays.sort(planeMeans, new Comparator<PlaneStatistics>() {
            @Override
            public int compare(PlaneStatistics p1, PlaneStatistics p2) {
                return Double.compare(p2.sta, p1.sta); // Sort in descending order
            }
        });
        for (int i = 0; i < numSlices; i++) {
        	planes[i] = planeMeans[i].index;
        }
		return planes;
	}
	
	private class PlaneStatistics {
        int index;
        double sta;
        
        PlaneStatistics(int index, double mean) {
            this.index = index;
            this.sta = mean;
        }
    }
	
	
	
    public static void main(String[] args) {
		Class<?> clazz = sFSC.class;
		String url = clazz.getResource("/" + clazz.getName().replace('.', '/') + ".class").toString();
		String pluginsDir = url.substring("file:".length(),
				url.length() - clazz.getName().length() - ".class".length());
		System.setProperty("plugins.dir", pluginsDir);
		new ImageJ();
		ImagePlus image = IJ.openImage();
		image.show();
		IJ.runPlugIn(clazz.getName(), "");  
    }
}
