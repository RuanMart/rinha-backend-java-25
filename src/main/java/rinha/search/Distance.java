package rinha.search;

public final class Distance {

    private Distance() {}

    public static double euclideanByte(double[] a, byte[] b, int bOffset, int dims) {
        double sum = 0.0;
        for (int i = 0; i < dims; i++) {
            double diff = a[i] - (b[bOffset + i] / 100.0);
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
