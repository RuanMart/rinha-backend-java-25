package rinha.search;

import rinha.config.Config;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.Arrays;

public final class IVFIndex {

    private final int numClusters;
    private final int dims;
    private final float[] centroids;
    private final double[] centroidFraudRatio;

    private volatile boolean ready = false;

    public IVFIndex(int numClusters, int dims, float[] centroids,
                    double[] centroidFraudRatio) {
        this.numClusters = numClusters;
        this.dims = dims;
        this.centroids = centroids;
        this.centroidFraudRatio = centroidFraudRatio;
    }

    public double searchCentroidScore(double[] query) {
        int best = 0;
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
                best = c;
            }
        }
        return centroidFraudRatio[best];
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

            dis.skipBytes(numVectors * dims);

            double[] centroidFraudRatio = new double[numClusters];
            for (int c = 0; c < numClusters; c++) {
                int start = clusterOffsets[c];
                int end = clusterOffsets[c + 1];
                int count = end - start;
                if (count > 0) {
                    int fraud = 0;
                    for (int i = start; i < end; i++) {
                        if (dis.readByte() == 1) fraud++;
                    }
                    centroidFraudRatio[c] = (double) fraud / count;
                }
            }

            return new IVFIndex(numClusters, dims, centroids, centroidFraudRatio);
        }
    }

    public static final class SearchResult {
        public final int fraudCount;
        public final int k;

        public SearchResult(int fraudCount, int k) {
            this.fraudCount = fraudCount;
            this.k = k;
        }

        public double fraudScore() {
            return (double) fraudCount / k;
        }

        public boolean approved() {
            return fraudScore() < Config.FRAUD_THRESHOLD;
        }
    }
}
