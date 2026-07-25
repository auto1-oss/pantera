# Docker

> **Guide:** User Guide | **Section:** Repositories / Docker

This page covers how to configure the Docker (or Podman) client to pull images from and push images to Pantera.

---

## Prerequisites

- Docker Engine 20.10+ or Podman
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Configure Docker Daemon

If your Pantera instance does not use TLS (HTTPS), you must add it as an insecure registry. Edit `/etc/docker/daemon.json`:

```json
{
  "insecure-registries": ["pantera-host:8080"]
}
```

Then restart the Docker daemon:

```bash
sudo systemctl restart docker
```

If Pantera is behind an Nginx reverse proxy with TLS termination (e.g., on port 8443), this step is not needed.

---

## Login

Authenticate with your Pantera credentials:

```bash
docker login pantera-host:8080 -u your-username -p your-jwt-token
```

Or interactively:

```bash
docker login pantera-host:8080
# Username: your-username
# Password: your-jwt-token
```

The credentials are stored in `~/.docker/config.json` for subsequent operations.

> Pantera authenticates Docker clients via Basic auth / JWT-as-password on
> every request — it does not run a separate OCI/Docker bearer
> token-issuing server (`/token`). This is not a gap: `docker login` /
> `docker pull` / `docker push` all work as shown above; a dedicated
> token server is simply not part of the design.

---

## Pull Images

### Through a Proxy Repository

Pull images from upstream registries (Docker Hub, GCR, ECR, etc.) through a Pantera proxy:

```bash
# Pull ubuntu through the docker proxy
docker pull pantera-host:8080/docker-proxy/library/ubuntu:22.04

# Pull nginx
docker pull pantera-host:8080/docker-proxy/library/nginx:latest

# Pull a non-library image
docker pull pantera-host:8080/docker-proxy/grafana/grafana:latest
```

The first pull fetches from upstream and caches locally. Subsequent pulls are served from cache.

### Through a Group Repository

If a Docker group is configured, all pulls go through one URL:

```bash
docker pull pantera-host:8080/docker-group/library/ubuntu:22.04
```

`tags/list` and `_catalog` against a group return the **union** of every
member's tags/repositories (deduplicated) — a tag published to any member is
visible through the group's listing endpoints. A manifest or blob GET still
resolves against the **first** member that has it (first-2xx-wins), which is
the correct behavior for content-addressed pulls. If a member is temporarily
unreachable, the listing degrades to whatever the remaining members can
answer rather than failing outright.

---

## Push Images

Push images to a local Docker repository:

### Step 1: Tag the Image

```bash
docker tag myapp:latest pantera-host:8080/docker-local/myapp:latest
docker tag myapp:latest pantera-host:8080/docker-local/myapp:1.0.0
```

### Step 2: Push

```bash
docker push pantera-host:8080/docker-local/myapp:latest
docker push pantera-host:8080/docker-local/myapp:1.0.0
```

---

## Delete Images / Garbage Collection

Local (`docker`) repositories support the standard Distribution-spec delete
endpoints, so `skopeo delete` and manual cleanup both work:

```bash
# Resolve the tag to its digest and delete the manifest reference
skopeo delete docker://pantera-host:8080/docker-local/myapp:1.0.0

# Delete an unreferenced blob directly by digest (registry GC)
curl -X DELETE -u your-username:your-jwt-token \
    https://pantera-host:8080/v2/docker-local/myapp/blobs/sha256:<digest>
```

Deleting a manifest removes the tag/digest reference (and, if it was pushed
with an OCI `subject`, its referrers-index entry) — it returns `202
Accepted`. It does **not** delete the underlying blob: blobs are
content-addressed and may be shared by other manifests, so blob removal is
the separate `DELETE .../blobs/<digest>` call, matching standard registry GC
behavior. Deleting a reference or digest that does not exist returns `404`.

**Scope:** delete is wired for **hosted (`docker`) repositories only** —
`docker-proxy` and `docker-group` reject `DELETE` with `405 Method Not
Allowed`; deletes always target the authoritative store, never a proxy
cache or a group.

---

## Chunked Blob Uploads

