package rinha.search;


import java.nio.ByteBuffer;

public final class Distance {

    private Distance() {}

    public static double euclidean(double[] a, short[] b, int bOffset, int dims) {
        double sum = 0.0;
        for (int i = 0; i < dims; i++) {
            double diff = a[i] - (b[bOffset + i] / 10000.0);
            sum += diff * diff;
        }
        return sum;
    }

    public static double euclideanBB(double[] a, ByteBuffer b, int bOffset, int dims) {
        double sum = 0.0;
        for (int i = 0; i < dims; i++) {
            double diff = a[i] - (b.getShort(bOffset + i * 2) / 10000.0);
            sum += diff * diff;
        }
        return sum;
    }

    public static double euclidean(double[] a, double[] b, int dims) {
        double sum = 0.0;
        for (int i = 0; i < dims; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }
}
