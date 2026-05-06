package rinha.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import rinha.config.Config;

import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

public final class DataPreprocessor {

    private static final int NUM_CLUSTERS = Config.IVF_NUM_CLUSTERS;
    private static final int KMEANS_ITERS = 20;
    private static final int DIMS = Config.DIMS;

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

        float[] allVecs = new float[n * DIMS];
        byte[] labels = new byte[n];
        for (int i = 0; i < n; i++) {
            RefEntry e = entries.get(i);
            for (int d = 0; d < DIMS; d++) {
                allVecs[i * DIMS + d] = (float) e.vector[d];
            }
            labels[i] = (byte) ("fraud".equals(e.label) ? 1 : 0);
        }
        entries = null;

        System.out.printf("Running k-means (C=%d, iter=%d, parallel)...%n", NUM_CLUSTERS, KMEANS_ITERS);
        long tKm = System.currentTimeMillis();

        float[] centroids = initCentroids(allVecs, n);
        int[] assignments = new int[n];

        for (int iter = 0; iter < KMEANS_ITERS; iter++) {
            long ti = System.currentTimeMillis();
            parallelAssign(allVecs, n, centroids, assignments);
            updateCentroids(allVecs, n, centroids, assignments);
            System.out.printf("    iter %2d  %,d ms%n", iter + 1, System.currentTimeMillis() - ti);
        }
        System.out.printf("K-means done in %,d ms%n", System.currentTimeMillis() - tKm);

        int[] listSizes = new int[NUM_CLUSTERS];
        for (int i = 0; i < n; i++) listSizes[assignments[i]]++;

        int[] listOffsets = new int[NUM_CLUSTERS];
        for (int c = 1; c < NUM_CLUSTERS; c++) listOffsets[c] = listOffsets[c - 1] + listSizes[c - 1];

        int[] permutation = new int[n];
        int[] cursor = new int[NUM_CLUSTERS];
        for (int i = 0; i < n; i++) {
            int c = assignments[i];
            permutation[listOffsets[c] + cursor[c]++] = i;
        }

        System.out.println("Writing V5 binary (SoA-within-blocks-of-8)...");
        try (var dos = new FileOutputStream(outputPath)) {
            ByteBuffer hdr = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            hdr.putInt(0x52494E48);
            hdr.putInt(5);
            hdr.putInt(n);
            hdr.putInt(DIMS);
            hdr.putInt(NUM_CLUSTERS);
            hdr.putInt(Config.IVF_NPROBE);
            hdr.putLong(0L);
            dos.write(hdr.array());

            byte[] soaBlock = new byte[DIMS * 8];
            for (int c = 0; c < NUM_CLUSTERS; c++) {
                int off = listOffsets[c];
                int sz = listSizes[c];
                int blocks = sz >> 3;
                int rem = sz & 7;

                for (int b = 0; b < blocks; b++) {
                    for (int d = 0; d < DIMS; d++) {
                        for (int lane = 0; lane < 8; lane++) {
                            float f = allVecs[permutation[off + b * 8 + lane] * DIMS + d];
                            soaBlock[d * 8 + lane] = quantize(f);
                        }
                    }
                    dos.write(soaBlock, 0, DIMS * 8);
                }

                for (int i = blocks * 8; i < sz; i++) {
                    int origIdx = permutation[off + i];
                    for (int d = 0; d < DIMS; d++) {
                        dos.write(quantize(allVecs[origIdx * DIMS + d]));
                    }
                }
            }

            byte[] clusterLabels = new byte[n];
            for (int i = 0; i < n; i++) {
                clusterLabels[i] = labels[permutation[i]];
            }
            dos.write(clusterLabels);

            ByteBuffer centBuf = ByteBuffer.allocate(NUM_CLUSTERS * DIMS * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (float v : centroids) centBuf.putFloat(v);
            dos.write(centBuf.array());

            ByteBuffer szBuf = ByteBuffer.allocate(NUM_CLUSTERS * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int s : listSizes) szBuf.putInt(s);
            dos.write(szBuf.array());
        }

        long totalBytes = 32L + (long) n * DIMS + n + (long) NUM_CLUSTERS * DIMS * 4 + (long) NUM_CLUSTERS * 4;
        System.out.printf("Done. %.1f MB%n", totalBytes / 1_048_576.0);
    }

