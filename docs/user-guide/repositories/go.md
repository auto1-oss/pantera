# Go Modules

> **Guide:** User Guide | **Section:** Repositories / Go

This page covers how to configure the Go toolchain to fetch modules through Pantera.

---

## Prerequisites

- Go 1.18+
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Configure GOPROXY

Set the `GOPROXY` environment variable to route module fetches through Pantera:

```bash
export GOPROXY="http://your-username:your-jwt-token@pantera-host:8080/go-proxy,direct"
export GONOSUMCHECK="*"
export GOINSECURE="pantera-host:8080"
```

| Variable | Purpose |
|----------|---------|
| `GOPROXY` | Routes module fetches through Pantera; falls back to `direct` if not found |
| `GONOSUMCHECK` | Skips checksum database verification (needed for private modules) |
| `GOINSECURE` | Allows HTTP (non-HTTPS) for the Pantera host |

### Shell Profile

Add the exports to your shell profile (`~/.bashrc`, `~/.zshrc`, or equivalent) for persistence:

```bash
# Pantera Go proxy
export GOPROXY="http://your-username:your-jwt-token@pantera-host:8080/go-proxy,direct"
export GONOSUMCHECK="*"
export GOINSECURE="pantera-host:8080"
```

### CI/CD Configuration

In CI/CD pipelines, set the environment variables as secrets:

```yaml
# GitHub Actions example
env:
  GOPROXY: "http://${{ secrets.PANTERA_USER }}:${{ secrets.PANTERA_TOKEN }}@pantera-host:8080/go-proxy,direct"
  GONOSUMCHECK: "*"
  GOINSECURE: "pantera-host:8080"
```

---

## Fetch Modules

Once `GOPROXY` is configured, standard Go commands work as expected:

```bash
go get github.com/gin-gonic/gin
go mod download
go build ./...
```

The proxy caches downloaded modules locally. Subsequent fetches from any developer or CI pipeline are served from cache.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `410 Gone` | Module not found upstream and cached as absent | Clear the negative cache; ask admin to check proxy config |
| `401 Unauthorized` | Token missing or expired | Regenerate the JWT token and update `GOPROXY` |
| `proxyconnect tcp: tls: first record does not look like a TLS handshake` | Go trying HTTPS on an HTTP endpoint | Set `GOINSECURE=pantera-host:8080` |
| `verifying module: checksum mismatch` | Sum database mismatch for proxied module | Set `GONOSUMCHECK=*` or `GONOSUMDB=*` |
| `go: module not found` with `direct` fallback | Module is genuinely missing | Verify the module path and version exist upstream |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Proxy repository:**

```yaml
# go-proxy.yaml
repo:
  type: go-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://proxy.golang.org
```

**Local repository:**

```yaml
# go-local.yaml
repo:
  type: go
  storage:
    type: fs
    path: /var/pantera/data
```

**Group repository:**

```yaml
# go-group.yaml
repo:
  type: go-group
  members:
    - go-local
    - go-proxy
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
