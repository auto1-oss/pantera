#!/bin/bash
set -e  # Exit on error

# Parse arguments
RUN_TESTS=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --with-tests|--run-tests|-t)
            RUN_TESTS=true
            shift
            ;;
        --help|-h)
            echo "Usage: ./build-and-deploy.sh [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --with-tests, -t    Run tests (default: skip tests for speed)"
            echo "  --help, -h          Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./build-and-deploy.sh              # Fast build, skip tests"
            echo "  ./build-and-deploy.sh --with-tests # Full build with tests"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

echo "=== Artipie Complete Build & Deploy ==="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration - Read version from pom.xml
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || grep -m 1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
IMAGE_NAME="auto1-artipie:${VERSION}"
COMPOSE_DIR="artipie-main/docker-compose"

echo "Detected version: ${VERSION}"

if [ "$RUN_TESTS" = true ]; then
    echo -e "${YELLOW}Mode: Full build WITH tests${NC}"
    TEST_FLAG=""
else
    echo -e "${YELLOW}Mode: Fast build (tests skipped)${NC}"
    TEST_FLAG="-DskipTests"
fi

echo -e "${YELLOW}Build all modules with dependencies${NC}"
# -U forces update of snapshots and releases
if [ "$RUN_TESTS" = true ]; then
    echo "Running tests (this will take longer)..."
    mvn clean install -U -Dmaven.test.skip=true -Dpmd.skip=true
    mvn install
else
    echo "Skipping tests and test compilation (use --with-tests to run them)..."
    mvn install -U -Dmaven.test.skip=true -Dpmd.skip=true
fi

echo ""
echo -e "${YELLOW}Verify image was created${NC}"
docker images ${IMAGE_NAME} --format "table {{.Repository}}\t{{.Tag}}\t{{.CreatedAt}}\t{{.Size}}"

echo ""
echo -e "${YELLOW}Restart Docker Compose${NC}"
cd ${COMPOSE_DIR}

echo "Stopping containers..."
docker-compose down

echo "Starting containers..."
docker-compose up -d

echo "Waiting for container to be ready..."
sleep 5

echo ""
echo -e "${YELLOW}Step 6: Verify deployment${NC}"

# Check container is running
if docker ps | grep -q "auto1-artipie"; then
    echo -e "${GREEN}✓ Container is running${NC}"
else
    echo -e "${RED}✗ Container is not running!${NC}"
    docker-compose logs artipie | tail -50
    exit 1
fi

echo ""
echo -e "${GREEN}=== Build and Deploy Complete ===${NC}"

