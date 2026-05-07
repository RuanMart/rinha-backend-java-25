FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
COPY pom.xml .
COPY src src
COPY data data
RUN mvn -q -T 1C package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=builder /build/target/rinha-2026-api-1.0.0.jar /app/app.jar
COPY --from=builder /build/src/main/resources/index.bin /app/index.bin

EXPOSE 8080

CMD ["java", \
     "-server", \
     "--add-modules", "jdk.incubator.vector", \
     "--add-opens", "java.base/sun.misc=ALL-UNNAMED", \
     "--sun-misc-unsafe-memory-access=allow", \
     "-XX:+UseSerialGC", \
     "-Xms80m", "-Xmx80m", \
     "-XX:ReservedCodeCacheSize=32m", \
     "-XX:+DisableExplicitGC", \
     "-XX:MetaspaceSize=16m", \
     "-XX:MaxMetaspaceSize=32m", \
     "-Xss256k", \
     "-XX:MaxDirectMemorySize=4m", \
     "-Dio.netty.allocator.type=unpooled", \
     "-Dio.netty.recycler.maxCapacityPerThread=0", \
     "-Dio.netty.allocator.numHeapArenas=2", \
     "-Dio.netty.allocator.numDirectArenas=0", \
     "-cp", "/app/app.jar", "rinha.Main"]
