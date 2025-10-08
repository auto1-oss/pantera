# Go Module Quickstart Guide

This guide shows how to test both **proxy (downloading)** and **direct publishing** of Go modules with Artipie.

## Prerequisites

- Artipie running at `http://localhost:8080`
- Go installed (1.21+)
- curl and zip utilities

## Quick Test Commands

### Test 1: Download from Proxy (via go_group)

```bash
cd /Users/ayd/DevOps/code/auto1/artipie/artipie-main/docker-compose/artipie/artifacts/go
./test-proxy.sh
```

This will:
- Download `github.com/google/uuid` through `go_proxy` (from proxy.golang.org)
- Cache it in Artipie
- Use it in a test program
- Verify it's accessible through `go_group`

### Test 2: Publish Sample Module

```bash
cd /Users/ayd/DevOps/code/auto1/artipie/artipie-main/docker-compose/artipie/artifacts/go
./publish-module.sh
```

This will publish `example.com/hello@v1.0.0` to the `go` repository.

### Test 3: Use Published Module

After publishing, test downloading your module:

```bash
# Create a new test project
mkdir -p /tmp/test-published && cd /tmp/test-published

# Initialize module
go mod init test-published

# Configure to use go_group (which includes both proxy and direct repos)
export GOPROXY=http://admin:password@localhost:8080/go_group
export GONOSUMDB=example.com/hello  # Skip checksum for local module
export GOINSECURE=*  # Allow HTTP for localhost testing

# Get the published module
go get example.com/hello@v1.0.0

# Create a program that uses it
cat > main.go << 'EOF'
package main

import (
    "fmt"
    "example.com/hello"
)

func main() {
    fmt.Println(hello.Greet("Artipie User"))
    fmt.Println("Version:", hello.Version())
}
EOF

# Run it
go run main.go
```

## Repository Flow

```
┌─────────────────────────────────────────────────────┐
│                    go_group                         │
│  (Combines proxy + direct repositories)             │
│                                                      │
│  Request flow:                                      │
│  1. Check go_proxy (remote cache)                  │
│  2. If not found, check go (direct/local)          │
└─────────────────────────────────────────────────────┘
         │                              │
         ▼                              ▼
┌──────────────────┐         ┌──────────────────┐
│    go_proxy      │         │       go         │
│                  │         │                  │
│  Mirrors public  │         │  Your private    │
│  modules from    │         │  modules         │
│  proxy.golang.org│         │                  │
└──────────────────┘         └──────────────────┘
```

## Verifying in Database

After running tests, check the database:

```sql
-- View all Go artifacts
SELECT id, repo_type, repo_name, name, version, size, 
       datetime(created_date/1000, 'unixepoch') as created,
       owner
FROM artifacts 
WHERE repo_type IN ('go', 'go-proxy')
ORDER BY created_date DESC;
```

Expected results:
- **go-proxy** entries: Modules downloaded through proxy (e.g., `github.com/google/uuid`)
- **go** entries: Modules you published directly (e.g., `example.com/hello`)

## Manual Testing

### Download a specific module through proxy:

```bash
export GOPROXY=http://admin:password@localhost:8080/go_group
export GONOSUMDB=*
export GOINSECURE=*  # Allow HTTP for localhost

# Download any public module
go mod download github.com/gin-gonic/gin@v1.9.1
```

### Check what's cached:

```bash
# List available versions
curl -u admin:password http://localhost:8080/go_proxy/github.com/google/uuid/@v/list

# Get module info
curl -u admin:password http://localhost:8080/go_proxy/github.com/google/uuid/@v/v1.3.0.info
```

## Troubleshooting

### Module not found
- Ensure Artipie is running: `docker ps`
- Check repository configs: `ls -la ../repo/go*.yaml`
- Verify GOPROXY is set correctly: `echo $GOPROXY`

### Authentication errors
- Default credentials: `admin:password`
- Update scripts with: `ARTIPIE_USER=... ARTIPIE_PASS=... ./script.sh`

### Checksum errors
- For testing, disable checksum: `export GONOSUMDB=*`
- For production, properly configure sumdb or use private sumdb

### "refusing to pass credentials to insecure URL" error
- This occurs because Go refuses HTTP authentication by default
- Solution: Set `export GOINSECURE=*` to allow HTTP for localhost
- For production, use HTTPS or configure specific patterns like `GOINSECURE=localhost:8081`

## Next Steps

1. **Test proxy caching**: Download different versions of modules
2. **Publish multiple versions**: Modify the sample module and publish v1.1.0
3. **Test dependency resolution**: Create a module that depends on another
4. **Monitor database**: Watch how artifacts are recorded

## Configuration Files

- **go.yaml**: Direct repository (`/var/artipie/data`)
- **go_proxy.yaml**: Proxy to proxy.golang.org
- **go_group.yaml**: Group combining both repositories
