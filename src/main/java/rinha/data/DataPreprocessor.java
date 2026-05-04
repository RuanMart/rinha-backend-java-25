package rinha.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import rinha.config.Config;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

public final class DataPreprocessor {

    private static final int NUM_CLUSTERS = Config.IVF_NUM_CLUSTERS;
    private static final int KMEANS_ITERS = Config.IVF_KMEANS_ITERATIONS;
    private static final int DIMS = Config.DIMENSIONS;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DataPreprocessor <input.gz> <output.bin>");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];

        System.out.println("Loading references from: " + inputPath);
        List<ReferenceEntry> entries = loadReferences(inputPath);
        int numVectors = entries.size();
        System.out.println("Loaded " + numVectors + " vectors");

        double[][] vectors = new double[numVectors][DIMS];
        byte[] labels = new byte[numVectors];
        for (int i = 0; i < numVectors; i++) {
            ReferenceEntry e = entries.get(i);
            for (int d = 0; d < DIMS; d++) {
                vectors[i][d] = e.vector[d];
            }
            labels[i] = (byte) ("fraud".equals(e.label) ? 1 : 0);
        }
        entries = null;

        System.out.println("Running K-means with " + NUM_CLUSTERS + " clusters...");
        double[][] centroids = kmeans(vectors, NUM_CLUSTERS, KMEANS_ITERS);
        System.out.println("K-means done");

        int[] assignments = new int[numVectors];
        for (int i = 0; i < numVectors; i++) {
            assignments[i] = nearestCentroid(vectors[i], centroids);
        }

        int[] order = new int[numVectors];
        for (int i = 0; i < numVectors; i++) order[i] = i;
        int[] finalAssignments = assignments;
        ArrayMergeSort.sort(order, finalAssignments);

        int[] clusterOffsets = new int[NUM_CLUSTERS + 1];
        for (int i = 0; i < numVectors; i++) {
            clusterOffsets[assignments[order[i]] + 1]++;
        }
        for (int c = 0; c < NUM_CLUSTERS; c++) {
            clusterOffsets[c + 1] += clusterOffsets[c];
        }

        byte[] sortedVectors = new byte[numVectors * DIMS];
        byte[] sortedLabels = new byte[numVectors];
        for (int i = 0; i < numVectors; i++) {
            int srcIdx = order[i];
            for (int d = 0; d < DIMS; d++) {
                int quantized = (int) Math.round(vectors[srcIdx][d] * 100);
                sortedVectors[i * DIMS + d] = (byte) Math.max(-128, Math.min(127, quantized));
            }
            sortedLabels[i] = labels[srcIdx];
        }

        System.out.println("Writing index to: " + outputPath);
        try (var dos = new DataOutputStream(new FileOutputStream(outputPath))) {
            dos.writeInt(numVectors);
            dos.writeInt(NUM_CLUSTERS);
            dos.writeInt(DIMS);

            for (int c = 0; c <= NUM_CLUSTERS; c++) {
                dos.writeInt(clusterOffsets[c]);
            }

            for (int c = 0; c < NUM_CLUSTERS; c++) {
                for (int d = 0; d < DIMS; d++) {
                    dos.writeFloat((float) centroids[c][d]);
                }
            }

            dos.write(sortedVectors);

            dos.write(sortedLabels);
        }

