#!/bin/bash

# Script to publish the example.com/hello Go module to Pantera

set -e

# Enable Go modules
export GO111MODULE=on

MODULE_PATH="example.com/hello"
VERSION="v1.0.1"
PANTERA_URL="${PANTERA_URL:-https://localhost:8443}"
PANTERA_USER="${PANTERA_USER:-ayd}"
PANTERA_PASS="${PANTERA_PASS:-ayd}"
REPO_NAME="${REPO_NAME:-test_prefix/api/go/go}"

echo "Publishing Go module: $MODULE_PATH @ $VERSION"
echo "Target repository: $PANTERA_URL/$REPO_NAME"

# Navigate to the module directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/example.com/hello"

# Create temporary directory for artifacts
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

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
cp -r ./* "$ZIP_DIR/$MODULE_PATH@$VERSION/"
cd "$ZIP_DIR"
zip -r "$ZIP_FILE" "$MODULE_PATH@$VERSION" -x "*.git*"
cd "$SCRIPT_DIR/example.com/hello"

echo "✓ Created module artifacts in $TMP_DIR"

# Helper: upload a file to the Go repository with error checking
upload_artifact() {
  local file="$1"
  local suffix="$2"
  local url="$PANTERA_URL/$REPO_NAME/$MODULE_PATH/@v/$VERSION.$suffix"
  echo "Uploading .$suffix file..."
  local http_code
  http_code=$(curl -k -s -o /dev/null -w "%{http_code}" -X PUT \
    -u "$PANTERA_USER:$PANTERA_PASS" \
    --data-binary "@$file" \
    "$url")
  if [[ "$http_code" -ge 200 && "$http_code" -lt 300 ]]; then
    echo "  ✓ Uploaded .$suffix (HTTP $http_code)"
  else
    echo "  ✗ Failed to upload .$suffix (HTTP $http_code)"
    echo "    URL: $url"
    exit 1
  fi
}

upload_artifact "$INFO_FILE" "info"
upload_artifact "$MOD_FILE" "mod"
upload_artifact "$ZIP_FILE" "zip"

echo ""
echo "✅ Successfully published $MODULE_PATH@$VERSION"

# Test the uploaded module by fetching it from outside the module directory.
# Use a *fresh* GOMODCACHE so prior runs (which may have cached an empty
# go.mod or a 404 from before the upload finished) cannot poison the test.
echo ""
echo "Testing module download from Pantera..."
TEST_DIR=$(mktemp -d)
TEST_MOD_CACHE=$(mktemp -d)
trap 'chmod -R u+w "$TEST_MOD_CACHE" 2>/dev/null; rm -rf "$TMP_DIR" "$TEST_DIR" "$TEST_MOD_CACHE"' EXIT
cd "$TEST_DIR"

# Initialize a test module
go mod init test-module

# Configure Go to use our proxy (go_group combines local + upstream).
# GOMODCACHE points at a brand-new directory so this run does NOT inherit
# stale module artifacts from earlier failed publishes.
export GOPROXY="https://$PANTERA_USER:$PANTERA_PASS@localhost:8443/test_prefix/api/go/go_group"
export GOINSECURE="*"
export GONOSUMDB="$MODULE_PATH"
export GONOSUMCHECK="$MODULE_PATH"
export GOSUMDB=off
export GOMODCACHE="$TEST_MOD_CACHE"

echo "Attempting to get $MODULE_PATH@$VERSION from group..."
if go get -v "$MODULE_PATH@$VERSION"; then
    echo "✅ Successfully downloaded $MODULE_PATH@$VERSION from Pantera group"
else
    echo "❌ Failed to download $MODULE_PATH@$VERSION from Pantera group"
fi