package com.HIT.WeisongZhao;

import static com.HIT.WeisongZhao.NativeTools.getLocalFileFromResource;

import java.io.File;
import java.io.IOException;

import com.HIT.WeisongZhao.utils.StructuredLog;

import ij.ImagePlus;
import ij.plugin.LutLoader;
import ij.process.LUT;

/**
 * Created by paxcalpt on 05/06/2017.
 */
public class NJ_LUT {

	public static void applyLUT(ImagePlus imp, String path) {
		File temp = null;
		try {
			temp = getLocalFileFromResource("/" + path);
		} catch (IOException e) {
			StructuredLog.warning(
					"srFRC",
					"LUT resource",
					"Could not find LUT resource: " + path);
		}
		if (temp != null) {
			LUT lut = new LutLoader().openLut(temp.getAbsolutePath());
			imp.setLut(lut);
		}
	}

	public static void applyLUT_PANEL_rFRC(ImagePlus imp) {
		applyLUT(imp, "sJet.lut");
	}

	public static void applyLUT_PANEL_rFRCmask(ImagePlus imp) {
		applyLUT(imp, "PANEL_Green.lut");
	}
}