        System.out.println("Index written successfully");
        System.out.println("  Vectors: " + numVectors);
        System.out.println("  Clusters: " + NUM_CLUSTERS);
        System.out.println("  Dimensions: " + DIMS);
        System.out.println("  Vector data: " + (numVectors * DIMS / 1024 / 1024) + " MB");
        System.out.println("  Labels: " + (numVectors / 1024 / 1024) + " MB");
    }

    private static List<ReferenceEntry> loadReferences(String path) throws Exception {
        try (var reader = new InputStreamReader(
                new GZIPInputStream(new java.io.FileInputStream(path)))) {
            Type type = new TypeToken<List<ReferenceEntry>>() {}.getType();
            return new Gson().fromJson(reader, type);
        }
    }

    private static double[][] kmeans(double[][] data, int k, int maxIter) {
        int n = data.length;
        double[][] centroids = initCentroidsKMeansPlusPlus(data, k);
        int[] assignments = new int[n];

        for (int iter = 0; iter < maxIter; iter++) {
            long iterStart = System.currentTimeMillis();

            int[] newAssignments = IntStream.range(0, n).parallel().map(i ->
                nearestCentroid(data[i], centroids)).toArray();

            int changed = 0;
            for (int i = 0; i < n; i++) {
                if (newAssignments[i] != assignments[i]) changed++;
                assignments[i] = newAssignments[i];
            }

            double[][] sums = new double[k][DIMS];
            int[] counts = new int[k];

            for (int i = 0; i < n; i++) {
                int c = assignments[i];
                counts[c]++;
                for (int d = 0; d < DIMS; d++) {
                    sums[c][d] += data[i][d];
                }
            }

            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < DIMS; d++) {
                        centroids[c][d] = sums[c][d] / counts[c];
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - iterStart;
            System.out.println("  Iteration " + (iter + 1) + "/" + maxIter +
                    " changed=" + changed + " time=" + elapsed + "ms");

            if (changed == 0) {
                System.out.println("  K-means converged at iteration " + iter);
                break;
            }
        }

        return centroids;
    }

    private static double[][] initCentroidsKMeansPlusPlus(double[][] data, int k) {
        int n = data.length;
        double[][] centroids = new double[k][DIMS];
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int firstIdx = rng.nextInt(n);
        centroids[0] = data[firstIdx].clone();

        double[] minDists = new double[n];
        Arrays.fill(minDists, Double.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            double totalDist = 0.0;
            double[] prev = centroids[c - 1];

            for (int i = 0; i < n; i++) {
                double dist = 0.0;
                for (int d = 0; d < DIMS; d++) {
                    double diff = data[i][d] - prev[d];
                    dist += diff * diff;
                }
                if (dist < minDists[i]) {
                    minDists[i] = dist;
                }
                totalDist += minDists[i];
            }

            double threshold = rng.nextDouble() * totalDist;
            double cumulative = 0.0;
            int chosen = n - 1;
            for (int i = 0; i < n; i++) {
                cumulative += minDists[i];
                if (cumulative >= threshold) {
                    chosen = i;
                    break;
                }
            }

            centroids[c] = data[chosen].clone();

            if (c % 500 == 0) {
                System.out.println("  K-means++ init: " + c + "/" + k);
            }
        }

        return centroids;
    }

    private static int nearestCentroid(double[] vector, double[][] centroids) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int c = 0; c < centroids.length; c++) {
            double dist = 0.0;
            for (int d = 0; d < DIMS; d++) {
                double diff = vector[d] - centroids[c][d];
                dist += diff * diff;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private static final class ReferenceEntry {
        double[] vector;
        String label;
    }

    private static final class ArrayMergeSort {
        static void sort(int[] order, int[] keys) {
            int[] temp = new int[order.length];
            mergeSort(order, temp, keys, 0, order.length - 1);
        }

        private static void mergeSort(int[] order, int[] temp, int[] keys, int left, int right) {
            if (left >= right) return;
            int mid = (left + right) >>> 1;
            mergeSort(order, temp, keys, left, mid);
            mergeSort(order, temp, keys, mid + 1, right);
            merge(order, temp, keys, left, mid, right);
        }

        private static void merge(int[] order, int[] temp, int[] keys, int left, int mid, int right) {
            System.arraycopy(order, left, temp, left, right - left + 1);
            int i = left, j = mid + 1, k = left;
            while (i <= mid && j <= right) {
                if (keys[temp[i]] <= keys[temp[j]]) {
                    order[k++] = temp[i++];
                } else {
                    order[k++] = temp[j++];
                }
            }
            while (i <= mid) order[k++] = temp[i++];
            while (j <= right) order[k++] = temp[j++];
        }
    }
}
