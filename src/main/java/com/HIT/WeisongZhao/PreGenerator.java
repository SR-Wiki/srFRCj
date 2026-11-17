package com.HIT.WeisongZhao;

import java.util.Arrays;
import org.apache.commons.math3.util.FastMath;
import org.nd4j.linalg.api.ndarray.INDArray;

import com.HIT.WeisongZhao.utils.Hanning;

import Jfft.FloatFFT_2D;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import static java.lang.Math.abs;
import static java.lang.Math.sqrt;

public class PreGenerator implements PlugIn{
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
		ImagePlus[] resampled_imp = getInterpolated(imp);
		resampled_imp[0].show();
		resampled_imp[1].show();
	}
	/**
	 * perform Fourier interpolation on the whole image stack.
	 */
	public static ImagePlus[] getInterpolated(ImagePlus imp) {
		int numOfSlices = imp.getStackSize();
		int height = imp.getHeight();
		int width = imp.getWidth();
		ImageStack ims = imp.getStack();
		ImageStack ims1 = new ImageStack(width / 2, height / 2);
		ImageStack ims2 = new ImageStack(width / 2, height / 2);
		
		for (int i = 1; i <= numOfSlices; i++) {
			ImageProcessor img = ims.getProcessor(i);
			ImagePlus resampled = getResampledImage(img);
			ims1.addSlice(resampled.getStack().getProcessor(1));
			ims2.addSlice(resampled.getStack().getProcessor(2));
		}
		ImagePlus imp1 = FourierInterpolation(new ImagePlus("", ims1), 2);
		imp1.setTitle("Interpolated Stack 1");
		ImagePlus imp2 = FourierInterpolation(new ImagePlus("", ims2), 2);
		imp2.setTitle("Interpolated Stack 2");
		ImagePlus[] combined = new ImagePlus[2];
		combined[0] = imp1; 
		combined[1] = imp2;
		return combined;
	}
	
	public static float[][] pixel2array(ImageProcessor img) {
		int height = img.getHeight();
		int width = img.getWidth();
		float[][] img_array = new float[height][width];
		for (int i = 0; i < height; i++) {
			for (int j = 0; j < width; j++) {
				img_array[i][j] = img.getPixelValue(i, j);
			}
		}
		return img_array;
	}
	
	// normalization: img = img ./ img.max()
	public static void normalize(FloatProcessor fp) {
		float max = 0;
		int height = fp.getHeight();
		int width = fp.getWidth();
		float[] pixels = (float[]) fp.getPixels();
		for (int i = 0; i < height; i++) {
			for (int j = 0; j < width; j++) {
				if (fp.getPixelValue(i, j) > max) 
					max = fp.getPixelValue(i, j);
			}
		}
		
		for (int i = 0; i < height; i++) {
			for (int j = 0; j < width; j++) 
				pixels[i * width + j] /= max;
		}
		fp.setPixels(pixels);
	}
	
	/**
	 * recover the original image size
	 * @param imp
	 */
	public void recoverSize(ImagePlus imp) {
		ImageStack ims = imp.getStack();
		int numOfSlices = ims.getSize();
		ImageStack new_stack = new ImageStack(this.original_width, this.original_height);
		for (int i = 1; i <= numOfSlices; i++) {
			ImageProcessor ip = ims.getProcessor(i);
			ip.setRoi(0, 0, this.original_width, this.original_height);
			new_stack.addSlice(ip.crop());
		}
		imp.setStack(new_stack);
	}
	/**
	 * pads image to the next square size power of two.
	 *
	 * @param dataImage
	 * @return The square tapered image
	 */
	public static FloatProcessor getSquaredImage(ImageProcessor dataImage) {
		final int[] SIZES = new int[] {16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768};
		final int size = FastMath.max(dataImage.getWidth(), dataImage.getHeight());

		// Pad to a power of 2
		int index = Arrays.binarySearch(SIZES, size);
		if (index < 0) {
//			this.changeSize = true;
			// Get the insert position
			index = -(index + 1);
			if (index == SIZES.length)
				return null; // Too large so error
		}
		final int newSize = SIZES[index];

		dataImage = dataImage.toFloat(0, null);
		int height = dataImage.getHeight();
		int width = dataImage.getWidth();
		INDArray window = Hanning.hanning2D(height, width);
		int a = (int) window.size(0);
		int b = (int) window.size(1);
		float[] data = (float[]) dataImage.getPixels();
		// apply window function to avoid artificial edge effects
		for (int i = 0; i < data.length; i++) {
			long row = i / width;
			long col = i % width;
			data[i] = data[i] * window.getFloat(width, col, row);
		}
		float[] pixels = new float[newSize * newSize];
		// Note that the limits at 0 and size-1 the taper is zero so this can be ignored
		final int maxy_1 = dataImage.getHeight() - 1;
		final int maxx_1 = dataImage.getWidth() - 1;
		final int oldWidth = dataImage.getWidth();
		for (int y = 1; y < maxy_1; y++) {
			for (int x = 1, i = y * oldWidth + 1, ii = y * newSize + 1; x < maxx_1; x++, i++, ii++) {
				pixels[ii] = data[i];
			}
		}
		return new FloatProcessor(newSize, newSize, pixels, null);
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
	
	
	public static ImagePlus[] getResampledImage(ImageStack ims) {
		
		int depth = ims.getSize();
		int old_width = ims.getWidth();
		int old_height = ims.getHeight();
		final int new_width = old_width / 2;
		final int new_height = old_height / 2;
		ImageStack ims1 = new ImageStack(new_width, new_height);
		ImageStack ims2 = new ImageStack(new_width, new_height);
		for (int i = 0; i < depth; i++) {
			ImageProcessor tmp_ip = ims.getProcessor(i + 1);
			ImagePlus tmp_imp = getResampledImage(tmp_ip);
			ImageStack tmp_ims = tmp_imp.getStack();
			ims1.addSlice(tmp_ims.getProcessor(1));
			ims2.addSlice(tmp_ims.getProcessor(2));
		}
		ImagePlus imp1 = new ImagePlus("", ims1);
		ImagePlus imp2 = new ImagePlus("", ims2);
		ImagePlus[] result = {imp1, imp2};
		return result;
	}

	public static ImagePlus getResampledImage(ImageProcessor dataImage) {
		
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
		FloatProcessor single_im1 = new FloatProcessor(new_width, new_height, pixels1);
		FloatProcessor single_im2 = new FloatProcessor(new_width, new_height, pixels2);
		ImageStack resampled_stack = new ImageStack(new_width, new_height);
		resampled_stack.addSlice(single_im1);
		resampled_stack.addSlice(single_im2);
		return new ImagePlus("Resampled Images", resampled_stack);
	}
	
	// Complex signal pad
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
	
	 public static double[] fftshift(double[] data) {
	        double[] temp = new double[data.length];
	        int shiftPointNum = data.length / 2;
	        for (int i = 0; i < data.length; i++) {
	            temp[(shiftPointNum + i) % data.length] = data[i];
	        }
	        return temp;
	    }
	 
	 public static void main(String[] args) {
	    	Class<?> clazz = PreGenerator.class;
			String url = clazz.getResource("/" + clazz.getName().replace('.', '/') + ".class").toString();
			String pluginsDir = url.substring("file:".length(),
					url.length() - clazz.getName().length() - ".class".length());
			System.setProperty("plugins.dir", pluginsDir);
			new ImageJ();
			ImagePlus image = IJ.openImage();
			image.show();
//			FloatProcessor fp = getSquaredImage(image.getProcessor());
//			ImagePlus temp = new ImagePlus("", fp);
//			temp.show();
			IJ.runPlugIn(clazz.getName(), "");  
	    }

}

