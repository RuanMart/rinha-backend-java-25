package rinha;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import rinha.config.Config;
import rinha.model.FraudRequest;
import rinha.model.FraudResponse;
import rinha.search.IVFIndex;
import rinha.vector.Vectorizer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class Main {

    private static final Gson GSON = new GsonBuilder().create();
    private static Config config;
    private static Vectorizer vectorizer;
    private static IVFIndex index;

    public static void main(String[] args) throws Exception {
        System.out.println("Initializing...");

        config = new Config();
        System.out.println("Config loaded (MCC risk entries: " + config.mccRisk.size() + ")");

        vectorizer = new Vectorizer(config);

        System.out.println("Loading IVF index...");
        long start = System.currentTimeMillis();
        try (var is = Main.class.getClassLoader().getResourceAsStream("index.bin")) {
            if (is == null) {
                throw new RuntimeException("index.bin not found in classpath. Run DataPreprocessor first.");
            }
            index = IVFIndex.load(is);
        }
        index.markReady();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("IVF index loaded in " + elapsed + "ms");

        HttpServer server = HttpServer.create(new InetSocketAddress(Config.PORT), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/ready", Main::handleReady);
        server.createContext("/fraud-score", Main::handleFraudScore);

        server.start();
        System.out.println("Server listening on port " + Config.PORT);
    }

    private static void handleReady(HttpExchange exchange) throws IOException {
        try {
            if (index.isReady()) {
                sendResponse(exchange, 200, "OK");
            } else {
                sendResponse(exchange, 503, "Not Ready");
            }
        } finally {
            exchange.close();
        }
    }

    private static void handleFraudScore(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            FraudRequest request;
            try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                request = GSON.fromJson(reader, FraudRequest.class);
            }

            if (request == null || request.transaction == null) {
                sendResponse(exchange, 400, "Bad Request");
                return;
            }

            double[] vector = vectorizer.vectorize(request);
            IVFIndex.SearchResult result = index.search(vector, Config.IVF_NPROBE, Config.KNN_K);

            FraudResponse response = new FraudResponse(result.approved(), result.fraudScore());
            String json = GSON.toJson(response);

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            try {
                FraudResponse fallback = new FraudResponse(true, 0.0);
                byte[] bytes = GSON.toJson(fallback).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
