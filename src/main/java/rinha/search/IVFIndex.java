package rinha.search;

import java.io.DataInputStream;
import java.io.InputStream;

public final class IVFIndex {

    private static final int K = 5;

    private final int numClusters;
    private final int dims;
    private final float[] centroids;
    private final int[] clusterOffsets;
    private final byte[] vectors;
    private final byte[] labels;

    private volatile boolean ready = false;

    public IVFIndex(int numClusters, int dims, float[] centroids,
                    int[] clusterOffsets, byte[] vectors, byte[] labels) {
        this.numClusters = numClusters;
        this.dims = dims;
        this.centroids = centroids;
        this.clusterOffsets = clusterOffsets;
        this.vectors = vectors;
        this.labels = labels;
    }

    public int search(double[] query) {
        int bestCluster = 0;
        double bestDist = Double.MAX_VALUE;
        for (int c = 0; c < numClusters; c++) {
            int cOff = c * dims;
            double sum = 0.0;
            for (int d = 0; d < dims; d++) {
                double diff = query[d] - centroids[cOff + d];
                sum += diff * diff;
            }
            if (sum < bestDist) {
                bestDist = sum;
                bestCluster = c;
            }
        }

        int start = clusterOffsets[bestCluster];
        int end = clusterOffsets[bestCluster + 1];

        double d0 = Double.MAX_VALUE, d1 = Double.MAX_VALUE,
               d2 = Double.MAX_VALUE, d3 = Double.MAX_VALUE,
               d4 = Double.MAX_VALUE;
        int i0 = -1, i1 = -1, i2 = -1, i3 = -1, i4 = -1;

        for (int i = start; i < end; i++) {
            int vOff = i * dims;
            double dist = 0.0;
            for (int d = 0; d < dims; d++) {
                double diff = query[d] - (vectors[vOff + d] * 0.01);
                dist += diff * diff;
            }

            if (dist < d4) {
                if (dist < d0) {
                    d4 = d3; i4 = i3;
                    d3 = d2; i3 = i2;
                    d2 = d1; i2 = i1;
                    d1 = d0; i1 = i0;
                    d0 = dist; i0 = i;
                } else if (dist < d1) {
                    d4 = d3; i4 = i3;
                    d3 = d2; i3 = i2;
                    d2 = d1; i2 = i1;
                    d1 = dist; i1 = i;
                } else if (dist < d2) {
                    d4 = d3; i4 = i3;
                    d3 = d2; i3 = i2;
                    d2 = dist; i2 = i;
                } else if (dist < d3) {
                    d4 = d3; i4 = i3;
                    d3 = dist; i3 = i;
                } else {
                    d4 = dist; i4 = i;
                }
            }
        }

        int fraudCount = 0;
        if (i0 >= 0 && labels[i0] == 1) fraudCount++;
        if (i1 >= 0 && labels[i1] == 1) fraudCount++;
        if (i2 >= 0 && labels[i2] == 1) fraudCount++;
        if (i3 >= 0 && labels[i3] == 1) fraudCount++;
        if (i4 >= 0 && labels[i4] == 1) fraudCount++;
        return fraudCount;
    }

    public void markReady() {
        this.ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    public static IVFIndex load(InputStream is) throws Exception {
        try (var dis = new DataInputStream(is)) {
            int numVectors = dis.readInt();
            int numClusters = dis.readInt();
            int dims = dis.readInt();

            int[] clusterOffsets = new int[numClusters + 1];
            for (int i = 0; i <= numClusters; i++) {
                clusterOffsets[i] = dis.readInt();
            }

            float[] centroids = new float[numClusters * dims];
            for (int i = 0; i < centroids.length; i++) {
                centroids[i] = dis.readFloat();
            }

            byte[] vectors = new byte[numVectors * dims];
            dis.readFully(vectors);

            byte[] labels = new byte[numVectors];
            dis.readFully(labels);

            return new IVFIndex(numClusters, dims, centroids, clusterOffsets, vectors, labels);
        }
    }
}