    private static byte quantize(float f) {
        int q = (int) (f * 127f + (f >= 0f ? 0.5f : -0.5f));
        if (q < -127) q = -127;
        else if (q > 127) q = 127;
        return (byte) q;
    }

    private static float[] initCentroids(float[] allVecs, int n) {
        float[] centroids = new float[NUM_CLUSTERS * DIMS];
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int first = rng.nextInt(n);
        System.arraycopy(allVecs, first * DIMS, centroids, 0, DIMS);

        float[] minDist = new float[n];
        Arrays.fill(minDist, Float.MAX_VALUE);

        for (int c = 1; c < NUM_CLUSTERS; c++) {
            int prevBase = (c - 1) * DIMS;
            double totalDist = 0.0;
            for (int i = 0; i < n; i++) {
                float d = vecDistSq14(allVecs, i * DIMS, centroids, prevBase);
                if (d < minDist[i]) minDist[i] = d;
                totalDist += minDist[i];
            }
            double target = rng.nextDouble() * totalDist;
            int chosen = 0;
            for (int i = 0; i < n; i++) {
                target -= minDist[i];
                if (target <= 0.0) { chosen = i; break; }
            }
            System.arraycopy(allVecs, chosen * DIMS, centroids, c * DIMS, DIMS);
            if (c % 100 == 0) System.out.printf("  k-means++ init: %d/%d%n", c, NUM_CLUSTERS);
        }
        return centroids;
    }

    private static void parallelAssign(float[] allVecs, int n, float[] centroids, int[] assignments) {
        int numC = NUM_CLUSTERS;
        IntStream.range(0, n).parallel().forEach(i -> {
            int vi = i * DIMS;
            float bestDist = Float.MAX_VALUE;
            int best = 0;
            for (int c = 0; c < numC; c++) {
                float d = vecDistSq14(allVecs, vi, centroids, c * DIMS);
                if (d < bestDist) { bestDist = d; best = c; }
            }
            assignments[i] = best;
        });
    }

    private static void updateCentroids(float[] allVecs, int n, float[] centroids, int[] assignments) {
        double[] sums = new double[NUM_CLUSTERS * DIMS];
        int[] counts = new int[NUM_CLUSTERS];
        for (int i = 0; i < n; i++) {
            int c = assignments[i];
            int vi = i * DIMS;
            int ci = c * DIMS;
            counts[c]++;
            for (int d = 0; d < DIMS; d++) sums[ci + d] += allVecs[vi + d];
        }
        for (int c = 0; c < NUM_CLUSTERS; c++) {
            if (counts[c] > 0) {
                int ci = c * DIMS;
                double inv = 1.0 / counts[c];
                for (int d = 0; d < DIMS; d++) centroids[ci + d] = (float) (sums[ci + d] * inv);
            }
        }
    }

    private static float vecDistSq14(float[] a, int ai, float[] b, int bi) {
        float d0 = a[ai] - b[bi];
        float d1 = a[ai + 1] - b[bi + 1];
        float d2 = a[ai + 2] - b[bi + 2];
        float d3 = a[ai + 3] - b[bi + 3];
        float d4 = a[ai + 4] - b[bi + 4];
        float d5 = a[ai + 5] - b[bi + 5];
        float d6 = a[ai + 6] - b[bi + 6];
        float d7 = a[ai + 7] - b[bi + 7];
        float d8 = a[ai + 8] - b[bi + 8];
        float d9 = a[ai + 9] - b[bi + 9];
        float d10 = a[ai + 10] - b[bi + 10];
        float d11 = a[ai + 11] - b[bi + 11];
        float d12 = a[ai + 12] - b[bi + 12];
        float d13 = a[ai + 13] - b[bi + 13];
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
                + d4 * d4 + d5 * d5 + d6 * d6 + d7 * d7
                + d8 * d8 + d9 * d9 + d10 * d10 + d11 * d11
                + d12 * d12 + d13 * d13;
    }

    private static List<RefEntry> loadReferences(String path) throws Exception {
        try (var reader = new InputStreamReader(
                new GZIPInputStream(new java.io.FileInputStream(path)))) {
            Type type = new TypeToken<List<RefEntry>>() {}.getType();
            return new Gson().fromJson(reader, type);
        }
    }

    private static final class RefEntry {
        double[] vector;
        String label;
    }
}
