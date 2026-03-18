# Artipie Benchmark Tool

Compares performance of Artipie **v1.20.12** vs **v1.22.0** across Maven, Docker, and NPM workloads using **real client tools** (not synthetic HTTP load generators).

## Prerequisites

- Docker and Docker Compose (v2+)
- Maven CLI (`mvn`)
- Node.js / NPM (`npm`)
- `curl`, `jq`, `bc`, `perl`

Docker daemon must allow insecure registries for localhost:

```json
{
  "insecure-registries": ["localhost:9081", "localhost:9091"]
}
```

## Quick Start

```bash
# 1. Ensure Docker images exist (tag your builds):
docker tag <your-old-image> auto1-artipie:1.20.12
docker tag <your-new-image> auto1-artipie:1.22.0

# 2. Run full benchmark
./bench.sh

# 3. View report
cat results/BENCHMARK-REPORT.md
```

## Usage

```bash
./bench.sh                          # Full run (build + all scenarios + report)
./bench.sh --skip-build             # Skip image build, use existing images
./bench.sh --scenarios "maven npm"  # Run only specific scenarios
./bench.sh --report-only            # Regenerate report from existing CSV data
./bench.sh --teardown               # Stop infrastructure and clean up
```

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `ARTIPIE_OLD_IMAGE` | `auto1-artipie:1.20.12` | Docker image for v1.20.12 |
| `ARTIPIE_NEW_IMAGE` | `auto1-artipie:1.22.0` | Docker image for v1.22.0 |
| `ARTIPIE_USER_NAME` | `artipie` | Auth username |
| `ARTIPIE_USER_PASS` | `artipie` | Auth password |
| `CONCURRENCY_LEVELS` | `1 5 10 20` | Concurrency levels for Maven/NPM |
| `DOCKER_CONCURRENCY_LEVELS` | `1 5 10` | Concurrency levels for Docker |
| `MAVEN_ITERATIONS` | `10` | Iterations per Maven test |
| `DOCKER_ITERATIONS` | `5` | Iterations per Docker test |
| `NPM_ITERATIONS` | `10` | Iterations per NPM test |

## Test Scenarios

### Maven (real `mvn` client)
- **Local Upload**: `mvn deploy:deploy-file` with JARs (1KB, 1MB, 10MB)
- **Local Download**: `mvn dependency:copy` at increasing concurrency (parallel JVMs, each with isolated local repo)
- **Proxy Download**: `mvn dependency:copy` through `maven_group` (resolves from Maven Central proxy, warm cache)

### Docker (real `docker` client)
- **Local Push**: `docker push` images (~5MB, ~50MB, ~200MB)
- **Local Pull**: `docker pull` with `docker rmi` between iterations; concurrent pulls use unique images with distinct layer digests
- **Proxy Pull**: `docker pull` through `docker_proxy` (Docker Hub images, warm cache)
- **Concurrent Push**: Parallel `docker push` of unique images

### NPM (real `npm` client)
- **Local Publish**: `npm publish` packages
- **Local Install**: `npm install` at increasing concurrency (parallel processes, each with fresh dir + cache)
- **Proxy Install**: `npm install` through `npm_group` (resolves from npmjs.org proxy, warm cache)

## Output

- `results/BENCHMARK-REPORT.md` — Full Markdown report with comparison tables
- `results/maven.csv` — Raw Maven benchmark data
- `results/docker.csv` — Raw Docker benchmark data
- `results/npm.csv` — Raw NPM benchmark data

## Architecture

```
benchmark/
├── bench.sh                     # Main orchestrator
├── docker-compose-bench.yml     # Two Artipie instances + infra
├── setup/
│   ├── artipie-old.yml          # Config for v1.20.12
│   ├── artipie-new.yml          # Config for v1.22.0
│   ├── settings-old.xml         # Maven settings for v1.20.12
│   ├── settings-new.xml         # Maven settings for v1.22.0
│   ├── repos/                   # Repository configs (shared)
│   ├── security/                # Auth config
│   ├── init-db.sql              # PostgreSQL init (separate DBs)
│   └── log4j2-bench.xml         # Quiet logging for benchmarks
├── scenarios/
│   ├── common.sh                # Shared timing/measurement library
│   ├── maven-bench.sh           # Maven scenarios (mvn client)
│   ├── docker-bench.sh          # Docker scenarios (docker client)
│   └── npm-bench.sh             # NPM scenarios (npm client)
├── fixtures/
│   └── generate-fixtures.sh     # Create test artifacts + Docker images
├── report/
│   └── generate-report.sh       # CSV -> Markdown report
└── results/                     # Output directory
```

Both Artipie instances run with identical resource limits (4 CPU, 8GB RAM)
to ensure a fair comparison. Each uses its own PostgreSQL database but
shares Valkey, matching a typical production topology.
