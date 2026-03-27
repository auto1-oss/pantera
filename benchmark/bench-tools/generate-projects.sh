#!/usr/bin/env bash
##
## Generates N worker projects from templates for each protocol.
## Each writer gets a unique artifact name: bench-w{ID}
##
## Usage: generate-projects.sh <num_workers>
##
set -euo pipefail

NUM_WORKERS="${1:-200}"
TEMPLATES="/bench/templates"
PROJECTS="/bench/projects"

echo "Generating ${NUM_WORKERS} worker projects per protocol..."

# ================================================================
# Maven
# ================================================================
generate_maven() {
    local dir="${PROJECTS}/maven"
    mkdir -p "${dir}/seed/src/main/java" "${dir}/read"

    # Seed project (read target) — deployed once in Phase 2
    sed "s/__WORKER_ID__/seed/g; s/__REGISTRY_HOST__/${REGISTRY_HOST:-localhost:9080}/g" \
        "${TEMPLATES}/maven/pom-seed.xml" > "${dir}/seed/pom.xml"
    # Create a minimal .jar content
    mkdir -p "${dir}/seed/src/main/java/com/bench"
    echo 'package com.bench; public class BenchRead { }' > "${dir}/seed/src/main/java/com/bench/BenchRead.java"

    # Read project — resolves bench-read:1.0 from group
    sed "s/__REGISTRY_HOST__/${REGISTRY_HOST:-localhost:9080}/g" \
        "${TEMPLATES}/maven/pom-read.xml" > "${dir}/read/pom.xml"

    # Settings.xml (shared)
    sed "s/__USERNAME__/${BENCH_USER:-pantera}/g; s/__PASSWORD__/${BENCH_PASS:-pantera}/g" \
        "${TEMPLATES}/maven/settings.xml" > "${dir}/settings.xml"

    # Writer projects
    for i in $(seq 1 "$NUM_WORKERS"); do
        local wdir="${dir}/w${i}"
        mkdir -p "${wdir}/src/main/java/com/bench"
        sed "s/__WORKER_ID__/${i}/g; s/__REGISTRY_HOST__/${REGISTRY_HOST:-localhost:9080}/g" \
            "${TEMPLATES}/maven/pom-write.xml" > "${wdir}/pom.xml"
        echo "package com.bench; public class BenchW${i} { }" > "${wdir}/src/main/java/com/bench/BenchW${i}.java"
    done
    echo "  Maven: ${NUM_WORKERS} writer projects + seed + reader"
}

# ================================================================
# npm
# ================================================================
generate_npm() {
    local dir="${PROJECTS}/npm"
    mkdir -p "${dir}/seed" "${dir}/read"

    # Seed project
    cp "${TEMPLATES}/npm/package-seed.json" "${dir}/seed/package.json"
    echo "module.exports = 'bench-read';" > "${dir}/seed/index.js"

    # Read project
    cp "${TEMPLATES}/npm/package-read.json" "${dir}/read/package.json"

    # .npmrc for writes (points at local npm repo)
    sed "s/__REGISTRY_HOST__/${REGISTRY_HOST:-localhost:9080}/g; s/__AUTH_TOKEN__/${NPM_AUTH_TOKEN:-}/g" \
        "${TEMPLATES}/npm/npmrc" > "${dir}/npmrc-write"

    # .npmrc for reads (points at npm_group)
    sed "s/__REGISTRY_HOST__/${REGISTRY_HOST:-localhost:9080}/g; s/__AUTH_TOKEN__/${NPM_AUTH_TOKEN:-}/g" \
        "${TEMPLATES}/npm/npmrc-read" > "${dir}/npmrc-read"

    # Writer projects
    for i in $(seq 1 "$NUM_WORKERS"); do
        local wdir="${dir}/w${i}"
        mkdir -p "${wdir}"
        sed "s/__WORKER_ID__/${i}/g" "${TEMPLATES}/npm/package-write.json" > "${wdir}/package.json"
        echo "module.exports = 'bench-w${i}';" > "${wdir}/index.js"
    done
    echo "  npm: ${NUM_WORKERS} writer projects + seed + reader"
}

# ================================================================
# Docker
# ================================================================
generate_docker() {
    local dir="${PROJECTS}/docker"
    mkdir -p "${dir}"

    cp "${TEMPLATES}/docker/Dockerfile.bench" "${dir}/Dockerfile"
    echo "  Docker: Dockerfile ready (images tagged at runtime)"
}

# ================================================================
# PHP / Composer
# ================================================================
generate_php() {
    local dir="${PROJECTS}/php"
    mkdir -p "${dir}/seed/src" "${dir}/read"

    # Seed project
    cp "${TEMPLATES}/php/composer-seed.json" "${dir}/seed/composer.json"
    mkdir -p "${dir}/seed/src"
    echo '<?php // bench/read' > "${dir}/seed/src/BenchRead.php"

    # Read project
    sed "s/__REGISTRY_HOST__/${REGISTRY_HOST:-localhost:9080}/g" \
        "${TEMPLATES}/php/composer-read.json" > "${dir}/read/composer.json"

    # Writer projects
    for i in $(seq 1 "$NUM_WORKERS"); do
        local wdir="${dir}/w${i}"
        mkdir -p "${wdir}/src"
        sed "s/__WORKER_ID__/${i}/g" "${TEMPLATES}/php/composer-write.json" > "${wdir}/composer.json"
        echo "<?php // bench/w${i}" > "${wdir}/src/BenchW${i}.php"
    done
    echo "  PHP: ${NUM_WORKERS} writer projects + seed + reader"
}

# ================================================================
# PyPI
# ================================================================
generate_pypi() {
    local dir="${PROJECTS}/pypi"
    mkdir -p "${dir}/seed" "${dir}/read"

    # Seed project
    cp "${TEMPLATES}/pypi/setup-seed.cfg" "${dir}/seed/setup.cfg"
    cat > "${dir}/seed/setup.py" << 'PYEOF'
from setuptools import setup
setup()
PYEOF
    echo '"""bench-read package."""' > "${dir}/seed/bench_read.py"

    # .pypirc (shared)
    sed "s/__REGISTRY_HOST__/${REGISTRY_HOST:-localhost:9080}/g; \
         s/__USERNAME__/${BENCH_USER:-pantera}/g; \
         s/__PASSWORD__/${BENCH_PASS:-pantera}/g" \
        "${TEMPLATES}/pypi/pypirc" > "${dir}/pypirc"

    # Writer projects
    for i in $(seq 1 "$NUM_WORKERS"); do
        local wdir="${dir}/w${i}"
        mkdir -p "${wdir}"
        sed "s/__WORKER_ID__/${i}/g" "${TEMPLATES}/pypi/setup-write.cfg" > "${wdir}/setup.cfg"
        cat > "${wdir}/setup.py" << 'PYEOF'
from setuptools import setup
setup()
PYEOF
        echo "\"\"\"bench-w${i} package.\"\"\"" > "${wdir}/bench_w${i}.py"
    done
    echo "  PyPI: ${NUM_WORKERS} writer projects + seed"
}

# ================================================================
# Main
# ================================================================
mkdir -p "${PROJECTS}"
generate_maven
generate_npm
generate_docker
generate_php
generate_pypi

echo "Project generation complete: ${NUM_WORKERS} workers per protocol"
