package rinha.search;

import rinha.config.Config;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.Arrays;

public final class IVFIndex {

    private final int numVectors;
    private final int numClusters;
    private final int dims;
    private final int[] clusterOffsets;
    private final float[] centroids;
    private final byte[] vectors;
    private final byte[] labels;

    private volatile boolean ready = false;

    public IVFIndex(int numVectors, int numClusters, int dims,
                    int[] clusterOffsets, float[] centroids,
                    byte[] vectors, byte[] labels) {
        this.numVectors = numVectors;
        this.numClusters = numClusters;
        this.dims = dims;
        this.clusterOffsets = clusterOffsets;
        this.centroids = centroids;
        this.vectors = vectors;
        this.labels = labels;
    }

    public void markReady() {
        this.ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    public SearchResult search(double[] query, int nprobe, int k) {
        double[] centroidDists = new double[numClusters];
        for (int c = 0; c < numClusters; c++) {
            int cOffset = c * dims;
            double sum = 0.0;
            for (int d = 0; d < dims; d++) {
                double diff = query[d] - centroids[cOffset + d];
                sum += diff * diff;
            }
            centroidDists[c] = sum;
        }

        int[] topClusters = new int[nprobe];
        double[] topDists = new double[nprobe];
        Arrays.fill(topDists, Double.MAX_VALUE);

        for (int c = 0; c < numClusters; c++) {
            double d = centroidDists[c];
            if (d < topDists[nprobe - 1]) {
                int insertPos = nprobe - 1;
                while (insertPos > 0 && d < topDists[insertPos - 1]) {
                    insertPos--;
                }
                System.arraycopy(topClusters, insertPos, topClusters, insertPos + 1, nprobe - 1 - insertPos);
                System.arraycopy(topDists, insertPos, topDists, insertPos + 1, nprobe - 1 - insertPos);
                topClusters[insertPos] = c;
                topDists[insertPos] = d;
            }
        }

        double[] bestDists = new double[k];
        Arrays.fill(bestDists, Double.MAX_VALUE);
        int[] bestIndices = new int[k];

        for (int ci = 0; ci < nprobe; ci++) {
            int cluster = topClusters[ci];
            int start = clusterOffsets[cluster];
            int end = clusterOffsets[cluster + 1];

            for (int i = start; i < end; i++) {
                int vOffset = i * dims;
                double dist = Distance.euclideanByte(query, vectors, vOffset, dims);

                if (dist < bestDists[k - 1]) {
                    int insertPos = k - 1;
                    while (insertPos > 0 && dist < bestDists[insertPos - 1]) {
                        insertPos--;
                    }
                    System.arraycopy(bestIndices, insertPos, bestIndices, insertPos + 1, k - 1 - insertPos);
                    System.arraycopy(bestDists, insertPos, bestDists, insertPos + 1, k - 1 - insertPos);
                    bestIndices[insertPos] = i;
                    bestDists[insertPos] = dist;
                }
            }
        }

        int fraudCount = 0;
        for (int i = 0; i < k; i++) {
            if (labels[bestIndices[i]] == 1) {
                fraudCount++;
            }
        }

        return new SearchResult(fraudCount, k);
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

            return new IVFIndex(numVectors, numClusters, dims,
                    clusterOffsets, centroids, vectors, labels);
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
