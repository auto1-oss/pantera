#!/bin/bash

# Script to test downloading modules through the go_proxy/go_group

set -e

# Enable Go modules
export GO111MODULE=on
export GOROOT=/usr/local/go
export PATH=$PATH:$GOROOT/bin
export GOPATH=$HOME/go

PASS=0
FAIL=0

echo "Testing Go proxy functionality with go_group repository"

# Create a temporary test project
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT

cd "$TEST_DIR"

echo ""
echo "1. Creating test Go project..."
go mod init test-project
echo "✓ Created test project"

echo ""
echo "2. Setting GOPROXY to use go_group..."
export GOPROXY="https://ayd:ayd@localhost:8443/test_prefix/api/go/go_group"
export GOINSECURE="*"
export GONOSUMDB="*"
export GONOSUMCHECK="*"
echo "   GOPROXY=$GOPROXY (with credentials)"
echo "   GOINSECURE=* GONOSUMDB=* GONOSUMCHECK=*"

# Helper: download a module and track pass/fail
download_module() {
  local module="$1"
  if go get -v "$module" 2>&1; then
    echo "  ✓ $module"
    PASS=$((PASS + 1))
  else
    echo "  ✗ $module FAILED"
    FAIL=$((FAIL + 1))
  fi
}

echo ""
echo "3. Downloading popular modules through proxy..."

# Core utilities
download_module "github.com/google/uuid@v1.6.0"
download_module "github.com/pkg/errors@v0.9.1"

# Web frameworks and HTTP
download_module "github.com/gin-gonic/gin@v1.10.0"
download_module "github.com/go-chi/chi/v5@v5.2.1"
download_module "github.com/gorilla/mux@v1.8.1"
download_module "github.com/go-resty/resty/v2@v2.16.5"

# CLI and configuration
download_module "github.com/spf13/cobra@v1.9.1"
download_module "github.com/spf13/viper@v1.20.1"

# Logging
download_module "github.com/sirupsen/logrus@v1.9.3"
download_module "go.uber.org/zap@v1.27.0"

# Serialization and data
download_module "github.com/json-iterator/go@v1.1.12"
download_module "google.golang.org/protobuf@v1.36.6"

# Database and cache clients
download_module "github.com/redis/go-redis/v9@v9.7.3"

# Testing
download_module "github.com/stretchr/testify@v1.10.0"

# Go extended stdlib
download_module "golang.org/x/sync@v0.12.0"
download_module "golang.org/x/text@v0.23.0"

# Monitoring and observability
download_module "github.com/prometheus/client_golang@v1.21.1"

# Firebase/Google (larger dependency tree)
download_module "github.com/firebase/genkit/go/ai@v1.0.5"

# Openai
download_module "github.com/openai/openai-go/v3"

echo ""
echo "   Results: $PASS passed, $FAIL failed out of $((PASS + FAIL)) modules"
if [[ $FAIL -gt 0 ]]; then
  echo "   ✗ Some modules failed to download"
  exit 1
fi
echo "   ✓ All modules downloaded successfully"

echo ""
echo "4. Testing cooldown block (fresh package should be rejected with 403)..."
OUTPUT=$(go get -v github.com/go-ap/processing@v0.0.0-20260417143241-6f16acf4256b 2>&1 || true)
if [[ $OUTPUT == *"403"* ]]; then
  echo "  ✓ Package correctly blocked by cooldown (HTTP 403)"
else
  echo "  ✗ Expected 403 cooldown block but image has been successfully cached"
fi

echo ""
echo "5. Creating a simple test program..."
cat > main.go << 'EOF'
package main

import (
    "fmt"
    "github.com/google/uuid"
)

func main() {
    id := uuid.New()
    fmt.Println("Generated UUID:", id.String())
}
EOF

echo ""
echo "6. Running the test program..."
go run main.go
echo "✓ Program executed successfully"

echo ""
echo "✅ Go proxy test completed successfully!"