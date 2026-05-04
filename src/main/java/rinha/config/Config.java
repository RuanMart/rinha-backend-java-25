package rinha.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Config {

    public static final int PORT = 8080;
    public static final int KNN_K = 5;
    public static final double FRAUD_THRESHOLD = 0.6;
    public static final int IVF_NPROBE = 8;
    public static final int IVF_NUM_CLUSTERS = 200;
    public static final int IVF_KMEANS_ITERATIONS = 10;
    public static final int DIMENSIONS = 14;

    public static final double MAX_AMOUNT = 10000.0;
    public static final double MAX_INSTALLMENTS = 12.0;
    public static final double AMOUNT_VS_AVG_RATIO = 10.0;
    public static final double MAX_MINUTES = 1440.0;
    public static final double MAX_KM = 1000.0;
    public static final double MAX_TX_COUNT_24H = 20.0;
    public static final double MAX_MERCHANT_AVG_AMOUNT = 10000.0;

    public final Map<String, Double> mccRisk;

    public Config() {
        Map<String, Double> loaded = loadMccRisk();
        this.mccRisk = Collections.unmodifiableMap(loaded);
    }

    private Map<String, Double> loadMccRisk() {
        try (var reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("mcc_risk.json"))) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> map = new Gson().fromJson(reader, type);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load mcc_risk.json", e);
        }
    }

    public double getMccRisk(String mcc) {
        return mccRisk.getOrDefault(mcc, 0.5);
    }

    public static double clamp(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
