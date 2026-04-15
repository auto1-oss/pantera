FROM eclipse-temurin:21-jre-alpine
ARG JAR_FILE
ARG APM_VERSION=1.55.4

# Install curl for downloading Elastic APM agent + jattach for programmatic attachment (jmap + jstack + jcmd + jinfo)
RUN apk add --no-cache curl jattach

ENV JVM_ARGS="-XX:+UseG1GC -XX:MaxGCPauseMillis=300 \
  -XX:G1HeapRegionSize=16m \
  -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled \
  -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/pantera/logs/dumps/heapdump.hprof \
  -Xlog:gc*:file=/var/pantera/logs/gc.log:time,uptime:filecount=5,filesize=100m \
  -Djava.io.tmpdir=/var/pantera/cache/tmp \
  -Dvertx.cacheDirBase=/var/pantera/cache/tmp \
  -Dio.netty.allocator.maxOrder=11 \
  -Dio.netty.leakDetection.level=simple"


RUN addgroup -g 2020 -S pantera && \
    adduser -u 2021 -S -G pantera -s /sbin/nologin pantera && \
    mkdir -p /etc/pantera /usr/lib/pantera /var/pantera/logs/dumps /var/pantera/cache/tmp /opt/apm && \
    chown -R pantera:pantera /etc/pantera /usr/lib/pantera /var/pantera && \
    curl -L "https://repo1.maven.org/maven2/co/elastic/apm/elastic-apm-agent/${APM_VERSION}/elastic-apm-agent-${APM_VERSION}.jar" \
    -o /opt/apm/elastic-apm-agent.jar && \
    chown pantera:pantera /opt/apm/elastic-apm-agent.jar

ENV TMPDIR=/var/pantera/cache/tmp
ENV PANTERA_VERSION=2.1.3

USER 2021:2020

COPY target/dependency  /usr/lib/pantera/lib
COPY target/${JAR_FILE} /usr/lib/pantera/pantera.jar

VOLUME /var/pantera /etc/pantera
WORKDIR /var/pantera

EXPOSE 8080 8086

# Default ulimit values (will be raised to Docker's hard limit at runtime)
ENV ULIMIT_NOFILE=1048576
ENV ULIMIT_NPROC=65536

CMD [ "sh", "-c", "\
  # Raise soft limits to hard limits (or configured values, whichever is lower) \n\
  ulimit -n ${ULIMIT_NOFILE} 2>/dev/null || ulimit -n $(ulimit -Hn) 2>/dev/null || true && \
  ulimit -u ${ULIMIT_NPROC} 2>/dev/null || ulimit -u $(ulimit -Hu) 2>/dev/null || true && \
  echo 'Ulimits - nofile: '$(ulimit -n)' nproc: '$(ulimit -u) && \
  exec java \
  -javaagent:/opt/apm/elastic-apm-agent.jar \
  $JVM_ARGS \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.security=ALL-UNNAMED \
  -cp /usr/lib/pantera/pantera.jar:/usr/lib/pantera/lib/* \
  com.auto1.pantera.VertxMain \
  --config-file=/etc/pantera/pantera.yml \
  --port=8080 \
  --api-port=8086" ]