Clients that push a layer as a sequence of `PATCH` chunks (large-layer
pushes via `oras`/`skopeo`, rather than Docker/BuildKit's single monolithic
`PATCH`) are fully supported: each chunk is validated for contiguity against
its `Content-Range` header and assembled in order before the final `PUT
?digest=` verifies the assembled bytes against the claimed digest. A
non-contiguous chunk (one that does not start where the upload actually left
off) is rejected with `416 Requested Range Not Satisfiable` rather than
silently accepted out of order.

---

## OCI 1.1 Referrers (cosign, oras, notation, SBOM)

Local (`docker`) repositories index and serve OCI 1.1 referrers, so tools that
attach signatures, SBOMs, and other artifacts by digest work against a hosted
repository:

```bash
# Sign an image and discover the signature via the referrers API
cosign sign --tlog-upload=false pantera-host:8080/docker-local/myapp:latest
cosign verify --insecure-ignore-tlog pantera-host:8080/docker-local/myapp:latest

# Attach an arbitrary artifact (e.g. an SBOM) and discover it
oras attach --artifact-type application/vnd.example.sbom.v1+json \
    pantera-host:8080/docker-local/myapp:latest ./sbom.json
oras discover pantera-host:8080/docker-local/myapp:latest
```

A manifest pushed with an OCI `subject` field is indexed against that subject;
`GET /v2/<name>/referrers/<digest>` always returns `200` with an OCI Image
Index (empty when nothing is indexed, per spec). The push response for a
subject-bearing manifest carries an `OCI-Subject: <digest>` header. Narrow a
listing with `?artifactType=<type>` — the response then carries an
`OCI-Filters-Applied: artifactType` header.

**Scope:** referrers are indexed and served for **hosted (`docker`)
repositories only**. `docker-proxy` and `docker-group` repositories always
answer with an empty referrers listing — proxying an upstream registry's own
referrers, and a fallback `sha256-<digest>` tag-schema index for registries
without the referrers API, are not implemented.

---

## Multi-Registry Proxy

A single Docker proxy repository can cache images from multiple upstream registries. This is useful when your builds pull from Docker Hub, GCR, Elastic, and Kubernetes registries:

```bash
# All of these go through the same proxy
docker pull pantera-host:8080/docker-proxy/library/ubuntu:22.04      # Docker Hub
docker pull pantera-host:8080/docker-proxy/elasticsearch:8.12.0       # Docker Hub (elastic)
```

The proxy tries each configured upstream in order until it finds the requested image.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `http: server gave HTTP response to HTTPS client` | Docker expects HTTPS by default | Add Pantera to `insecure-registries` in `daemon.json` |
| `unauthorized: authentication required` | Not logged in or token expired | Run `docker login` with a fresh JWT token |
| `denied: requested access to the resource is denied` | User lacks push permission | Contact admin for write access to the Docker local repository |
| `manifest unknown` | Image not cached in proxy yet | Verify the image path matches upstream (include `library/` for official images) |
| Push fails with `500 Internal Server Error` | Large layer upload timeout | Ask admin to increase `proxy_timeout` and check Nginx `client_max_body_size` |
| Pull is slow for first request | Image being fetched from upstream for the first time | This is expected; subsequent pulls will be fast from cache |
| `EOF` during push | Connection reset, often from proxy/LB | Increase timeouts in Nginx (`proxy_read_timeout 300s`) and set `client_max_body_size 0` |
| `skopeo delete` / blob `DELETE` returns `405 Method Not Allowed` | Target is a `docker-proxy` or `docker-group` repository | Delete against the hosted (`docker`) repository directly — proxy/group repos are read-through, not authoritative |
| Chunked push fails with `416 Requested Range Not Satisfiable` | A `PATCH` chunk's `Content-Range` start does not match the bytes already received | Restart the upload (`POST` a new session) — chunks must be sent strictly in order with no gaps or overlaps |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Local repository:**

```yaml
# docker-local.yaml
repo:
  type: docker
  storage:
    type: fs
    path: /var/pantera/data
```

**Proxy repository (multiple upstreams):**

```yaml
# docker-proxy.yaml
repo:
  type: docker-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://registry-1.docker.io
    - url: https://docker.elastic.co
    - url: https://gcr.io
    - url: https://k8s.gcr.io
```

**Group repository:**

```yaml
# docker-group.yaml
repo:
  type: docker-group
  members:
    - docker-local
    - docker-proxy
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
- [REST API Reference](../../rest-api-reference.md) -- Repository management endpoints
