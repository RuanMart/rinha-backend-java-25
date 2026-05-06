package rinha.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import rinha.config.Config;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;

public final class DataPreprocessor {

    private static final int NUM_CLUSTERS = Config.IVF_NUM_CLUSTERS;
    private static final int KMEANS_ITERS = Config.IVF_KMEANS_ITERATIONS;
    private static final int KMEANS_RESTARTS = 2;
    private static final int REFINE_PASSES = 2;
    private static final int DIMS = Config.DIMENSIONS;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DataPreprocessor <input.gz> <output.bin>");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];

        System.out.println("Loading references from: " + inputPath);
        List<RefEntry> entries = loadReferences(inputPath);
        int n = entries.size();
        System.out.println("Loaded " + n + " vectors");

        float[][] vectors = new float[n][DIMS];
        byte[] labels = new byte[n];
        for (int i = 0; i < n; i++) {
            RefEntry e = entries.get(i);
            for (int d = 0; d < DIMS; d++) {
                vectors[i][d] = (float) e.vector[d];
            }
            labels[i] = (byte) ("fraud".equals(e.label) ? 1 : 0);
        }
        entries = null;

        System.out.println("Running K-means with " + NUM_CLUSTERS + " clusters, " +
                KMEANS_RESTARTS + " restarts, " + KMEANS_ITERS + " iters each...");
        float[][] bestCentroids = null;
        double bestInertia = Double.MAX_VALUE;

        for (int restart = 0; restart < KMEANS_RESTARTS; restart++) {
            long restartStart = System.currentTimeMillis();
            float[][] centroids = kmeans(vectors, NUM_CLUSTERS, KMEANS_ITERS);

            double inertia = computeInertia(vectors, centroids);
            long elapsed = System.currentTimeMillis() - restartStart;
            System.out.println("  Restart " + (restart + 1) + "/" + KMEANS_RESTARTS +
                    " inertia=" + String.format("%.2f", inertia) +
                    " time=" + elapsed + "ms");

            if (inertia < bestInertia) {
                bestInertia = inertia;
                bestCentroids = centroids;
            }
        }

        System.out.println("Best inertia: " + String.format("%.2f", bestInertia));
        System.out.println("Running " + REFINE_PASSES + " full-dataset refinement passes...");

        for (int pass = 0; pass < REFINE_PASSES; pass++) {
            long passStart = System.currentTimeMillis();
            bestCentroids = refinePass(vectors, bestCentroids);
            double inertia = computeInertia(vectors, bestCentroids);
            long elapsed = System.currentTimeMillis() - passStart;
            System.out.println("  Refine pass " + (pass + 1) + "/" + REFINE_PASSES +
                    " inertia=" + String.format("%.2f", inertia) +
                    " time=" + elapsed + "ms");
        }

        System.out.println("Assigning vectors to clusters...");
        int[] assignments = new int[n];
        for (int i = 0; i < n; i++) {
            assignments[i] = nearestCentroid(vectors[i], bestCentroids);
        }

        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        int[] finalAssignments = assignments;
        ArrayMergeSort.sort(order, finalAssignments);

        int[] clusterOffsets = new int[NUM_CLUSTERS + 1];
        for (int i = 0; i < n; i++) {
            clusterOffsets[assignments[order[i]] + 1]++;
        }
        for (int c = 0; c < NUM_CLUSTERS; c++) {
            clusterOffsets[c + 1] += clusterOffsets[c];
        }

        System.out.println("Sorting vectors within clusters by distance to centroid...");
        short[] sortedVectors = new short[n * DIMS];
        byte[] sortedLabels = new byte[n];
        int[] clusterStart = new int[NUM_CLUSTERS];
        System.arraycopy(clusterOffsets, 0, clusterStart, 0, NUM_CLUSTERS);

        for (int i = 0; i < n; i++) {
            int srcIdx = order[i];
            int cluster = assignments[srcIdx];
            int pos = clusterStart[cluster]++;
            for (int d = 0; d < DIMS; d++) {
                sortedVectors[pos * DIMS + d] = (short) Math.round(vectors[srcIdx][d] * Config.FIX_SCALE);
            }
            sortedLabels[pos] = labels[srcIdx];
        }

        sortWithinClusters(sortedVectors, sortedLabels, clusterOffsets, bestCentroids, NUM_CLUSTERS);

