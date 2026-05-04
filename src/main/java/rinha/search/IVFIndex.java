package rinha.search;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class IVFIndex {

    private static final int K = 5;
    private static final int DIMS = 14;

    private final int numClusters;
    private final int numVectors;
    private final short[] quantCentroids;
    private final int[] clusterOffsets;
    private final short[] vectors;
    private final byte[] labels;
    private final int[] origIds;
    private final short[] bboxMin;
    private final short[] bboxMax;

    private volatile boolean ready = false;

    public IVFIndex(int numClusters, int numVectors, short[] quantCentroids,
                    int[] clusterOffsets, short[] vectors, byte[] labels,
                    int[] origIds, short[] bboxMin, short[] bboxMax) {
        this.numClusters = numClusters;
        this.numVectors = numVectors;
        this.quantCentroids = quantCentroids;
        this.clusterOffsets = clusterOffsets;
        this.vectors = vectors;
        this.labels = labels;
        this.origIds = origIds;
        this.bboxMin = bboxMin;
        this.bboxMax = bboxMax;
    }

    public int search(short[] query) {
        int bestCluster = 0;
        long bestDist = Long.MAX_VALUE;
        for (int c = 0; c < numClusters; c++) {
            int cOff = c * DIMS;
            long dist = 0;
            for (int d = 0; d < DIMS; d++) {
                int diff = query[d] - quantCentroids[cOff + d];
                dist += (long) diff * diff;
            }
            if (dist < bestDist) {
                bestDist = dist;
                bestCluster = c;
            }
        }

        long[] topDist = new long[K];
        int[] topIdx = new int[K];
        int[] topOrig = new int[K];
        for (int i = 0; i < K; i++) {
            topDist[i] = Long.MAX_VALUE;
            topIdx[i] = -1;
            topOrig[i] = Integer.MAX_VALUE;
        }
        int worstIdx = 0;

        int start = clusterOffsets[bestCluster];
        int end = clusterOffsets[bestCluster + 1];
        worstIdx = scanRange(start, end, query, topDist, topIdx, topOrig, worstIdx);

        for (int c = 0; c < numClusters; c++) {
            if (c == bestCluster) continue;

            long worstDist = topDist[worstIdx];
            long lb = bboxLowerBound(query, c);
            if (lb <= worstDist) {
                int cStart = clusterOffsets[c];
                int cEnd = clusterOffsets[c + 1];
                worstIdx = scanRange(cStart, cEnd, query, topDist, topIdx, topOrig, worstIdx);
            }
        }

        int fraudCount = 0;
        for (int i = 0; i < K; i++) {
            if (topIdx[i] >= 0 && labels[topIdx[i]] == 1) fraudCount++;
        }
        return fraudCount;
    }

    private long bboxLowerBound(short[] query, int c) {
        int cOff = c * DIMS;
        long lb = 0;
        for (int d = 0; d < DIMS; d++) {
            short qVal = query[d];
            short minVal = bboxMin[cOff + d];
            short maxVal = bboxMax[cOff + d];
            int diff;
            if (qVal < minVal) diff = minVal - qVal;
            else if (qVal > maxVal) diff = qVal - maxVal;
            else diff = 0;
            lb += (long) diff * diff;
        }
        return lb;
    }

    private int scanRange(int start, int end, short[] query,
                          long[] topDist, int[] topIdx, int[] topOrig, int worstIdx) {
        long worstDist = topDist[worstIdx];
        int worstOrig = topOrig[worstIdx];

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

            if (!tooFar) {
                int oid = origIds[i];
                if (dist < worstDist || (dist == worstDist && oid < worstOrig)) {
                    topDist[worstIdx] = dist;
                    topIdx[worstIdx] = i;
                    topOrig[worstIdx] = oid;

                    worstIdx = 0;
                    worstDist = topDist[0];
                    worstOrig = topOrig[0];
                    for (int j = 1; j < K; j++) {
                        long jd = topDist[j];
                        if (jd > worstDist || (jd == worstDist && topOrig[j] > worstOrig)) {
                            worstIdx = j;
                            worstDist = jd;
                            worstOrig = topOrig[j];
                        }
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

        int[] origIds = new int[numVectors];
        readInts(dis, origIds);

        short[] bboxMin = new short[numClusters * dims];
        readShorts(dis, bboxMin);

        short[] bboxMax = new short[numClusters * dims];
        readShorts(dis, bboxMax);

        return new IVFIndex(numClusters, numVectors, quantCentroids,
                clusterOffsets, vectors, labels, origIds, bboxMin, bboxMax);
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

    private static void readInts(DataInputStream dis, int[] arr) throws Exception {
        int chunkSize = 65536;
        byte[] buf = new byte[chunkSize * 4];
        int offset = 0;
        while (offset < arr.length) {
            int toRead = Math.min(chunkSize, arr.length - offset);
            dis.readFully(buf, 0, toRead * 4);
            ByteBuffer.wrap(buf, 0, toRead * 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .asIntBuffer()
                    .get(arr, offset, toRead);
            offset += toRead;
        }
    }
}
