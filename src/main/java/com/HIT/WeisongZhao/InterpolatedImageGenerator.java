package com.HIT.WeisongZhao;


import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import com.HIT.WeisongZhao.utils.FloatFFT_2D;
import com.HIT.WeisongZhao.utils.FloatFFT_1D;
import static java.lang.Math.abs;
import static java.lang.Math.sqrt;




//import Fourier_Oversample.FourierInterpolation;
//import com.mathworks.toolbox.javabuilder.*;


/**
 * diagonal resampling -> Fourier Interpolation -> two independent images preserving original sizes
 * image sizes are assumed to be power of 2, if not, zero-padding will be applied
 */
public class InterpolatedImageGenerator implements PlugIn{
	private int original_height = 512, original_width = 512;
	private Boolean changeSize = false;
	
	@Override
	public void run(String args) {
		if (IJ.versionLessThan("1.46j"))
			return;
		ImagePlus imp = WindowManager.getCurrentImage();
		if (imp == null) {
			IJ.noImage();
			return;
		}
		ImageStack ims = imp.getStack();
		this.original_height = ims.getHeight();
		this.original_width = ims.getWidth();
		String[] generateChoices = {"true", "false"};
		
		ImagePlus[] resampled = getResampledImage(imp, true);
		ImagePlus[] interpolated = getInterpolated(resampled);
		interpolated[0].show();
		interpolated[1].show();
	}
	
	public ImagePlus[] getInterpolated(ImagePlus[] imp) {
		ImagePlus[] result = new ImagePlus[2];
		result[0] = FourierInterpolation(imp[0], 2);
		result[1] = FourierInterpolation(imp[1], 2);
		result[0].setTitle("sFRC - Fourier resampled sample A");
		result[1].setTitle("sFRC - Fourier resampled sample B");
		return result;
	}
	
	public static ImagePlus[] getResampledImage(ImagePlus imp, Boolean wholeStack) {
		int height = imp.getHeight();
		int width = imp.getWidth();
		ImageStack ims1 = new ImageStack(width / 2, height / 2);
		ImageStack ims2 = new ImageStack(width / 2, height / 2);
		
		if (wholeStack) {
			for (int i = 0; i < imp.getImageStackSize(); i++) {
				ImagePlus tmp = PreGenerator.getResampledImage(imp.getStack().getProcessor(i + 1));
				ims1.addSlice((FloatProcessor) tmp.getStack().getProcessor(1));
				ims2.addSlice((FloatProcessor) tmp.getStack().getProcessor(2));
			}
			
		} else {
			ImagePlus first = PreGenerator.getResampledImage(imp.getStack().getProcessor(imp.getCurrentSlice()));
			ims1.addSlice(first.getStack().getProcessor(1));
			ims2.addSlice(first.getStack().getProcessor(2));
		}
		
		ImagePlus[] result = new ImagePlus[2];
		result[0] = new ImagePlus("", ims1);
		result[1] = new ImagePlus("", ims2);
		return result;
	}
	
	
	public static ImageProcessor[] getResampledImage(ImageProcessor dataImage) {
		
		int old_width = dataImage.getWidth();
		int old_height = dataImage.getHeight();
		final int new_width = old_width / 2;
		final int new_height = old_height / 2;
		float[] data = (float[]) dataImage.convertToFloatProcessor().getPixels();
		float[] pixels1 = new float[new_height * new_width];
		float[] pixels2 = new float[new_height * new_width];
		for (int x = 0; x < new_height; x++) {
			for (int y = 0; y < new_width; y++) {
				int first_index = 2 * (x * old_width + y);
				int next_index = 2 * (x * old_width + y) + old_width;
				pixels1[x * new_width + y] = (data[first_index] + data[next_index + 1]) / 2;
				pixels2[x * new_width + y] = (data[first_index + 1] + data[next_index]) / 2;
			}
		}
		FloatProcessor[] result = new FloatProcessor[2];
		result[0] = new FloatProcessor(new_width, new_height, pixels1);
		result[1] = new FloatProcessor(new_width, new_height, pixels2);
		return result;
	}
	
