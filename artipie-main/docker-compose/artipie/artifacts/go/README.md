# Go Module Testing

This directory contains a sample Go module for testing Artipie Go repository functionality.

## Repository Setup

Your Artipie instance has three Go repositories configured:

1. **`go`** - Direct repository for publishing your own modules
2. **`go_proxy`** - Proxy repository that mirrors packages from https://proxy.golang.org
3. **`go_group`** - Group repository that combines both (checks go_proxy first, then go)

## Sample Module

**Module**: `example.com/hello`  
**Version**: `v1.0.0`

### Testing Proxy Download (from go_group)

```bash
export GOPROXY=http://admin:password@localhost:8080/go_group
export GONOSUMDB=*
export GOINSECURE=*  # Allow HTTP for localhost

# Download any public module
go mod download github.com/gin-gonic/gin@v1.9.1
```

### Publishing the Sample Module

{{ ... }}

```bash
# Navigate to the module directory
cd example.com/hello

# Create a git repository and tag the version
git init
git add .
git commit -m "Initial commit"
git tag v1.0.0

# Zip the module
cd ..
zip -r hello-v1.0.0.zip hello/

# Upload to Artipie go repository
# The path format is: /{module_path}/@v/{version}.zip
curl -X PUT \
  -u admin:password \
  --data-binary @hello-v1.0.0.zip \
  http://localhost:8080/go/example.com/hello/@v/v1.0.0.zip

# Also upload the .info file
cat > v1.0.0.info << EOF
{"Version":"v1.0.0","Time":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF

curl -X PUT \
  -u admin:password \
  --data-binary @v1.0.0.info \
  http://localhost:8080/go/example.com/hello/@v/v1.0.0.info

# Upload the .mod file
curl -X PUT \
  -u admin:password \
  --data-binary @hello/go.mod \
  http://localhost:8080/go/example.com/hello/@v/v1.0.0.mod
```

### Using the Published Module

Once published, you can use it through the `go_group` repository:

```bash
# Set GOPROXY to use go_group (with authentication)
export GOPROXY=http://admin:password@localhost:8080/go_group
export GONOSUMDB=example.com/hello  # Skip checksum for local module
export GOINSECURE=*  # Allow HTTP for localhost testing

# Create a test project
mkdir test-project
cd test-project
go mod init test

# Add the module
go get example.com/hello@v1.0.0

# Use it in your code
cat > main.go << 'EOF'
package main

import (
    "fmt"
    "example.com/hello"
)

func main() {
    fmt.Println(hello.Greet("Artipie"))
    fmt.Println("Module version:", hello.Version())
}
EOF

go run main.go
```

## Repository Structure

After publishing, the Go repository will have this structure:

```
/var/artipie/data/
└── example.com/
    └── hello/
        └── @v/
            ├── v1.0.0.zip      # Module source code
            ├── v1.0.0.info     # Version metadata
            ├── v1.0.0.mod      # go.mod file
            └── list            # List of available versions (auto-generated)
```

## Verification

Check if the module is accessible:

```bash
# Get module info
curl -u admin:password http://localhost:8080/go/example.com/hello/@v/v1.0.0.info

# Get module list
curl -u admin:password http://localhost:8080/go/example.com/hello/@v/list

# Download the module
curl -u admin:password http://localhost:8080/go/example.com/hello/@v/v1.0.0.zip -o downloaded.zip
```

## Database Verification

After operations, check the database to verify artifact records:

```sql
SELECT * FROM artifacts WHERE repo_type = 'go' OR repo_type = 'go-proxy';
```

You should see records for:
- Published modules in the `go` repository
- Cached modules from the `go_proxy` repository
