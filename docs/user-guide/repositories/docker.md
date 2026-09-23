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

Authenticate with your Pantera credentials (`--password-stdin` keeps the token out of the process list):

```bash
echo 'your-api-token' | docker login pantera-host:8080 -u 'your-username' --password-stdin
```

Or interactively:

```bash
docker login pantera-host:8080
# Username: your-username
# Password: your-jwt-token
```

The credentials are stored in `~/.docker/config.json` for subsequent operations.

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

---

## Push Images

Push images to a local Docker repository. Proxy and group repositories are read-only: a push to them fails with `405 UNSUPPORTED` (`docker push` reports `unsupported`).

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

Blob uploads may be monolithic or chunked (several `PATCH` requests with `Content-Range: <start>-<end>`, then the committing `PUT`), so resumable pushes from tools such as `crane`, `oras` or `skopeo` work. A chunk whose start is not the end of the data already uploaded is refused with `416` and a `Range: 0-<last byte held>` header; resume from there.

---

## List Images and Tags

```bash
# Images in a repository (needs the 'catalog' registry permission)
curl -u 'your-username:your-api-token' http://pantera-host:8080/v2/docker-local/_catalog

# Tags of an image
curl -u 'your-username:your-api-token' http://pantera-host:8080/v2/docker-local/myapp/tags/list
```

The catalog is per repository (`/v2/<repo>/_catalog`) and lists names with the repository prefix (`docker-local/myapp`); there is no registry-wide `/v2/_catalog`. Both endpoints page with `?n=<count>&last=<name>`; a full tags page carries a `Link: <...>; rel="next"` header pointing at the next page. A tags request for an image the repository does not hold answers `404 NAME_UNKNOWN`.

---

## Delete Images

The registry API does not delete: `DELETE /v2/<repo>/<image>/manifests/<reference>` and `DELETE /v2/<repo>/<image>/blobs/<digest>` answer `405 UNSUPPORTED` (so `skopeo delete` and `crane delete` report the operation as unsupported, not the image as missing).

Delete a tag from a local repository in the UI (repository browser), or with the REST API (needs `api_repository_permissions: delete`):

```bash
curl -X DELETE http://pantera-host:8086/api/v1/repositories/docker-local/packages \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path": "docker/registry/v2/repositories/myapp/_manifests/tags/1.0.0"}'
```

The `path` is the tag's storage folder: `docker/registry/v2/repositories/<image>/_manifests/tags/<tag>`. See [REST API Reference](../../rest-api-reference.md#delete-apiv1repositoriesnamepackages).

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
| `denied` when re-pushing an existing tag (e.g. `latest`) with new content | Moving an existing tag needs the `overwrite` action on top of `push` | Push a new tag, or ask the admin to grant `overwrite` |
| Push fails with `unsupported` (405) | The target is a proxy or group repository | Push to a local (`docker`) repository instead |
| `name unknown` (404 `NAME_UNKNOWN`) on a tags list | The repository holds no tags for that image name | Check the image path (`<repo>/<image>`, include `library/` for official images) |
| `size invalid` (413 `SIZE_INVALID`) during push | A layer exceeds the server's request-body limit | Ask the admin to raise the limit |
| `manifest unknown` | Image not cached in proxy yet | Verify the image path matches upstream (include `library/` for official images) |
| Push fails with `500 Internal Server Error` | Large layer upload timeout | Ask admin to increase `proxy_timeout` and check Nginx `client_max_body_size` |
| Pull is slow for first request | Image being fetched from upstream for the first time | This is expected; subsequent pulls will be fast from cache |
| `EOF` during push | Connection reset, often from proxy/LB | Increase timeouts in Nginx (`proxy_read_timeout 300s`) and set `client_max_body_size 0` |

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
