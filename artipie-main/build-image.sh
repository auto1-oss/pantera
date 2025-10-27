#!/bin/bash
set -e

echo "Building Artipie Docker Image"
echo "=============================="
echo ""

cd "$(dirname "$0")"

# Build the JAR
echo "1. Building JAR..."
mvn clean package -DskipTests

# Check if target directory has dependencies
if [ ! -d "target/dependency" ]; then
    echo "2. Copying dependencies..."
    mvn dependency:copy-dependencies
fi

# Build Docker image
echo "3. Building Docker image..."
docker build \
    -t auto1-artipie:1.0-SNAPSHOT \
    --build-arg JAR_FILE=artipie-main-1.18.12.jar \
    -f Dockerfile \
    .

echo ""
echo "✓ Docker image built successfully: auto1-artipie:1.18.12"
echo ""
echo "Verify with: docker images | grep auto1-artipie"
