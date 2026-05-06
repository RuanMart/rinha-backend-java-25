package rinha.search;

import rinha.config.Config;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class IVFIndex {

    private static final int K = 5;
    private static final int DIMS = Config.DIMENSIONS;
    private static final int NPROBE = Config.IVF_NPROBE;
    private static final int RETRY_EXTRA = Config.IVF_RETRY_EXTRA;
    private static final int MAX_NPROBE = NPROBE + RETRY_EXTRA;

    private final int numClusters;
    private final float[] centroids;
    private final short[] bboxMin;
    private final short[] bboxMax;
    private final int[] clusterOffsets;
    private final short[] vectors;
    private final byte[] labels;

    private volatile boolean ready = false;

    public IVFIndex(int numClusters, float[] centroids,
                    short[] bboxMin, short[] bboxMax,
                    int[] clusterOffsets, short[] vectors, byte[] labels) {
        this.numClusters = numClusters;
        this.centroids = centroids;
        this.bboxMin = bboxMin;
        this.bboxMax = bboxMax;
        this.clusterOffsets = clusterOffsets;
        this.vectors = vectors;
        this.labels = labels;
    }

    public int search(short[] query) {
        int[] probeClusters = new int[MAX_NPROBE];
        float[] probeDists = new float[MAX_NPROBE];
        for (int i = 0; i < MAX_NPROBE; i++) {
            probeDists[i] = Float.MAX_VALUE;
            probeClusters[i] = -1;
        }

        for (int c = 0; c < numClusters; c++) {
            int cOff = c * DIMS;
            float dist = 0.0f;
            for (int d = 0; d < DIMS; d++) {
                float diff = (float) query[d] - centroids[cOff + d];
                dist += diff * diff;
            }
            insertProbe(c, dist, probeClusters, probeDists, MAX_NPROBE);
        }

        long[] topDist = new long[K];
        int[] topIdx = new int[K];
        for (int i = 0; i < K; i++) {
            topDist[i] = Long.MAX_VALUE;
            topIdx[i] = -1;
        }
        int worstIdx = 0;
        long worstDist = Long.MAX_VALUE;

        worstIdx = scanProbeSlice(probeClusters, 0, NPROBE, query, topDist, topIdx, worstIdx, worstDist);
        worstDist = topDist[worstIdx];

        int fraudCount = countFrauds(topIdx);

        if ((fraudCount == 2 || fraudCount == 3) && RETRY_EXTRA > 0) {
            worstIdx = scanProbeSlice(probeClusters, NPROBE, MAX_NPROBE, query, topDist, topIdx, worstIdx, worstDist);
            fraudCount = countFrauds(topIdx);
        }

        return fraudCount;
    }

    private int countFrauds(int[] topIdx) {
        int fraudCount = 0;
        for (int i = 0; i < K; i++) {
            if (topIdx[i] >= 0 && labels[topIdx[i]] == 1) fraudCount++;
        }
        return fraudCount;
    }

    private void insertProbe(int cluster, float dist, int[] bestC, float[] bestP, int nprobe) {
        if (dist >= bestP[nprobe - 1]) return;
        int pos = nprobe - 1;
        while (pos > 0 && dist < bestP[pos - 1]) pos--;
        for (int i = nprobe - 1; i > pos; i--) {
            bestP[i] = bestP[i - 1];
            bestC[i] = bestC[i - 1];
        }
        bestP[pos] = dist;
        bestC[pos] = cluster;
    }

    private int scanProbeSlice(int[] clusters, int from, int to, short[] query,
                               long[] topDist, int[] topIdx, int worstIdx, long worstDist) {
        for (int pi = from; pi < to; pi++) {
            int c = clusters[pi];
            if (c < 0 || c >= numClusters) continue;
            int start = clusterOffsets[c];
            int end = clusterOffsets[c + 1];
            if (start >= end) continue;
            if (worstDist != Long.MAX_VALUE && bboxMin != null) {
                if (!bboxMayImprove(query, c, worstDist)) continue;
            }
            worstIdx = scanRange(start, end, query, topDist, topIdx, worstIdx);
            worstDist = topDist[worstIdx];
        }
        return worstIdx;
    }

    private boolean bboxMayImprove(short[] query, int c, long worstDist) {
        int off = c * DIMS;
        long d = 0;
        for (int j = 0; j < DIMS; j++) {
            int qv = query[j];
            int mn = bboxMin[off + j];
            int mx = bboxMax[off + j];
            int diff = 0;
            if (qv < mn) diff = mn - qv;
            else if (qv > mx) diff = qv - mx;
            d += (long) diff * diff;
            if (d > worstDist) return false;
        }
        return true;
    }

    private int scanRange(int start, int end, short[] query,
                          long[] topDist, int[] topIdx, int worstIdx) {
        long worstDist = topDist[worstIdx];

        for (int i = start; i < end; i++) {
            int vOff = i * DIMS;
            long dist = 0;
            boolean tooFar = false;
            for (int d = 0; d < DIMS; d++) {
                int diff = query[d] - vectors[vOff + d];
                dist += (long) diff * diff;
                if (dist > worstDist) {
                    tooFar = true;
                    break;
                }
            }

            if (!tooFar && dist < worstDist) {
                topDist[worstIdx] = dist;
                topIdx[worstIdx] = i;

                worstIdx = 0;
                worstDist = topDist[0];
                for (int j = 1; j < K; j++) {
                    if (topDist[j] > worstDist) {
                        worstIdx = j;
                        worstDist = topDist[j];
                    }
                }
            }
        }

        return worstIdx;
    }

    public void markReady() {
        this.ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    public static IVFIndex load(InputStream is) throws Exception {
        var dis = new DataInputStream(new BufferedInputStream(is, 1 << 20));

        int numVectors = dis.readInt();
        int numClusters = dis.readInt();
        int dims = dis.readInt();

        int[] clusterOffsets = new int[numClusters + 1];
        for (int i = 0; i <= numClusters; i++) {
            clusterOffsets[i] = dis.readInt();
        }

        float[] centroids = new float[numClusters * dims];
        for (int i = 0; i < centroids.length; i++) {
            centroids[i] = dis.readFloat() * Config.FIX_SCALE;
        }

        short[] bboxMin = null;
        short[] bboxMax = null;
        if (dis.available() > 0) {
            try {
                bboxMin = new short[numClusters * dims];
                readShorts(dis, bboxMin);
                bboxMax = new short[numClusters * dims];
                readShorts(dis, bboxMax);
            } catch (Exception e) {
                bboxMin = null;
                bboxMax = null;
            }
        }

        short[] vectors = new short[numVectors * dims];
        readShorts(dis, vectors);

        byte[] labels = new byte[numVectors];
        dis.readFully(labels);

        return new IVFIndex(numClusters, centroids, bboxMin, bboxMax,
                clusterOffsets, vectors, labels);
    }

    private static void readShorts(DataInputStream dis, short[] arr) throws Exception {
        int chunkSize = 65536;
        byte[] buf = new byte[chunkSize * 2];
        int offset = 0;
        while (offset < arr.length) {
            int toRead = Math.min(chunkSize, arr.length - offset);
            dis.readFully(buf, 0, toRead * 2);
            ByteBuffer.wrap(buf, 0, toRead * 2)
                    .order(ByteOrder.BIG_ENDIAN)
                    .asShortBuffer()
                    .get(arr, offset, toRead);
            offset += toRead;
        }
    }
}
