package rinha.config;

public final class Config {

    public static final int PORT = 8080;
    public static final int DIMS = 14;
    public static final int IVF_NUM_CLUSTERS = 512;
    public static final int IVF_KMEANS_ITERATIONS = 30;
    public static final int IVF_NPROBE = 8;
    public static final int IVF_MAX_NPROBE = 128;
    public static final byte FRAUD = 1;
    public static final byte LEGIT = 0;

    public static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float mccRisk(int mcc) {
        switch (mcc) {
            case 5411: return 0.15f;
            case 5812: return 0.30f;
            case 5912: return 0.20f;
            case 5944: return 0.45f;
            case 7801: return 0.80f;
            case 7802: return 0.75f;
            case 7995: return 0.85f;
            case 4511: return 0.35f;
            case 5311: return 0.25f;
            case 5999: return 0.50f;
            default:   return 0.50f;
        }
    }
}