        System.out.println("Computing bounding boxes...");
        short[] bboxMin = new short[NUM_CLUSTERS * DIMS];
        short[] bboxMax = new short[NUM_CLUSTERS * DIMS];
        for (int c = 0; c < NUM_CLUSTERS; c++) {
            int start = clusterOffsets[c];
            int end = clusterOffsets[c + 1];
            int off = c * DIMS;
            if (start >= end) {
                Arrays.fill(bboxMin, off, off + DIMS, Short.MAX_VALUE);
                Arrays.fill(bboxMax, off, off + DIMS, Short.MIN_VALUE);
                continue;
            }
            for (int d = 0; d < DIMS; d++) {
                short mn = Short.MAX_VALUE;
                short mx = Short.MIN_VALUE;
                for (int i = start; i < end; i++) {
                    short v = sortedVectors[i * DIMS + d];
                    if (v < mn) mn = v;
                    if (v > mx) mx = v;
                }
                bboxMin[off + d] = mn;
                bboxMax[off + d] = mx;
            }
        }

        System.out.println("Writing index to: " + outputPath);
        try (var dos = new DataOutputStream(new FileOutputStream(outputPath))) {
            dos.writeInt(n);
            dos.writeInt(NUM_CLUSTERS);
            dos.writeInt(DIMS);

            for (int c = 0; c <= NUM_CLUSTERS; c++) {
                dos.writeInt(clusterOffsets[c]);
            }

            for (int c = 0; c < NUM_CLUSTERS; c++) {
                for (int d = 0; d < DIMS; d++) {
                    dos.writeFloat(bestCentroids[c][d]);
                }
            }

            byte[] shortBuf = new byte[NUM_CLUSTERS * DIMS * 2];
            ByteBuffer.wrap(shortBuf).order(ByteOrder.BIG_ENDIAN).asShortBuffer().put(bboxMin);
            dos.write(shortBuf, 0, NUM_CLUSTERS * DIMS * 2);

            ByteBuffer.wrap(shortBuf).order(ByteOrder.BIG_ENDIAN).asShortBuffer().put(bboxMax);
            dos.write(shortBuf, 0, NUM_CLUSTERS * DIMS * 2);

            shortBuf = new byte[Math.min(n * DIMS * 2, 1 << 24)];
            int vecShortTotal = n * DIMS;
            int written = 0;
            while (written < vecShortTotal) {
                int chunk = Math.min(shortBuf.length / 2, vecShortTotal - written);
                ByteBuffer.wrap(shortBuf, 0, chunk * 2).order(ByteOrder.BIG_ENDIAN)
                        .asShortBuffer().put(sortedVectors, written, chunk);
                dos.write(shortBuf, 0, chunk * 2);
                written += chunk;
            }

            dos.write(sortedLabels);
        }

