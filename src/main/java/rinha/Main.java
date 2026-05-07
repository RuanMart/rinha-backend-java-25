package rinha;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.util.CharsetUtil;
import rinha.config.Config;
import rinha.search.IVFIndex;
import rinha.vector.Vectorizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class Main {

    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    private static final ByteBuf[] HTTP_OK = buildOkResponses();

    private static ByteBuf[] buildOkResponses() {
        String[] bodies = {
                "{\"approved\":true,\"fraud_score\":0.0}",
                "{\"approved\":true,\"fraud_score\":0.2}",
                "{\"approved\":true,\"fraud_score\":0.4}",
                "{\"approved\":false,\"fraud_score\":0.6}",
                "{\"approved\":false,\"fraud_score\":0.8}",
                "{\"approved\":false,\"fraud_score\":1.0}",
        };
        ByteBuf[] r = new ByteBuf[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            r[i] = encodeResponse(200, "OK", "application/json", bodies[i]);
        }
        return r;
    }

    private static final ByteBuf HTTP_BAD_REQUEST = encodeResponse(400, "Bad Request", "text/plain", "Bad Request");
    private static final ByteBuf HTTP_NOT_FOUND = encodeResponse(404, "Not Found", "text/plain", "Not Found");
    private static final ByteBuf HTTP_METHOD_NOT_ALLOWED = encodeResponse(405, "Method Not Allowed", "text/plain", "Method Not Allowed");
    private static final ByteBuf HTTP_SERVICE_UNAVAILABLE = encodeResponse(503, "Service Unavailable", "text/plain", "Starting");
    private static final ByteBuf HTTP_READY_OK = encodeResponse(200, "OK", "text/plain", "OK");

    private static ByteBuf encodeResponse(int status, String statusText,
                                          String contentType, String body) {
        String raw = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: keep-alive\r\n\r\n"
                + body;
        byte[] bytes = raw.getBytes(CharsetUtil.US_ASCII);
        return Unpooled.unreleasableBuffer(
                Unpooled.directBuffer(bytes.length).writeBytes(bytes));
    }

    static IVFIndex STORE;
    static volatile boolean READY = false;

    public static void main(String[] args) throws Exception {
        Files.deleteIfExists(Paths.get("/tmp/ready"));

        boolean useEpoll = Epoll.isAvailable();
        IoHandlerFactory ioFactory = useEpoll
                ? EpollIoHandler.newFactory()
                : NioIoHandler.newFactory();
        Class<? extends ServerChannel> channelClass = useEpoll
                ? EpollServerSocketChannel.class
                : NioServerSocketChannel.class;
        System.out.println("Transport: " + (useEpoll ? "epoll" : "nio"));

        MultiThreadIoEventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, ioFactory);
        MultiThreadIoEventLoopGroup worker = new MultiThreadIoEventLoopGroup(2, ioFactory);

        Channel channel = new ServerBootstrap()
                .group(boss, worker)
                .channel(channelClass)
                .option(ChannelOption.SO_BACKLOG, 2048)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, false)
                .childOption(ChannelOption.SO_RCVBUF, 32768)
                .childOption(ChannelOption.SO_SNDBUF, 32768)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpRequestDecoder(4096, 8192, 8192))
                                .addLast(new HttpObjectAggregator(8192))
                                .addLast(RequestHandler.INSTANCE);
                    }
                })
                .bind(PORT)
                .sync()
                .channel();

        try {
            String binPath = System.getenv().getOrDefault("INDEX_PATH", "/app/index.bin");
            long t = System.currentTimeMillis();
            System.out.println("Loading index from: " + binPath);
            STORE = IVFIndex.load(binPath);

            String nprobeEnv = System.getenv("NPROBE");
            if (nprobeEnv != null && !nprobeEnv.isEmpty()) {
                int overrideNprobe = Integer.parseInt(nprobeEnv.trim());
                System.out.printf("NPROBE env override: %d → %d%n", STORE.defaultNprobe, overrideNprobe);
                STORE.defaultNprobe = overrideNprobe;
            }
            System.out.printf("Loaded index (C=%d, nprobe=%d) in %d ms%n",
                    STORE.numClusters, STORE.defaultNprobe,
                    System.currentTimeMillis() - t);

            t = System.currentTimeMillis();
            System.out.println("Running JIT warmup (3 × 10k queries per worker)...");
            java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(2);
            for (int w = 0; w < 2; w++) {
                worker.next().submit(() -> {
                    warmup(STORE);
                    warmupParser();
                    latch.countDown();
                });
            }
            latch.await();
            System.out.printf("Warmup done in %d ms%n", System.currentTimeMillis() - t);

            READY = true;
            STORE.ready = true;
            Files.writeString(Paths.get("/tmp/ready"), "OK");
            System.out.println("Ready.");

            channel.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    private static void warmup(IVFIndex store) {
        float[] vec = new float[Config.DIMS];
        long seed = 0x9E3779B97F4A7C15L;
        int nprobe = store.defaultNprobe;

        for (int i = 0; i < 10_000; i++) {
            for (int d = 0; d < Config.DIMS; d++) {
                seed ^= seed << 13;
                seed ^= seed >>> 7;
                seed ^= seed << 17;
                vec[d] = Float.intBitsToFloat(((int) (seed >>> 41) & 0x007FFFFF) | 0x3F800000) - 1f;
            }
            store.search(vec);
        }
    }

    private static void warmupParser() {
        String body =
                "{\"transaction\":{\"amount\":100.0,\"installments\":1,"
                        + "\"requested_at\":\"2025-01-15T14:30:00Z\"},"
                        + "\"customer\":{\"avg_amount\":200.0,\"tx_count_24h\":3,"
                        + "\"known_merchants\":[\"merch-abc\"]},"
                        + "\"merchant\":{\"id\":\"merch-abc\",\"mcc\":\"5812\","
                        + "\"avg_amount\":150.0},"
                        + "\"terminal\":{\"is_online\":false,\"card_present\":true,"
                        + "\"km_from_home\":5.0},"
                        + "\"last_transaction\":{\"timestamp\":\"2025-01-15T12:00:00Z\","
                        + "\"km_from_current\":3.0}}";
        byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        float[] scratch = new float[Config.DIMS];
        for (int i = 0; i < 10_000; i++) {
            Vectorizer.vectorize(bytes, bytes.length, scratch);
        }
    }

    @ChannelHandler.Sharable
    static final class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        static final RequestHandler INSTANCE = new RequestHandler();
        private static final ThreadLocal<float[]> TL_VEC =
                ThreadLocal.withInitial(() -> new float[Config.DIMS]);
        private static final ThreadLocal<byte[]> TL_BYTES =
                ThreadLocal.withInitial(() -> new byte[2048]);

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            if (!READY) {
                ctx.writeAndFlush(HTTP_SERVICE_UNAVAILABLE.duplicate(), ctx.voidPromise());
                return;
            }

            String uri = req.uri();
            HttpMethod m = req.method();

            if ("/fraud-score".equals(uri)) {
                if (HttpMethod.POST.equals(m)) {
                    handleFraudScore(ctx, req);
                } else {
                    ctx.writeAndFlush(HTTP_METHOD_NOT_ALLOWED.duplicate(), ctx.voidPromise());
                }
                return;
            }

            if ("/ready".equals(uri)) {
                if (HttpMethod.GET.equals(m)) {
                    ctx.writeAndFlush(HTTP_READY_OK.duplicate(), ctx.voidPromise());
                } else {
                    ctx.writeAndFlush(HTTP_METHOD_NOT_ALLOWED.duplicate(), ctx.voidPromise());
                }
                return;
            }

            ctx.writeAndFlush(HTTP_NOT_FOUND.duplicate(), ctx.voidPromise());
        }

        private static void handleFraudScore(ChannelHandlerContext ctx, FullHttpRequest req) {
            ByteBuf content = req.content();
            int len = content.readableBytes();
            byte[] b = TL_BYTES.get();
            if (len > b.length) {
                b = new byte[(len + 511) & ~511];
                TL_BYTES.set(b);
            }
            content.getBytes(content.readerIndex(), b, 0, len);

            try {
                float[] vec = TL_VEC.get();
                Vectorizer.vectorize(b, len, vec);
                int fraudCount = STORE.search(vec);
                ctx.writeAndFlush(HTTP_OK[fraudCount].duplicate(), ctx.voidPromise());
            } catch (Exception e) {
                ctx.writeAndFlush(HTTP_OK[0].duplicate(), ctx.voidPromise());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
