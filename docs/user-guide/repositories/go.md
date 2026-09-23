# Go Modules

> **Guide:** User Guide | **Section:** Repositories / Go

This page covers how to configure the Go toolchain to fetch modules through Pantera.

---

## Prerequisites

- Go 1.18+
- A Pantera account and an API token (see [Getting Started](../getting-started.md)), or generate one in the UI's **Set Me Up** page
- Pantera served over **HTTPS** (see [Plain HTTP Registries](#plain-http-registries) below)

The examples use `https://pantera-host/go-group`. Set Me Up in the UI (`/setup/go`) generates the same commands with your registry URL, repository and token filled in.

---

## Store Credentials in ~/.netrc

Keep the token out of `GOPROXY`: URL credentials show up in `go env` and error output, and break on usernames containing `@`. Go reads `~/.netrc` (`%USERPROFILE%\_netrc` on Windows) for https proxies:

```bash
cat >> ~/.netrc <<'EOF'
machine pantera-host login your-username password your-api-token
EOF
chmod 600 ~/.netrc
```

If the registry URL has a port, add a second line with `machine pantera-host:8443 ...`: Go 1.24+ matches `host:port`, while curl and older Go match the bare host name.

---

## Configure GOPROXY

```bash
go env -w GOPROXY=https://pantera-host/go-group GONOSUMDB=github.com/your-org/*
```

`go env -w` persists the settings for every shell.

| Variable | Purpose |
|----------|---------|
| `GOPROXY` | Routes every module fetch through Pantera. There is deliberately **no `,direct`** fallback: with it, any 404 from Pantera sends `go` straight to the VCS host, bypassing the cache, cooldown and audit. Resolve through a group or proxy repository to get public modules too. |
| `GONOSUMDB` | Private module path prefixes (comma-separated globs) that skip the public checksum database; public modules stay verified. Use `GONOSUMDB`, not `GOPRIVATE`: `GOPRIVATE` also makes `go` bypass the proxy for those modules. |

### CI/CD Configuration

In CI/CD pipelines, write `~/.netrc` from secrets and set the environment variables:

```yaml
# GitHub Actions example
env:
  GOPROXY: "https://pantera-host/go-group"
  GONOSUMDB: "github.com/your-org/*"
steps:
  - run: |
      printf 'machine pantera-host login %s password %s\n' "${{ secrets.PANTERA_USER }}" "${{ secrets.PANTERA_TOKEN }}" >> ~/.netrc
      chmod 600 ~/.netrc
```

### Plain HTTP Registries

The `go` command never sends credentials over plain HTTP: it refuses `user:token@` in an `http://` `GOPROXY`, and `~/.netrc` / `GOAUTH` apply to https only. `GOINSECURE` does not affect `GOPROXY`, and `-insecure` no longer exists. Since Pantera requires authentication, an `http://` registry cannot serve the `go` command — ask your administrator to serve Pantera over HTTPS. Until then, curl can download and publish module files with the same `~/.netrc`:

```bash
curl --netrc -fsS http://pantera-host:8080/go-group/rsc.io/quote/@v/list
curl --netrc -fsSLO http://pantera-host:8080/go-group/rsc.io/quote/@v/v1.5.2.zip
```

---

## Fetch Modules

Once `GOPROXY` is configured, standard Go commands work as expected:

```bash
go get rsc.io/quote@v1.5.2
go mod download
go build ./...
go list -m -versions rsc.io/quote   # verify: lists the versions through Pantera
```

The proxy caches downloaded modules. Subsequent fetches from any developer or CI pipeline are served from cache.

---

## Publishing Modules

The `go` command has no publish verb. Publish to a `go` local repository by uploading the module's `.info`, `.mod` and `.zip` files with curl.

**1. Package the module** — in the module root, after committing and tagging:

```bash
MOD=$(go list -m)
VER=v0.1.0
ESC=$(printf %s "$MOD" | perl -pe 's/([A-Z])/!\l$1/g')
STAGE=$(mktemp -d)
mkdir -p "$STAGE/$MOD@$VER"
git archive HEAD | tar -x -C "$STAGE/$MOD@$VER"
(cd "$STAGE" && zip -qrD "$VER.zip" "$MOD@$VER")
cp go.mod "$STAGE/$VER.mod"
printf '{"Version":"%s","Time":"%s"}\n' "$VER" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STAGE/$VER.info"
```

`git archive` packs the committed files as `go` would fetch them from VCS. `zip -D` omits directory entries, which is required for `go mod verify` to pass for consumers. Uppercase letters in the module path are escaped (`!` + lowercase) for the URL, as the Go module protocol requires. Search shows and finds the module under its real path (`github.com/BurntSushi/toml`, not `github.com/!burnt!sushi/toml`).

**2. Upload** — `.info` and `.mod` first, `.zip` last (the zip adds the version to `@v/list`). Each upload answers `201`:

```bash
for f in info mod zip; do
  curl --netrc -fsS -T "$STAGE/$VER.$f" "https://pantera-host/go-local/$ESC/@v/$VER.$f"
done
```

**Never reuse a version.** Go versions are immutable: republishing one with different content breaks every consumer whose `go.sum` already recorded it. Bump `VER` instead.

---

## Go Proxy (`go-proxy`)

A `go-proxy` repository caches modules from an upstream Go module proxy (typically `https://proxy.golang.org`) on first request, then serves subsequent requests from the local cache. Cached bytes survive upstream outages and are shared across all clients pointing at the same Pantera host.

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

A `go-group` repository is a virtual repository that fans out requests across a list of member repositories (`go` locals and `go-proxy` proxies) in resolution order. For each file (`.info`, `.mod`, `.zip`), the first member that serves it wins. The version list (`@v/list`) combines the lists of every member, so `go list -m -versions` shows both your private versions and the upstream ones. Groups do not store artifacts themselves — they delegate to members.

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

Clients set `GOPROXY` to the group URL (`https://pantera-host/go-group`); Pantera handles fan-out. See the [Management UI guide](../ui-guide.md#adding-members-to-a-group-repository) for how to add, reorder, and create members from the web interface.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `410 Gone` | Module not found upstream and cached as absent | Clear the negative cache; ask admin to check proxy config |
| `401 Unauthorized` | Token missing or expired, or the `~/.netrc` entry does not match the host | Regenerate the token and update `~/.netrc` (add a `host:port` line if the URL has a port) |
| `401` or refused credentials with an `http://` `GOPROXY` | Go never sends credentials over plain HTTP | Serve Pantera over HTTPS; meanwhile use curl (see [Plain HTTP Registries](#plain-http-registries)) |
| `verifying module: checksum mismatch` | Sum database mismatch for a private module | Add the module's path prefix to `GONOSUMDB` |
| `go mod verify` fails for a published module | Zip built with directory entries | Rebuild with `zip -qrD` and publish a new version |
| `go: module not found` | Module is genuinely missing, or you resolve from a local repository | Verify the module path and version exist upstream; resolve through a group or proxy |

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

- [Getting Started](../getting-started.md) -- Obtaining API tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
