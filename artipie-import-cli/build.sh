#!/bin/bash
set -e

echo "=== Artipie Import CLI - Rust Edition ==="
echo ""

# Check if Rust is installed
if ! command -v cargo &> /dev/null; then
    echo "❌ Rust is not installed!"
    echo ""
    echo "Install Rust with:"
    echo "  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    echo "  source \$HOME/.cargo/env"
    exit 1
fi

echo "✓ Rust version: $(rustc --version)"
echo ""

# Build release version
echo "Building optimized release binary..."
cargo build --release

echo ""
echo "=== Build Complete! ==="
echo ""
echo "Binary location: target/release/artipie-import-cli"
echo "Binary size: $(du -h target/release/artipie-import-cli | cut -f1)"
echo ""
echo "Quick test:"
echo "  ./target/release/artipie-import-cli --help"
echo ""

