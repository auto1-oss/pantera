#!/bin/bash
set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

echo_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

echo_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if running from correct directory
if [ ! -f "pom.xml" ]; then
    echo_error "Must be run from artipie root directory (where pom.xml is)"
    exit 1
fi

ARTIPIE_ROOT="$(pwd)"
VERSION="1.18.12"

echo_info "Starting Artipie rebuild and deployment process..."

# Step 1: Clean Maven repository
echo_info "Step 1/6: Cleaning Maven repository..."
if [ -d ~/.m2/repository ]; then
    echo_warn "Removing ~/.m2/repository"
    rm -rf ~/.m2/repository
else
    echo_info "~/.m2/repository does not exist, skipping removal"
fi

# Step 2: Restore from backup
echo_info "Step 2/6: Restoring Maven dependencies from backup..."
if [ -d ~/.m2/repository-artipie-deps-only ]; then
    echo_info "Copying ~/.m2/repository-artipie-deps-only to ~/.m2/repository"
    cp -r ~/.m2/repository-artipie-deps-only ~/.m2/repository
else
    echo_error "Backup directory ~/.m2/repository-artipie-deps-only does not exist!"
    echo_error "Please create it first by running: cp -r ~/.m2/repository ~/.m2/repository-artipie-deps-only"
    exit 1
fi

# Step 3: Maven clean package
echo_info "Step 3/6: Building Artipie with Maven..."
cd "$ARTIPIE_ROOT"
#if mvn clean package -Pdocker-build; then
if mvn clean package -Pdocker-build -DskipTests; then
    echo_info "Maven build completed successfully"
else
    echo_error "Maven build failed!"
    exit 1
fi

# Step 4: Check if JAR was created
echo_info "Step 4/6: Verifying JAR file..."
JAR_PATH="$ARTIPIE_ROOT/artipie-main/target/artipie-main-${VERSION}.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo_error "JAR file not found at: $JAR_PATH"
    exit 1
fi
echo_info "JAR file found: $JAR_PATH"

# Step 5: Build Docker image
echo_info "Step 5/6: Building Docker image..."
cd "$ARTIPIE_ROOT/artipie-main"
if docker build --build-arg JAR_FILE="artipie-main-${VERSION}.jar" -t "auto1-artipie:${VERSION}" .; then
    echo_info "Docker image built successfully: auto1-artipie:${VERSION}"
else
    echo_error "Docker build failed!"
    exit 1
fi

# Step 6: Start with docker-compose
echo_info "Step 6/6: Starting services with docker-compose..."
cd "$ARTIPIE_ROOT/artipie-main/docker-compose"

# Check if docker-compose.yaml exists
if [ ! -f "docker-compose.yaml" ]; then
    echo_error "docker-compose.yaml not found in $(pwd)"
    exit 1
fi

# Stop any existing containers first
echo_info "Stopping any existing containers..."
docker-compose down || true

echo_info "Starting docker-compose..."
echo_warn "Press Ctrl+C to stop the services"
echo ""

# Run docker-compose in foreground
docker-compose up
