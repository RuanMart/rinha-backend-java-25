package rinha;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import rinha.config.Config;
import rinha.model.FraudRequest;
import rinha.model.FraudResponse;
import rinha.search.IVFIndex;
import rinha.vector.Vectorizer;

import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class Main {

    private static final Gson GSON = new GsonBuilder().create();
    private static final byte[] FALLBACK_JSON = "{\"approved\":true,\"fraud_score\":0.0}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HTTP_OK_HDR = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SEP = {'\r', '\n', '\r', '\n'};
    private static final byte[] READY_OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.UTF_8);
    private static final byte[] READY_FAIL = "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_FOUND = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8);

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

        try (var ss = new ServerSocket(Config.PORT, 8192)) {
            System.out.println("Server listening on port " + Config.PORT);
            while (true) {
                Socket s = ss.accept();
                Thread.startVirtualThread(() -> handleConnection(s));
            }
        }
    }

    private static void handleConnection(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            var in = socket.getInputStream();
            var out = new BufferedOutputStream(socket.getOutputStream());

            byte[] buf = new byte[16384];
            int pos = 0;

            for (;;) {
                int hdrEnd = find(buf, 0, pos, SEP);
                while (hdrEnd < 0) {
                    if (pos >= buf.length) return;
                    int n = in.read(buf, pos, buf.length - pos);
                    if (n < 0) return;
                    int from = Math.max(0, pos - 3);
                    pos += n;
                    hdrEnd = find(buf, from, pos, SEP);
                }

                int bodyStart = hdrEnd + 4;
                int clen = contentLength(buf, hdrEnd);

                while (pos < bodyStart + clen) {
                    if (pos >= buf.length) return;
                    int n = in.read(buf, pos, buf.length - pos);
                    if (n < 0) return;
                    pos += n;
                }

                int sp1 = -1, sp2 = -1;
                for (int i = 0; i < hdrEnd; i++) {
                    if (buf[i] == ' ') {
                        if (sp1 < 0) sp1 = i;
                        else { sp2 = i; break; }
                    }
                }
                if (sp1 < 0 || sp2 < 0) return;

                int pathLen = sp2 - sp1 - 1;

                if (pathLen == 12 && buf[sp1 + 2] == 'f') {
                    if (sp1 == 4 && buf[0] == 'P') {
                        processFraud(buf, bodyStart, clen, out);
                    } else {
                        out.write(NOT_ALLOWED);
                        return;
                    }
                } else if (pathLen == 6 && buf[sp1 + 2] == 'r') {
                    if (index.isReady()) {
                        out.write(READY_OK);
                    } else {
                        out.write(READY_FAIL);
                        return;
                    }
                } else {
                    out.write(NOT_FOUND);
                    return;
                }

                out.flush();

                int consumed = bodyStart + clen;
                int rem = pos - consumed;
                if (rem > 0) System.arraycopy(buf, consumed, buf, 0, rem);
                pos = rem;
            }
        } catch (SocketTimeoutException ignored) {
        } catch (Exception ignored) {
        }
    }

    private static void processFraud(byte[] buf, int off, int len, java.io.OutputStream out) throws Exception {
        byte[] jsonBytes;
        try {
            FraudRequest req = GSON.fromJson(new String(buf, off, len, StandardCharsets.UTF_8), FraudRequest.class);
            double score = index.searchCentroidScore(vectorizer.vectorize(req));
            jsonBytes = GSON.toJson(new FraudResponse(score < Config.FRAUD_THRESHOLD, score)).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            jsonBytes = FALLBACK_JSON;
        }
        out.write(HTTP_OK_HDR);
        out.write(Integer.toString(jsonBytes.length).getBytes(StandardCharsets.UTF_8));
        out.write(SEP);
        out.write(jsonBytes);
    }

    private static int find(byte[] buf, int from, int limit, byte[] pat) {
        outer:
        for (int i = from; i <= limit - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (buf[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int contentLength(byte[] buf, int hdrEnd) {
        int i = 0;
        while (i < hdrEnd - 1) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') { i += 2; break; }
            i++;
        }
        while (i <= hdrEnd - 16) {
            if (iMatch(buf, i, "content-length:")) {
                i += 16;
                while (i < hdrEnd && buf[i] == ' ') i++;
                int v = 0;
                while (i < hdrEnd && buf[i] >= '0' && buf[i] <= '9') v = v * 10 + (buf[i++] - '0');
                return v;
            }
            while (i < hdrEnd - 1) {
                if (buf[i] == '\r' && buf[i + 1] == '\n') { i += 2; break; }
                i++;
            }
        }
        return 0;
    }

    private static boolean iMatch(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) {
            if ((b[o + i] | 0x20) != (s.charAt(i) | 0x20)) return false;
        }
        return true;
    }
}
