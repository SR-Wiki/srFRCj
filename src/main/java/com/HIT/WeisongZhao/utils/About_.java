package com.HIT.WeisongZhao.utils;

import javax.swing.JDialog;

import ij.IJ;
import ij.ImageJ;
import ij.plugin.PlugIn;

public class About_ extends JDialog implements PlugIn {
	private String defaultMessage = "(c) 2026 	HIT";
	private WalkBar walk = new WalkBar(this.defaultMessage, true, false, true);

	@Override
	public void run(String arg) {
		this.walk.fillAbout(
		        "sFRC / sFSC single-acquisition resolution assessment",
		        " updated, v0.1.11",

		        "sFRC: single-frame Fourier Ring Correlation."
		                + "<br>sFSC: single-volume Fourier Shell Correlation with Directional Resolution Inference (DRI) for anisotropic 3D resolution."
		                + "<br>srFRC: single-frame rolling Fourier Ring Correlation.",

		        "School of Instrumentation Science and Engineering",
		        "Harbin Institute of Technology",
		        "Yuzhen Liu",

		        "<p style=\"text-align:left\">"

		        + "<b>It enables SINGLE-ACQUISITION resolution assessment in fluorescence microscopy:</b>"

		        + "<br><br>(i) <b>Global 2D resolution estimation by sFRC</b>"
		        + " from a single fluorescence image, avoiding the need for repeated acquisitions."
		        + " sFRC generates two complementary observations by diagonal resampling"
		        + " and analytically corrects the resulting Fourier-correlation bias;"

		        + "<br><br>(ii) <b>Anisotropic 3D resolution estimation by sFSC</b>"
		        + " from a single fluorescence volume."
		        + " The same pair-generation strategy is applied within individual xy planes,"
		        + " while <b>DRI</b> infers the axial cutoff from selected non-paraxial"
		        + " directional FSC measurements, providing separate lateral and axial resolution estimates;"

		        + "<br><br>(iii) <b>Local resolution mapping by srFRC</b>"
		        + " from a single image, revealing spatially heterogeneous resolution"
		        + " and regions with reduced local reliability;"

		        + "<br><br>(iv) <b>Resolution assessment of computational SR reconstructions</b>"
		        + " using a reconstruction-adapted workflow in which complementary observations"
		        + " are generated before reconstruction and reconstructed independently;"

		        + "<br><br>(v) <b>Resolution-guided image processing</b>,"
		        + " where the measured resolution can provide quantitative constraints"
		        + " for filtering and deconvolution with reduced manual parameter tuning."

		        + "<br><br><b>Why this framework?</b>"
		        + "<br>Effective resolution in fluorescence microscopy depends not only on the optical system,"
		        + " but also on fluorophore properties, signal-to-noise ratio, sample dynamics"
		        + " and reconstruction procedures."
		        + " Conventional FRC/FSC generally requires two observations of the same underlying structure,"
		        + " while repeated fluorescence acquisitions may be affected by motion,"
		        + " photobleaching and acquisition variability."
		        + " Three-dimensional imaging further introduces direction-dependent resolution,"
		        + " unequal sampling and sequential z-stack inconsistencies."
		        + " The sFRC/sFSC framework therefore provides a unified approach for"
		        + " acquisition-specific 2D and anisotropic 3D resolution assessment"
		        + " from individual fluorescence acquisitions."

		        + "<br><br><b>It is a part of the manuscript:</b>"
		        + "<br>Yuzhen Liu, Xianxin Ye, Liying Qu, <i>et al.</i>"
		        + " Single-acquisition estimation of anisotropic 3D resolution"
		        + " in super-resolution fluorescence microscopy."

		        + "<br><br><b>Open source:</b>"
		        + "<br>Fiji/ImageJ: https://github.com/SR-Wiki/srFRCJ"
		        + "<br>MATLAB: https://github.com/SR-Wiki/srFRCM"
		        + "<br>Python: https://github.com/SR-Wiki/srFRCpy"

		        + "<br><br><b>Example data:</b>"
		        + "<br>https://github.com/SR-Wiki/srFRCJ/releases/latest/download/example_data.zip"

		        + "<br><br><b>Note:</b>"
		        + "<br>The reported resolution is an image-based estimate of statistically"
		        + " supported spatial-frequency information and should be interpreted together"
		        + " with image SNR, sampling density and biological context."

		        + ""
		);

		this.walk.showAbout_PANEL();

	}

	public static void main(String[] args) {

		Class<?> clazz = About_.class;

		// start ImageJ
		new ImageJ();

		IJ.runPlugIn(clazz.getName(), "");
	}

}