# Go Proxy Implementation

## Overview
Added comprehensive Go proxy support to the go-adapter with cooldown service integration, following the same patterns as maven-adapter and gradle-adapter.

## New Features

### Go Proxy Repository (`go-proxy`)
- Proxies remote Go module repositories (e.g., proxy.golang.org)
- Caches modules locally for faster access
- Integrates with Artipie's cooldown service
- Tracks artifact events with owner information
- Supports all Go module proxy protocol endpoints

### Go Group Repository (`go-group`)
- Aggregates multiple Go repositories
- Searches members in order
- Read-only access

## Components

### Core Classes

1. **GoProxySlice** (`com.artipie.http.GoProxySlice`)
   - Main HTTP slice for Go proxy
   - Routes HEAD and GET requests
   - Integrates caching and cooldown

2. **CachedProxySlice** (`com.artipie.http.CachedProxySlice`)
   - Caching layer with cooldown preflight checks
   - Validates artifacts using checksum headers
   - Emits proxy artifact events
   - Handles Last-Modified headers

3. **GoCooldownInspector** (`com.artipie.http.GoCooldownInspector`)
   - Parses go.mod files for dependencies
   - Supports both single-line and block require statements
   - Filters out indirect dependencies
   - Extracts release dates from Last-Modified headers

4. **HeadProxySlice** (`com.artipie.http.HeadProxySlice`)
   - Handles HEAD requests to remote repositories

5. **RepoHead** (`com.artipie.http.RepoHead`)
   - Helper for performing HEAD requests

6. **GoProxy** (`com.artipie.adapters.go.GoProxy`)
   - Adapter class in artipie-main
   - Creates GoProxySlice with configuration

## Configuration

### Go Proxy Repository

```yaml
repo:
  type: go-proxy
  storage:
    type: fs
    path: /var/artipie/data
  remote:
    url: https://proxy.golang.org
```

### Go Group Repository

```yaml
repo:
  type: go-group
  members:
    - go_proxy
    - go
```

## Usage

### Configure Go to use Artipie proxy

```bash
# Set GOPROXY environment variable
export GOPROXY=http://localhost:8080/go-proxy

# Or configure in go env
go env -w GOPROXY=http://localhost:8080/go-proxy
```

### With Authentication

```bash
# Use .netrc for authentication
cat >> ~/.netrc << EOF
machine localhost
login alice
password secret
EOF
```

### Download modules

```bash
go get github.com/pkg/errors@v0.9.1
go mod download
```

## Go Module Proxy Protocol

The implementation supports the standard Go module proxy protocol:

- `GET /{module}/@v/list` - List available versions
- `GET /{module}/@v/{version}.info` - Get version metadata (JSON)
- `GET /{module}/@v/{version}.mod` - Get go.mod file
- `GET /{module}/@v/{version}.zip` - Get module source archive
- `GET /{module}/@latest` - Get latest version info

## Cooldown Service Integration

The Go proxy integrates with Artipie's cooldown service to:

1. **Parse go.mod files** - Extracts dependencies from both single-line and block require statements
2. **Filter indirect dependencies** - Excludes dependencies marked with `// indirect`
3. **Track release dates** - Uses Last-Modified headers from remote
4. **Evaluate policies** - Checks cooldown policies before proxying requests
5. **Prevent excessive requests** - Blocks repeated downloads based on cooldown rules

### go.mod Parsing Example

```go
module example.com/myapp

go 1.21

require (
    github.com/pkg/errors v0.9.1
    golang.org/x/sync v0.3.0
    example.com/lib v1.2.3 // indirect  <- excluded
)

require github.com/stretchr/testify v1.8.4  <- included
```

The inspector extracts:
- `github.com/pkg/errors@v0.9.1`
- `golang.org/x/sync@v0.3.0`
- `github.com/stretchr/testify@v1.8.4`

## Architecture

```
GoProxySlice
├── HeadProxySlice (HEAD requests)
└── CachedProxySlice (GET requests)
    ├── Cache (artifact caching)
    ├── CooldownService (preflight checks)
    └── GoCooldownInspector (dependency parsing)
```

## Testing

Run tests:
```bash
mvn test -pl go-adapter
```

Run integration tests:
```bash
mvn verify -pl go-adapter -Dtest.integration=true
```

## Supported Remote Repositories

- **proxy.golang.org** - Official Go module proxy
- **goproxy.io** - Alternative Go proxy
- **goproxy.cn** - China-based Go proxy
- Any custom Go module proxy implementing the protocol
