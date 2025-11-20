FROM eclipse-temurin:21-jre-alpine
ARG JAR_FILE
ARG APM_VERSION=1.55.1

# Install curl for downloading Elastic APM agent + jattach for programmatic attachment (jmap + jstack + jcmd + jinfo)
RUN apk add --no-cache curl jattach


ENV JVM_ARGS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled \
  -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/artipie/logs/dumps/heapdump.hprof \
  -Xlog:gc*:file=/var/artipie/logs/gc.log:time,uptime:filecount=5,filesize=100m \
  -Djava.io.tmpdir=/var/artipie/cache/tmp \
  -Dvertx.cacheDirBase=/var/artipie/cache/tmp \
  -Dio.netty.leakDetection.level=simple"

RUN addgroup -g 2020 -S artipie && \
    adduser -u 2021 -S -G artipie -s /sbin/nologin artipie && \
    mkdir -p /etc/artipie /usr/lib/artipie /var/artipie/logs/dumps /var/artipie/cache/tmp /opt/apm && \
    chown -R artipie:artipie /etc/artipie /usr/lib/artipie /var/artipie && \
    curl -L "https://repo1.maven.org/maven2/co/elastic/apm/elastic-apm-agent/${APM_VERSION}/elastic-apm-agent-${APM_VERSION}.jar" \
    -o /opt/apm/elastic-apm-agent.jar && \
    chown artipie:artipie /opt/apm/elastic-apm-agent.jar

ENV TMPDIR=/var/artipie/cache/tmp
ENV ARTIPIE_VERSION=1.20.1

USER 2021:2020

COPY target/dependency  /usr/lib/artipie/lib
COPY target/${JAR_FILE} /usr/lib/artipie/artipie.jar

VOLUME /var/artipie /etc/artipie
WORKDIR /var/artipie

EXPOSE 8080 8086
CMD [ "sh", "-c", "java \
  -javaagent:/opt/apm/elastic-apm-agent.jar \
  $JVM_ARGS \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.security=ALL-UNNAMED \
  -cp /usr/lib/artipie/artipie.jar:/usr/lib/artipie/lib/* \
  com.artipie.VertxMain \
  --config-file=/etc/artipie/artipie.yml \
  --port=8080 \
  --api-port=8086" ]