	public static ImagePlus FourierInterpolation(ImagePlus imp, int N) {
		int w = imp.getWidth(), h = imp.getHeight(), d = imp.getImageStackSize();
		ImageStack stackA = imp.getStack();
		float[][] dataAin = new float[d][w * h];
		for (int i = 0; i < d; i++) {
			dataAin[i] = (float[]) stackA.getProcessor(i + 1).convertToFloat().getPixels();
		}
		ImageStack result = new ImageStack(N * w, N * h);
		for (int z = 0; z < d; z++) {
			float[] a = new float[2 * w * h];
			for (int k2 = 0; k2 < h; k2++) {
				for (int k1 = 0; k1 < w; k1++) {
					a[k2 * 2 * w + 2 * k1] = dataAin[z][k2 * w + k1];
					a[k2 * 2 * w + 2 * k1 + 1] = dataAin[z][k2 * w + k1];
				}
			}
			int s = w;
			if (w < h)
				s = h;
			if (w > h)
				s = w;
			if (w == h)
				s = w;
			float[] b = new float[N * N * s * s];
			float[] bl;
			float[] largea = padScale(a, w, h);

			FloatFFT_2D FFT_J = new FloatFFT_2D(s, s);

			FFT_J.complexForward(largea);

			bl = pad2D(largea, s, s, N);

			FloatFFT_2D FFT_Jinverse = new FloatFFT_2D(N * s, N * s);

			FFT_Jinverse.complexInverse(bl, false);

			for (int i = 0; i < bl.length / 2; i++) {
				b[i] = (float) sqrt(abs(bl[i * 2] * bl[i * 2] + bl[i * 2 + 1] * bl[i * 2 + 1]));
			}

			result.addSlice("", cutScale(b, w, h, N));
		}
		ImagePlus image = new ImagePlus("Fourier interpolated", result);
//		image.show();
		return image;
	}
	
    /**
     * Quantitative Fourier upsampling to an exact output size. Uses the same
     * complex FFT / spectral zero-padding approach as FourierInterpolation(imp,N),
     * but retains real-valued amplitudes (nm maps must not use complex magnitude).
     * Even-size Nyquist bins are split between positive/negative frequencies.
     * This also handles rectangular, odd-sized and singleton-axis inputs.
     */
    public static ImagePlus FourierInterpolation(ImagePlus imp, int targetWidth, int targetHeight) {
        int width = imp.getWidth(), height = imp.getHeight();
        if (targetWidth < width || targetHeight < height)
            throw new IllegalArgumentException("Fourier upsampling cannot reduce image dimensions.");
        ImageStack output = new ImageStack(targetWidth, targetHeight);
        for (int slice = 1; slice <= imp.getStackSize(); slice++) {
            float[] source = (float[]) imp.getStack().getProcessor(slice).convertToFloatProcessor().getPixels();
            float[] spectrum = new float[2 * width * height];
            for (int i = 0; i < source.length; i++) spectrum[2 * i] = source[i];
            transform2D(spectrum, width, height, false);
            float[] padded = new float[2 * targetWidth * targetHeight];
            for (int y = 0; y < height; y++) {
                int fy = y <= height / 2 ? y : y - height;
                boolean splitY = height % 2 == 0 && y == height / 2 && targetHeight > height;
                for (int x = 0; x < width; x++) {
                    int fx = x <= width / 2 ? x : x - width;
                    boolean splitX = width % 2 == 0 && x == width / 2 && targetWidth > width;
                    int src = 2 * (y * width + x);
                    double weight = (splitX ? 0.5 : 1.0) * (splitY ? 0.5 : 1.0);
                    for (int sy = 0; sy < (splitY ? 2 : 1); sy++) {
                        int ky = sy == 0 ? fy : -fy;
                        int dy = (ky + targetHeight) % targetHeight;
                        for (int sx = 0; sx < (splitX ? 2 : 1); sx++) {
                            int kx = sx == 0 ? fx : -fx;
                            int dx = (kx + targetWidth) % targetWidth;
                            int dst = 2 * (dy * targetWidth + dx);
                            padded[dst] += spectrum[src] * weight;
                            padded[dst + 1] += spectrum[src + 1] * weight;
                        }
                    }
                }
            }
            transform2D(padded, targetWidth, targetHeight, true);
            // Normalized inverse FFT divides by output area; compensate for
            // spectral zero padding so constant maps and original samples retain nm.
            double scale = (double) targetWidth * targetHeight / ((double) width * height);
            float[] values = new float[targetWidth * targetHeight];
            for (int i = 0; i < values.length; i++) values[i] = (float) (padded[2 * i] * scale);
            output.addSlice(imp.getStack().getSliceLabel(slice), new FloatProcessor(targetWidth, targetHeight, values));
        }
        ImagePlus result = new ImagePlus(imp.getTitle(), output);
        result.setCalibration(imp.getCalibration().copy());
        result.getCalibration().pixelWidth *= (double) width / targetWidth;
        result.getCalibration().pixelHeight *= (double) height / targetHeight;
        return result;
    }

