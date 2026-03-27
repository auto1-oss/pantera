.PHONY: build release install clean test

# Build debug version
build:
	cargo build

# Build optimized release version
release:
	cargo build --release
	@echo ""
	@echo "Binary created at: target/release/artipie-import-cli"
	@ls -lh target/release/artipie-import-cli

# Install to /usr/local/bin
install: release
	sudo cp target/release/artipie-import-cli /usr/local/bin/
	@echo "Installed to /usr/local/bin/artipie-import-cli"

# Clean build artifacts
clean:
	cargo clean

# Run tests
test:
	cargo test

# Build static binary (Linux only)
static:
	cargo build --release --target x86_64-unknown-linux-musl
	@echo ""
	@echo "Static binary created at: target/x86_64-unknown-linux-musl/release/artipie-import-cli"

# Show help
help:
	@echo "Artipie Import CLI - Rust Edition"
	@echo ""
	@echo "Available targets:"
	@echo "  make build    - Build debug version"
	@echo "  make release  - Build optimized release version"
	@echo "  make install  - Install to /usr/local/bin (requires sudo)"
	@echo "  make clean    - Clean build artifacts"
	@echo "  make test     - Run tests"
	@echo "  make static   - Build static binary (Linux only)"
	@echo ""
	@echo "Usage example:"
	@echo "  ./target/release/artipie-import-cli \\"
	@echo "    --url https://artipie.example.com \\"
	@echo "    --export-dir /path/to/export \\"
	@echo "    --token YOUR_TOKEN \\"
	@echo "    --concurrency 200 \\"
	@echo "    --batch-size 1000 \\"
	@echo "    --resume"