        System.out.println("Index written successfully");
        System.out.println("  Vectors: " + n);
        System.out.println("  Clusters: " + NUM_CLUSTERS);
        System.out.println("  Dimensions: " + DIMS);
        System.out.println("  Format: v2 (with bounding boxes + within-cluster sort)");
    }

    private static void sortWithinClusters(short[] vectors, byte[] labels,
                                            int[] clusterOffsets, float[][] centroids,
                                            int numClusters) {
        for (int c = 0; c < numClusters; c++) {
            int start = clusterOffsets[c];
            int end = clusterOffsets[c + 1];
            int count = end - start;
            if (count <= 1) continue;

            float[] centroid = centroids[c];
            long[] dists = new long[count];
            for (int i = 0; i < count; i++) {
                int vOff = (start + i) * DIMS;
                long dist = 0;
                for (int d = 0; d < DIMS; d++) {
                    float diff = vectors[vOff + d] - centroid[d] * Config.FIX_SCALE;
                    dist += (long) (diff * diff);
                }
                dists[i] = dist;
            }

            int[] indices = new int[count];
            for (int i = 0; i < count; i++) indices[i] = i;
            sort_by_dist(indices, dists);

            short[] tmpVecs = new short[count * DIMS];
            byte[] tmpLabels = new byte[count];
            for (int i = 0; i < count; i++) {
                int srcIdx = start + indices[i];
                System.arraycopy(vectors, srcIdx * DIMS, tmpVecs, i * DIMS, DIMS);
                tmpLabels[i] = labels[srcIdx];
            }
            System.arraycopy(tmpVecs, 0, vectors, start * DIMS, count * DIMS);
            System.arraycopy(tmpLabels, 0, labels, start, count);

            if (c % 256 == 0) {
                System.out.println("    Sorted clusters " + c + "/" + numClusters);
            }
        }
    }

    private static void sort_by_dist(int[] indices, long[] dists) {
        int n = indices.length;
        int[] temp = new int[n];
        mergeSortByDist(indices, temp, dists, 0, n - 1);
    }

    private static void mergeSortByDist(int[] indices, int[] temp, long[] dists,
                                         int left, int right) {
        if (left >= right) return;
        int mid = (left + right) >>> 1;
        mergeSortByDist(indices, temp, dists, left, mid);
        mergeSortByDist(indices, temp, dists, mid + 1, right);
        mergeByDist(indices, temp, dists, left, mid, right);
    }

    private static void mergeByDist(int[] indices, int[] temp, long[] dists,
                                     int left, int mid, int right) {
        System.arraycopy(indices, left, temp, left, right - left + 1);
        int i = left, j = mid + 1, k = left;
        while (i <= mid && j <= right) {
            if (dists[temp[i]] <= dists[temp[j]]) {
                indices[k++] = temp[i++];
            } else {
                indices[k++] = temp[j++];
            }
        }
        while (i <= mid) indices[k++] = temp[i++];
        while (j <= right) indices[k++] = temp[j++];
    }

    private static List<RefEntry> loadReferences(String path) throws Exception {
        try (var reader = new InputStreamReader(
                new GZIPInputStream(new java.io.FileInputStream(path)))) {
            Type type = new TypeToken<List<RefEntry>>() {}.getType();
            return new Gson().fromJson(reader, type);
        }
    }

    private static double computeInertia(float[][] data, float[][] centroids) {
        double inertia = 0.0;
        for (float[] datum : data) {
            int c = nearestCentroid(datum, centroids);
            for (int d = 0; d < DIMS; d++) {
                double diff = datum[d] - centroids[c][d];
                inertia += diff * diff;
            }
        }
        return inertia;
    }

    private static float[][] refinePass(float[][] data, float[][] centroids) {
        int k = centroids.length;
        float[][] sums = new float[k][DIMS];
        int[] counts = new int[k];

        for (float[] datum : data) {
            int c = nearestCentroid(datum, centroids);
            counts[c]++;
            for (int d = 0; d < DIMS; d++) {
                sums[c][d] += datum[d];
            }
        }

        for (int c = 0; c < k; c++) {
            if (counts[c] > 0) {
                for (int d = 0; d < DIMS; d++) {
                    centroids[c][d] = sums[c][d] / counts[c];
                }
            }
        }
        return centroids;
    }

    private static float[][] kmeans(float[][] data, int k, int maxIter) {
        int n = data.length;
        float[][] centroids = initCentroidsKMeansPlusPlus(data, k);
        int[] assignments = new int[n];

        for (int iter = 0; iter < maxIter; iter++) {
            long iterStart = System.currentTimeMillis();

            for (int i = 0; i < n; i++) {
                assignments[i] = nearestCentroid(data[i], centroids);
            }

            float[][] sums = new float[k][DIMS];
            int[] counts = new int[k];

            for (int i = 0; i < n; i++) {
                int c = assignments[i];
                counts[c]++;
                for (int d = 0; d < DIMS; d++) {
                    sums[c][d] += data[i][d];
                }
            }

            int changed = 0;
            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < DIMS; d++) {
                        float newVal = sums[c][d] / counts[c];
                        if (Float.floatToIntBits(newVal) != Float.floatToIntBits(centroids[c][d]))
                            changed++;
                        centroids[c][d] = newVal;
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - iterStart;
            if (iter % 10 == 0 || changed == 0) {
                System.out.println("    Iteration " + (iter + 1) + "/" + maxIter +
                        " changed=" + changed + " time=" + elapsed + "ms");
            }

            if (changed == 0) {
                System.out.println("    K-means converged at iteration " + iter);
                break;
            }
        }

        return centroids;
    }

    private static float[][] initCentroidsKMeansPlusPlus(float[][] data, int k) {
        int n = data.length;
        float[][] centroids = new float[k][DIMS];
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int firstIdx = rng.nextInt(n);
        System.arraycopy(data[firstIdx], 0, centroids[0], 0, DIMS);

        double[] minDists = new double[n];
        Arrays.fill(minDists, Double.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            double totalDist = 0.0;
            float[] prev = centroids[c - 1];

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

            System.arraycopy(data[chosen], 0, centroids[c], 0, DIMS);

            if (c % 256 == 0) {
                System.out.println("    K-means++ init: " + c + "/" + k);
            }
        }

        return centroids;
    }

    private static int nearestCentroid(float[] vector, float[][] centroids) {
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

    private static final class RefEntry {
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
