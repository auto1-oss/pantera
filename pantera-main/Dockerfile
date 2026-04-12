FROM eclipse-temurin:21-jre-alpine
ARG JAR_FILE
ARG APM_VERSION=1.55.4
ARG TRIVY_VERSION=0.69.3
ARG GRYPE_VERSION=0.110.0

# Install curl, jattach, and vulnerability scanners (Trivy + Grype).
# Both are static Go binaries — no glibc required on Alpine.
# Trivy asset names use a hyphen (Linux-64bit), Grype uses underscore (linux_amd64).
RUN apk add --no-cache curl jattach && \
    ARCH="$(uname -m)" && \
    case "${ARCH}" in \
      x86_64)  TRIVY_ARCH="Linux-64bit" ; GRYPE_ARCH="amd64" ;; \
      aarch64) TRIVY_ARCH="Linux-ARM64" ; GRYPE_ARCH="arm64" ;; \
      armv7l)  TRIVY_ARCH="Linux-ARM"   ; GRYPE_ARCH="armv6" ;; \
      *)       echo "Unsupported arch: ${ARCH}" && exit 1 ;; \
    esac && \
    curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_${TRIVY_ARCH}.tar.gz" \
      -o /tmp/trivy.tar.gz && \
    tar -xzf /tmp/trivy.tar.gz -C /usr/local/bin trivy && \
    rm /tmp/trivy.tar.gz && \
    chmod 755 /usr/local/bin/trivy && \
    curl -fsSL "https://github.com/anchore/grype/releases/download/v${GRYPE_VERSION}/grype_${GRYPE_VERSION}_linux_${GRYPE_ARCH}.tar.gz" \
      -o /tmp/grype.tar.gz && \
    tar -xzf /tmp/grype.tar.gz -C /usr/local/bin grype && \
    rm /tmp/grype.tar.gz && \
    chmod 755 /usr/local/bin/grype

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
    mkdir -p /etc/pantera /usr/lib/pantera /var/pantera/logs/dumps /var/pantera/cache/tmp /opt/apm \
             /var/pantera/trivy/db /var/pantera/grype/db && \
    chown -R pantera:pantera /etc/pantera /usr/lib/pantera /var/pantera && \
    curl -L "https://repo1.maven.org/maven2/co/elastic/apm/elastic-apm-agent/${APM_VERSION}/elastic-apm-agent-${APM_VERSION}.jar" \
    -o /opt/apm/elastic-apm-agent.jar && \
    chown pantera:pantera /opt/apm/elastic-apm-agent.jar

ENV TMPDIR=/var/pantera/cache/tmp
ENV PANTERA_VERSION=2.0.7
# Vulnerability scanner DB directories — each scanner downloads its CVE database
# on first scan and caches it here. Mount named volumes for persistence.
ENV TRIVY_CACHE_DIR=/var/pantera/trivy/db
ENV GRYPE_DB_CACHE_DIR=/var/pantera/grype/db

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
