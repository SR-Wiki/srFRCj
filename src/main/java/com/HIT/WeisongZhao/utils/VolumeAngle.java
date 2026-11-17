package com.HIT.WeisongZhao.utils;

import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.indexing.conditions.Conditions;

public class VolumeAngle {
	private int depth, height, width;
	private INDArray zenith, azimuth, radius, antiZenith;
	private INDArray mask;
	private INDArray X, Y, Z;
	
	public VolumeAngle(int depth, int height, int width) {
		this.depth = depth;
		this.height = height;
		this.width = width;
		calculateVolumeAngles();
	}
	
	public INDArray getMask() {
		return this.mask;
	}
	
	public void setAngle(double lower, double upper) {
		createConeMask(lower, upper);
	}
    
    /**
     * Calculate the angle of each voxel in the 3D volume relative to the center point
     * @param volume Input 3D INDArray volume
     * @return VolumeAngles object containing zenith angle, azimuth angle and radius
     */
    private void calculateVolumeAngles() {
    	INDArray[] meshGrid = ArrayUtils.meshGrid3D(depth, height, width);
        this.X = meshGrid[0];
        this.Y = meshGrid[1];
        this.Z = meshGrid[2];
//        System.out.println(X.maxNumber().doubleValue());
        // Calculate radius (distance to center)
        this.radius = Transforms.sqrt(
                X.mul(X).add(Y.mul(Y)).add(Z.mul(Z))
            );
        // Calculate zenith angle (angle with Z-axis)
        calculateZenithAngle();
    }
    
    /**
     * Calculate the zenith angle (angle with Z-axis)
     * @param Z Z coordinate array
     * @param radius Radius array
     * @return Zenith angle array (radians)
     */
    private void calculateZenithAngle() {
        // Calculate |Z| / radius
        INDArray absZ = Transforms.abs(Z);
        INDArray ratio = absZ.div(radius.add(1e-4));
        // Handle division by zero (radius may be 0)
        ratio.replaceWhere(Nd4j.zerosLike(ratio), Conditions.isNan());
        ratio.replaceWhere(Nd4j.zerosLike(ratio), Conditions.isInfinite());
        // Calculate arccosine to obtain zenith angle
        this.zenith = Transforms.acos(ratio);
        this.antiZenith = Transforms.asin(ratio);
    }
    

    /**
     * Create a cone mask based on zenith angle
     * @param zenith Zenith angle array
     * @param zenithThreshold Zenith angle threshold (radians)
     * @return Cone mask array
     */
    public static INDArray createConeMask(INDArray zenith, double lower, double upper) {
    	INDArray fullOne = Nd4j.onesLike(zenith);
    	INDArray mask1 = Transforms.greaterThanOrEqual(zenith, fullOne.mul(lower));
    	INDArray mask2 = Transforms.lessThanOrEqual(zenith, fullOne.mul(upper));
    	mask1 = mask1.castTo(DataType.UINT8);
//    	System.out.println(mask1.sumNumber().doubleValue());
    	mask2 = mask2.castTo(DataType.UINT8);
//    	System.out.println(mask2.sumNumber().doubleValue());
    	return mask1.muli(mask2);
    	
//        return zenith.gte(lower).mul(zenith.lte(upper));
    }
  
    private void createConeMask(double lower, double upper) {
    	this.mask = createConeMask(antiZenith, lower, upper);
    }
    
    /**
     * Create Gaussian angle mask (smooth transition)
     * @param zenith Zenith angle array
     * @param zenithThreshold Zenith angle threshold (radians)
     * @param sigma Gaussian standard deviation
     * @return Gaussian angle mask array
     */
    public static INDArray createGaussianZenithMask(INDArray zenith, double lower, double upper) {
        // Create mask array
        INDArray mask = Nd4j.onesLike(zenith);
        mask = createConeMask(zenith, lower, upper);
        double sigma = upper / 2 / Math.sqrt(2);
        return mask.muli(Transforms.exp(zenith.mul(zenith).mul(-1).div(sigma).div(sigma)));
    }
    
    /**
     * Spherical coordinate container class
     */
    public static class SphericalCoordinates {
        private final INDArray radius;
        private final INDArray zenith;
        private final INDArray azimuth;
        
        public SphericalCoordinates(INDArray radius, INDArray zenith, INDArray azimuth) {
            this.radius = radius;
            this.zenith = zenith;
            this.azimuth = azimuth;
        }
        
        public INDArray getRadius() {
            return radius;
        }
        
        public INDArray getZenith() {
            return zenith;
        }
        
        public INDArray getAzimuth() {
            return azimuth;
        }
    }
    
    /**
     * Test method
     */
    public static void main(String[] args) {
    }
}