    private static void transform2D(float[] data, int width, int height, boolean inverse) {
        if (width > 1 && height > 1) {
            FloatFFT_2D fft = new FloatFFT_2D(height, width);
            if (inverse) fft.complexInverse(data, true); else fft.complexForward(data);
        } else if (width * height > 1) {
            FloatFFT_1D fft = new FloatFFT_1D(width * height);
            if (inverse) fft.complexInverse(data, true); else fft.complexForward(data);
        }
    }

	public static float[] padScale(float[] input, int w, int h) {
		int s = w;
		if (w < h)
			s = h;
		if (w > h)
			s = w;
		if (w == h)
			s = w;
		float[] scale = new float[2 * s * s];
		if (w < h) {
			for (int i = 0; i < h; i++) {
				for (int j = 0; j < w; j++) {
					scale[i * 2 * h + j * 2] = input[i * 2 * w + j * 2];
					scale[i * 2 * h + j * 2 + 1] = input[i * 2 * w + j * 2 + 1];
				}
			}
		} else if (w > h) {
			for (int i = 0; i < h; i++) {
				for (int j = 0; j < w; j++) {
					scale[i * 2 * w + j * 2] = input[i * 2 * w + j * 2];
					scale[i * 2 * w + j * 2 + 1] = input[i * 2 * w + j * 2 + 1];
				}
			}
		} else {
			scale = input;
		}
		return scale;
	}

	// Real signal cut
	public static float[] cutScale(float[] input, int w, int h, int N) {
		//
		int s = N * w;
		if (w < h)
			s = N * h;
		if (w > h)
			s = N * w;
		if (w == h)
			s = N * w;
		float[] small = new float[N * N * w * h];
		if (w == h) {
			small = input;
		} else {
			for (int i = 0; i < N * h; i++) {
				for (int j = 0; j < N * w; j++) {
					small[i * N * w + j] = input[i * s + j];
				}
			}
		}
		return small;
	}

	public static float[] pad2D(float[] input, int w, int h, int N) {

		int lx = N * w;
		int ly = N * h;

		int ow = w / 2;
		int oh = h / 2;

		if (lx == w)
			if (ly == h)
				return input;

		float[] large = new float[2 * N * N * w * h];
		// first
		for (int i = 0; i < oh; i++) {
			for (int j = 0; j < ow; j++) {
				large[j * 2 + i * lx * 2] = input[j * 2 + i * w * 2];
				large[j * 2 + i * lx * 2 + 1] = input[j * 2 + i * w * 2 + 1];
			}
		}
		// second
		for (int i = 0; i < oh; i++) {
			for (int j = ow; j < w; j++) {
				large[j * 2 + i * lx * 2 + (N - 1) * w * 2] = input[j * 2 + i * w * 2];
				large[j * 2 + i * lx * 2 + (N - 1) * w * 2 + 1] = input[j * 2 + i * w * 2 + 1];
			}
		}
		// third
		for (int i = oh; i < h; i++) {
			for (int j = 0; j < ow; j++) {
				large[j * 2 + i * lx * 2 + N * w * 2 * (N - 1) * h] = input[j * 2 + i * w * 2];
				large[j * 2 + i * lx * 2 + N * w * 2 * (N - 1) * h + 1] = input[j * 2 + i * w * 2 + 1];
			}
		}

		for (int i = oh; i < h; i++) {
			for (int j = ow; j < w; j++) {
				large[j * 2 + i * lx * 2 + (N) * w * 2 * (N - 1) * h + (N - 1) * w * 2] = input[j * 2 + i * w * 2];
				large[j * 2 + i * lx * 2 + (N) * w * 2 * (N - 1) * h + (N - 1) * w * 2 + 1] = input[j * 2 + i * w * 2
						+ 1];
			}
		}
		return large;
	}

	public static void main(String[] args) {
		Class<?> clazz = InterpolatedImageGenerator.class;
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
