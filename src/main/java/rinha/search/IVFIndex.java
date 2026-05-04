package rinha.search;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class IVFIndex {

    private static final int K = 5;
    private static final int DIMS = 14;
    private static final int NPROBE = 2;

    private final int numClusters;
    private final short[] quantCentroids;
    private final int[] clusterOffsets;
    private final short[] vectors;
    private final byte[] labels;

    private volatile boolean ready = false;

    public IVFIndex(int numClusters, short[] quantCentroids,
                    int[] clusterOffsets, short[] vectors, byte[] labels) {
        this.numClusters = numClusters;
        this.quantCentroids = quantCentroids;
        this.clusterOffsets = clusterOffsets;
        this.vectors = vectors;
        this.labels = labels;
    }

    public int search(short[] query) {
        int[] probeClusters = new int[NPROBE];
        long[] probeDists = new long[NPROBE];
        for (int i = 0; i < NPROBE; i++) probeDists[i] = Long.MAX_VALUE;

        for (int c = 0; c < numClusters; c++) {
            int cOff = c * DIMS;
            long dist = 0;
            for (int d = 0; d < DIMS; d++) {
                int diff = query[d] - quantCentroids[cOff + d];
                dist += (long) diff * diff;
            }
            for (int i = 0; i < NPROBE; i++) {
                if (dist < probeDists[i]) {
                    System.arraycopy(probeDists, i, probeDists, i + 1, NPROBE - 1 - i);
                    System.arraycopy(probeClusters, i, probeClusters, i + 1, NPROBE - 1 - i);
                    probeDists[i] = dist;
                    probeClusters[i] = c;
                    break;
                }
            }
        }

        long[] topDist = new long[K];
        int[] topIdx = new int[K];
        for (int i = 0; i < K; i++) {
            topDist[i] = Long.MAX_VALUE;
            topIdx[i] = -1;
        }
        int worstIdx = 0;

        for (int p = 0; p < NPROBE; p++) {
            int cluster = probeClusters[p];
            int start = clusterOffsets[cluster];
            int end = clusterOffsets[cluster + 1];
            worstIdx = scanRange(start, end, query, topDist, topIdx, worstIdx);
        }

        int fraudCount = 0;
        for (int i = 0; i < K; i++) {
            if (topIdx[i] >= 0 && labels[topIdx[i]] == 1) fraudCount++;
        }
        return fraudCount;
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

        int centroidCount = numClusters * dims;
        short[] quantCentroids = new short[centroidCount];
        for (int i = 0; i < centroidCount; i++) {
            quantCentroids[i] = (short) Math.round(dis.readFloat() * 10000);
        }

        short[] vectors = new short[numVectors * dims];
        readShorts(dis, vectors);

        byte[] labels = new byte[numVectors];
        dis.readFully(labels);

        return new IVFIndex(numClusters, quantCentroids,
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
