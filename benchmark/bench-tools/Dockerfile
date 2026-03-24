FROM eclipse-temurin:21-jdk-alpine AS base

# System packages
RUN apk add --no-cache \
    bash coreutils bc gawk \
    curl wget \
    nodejs npm \
    python3 py3-pip py3-setuptools py3-wheel \
    php83 php83-phar php83-json php83-mbstring php83-openssl php83-curl php83-iconv php83-zip \
    zip \
    skopeo \
    util-linux  # for flock

# Maven
ARG MAVEN_VERSION=3.9.9
RUN wget -q "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -O /tmp/maven.tar.gz && \
    tar -xzf /tmp/maven.tar.gz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn && \
    rm /tmp/maven.tar.gz

# Twine (PyPI upload tool)
RUN pip3 install --break-system-packages twine

# Composer
RUN wget -q https://getcomposer.org/installer -O /tmp/composer-setup.php && \
    php83 /tmp/composer-setup.php --install-dir=/usr/local/bin --filename=composer && \
    rm /tmp/composer-setup.php
# Symlink php for convenience
RUN ln -sf /usr/bin/php83 /usr/bin/php

# hey — HTTP load generator (for registry throughput tests)
RUN apk add --no-cache go && \
    go install github.com/rakyll/hey@latest && \
    mv /root/go/bin/hey /usr/local/bin/hey && \
    apk del go && rm -rf /root/go /root/.cache

WORKDIR /bench

# Copy templates and scripts
COPY templates/ /bench/templates/
COPY generate-projects.sh /bench/generate-projects.sh
COPY run-bench.sh /bench/run-bench.sh
COPY aggregate.sh /bench/aggregate.sh
RUN chmod +x /bench/*.sh

# Generate projects at build time (default 200 workers)
ARG NUM_WORKERS=200
RUN /bench/generate-projects.sh "$NUM_WORKERS"

ENTRYPOINT ["/bench/run-bench.sh"]
