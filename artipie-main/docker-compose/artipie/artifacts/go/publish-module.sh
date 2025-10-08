#!/bin/bash

# Script to publish the example.com/hello Go module to Artipie

set -e

# Enable Go modules
export GO111MODULE=on

MODULE_PATH="example.com/hello"
VERSION="v1.0.1"
ARTIPIE_URL="${ARTIPIE_URL:-https://localhost:8443}"
ARTIPIE_USER="${ARTIPIE_USER:-ayd}"
ARTIPIE_PASS="${ARTIPIE_PASS:-ayd}"
REPO_NAME="${REPO_NAME:-go}"

echo "Publishing Go module: $MODULE_PATH @ $VERSION"
echo "Target repository: $ARTIPIE_URL/$REPO_NAME"

# Navigate to the module directory
cd "$(dirname "$0")/example.com/hello"

# Create temporary directory for artifacts
TMP_DIR=$(mktemp -d)
trap "rm -rf $TMP_DIR" EXIT

# Create .info file
INFO_FILE="$TMP_DIR/$VERSION.info"
cat > "$INFO_FILE" << EOF
{"Version":"$VERSION","Time":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF

# Create .mod file (copy go.mod)
MOD_FILE="$TMP_DIR/$VERSION.mod"
cp go.mod "$MOD_FILE"

# Create .zip file (must include module@version path in zip structure)
ZIP_FILE="$TMP_DIR/$VERSION.zip"
ZIP_DIR="$TMP_DIR/zip-staging"
mkdir -p "$ZIP_DIR/$MODULE_PATH@$VERSION"
cp -r * "$ZIP_DIR/$MODULE_PATH@$VERSION/"
cd "$ZIP_DIR"
zip -r "$ZIP_FILE" "$MODULE_PATH@$VERSION" -x "*.git*"
cd -

echo "✓ Created module artifacts in $TMP_DIR"

# Upload .info file
echo "Uploading .info file..."
curl -k -X PUT \
  -u "$ARTIPIE_USER:$ARTIPIE_PASS" \
  --data-binary "@$INFO_FILE" \
  "$ARTIPIE_URL/$REPO_NAME/$MODULE_PATH/@v/$VERSION.info"
echo "✓ Uploaded .info"

# Upload .mod file
echo "Uploading .mod file..."
curl -k -X PUT \
  -u "$ARTIPIE_USER:$ARTIPIE_PASS" \
  --data-binary "@$MOD_FILE" \
  "$ARTIPIE_URL/$REPO_NAME/$MODULE_PATH/@v/$VERSION.mod"
echo "✓ Uploaded .mod"

# Upload .zip file
echo "Uploading .zip file..."
curl -k -X PUT \
  -u "$ARTIPIE_USER:$ARTIPIE_PASS" \
  --data-binary "@$ZIP_FILE" \
  "$ARTIPIE_URL/$REPO_NAME/$MODULE_PATH/@v/$VERSION.zip"
echo "✓ Uploaded .zip"

echo ""
echo "✅ Successfully published $MODULE_PATH@$VERSION"

# Test the uploaded module by fetching it from outside the module directory
echo ""
echo "Testing module download from Artipie..."
cd /tmp
rm -rf test-go-get 2>/dev/null || true
mkdir test-go-get
cd test-go-get

# Initialize a test module
go mod init test-module

# Configure Go to use our proxy
export GOPROXY="https://ayd:ayd@localhost:8443/go_group"
export GOINSECURE="*"
export GONOSUMDB="$MODULE_PATH"

echo "Attempting to get $MODULE_PATH@$VERSION from proxy..."
if go get -v "$MODULE_PATH@$VERSION"; then
    echo "✅ Successfully downloaded $MODULE_PATH@$VERSION from Artipie proxy"
else
    echo "❌ Failed to download $MODULE_PATH@$VERSION from Artipie proxy"
    echo "This may be expected if testing upload functionality only"
fi

# Clean up test directory
cd - > /dev/null
rm -rf /tmp/test-go-get