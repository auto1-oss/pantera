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
