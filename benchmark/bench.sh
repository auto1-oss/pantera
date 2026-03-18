#!/usr/bin/env bash
##
## Artipie Benchmark Tool
## Compares v1.20.12 vs v1.22.0 across Maven, Docker, and NPM workloads
## using real client tools (mvn, docker, npm).
##
## Usage:
##   ./bench.sh                        # Full benchmark (build + infra + all scenarios + report)
##   ./bench.sh --skip-build           # Skip Docker image build (use existing images)
##   ./bench.sh --scenarios "maven"    # Run only Maven scenarios
##   ./bench.sh --teardown             # Only tear down infrastructure
##   ./bench.sh --report-only          # Only regenerate report from existing CSV results
##
## Prerequisites:
##   - Docker and Docker Compose
##   - mvn (Maven CLI)
##   - npm (Node.js / NPM)
##   - perl (for timing — pre-installed on macOS/Linux)
##   - curl, jq, bc
##
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Defaults
SKIP_BUILD=false
SCENARIOS="maven docker npm"
TEARDOWN_ONLY=false
REPORT_ONLY=false

# Parse args
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)   SKIP_BUILD=true; shift ;;
        --scenarios)    SCENARIOS="$2"; shift 2 ;;
        --teardown)     TEARDOWN_ONLY=true; shift ;;
        --report-only)  REPORT_ONLY=true; shift ;;
        -h|--help)
            head -20 "$0" | grep '^##' | sed 's/^## \?//'
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

export ARTIPIE_USER_NAME="${ARTIPIE_USER_NAME:-artipie}"
export ARTIPIE_USER_PASS="${ARTIPIE_USER_PASS:-artipie}"

# Default concurrency and iteration settings
export CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-1 5 10 20}"
export MAVEN_ITERATIONS="${MAVEN_ITERATIONS:-10}"
export DOCKER_ITERATIONS="${DOCKER_ITERATIONS:-5}"
export DOCKER_CONCURRENCY_LEVELS="${DOCKER_CONCURRENCY_LEVELS:-1 5 10}"
export NPM_ITERATIONS="${NPM_ITERATIONS:-10}"

log() { echo ""; echo "============================================================"; echo "  $*"; echo "============================================================"; }

# ============================================================
# Prerequisite checks
# ============================================================
check_prerequisites() {
    local missing=()
    command -v docker >/dev/null 2>&1 || missing+=("docker")
    command -v mvn >/dev/null 2>&1 || missing+=("mvn (Maven CLI)")
    command -v npm >/dev/null 2>&1 || missing+=("npm (Node.js)")
    command -v perl >/dev/null 2>&1 || missing+=("perl")
    command -v curl >/dev/null 2>&1 || missing+=("curl")
    command -v jq >/dev/null 2>&1 || missing+=("jq")
    command -v bc >/dev/null 2>&1 || missing+=("bc")

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "ERROR: Missing prerequisites:"
        for m in "${missing[@]}"; do
            echo "  - $m"
        done
        exit 1
    fi

    # Check Docker insecure registries for localhost:9081 and localhost:9091
    local docker_info
    docker_info=$(docker info 2>/dev/null || true)
    local needs_insecure=false
    for port in 9081 9091; do
        if ! echo "$docker_info" | grep -q "localhost:${port}"; then
            echo "WARNING: localhost:${port} may not be in Docker's insecure-registries."
            needs_insecure=true
        fi
    done
    if $needs_insecure; then
        echo ""
        echo "Add to your Docker daemon config (daemon.json):"
        echo '  { "insecure-registries": ["localhost:9081", "localhost:9091"] }'
        echo ""
        echo "Continuing anyway — Docker push/pull may fail if not configured."
        echo ""
    fi

    echo "All prerequisites found."
    echo "  mvn:    $(mvn --version 2>&1 | head -1)"
    echo "  docker: $(docker --version 2>&1 | head -1)"
    echo "  npm:    $(npm --version 2>&1)"
}

# ============================================================
# Build Docker images
# ============================================================
build_images() {
    log "Building Docker images for both versions"

    local project_root
    project_root="$(dirname "$SCRIPT_DIR")"

    if docker image inspect "167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.20.12" >/dev/null 2>&1 && \
       docker image inspect "167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.22.0" >/dev/null 2>&1; then
        echo "Both Docker images already exist."
        echo "  167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.20.12"
        echo "  167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.22.0"
        return 0
    fi

    echo ""
    echo "IMPORTANT: Docker images need to be built from source."
    echo ""
    echo "If you have pre-built images, tag them as:"
    echo "  docker tag <your-old-image> 167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.20.12"
    echo "  docker tag <your-new-image> 167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.22.0"
    echo ""
    echo "Or set environment variables:"
    echo "  export ARTIPIE_OLD_IMAGE=your-registry/artipie:1.20.12"
    echo "  export ARTIPIE_NEW_IMAGE=your-registry/artipie:1.22.0"
    echo ""

    if ! docker image inspect "167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.22.0" >/dev/null 2>&1; then
        echo "Building v1.22.0 image from current branch..."
        cd "$project_root"
        mvn -pl artipie-main -am package -DskipTests -q 2>/dev/null || {
            echo "WARNING: Maven build failed. Please build manually."
            return 1
        }
        local jar_file
        jar_file=$(ls artipie-main/target/artipie-main-*.jar 2>/dev/null | grep -v sources | head -1)
        if [[ -n "$jar_file" ]]; then
            local jar_name
            jar_name=$(basename "$jar_file")
            cd artipie-main
            docker build --build-arg "JAR_FILE=${jar_name}" -t 167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.22.0 .
            cd "$project_root"
        fi
    fi

    echo ""
    echo "NOTE: Building v1.20.12 requires checking out that tag."
    echo "If not available, tag an existing image:"
    echo "  docker tag <image> 167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.20.12"
}

