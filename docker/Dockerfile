FROM ghcr.io/graalvm/native-image-community:25 AS builder

WORKDIR /build

COPY pom.xml .
COPY src src
COPY data data

RUN microdnf install -y findutils

ENV MAVEN_VERSION=3.9.9
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz | tar xz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

ENV MAVEN_OPTS="-Xmx4g"

RUN mvn -B package -DskipTests

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends libstdc++6 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/target/rinha-2026-api ./app

EXPOSE 8080

CMD ["./app", "--enable-preview", "-R:MaxHeapSize=120m"]
