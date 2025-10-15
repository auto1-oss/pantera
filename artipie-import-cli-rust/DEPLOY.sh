#!/bin/bash
# Deployment script for Artipie Import CLI
# Usage: ./DEPLOY.sh [server-address] [server-user]

set -e

SERVER=${1:-"root@ssvc-db-prod-1"}
REMOTE_DIR="/usr/local/bin"
BINARY="target/release/artipie-import-cli"

echo "=== Artipie Import CLI Deployment ==="
echo ""

# Check binary exists
if [ ! -f "$BINARY" ]; then
    echo "❌ Binary not found at $BINARY"
    echo "Run: cargo build --release"
    exit 1
fi

# Show binary info
echo "✓ Binary found:"
ls -lh "$BINARY"
file "$BINARY"
echo ""

# Verify binary works
echo "✓ Testing binary..."
./"$BINARY" --version
echo ""

# Copy to server
echo "📦 Deploying to $SERVER:$REMOTE_DIR..."
scp "$BINARY" "$SERVER:$REMOTE_DIR/"

# Set permissions
echo "🔐 Setting permissions..."
ssh "$SERVER" "chmod +x $REMOTE_DIR/artipie-import-cli"

# Verify on server
echo "✓ Verifying deployment..."
ssh "$SERVER" "$REMOTE_DIR/artipie-import-cli --version"

echo ""
echo "=== Deployment Complete! ==="
echo ""
echo "Next steps:"
echo "1. SSH to server: ssh $SERVER"
echo "2. Test: artipie-import-cli --help"
echo "3. Run: screen -S artipie-import"
echo "4. Import: artipie-import-cli --url ... --resume"
echo ""
echo "Documentation:"
echo "- PRODUCTION_GUIDE.md - Complete deployment guide"
echo "- TESTING.md - Testing procedures"
echo "- PRODUCTION_READY.md - Feature overview"
echo ""
