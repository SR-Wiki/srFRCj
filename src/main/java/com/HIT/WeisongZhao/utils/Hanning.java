package com.HIT.WeisongZhao.utils;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

public class Hanning {
	
    /**
     * Create a 1D Hanning window
     * @param n Window length
     * @return 1D Hanning window as INDArray
     */
    public static INDArray hanning1D(int n) {
        if (n == 1) {
            return Nd4j.ones(1);
        }
        
        INDArray window = Nd4j.create(n);
        double alpha = 0.5;
        double beta = 1 - alpha;
        
        for (int i = 0; i < n; i++) {
            double value = alpha - beta * Math.cos(2 * Math.PI * i / (n - 1));
            window.putScalar(i, value);
        }
        
        return window;
    }
    /**
     * Create a 2D Hanning window
     * @param n1 First dimension size
     * @param n2 Second dimension size
     * @return 2D Hanning window as INDArray
     */
    public static INDArray hanning2D(int n1, int n2) {
        INDArray h1 = hanning1D(n1);
        INDArray h2 = hanning1D(n2);
        
        // Compute 2D window using outer product
        return h1.reshape(n1, 1).mmul(h2.reshape(1, n2));
    }
    
    /**
     * Optimized 3D Hanning window implementation (using broadcasting)
     * @param n1 First dimension size
     * @param n2 Second dimension size
     * @param n3 Third dimension size
     * @return 3D Hanning window as INDArray
     */
    public static INDArray hanning3D(int n1, int n2, int n3) {
        INDArray h1 = hanning1D(n1).reshape(n1, 1, 1);
        INDArray h2 = hanning1D(n2).reshape(1, n2, 1);
        INDArray h3 = hanning1D(n3).reshape(1, 1, n3);
        
        // Compute outer product using broadcasting
        return h1.mul(h2).mul(h3);
    }
    
    
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		INDArray a = hanning3D(32, 512, 512);
		System.out.println(a.slice(16).slice(256));
		
	}

}