# ============================================================
# Start infrastructure
# ============================================================
start_infra() {
    log "Starting benchmark infrastructure"

    cd "$SCRIPT_DIR"
    docker compose -f docker-compose-bench.yml down -v 2>/dev/null || true
    docker compose -f docker-compose-bench.yml up -d

    echo "Waiting for services to be healthy..."
    sleep 10

    for api_port in 9082 9092; do
        local name="v1.20.12"
        [[ "$api_port" == "9092" ]] && name="v1.22.0"
        echo -n "  Waiting for $name (API port $api_port)..."
        for i in $(seq 1 60); do
            if curl -sf "http://localhost:${api_port}/api/health" > /dev/null 2>&1 || \
               curl -sf "http://localhost:${api_port}/api/v1/health" > /dev/null 2>&1; then
                echo " ready"
                break
            fi
            if [[ $i -eq 60 ]]; then
                echo " TIMEOUT"
                echo "Logs for failed instance:"
                docker compose -f docker-compose-bench.yml logs "$([ "$api_port" = "9082" ] && echo artipie-old || echo artipie-new)" | tail -30
                exit 1
            fi
            sleep 2
            echo -n "."
        done
    done

    # JVM warmup: hit each instance a few times
    echo "  JVM warmup..."
    local auth
    auth=$(echo -n "${ARTIPIE_USER_NAME}:${ARTIPIE_USER_PASS}" | base64)
    for port in 9081 9091; do
        for i in $(seq 1 10); do
            curl -sf "http://localhost:${port}/maven/" \
                -H "Authorization: Basic ${auth}" -o /dev/null 2>/dev/null || true
        done
    done
    sleep 2
    echo "  Infrastructure ready."
}

# ============================================================
# Teardown
# ============================================================
teardown() {
    log "Tearing down benchmark infrastructure"
    cd "$SCRIPT_DIR"
    docker compose -f docker-compose-bench.yml down -v 2>/dev/null || true
    echo "Infrastructure stopped and volumes removed."
}

# ============================================================
# Main
# ============================================================
main() {
    echo ""
    echo "  Artipie Benchmark Tool (real-client mode)"
    echo "  v1.20.12 vs v1.22.0"
    echo "  Scenarios: ${SCENARIOS}"
    echo "  Concurrency: ${CONCURRENCY_LEVELS}"
    echo ""

    if $TEARDOWN_ONLY; then
        teardown
        exit 0
    fi

    if $REPORT_ONLY; then
        bash "${SCRIPT_DIR}/report/generate-report.sh"
        exit 0
    fi

    check_prerequisites

    if ! $SKIP_BUILD; then
        build_images
    fi

    # Generate fixtures
    log "Generating test fixtures"
    bash "${SCRIPT_DIR}/fixtures/generate-fixtures.sh"

    # Start infra
    start_infra

    # Clean previous results
    rm -f "${SCRIPT_DIR}/results/"*.csv

    # Run scenarios
    for scenario in $SCENARIOS; do
        case "$scenario" in
            maven)
                log "Running Maven benchmarks"
                bash "${SCRIPT_DIR}/scenarios/maven-bench.sh"
                ;;
            docker)
                log "Running Docker benchmarks"
                bash "${SCRIPT_DIR}/scenarios/docker-bench.sh"
                ;;
            npm)
                log "Running NPM benchmarks"
                bash "${SCRIPT_DIR}/scenarios/npm-bench.sh"
                ;;
            *)
                echo "Unknown scenario: $scenario"
                ;;
        esac
    done

    # Generate report
    log "Generating report"
    bash "${SCRIPT_DIR}/report/generate-report.sh"

    echo ""
    echo "============================================================"
    echo "  BENCHMARK COMPLETE"
    echo "  Report: ${SCRIPT_DIR}/results/BENCHMARK-REPORT.md"
    echo "  Raw CSV: ${SCRIPT_DIR}/results/*.csv"
    echo "============================================================"
    echo ""
    echo "Tip: Run './bench.sh --teardown' to stop infrastructure"
}

main "$@"
