package rinha.search;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import rinha.config.Config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class IVFIndex {

    private static final int K = 5;
    private static final int DIMS = Config.DIMS;

    private static final VectorSpecies<Byte> BYTE_SPEC = ByteVector.SPECIES_64;
    private static final VectorSpecies<Short> SHORT_SPEC = ShortVector.SPECIES_128;
    private static final VectorSpecies<Integer> INT_SPEC = IntVector.SPECIES_256;

    static final ThreadLocal<float[]> TL_DIST =
            ThreadLocal.withInitial(() -> new float[K]);
    static final ThreadLocal<int[]> TL_IDX =
            ThreadLocal.withInitial(() -> new int[K]);
    static final ThreadLocal<byte[]> TL_VEC_INT8 =
            ThreadLocal.withInitial(() -> new byte[DIMS]);
    static final ThreadLocal<float[]> TL_PROBE_DIST =
            ThreadLocal.withInitial(() -> new float[Config.IVF_MAX_NPROBE]);
    static final ThreadLocal<int[]> TL_PROBE_IDX =
            ThreadLocal.withInitial(() -> new int[Config.IVF_MAX_NPROBE]);

    final byte[] vectors;
    final byte[] labels;
    final float[] centroids;
    final int[] listOffsets;
    final int[] listSizes;
    public final int numClusters;
    public int defaultNprobe;
    final boolean soaLayout;
    byte[] bboxMinInt8;
    byte[] bboxMaxInt8;

    public volatile boolean ready = false;

    public IVFIndex(byte[] vectors, byte[] labels, float[] centroids,
                    int[] listOffsets, int[] listSizes, int numClusters,
                    int defaultNprobe, boolean soaLayout,
                    byte[] bboxMinInt8, byte[] bboxMaxInt8) {
        this.vectors = vectors;
        this.labels = labels;
        this.centroids = centroids;
        this.listOffsets = listOffsets;
        this.listSizes = listSizes;
        this.numClusters = numClusters;
        this.defaultNprobe = defaultNprobe;
        this.soaLayout = soaLayout;
        this.bboxMinInt8 = bboxMinInt8;
        this.bboxMaxInt8 = bboxMaxInt8;
    }

    public static void quantize(float[] query, byte[] out) {
        for (int i = 0; i < DIMS; i++) {
            float f = query[i];
            int q = (int) (f * 127f + (f >= 0f ? 0.5f : -0.5f));
            if (q < -127) q = -127;
            else if (q > 127) q = 127;
            out[i] = (byte) q;
        }
    }

    public int search(float[] query) {
        byte[] queryInt8 = TL_VEC_INT8.get();
        quantize(query, queryInt8);

        float[] topDist = TL_DIST.get();
        int[] topIdx = TL_IDX.get();
        topDist[0] = topDist[1] = topDist[2] = topDist[3] = topDist[4] = Float.MAX_VALUE;
        topIdx[0] = topIdx[1] = topIdx[2] = topIdx[3] = topIdx[4] = -1;

        int bound = Math.min(defaultNprobe, numClusters);

        if (bound == 1) {
            int bestC = 0;
            float bestDist = Float.MAX_VALUE;
            for (int c = 0; c < numClusters; c++) {
                float cd = centDistSqEt(query, centroids, c, bestDist);
                if (cd < bestDist) { bestDist = cd; bestC = c; }
            }
            int off = listOffsets[bestC];
            int sz = listSizes[bestC];
            if (soaLayout) {
                scanClusterSoA(queryInt8, vectors, off, sz, topDist, topIdx, Integer.MAX_VALUE);
            } else {
                int worstInt = Integer.MAX_VALUE;
                for (int li = 0, vb = off * DIMS; li < sz; li++, vb += DIMS) {
                    int d = distSqInt8(queryInt8, vectors, vb, worstInt);
                    if (d < worstInt) {
                        insert(topDist, topIdx, (float) d, off + li);
                        float w = topDist[K - 1];
                        worstInt = (w == Float.MAX_VALUE) ? Integer.MAX_VALUE : (int) w;
                    }
                }
            }
        } else {
            float[] probeDist = TL_PROBE_DIST.get();
            int[] probeIdx = TL_PROBE_IDX.get();
            for (int i = 0; i < bound; i++) { probeDist[i] = Float.MAX_VALUE; probeIdx[i] = -1; }
            float probeWorst = Float.MAX_VALUE;

            for (int c = 0; c < numClusters; c++) {
                float cd = centDistSqEt(query, centroids, c, probeWorst);
                if (cd < probeWorst) {
                    insertProbe(probeDist, probeIdx, cd, c, bound);
                    probeWorst = probeDist[bound - 1];
                }
            }

            int worstInt = Integer.MAX_VALUE;
            for (int p = 0; p < bound; p++) {
                int c = probeIdx[p];
                if (c < 0) break;

                if (bboxMinInt8 != null) {
                    int lb = bboxLbInt8(queryInt8, bboxMinInt8, bboxMaxInt8, c);
                    if (lb >= worstInt) continue;
                }

                int off = listOffsets[c];
                int sz = listSizes[c];
                if (soaLayout) {
                    worstInt = scanClusterSoA(queryInt8, vectors, off, sz, topDist, topIdx, worstInt);
                } else {
                    for (int li = 0, vb = off * DIMS; li < sz; li++, vb += DIMS) {
                        int d = distSqInt8(queryInt8, vectors, vb, worstInt);
                        if (d < worstInt) {
                            insert(topDist, topIdx, (float) d, off + li);
                            float w = topDist[K - 1];
                            worstInt = (w == Float.MAX_VALUE) ? Integer.MAX_VALUE : (int) w;
                        }
                    }
                }
            }
        }

        return fraudCount(topIdx);
    }

    private int fraudCount(int[] topIdx) {
        int count = 0;
        for (int i = 0; i < K; i++) {
            int idx = topIdx[i];
            if (idx >= 0) count += (labels[idx] == Config.FRAUD) ? 1 : 0;
        }
        return count;
    }

    private int scanClusterSoA(byte[] q, byte[] vecs, int off, int sz,
                               float[] topDist, int[] topIdx, int worstInt) {
        int clusterBase = off * DIMS;
        int blocks = sz >> 3;
        int rem = sz & 7;

        for (int b = 0; b < blocks; b++) {
            int blockBase = clusterBase + b * DIMS * 8;
            IntVector acc = IntVector.zero(INT_SPEC);
            for (int d = 0; d < DIMS; d++) {
                ByteVector dbV = ByteVector.fromArray(BYTE_SPEC, vecs, blockBase + d * 8);
                ShortVector dbS = (ShortVector) dbV.convertShape(VectorOperators.B2S, SHORT_SPEC, 0);
                ShortVector qS = ShortVector.broadcast(SHORT_SPEC, (short) q[d]);
                IntVector diffI = (IntVector) qS.sub(dbS).convertShape(VectorOperators.S2I, INT_SPEC, 0);
                acc = acc.add(diffI.mul(diffI));
            }

            int vecBase = off + (b << 3);
            for (int lane = 0; lane < 8; lane++) {
                int dist = acc.lane(lane);
                if (dist < worstInt) {
                    insert(topDist, topIdx, (float) dist, vecBase + lane);
                    float w = topDist[K - 1];
                    worstInt = (w == Float.MAX_VALUE) ? Integer.MAX_VALUE : (int) w;
                }
            }
        }

        int remBase = clusterBase + blocks * DIMS * 8;
        int remStart = off + (blocks << 3);
        for (int i = 0; i < rem; i++) {
            int dist = distSqInt8(q, vecs, remBase + i * DIMS, worstInt);
            if (dist < worstInt) {
                insert(topDist, topIdx, (float) dist, remStart + i);
                float w = topDist[K - 1];
                worstInt = (w == Float.MAX_VALUE) ? Integer.MAX_VALUE : (int) w;
            }
        }

        return worstInt;
    }

    private static float centDistSqEt(float[] q, float[] c, int ci, float threshold) {
        int b = ci * DIMS;
        float sum = 0, d;
        d = q[0] - c[b]; sum += d * d; if (sum >= threshold) return sum;
        d = q[1] - c[b + 1]; sum += d * d; if (sum >= threshold) return sum;
        d = q[2] - c[b + 2]; sum += d * d; if (sum >= threshold) return sum;
        d = q[3] - c[b + 3]; sum += d * d; if (sum >= threshold) return sum;
        d = q[4] - c[b + 4]; sum += d * d; if (sum >= threshold) return sum;
        d = q[5] - c[b + 5]; sum += d * d; if (sum >= threshold) return sum;
        d = q[6] - c[b + 6]; sum += d * d; if (sum >= threshold) return sum;
        d = q[7] - c[b + 7]; sum += d * d; if (sum >= threshold) return sum;
        d = q[8] - c[b + 8]; sum += d * d; if (sum >= threshold) return sum;
        d = q[9] - c[b + 9]; sum += d * d; if (sum >= threshold) return sum;
        d = q[10] - c[b + 10]; sum += d * d; if (sum >= threshold) return sum;
        d = q[11] - c[b + 11]; sum += d * d; if (sum >= threshold) return sum;
        d = q[12] - c[b + 12]; sum += d * d; if (sum >= threshold) return sum;
        d = q[13] - c[b + 13]; sum += d * d;
        return sum;
    }

    private static int distSqInt8(byte[] q, byte[] vecs, int base, int threshold) {
        ShortVector qS = (ShortVector) ByteVector.fromArray(BYTE_SPEC, q, 0)
                .convertShape(VectorOperators.B2S, SHORT_SPEC, 0);
        ShortVector vS = (ShortVector) ByteVector.fromArray(BYTE_SPEC, vecs, base)
                .convertShape(VectorOperators.B2S, SHORT_SPEC, 0);
        IntVector dI = (IntVector) qS.sub(vS)
                .convertShape(VectorOperators.S2I, INT_SPEC, 0);
        int sum = dI.mul(dI).reduceLanes(VectorOperators.ADD);
        if (sum >= threshold) return sum;
        int d;
        d = q[8] - vecs[base + 8]; sum += d * d; if (sum >= threshold) return sum;
        d = q[9] - vecs[base + 9]; sum += d * d; if (sum >= threshold) return sum;
        d = q[10] - vecs[base + 10]; sum += d * d; if (sum >= threshold) return sum;
        d = q[11] - vecs[base + 11]; sum += d * d; if (sum >= threshold) return sum;
        d = q[12] - vecs[base + 12]; sum += d * d; if (sum >= threshold) return sum;
        d = q[13] - vecs[base + 13]; sum += d * d;
        return sum;
    }

    private static int bboxLbInt8(byte[] q, byte[] bmin, byte[] bmax, int c) {
        int b = c * DIMS;
        int sum = 0;
        int qi, lo, hi, diff;
        qi = (int) q[0]; lo = (int) bmin[b]; hi = (int) bmax[b]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[1]; lo = (int) bmin[b + 1]; hi = (int) bmax[b + 1]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[2]; lo = (int) bmin[b + 2]; hi = (int) bmax[b + 2]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[3]; lo = (int) bmin[b + 3]; hi = (int) bmax[b + 3]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[4]; lo = (int) bmin[b + 4]; hi = (int) bmax[b + 4]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[5]; lo = (int) bmin[b + 5]; hi = (int) bmax[b + 5]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[6]; lo = (int) bmin[b + 6]; hi = (int) bmax[b + 6]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[7]; lo = (int) bmin[b + 7]; hi = (int) bmax[b + 7]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[8]; lo = (int) bmin[b + 8]; hi = (int) bmax[b + 8]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[9]; lo = (int) bmin[b + 9]; hi = (int) bmax[b + 9]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[10]; lo = (int) bmin[b + 10]; hi = (int) bmax[b + 10]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[11]; lo = (int) bmin[b + 11]; hi = (int) bmax[b + 11]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[12]; lo = (int) bmin[b + 12]; hi = (int) bmax[b + 12]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        qi = (int) q[13]; lo = (int) bmin[b + 13]; hi = (int) bmax[b + 13]; if (qi < lo) { diff = lo - qi; sum += diff * diff; } else if (qi > hi) { diff = qi - hi; sum += diff * diff; }
        return sum;
    }

    private static void insertProbe(float[] topDist, int[] topIdx, float d, int idx, int k) {
        int pos = k - 1;
        while (pos > 0 && d < topDist[pos - 1]) {
            topDist[pos] = topDist[pos - 1];
            topIdx[pos] = topIdx[pos - 1];
            pos--;
        }
        topDist[pos] = d;
        topIdx[pos] = idx;
    }

    private static void insert(float[] topDist, int[] topIdx, float d, int idx) {
        int pos = K - 1;
        while (pos > 0 && d < topDist[pos - 1]) {
            topDist[pos] = topDist[pos - 1];
            topIdx[pos] = topIdx[pos - 1];
            pos--;
        }
        topDist[pos] = d;
        topIdx[pos] = idx;
    }

    public static IVFIndex load(String path) throws IOException {
        try (FileChannel fc = FileChannel.open(Paths.get(path), StandardOpenOption.READ)) {
            ByteBuffer hdr = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            readFully(fc, hdr);
            hdr.flip();

            int magic = hdr.getInt();
            int version = hdr.getInt();
            int count = hdr.getInt();
            int dims = hdr.getInt();
            int clusters = hdr.getInt();
            int nprobe = hdr.getInt();

            if (magic != 0x52494E48) throw new IOException("bad magic");
            if (dims != DIMS) throw new IOException("bad dims: " + dims);
            if (version != 5) throw new IOException("expected version 5, got " + version);

            byte[] vectors = new byte[count * DIMS];
            readFully(fc, ByteBuffer.wrap(vectors));

            byte[] labels = new byte[count];
            readFully(fc, ByteBuffer.wrap(labels));

            float[] centroids = new float[clusters * DIMS];
            ByteBuffer centBuf = ByteBuffer.allocateDirect(clusters * DIMS * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            readFully(fc, centBuf);
            centBuf.flip();
            centBuf.asFloatBuffer().get(centroids);

            int[] listSizes = new int[clusters];
            ByteBuffer szBuf = ByteBuffer.allocateDirect(clusters * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            readFully(fc, szBuf);
            szBuf.flip();
            szBuf.asIntBuffer().get(listSizes);

            int[] listOffsets = new int[clusters];
            for (int c = 1; c < clusters; c++) {
                listOffsets[c] = listOffsets[c - 1] + listSizes[c - 1];
            }

            IVFIndex index = new IVFIndex(vectors, labels, centroids, listOffsets,
                    listSizes, clusters, nprobe, true, null, null);

            index.buildBboxes();
            return index;
        }
    }

    private void buildBboxes() {
        bboxMinInt8 = new byte[numClusters * DIMS];
        bboxMaxInt8 = new byte[numClusters * DIMS];
        for (int i = 0, n = numClusters * DIMS; i < n; i++) {
            bboxMinInt8[i] = (byte) 127;
            bboxMaxInt8[i] = (byte) -127;
        }
        for (int c = 0; c < numClusters; c++) {
            int off = listOffsets[c];
            int sz = listSizes[c];
            int bbase = c * DIMS;
            int clusterBase = off * DIMS;
            int blocks = sz >> 3;
            int rem = sz & 7;
            for (int b = 0; b < blocks; b++) {
                for (int d = 0; d < DIMS; d++) {
                    int pos = clusterBase + b * DIMS * 8 + d * 8;
                    for (int lane = 0; lane < 8; lane++) {
                        byte v = vectors[pos + lane];
                        if (v < bboxMinInt8[bbase + d]) bboxMinInt8[bbase + d] = v;
                        if (v > bboxMaxInt8[bbase + d]) bboxMaxInt8[bbase + d] = v;
                    }
                }
            }
            int remBase = clusterBase + blocks * DIMS * 8;
            for (int i = 0; i < rem; i++) {
                int vbase = remBase + i * DIMS;
                for (int d = 0; d < DIMS; d++) {
                    byte v = vectors[vbase + d];
                    if (v < bboxMinInt8[bbase + d]) bboxMinInt8[bbase + d] = v;
                    if (v > bboxMaxInt8[bbase + d]) bboxMaxInt8[bbase + d] = v;
                }
            }
        }
    }

    private static void readFully(FileChannel fc, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = fc.read(buf);
            if (n == -1) throw new IOException("unexpected EOF");
        }
    }
}
