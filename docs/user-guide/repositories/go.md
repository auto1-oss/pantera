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
export GOSUMDB=off
export GOINSECURE="pantera-host:8080"
```

| Variable | Purpose |
|----------|---------|
| `GOPROXY` | Routes module fetches through Pantera; falls back to `direct` if not found |
| `GOSUMDB` | Set to `off` to disable Go checksum-database verification globally (blunt escape hatch); prefer `GOPRIVATE` below when only specific module prefixes need it |
| `GOINSECURE` | Allows HTTP (non-HTTPS) for the Pantera host |

For internal/private modules that are never published to the public Go checksum database, scope the exemption instead of disabling verification globally:

```bash
export GOPRIVATE="git.internal.example.com/*"
```

`GOPRIVATE` disables sum-database and proxy lookups only for module paths matching the prefix, leaving checksum verification intact for every other module.

### Shell Profile

Add the exports to your shell profile (`~/.bashrc`, `~/.zshrc`, or equivalent) for persistence:

```bash
# Pantera Go proxy
export GOPROXY="http://your-username:your-jwt-token@pantera-host:8080/go-proxy,direct"
export GOSUMDB=off
export GOINSECURE="pantera-host:8080"
```

### CI/CD Configuration

In CI/CD pipelines, set the environment variables as secrets:

```yaml
# GitHub Actions example
env:
  GOPROXY: "http://${{ secrets.PANTERA_USER }}:${{ secrets.PANTERA_TOKEN }}@pantera-host:8080/go-proxy,direct"
  GOSUMDB: "off"
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

## Go Proxy (`go-proxy`)

A `go-proxy` repository caches modules from an upstream Go module proxy (typically `https://proxy.golang.org`) on first request, then serves subsequent requests from the local cache. Cached bytes survive upstream outages and are shared across all clients pointing at the same Pantera host. Version resolution (`go get`, `go list -m -versions`, and bare `go get <module>`) is cached too — up to a 12-hour freshness window, refreshed in the background on the next request past that window — so it keeps working against a cached module even while the upstream proxy is unreachable, falling back to the last-known version list if a refresh attempt fails.

**When to use**

- Teams that want a shared module cache to reduce egress and speed up CI.
- Air-gapped or rate-limited environments that need a reliable mirror of `proxy.golang.org`.
- Any Go development where reproducible, auditable dependency resolution matters.

**Minimal YAML**

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

Point `GOPROXY` at the proxy URL (see [Configure GOPROXY](#configure-goproxy) above). See [Cooldown](../cooldown.md) for controls over newly published upstream versions, and the [Management UI guide](../ui-guide.md#creating-repositories) for admin workflows.

---

## Go Group (`go-group`)

A `go-group` repository is a virtual repository that fans out requests across a list of member repositories (`go` locals and `go-proxy` proxies) in resolution order. The first member that serves the module wins. Groups do not store artifacts themselves — they delegate to members.

**When to use**

- You want developers to publish internal Go modules to a `go` local while still resolving public modules through a `go-proxy` in the same URL.
- You want to switch upstream proxies (e.g., primary and fallback) without reconfiguring every client.

**Minimal YAML**

```yaml
# go-group.yaml
repo:
  type: go-group
  members:
    - go-local
    - go-proxy
```

Clients set `GOPROXY` to the group URL (`http://pantera-host:8080/go-group`); Pantera handles fan-out. See the [Management UI guide](../ui-guide.md#adding-members-to-a-group-repository) for how to add, reorder, and create members from the web interface.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `410 Gone` | Module not found upstream and cached as absent | Clear the negative cache; ask admin to check proxy config |
| `401 Unauthorized` | Token missing or expired | Regenerate the JWT token and update `GOPROXY` |
| `proxyconnect tcp: tls: first record does not look like a TLS handshake` | Go trying HTTPS on an HTTP endpoint | Set `GOINSECURE=pantera-host:8080` |
| `verifying module: checksum mismatch` | Sum database mismatch for proxied module | Set `GOPRIVATE=<module-prefix>` for the affected internal modules (preferred), or `GOSUMDB=off` as a blunt global escape hatch |
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
