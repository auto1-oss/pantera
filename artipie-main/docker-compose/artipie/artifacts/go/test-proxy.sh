#!/bin/bash

# Script to test downloading modules through the go_proxy/go_group

set -e

# Enable Go modules
export GO111MODULE=on
export GOROOT=/usr/local/go
export PATH=$PATH:$GOROOT/bin
export GOPATH=$HOME/go



echo "Testing Go proxy functionality with go_group repository"

# Create a temporary test project
TEST_DIR=$(mktemp -d)
trap "rm -rf $TEST_DIR" EXIT

cd "$TEST_DIR"

echo ""
echo "1. Creating test Go project..."
go mod init test-project
echo "✓ Created test project"

echo ""
echo "2. Setting GOPROXY to use go_group..."
# Extract URL components and inject credentials
export GOPROXY="https://ayd:ayd@localhost:8443/artifactory/go_group"
export GOINSECURE="*"  # Allow insecure connections for localhost testing
echo "   GOPROXY=$GOPROXY (with credentials)"
echo "   GOINSECURE=* (allowing insecure connections)"

echo ""
echo "3. Downloading a popular module through proxy (github.com/google/uuid)..."
go get -v github.com/google/uuid@v1.6.0
go get -v github.com/firebase/genkit/go/ai@v1.0.5
echo "✓ Successfully downloaded through proxy"

echo ""
echo "4. Creating a simple test program..."
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
echo "5. Running the test program..."
go run main.go
echo "✓ Program executed successfully"

echo ""
echo "✅ Go proxy test completed successfully!"
echo ""
echo "The module was:"
echo "  1. Downloaded from proxy.golang.org through go_proxy"
echo "  2. Cached in Artipie"
echo "  3. Made available through go_group"
echo "  4. Used in your test project"
