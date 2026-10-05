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

The catalog is per repository (`/v2/<repo>/_catalog`) and lists full image names with the repository prefix (`docker-local/myapp`, `docker-local/team/tools/builder`); there is no registry-wide `/v2/_catalog`. A proxy repository's catalog lists the images it has cached. A group's catalog is the union of its members' catalogs, named under the group (`docker-group/myapp`), which are the names you pull through the group; each member contributes only if you hold the `catalog` permission on it.

Both endpoints page with `?n=<count>&last=<name>`. On every repository type, a full page (catalog or tags) carries a `Link: <...>; rel="next"` header pointing at the next page. The catalog's `last` is a name from an earlier page, including the repository prefix. A `last` outside the repository (`400 NAME_INVALID`) or an `n` that is not a non-negative integer (`400 PAGINATION_NUMBER_INVALID`) is rejected. A tags request for an image the repository does not hold answers `404 NAME_UNKNOWN`, on every page (with or without `last`); a `last` past the final tag of an image the repository holds answers an empty page. Through a group, the tags pages of an image come from the first member that holds it, so following the group's `Link` keeps paging that member's tags.

---

## Delete Images

Local (`docker`) repositories support the Distribution-spec delete endpoints, so `skopeo delete`, `crane delete` and manual cleanup work. They need the `delete` action in `docker_repository_permissions`; `pull`, `push` and `overwrite` do not include it.

```bash
# Delete an image (skopeo resolves the tag to its digest and deletes that)
skopeo delete --creds 'your-username:your-api-token' \
    docker://pantera-host:8080/docker-local/myapp:1.0.0

# Delete one tag only; the image stays pullable by digest
curl -X DELETE -u 'your-username:your-api-token' \
    http://pantera-host:8080/v2/docker-local/myapp/manifests/1.0.0

# Delete a blob of this image by digest (before deleting the manifest, see below)
curl -X DELETE -u 'your-username:your-api-token' \
    http://pantera-host:8080/v2/docker-local/myapp/blobs/sha256:<digest>
```

| Request | Effect |
|---------|--------|
| `DELETE /v2/<repo>/<image>/manifests/<tag>` | Removes that tag only. The manifest and any other tags pointing at it stay, and the image stays pullable by digest |
| `DELETE /v2/<repo>/<image>/manifests/<digest>` | Removes the manifest and every tag that points at it. This is what `skopeo delete` sends, so it removes all tags of that image |
| `DELETE /v2/<repo>/<image>/blobs/<digest>` | Removes the blob's data, if only this image uses it (see below). The image's manifests are left in place |

Manifest deletes answer `202 Accepted`, or `404 MANIFEST_UNKNOWN` for an unknown tag or digest. Removed tags disappear from search. Deleting a manifest never deletes its blobs.

Blobs are stored once per registry and can be shared by several images, so a blob delete is checked against every image:

| Blob state | Answer |
|------------|--------|
| No manifest of `<image>` is, or references, the digest (including a blob that was uploaded but never referenced by a manifest) | `404 BLOB_UNKNOWN` |
| A manifest of any other image also references the digest, or another image's manifest cannot be read to rule that out | `409` with code `DENIED`; nothing is removed |
| Only `<image>` references it | `202 Accepted`; the blob data is removed |

A blob can therefore only be deleted while a manifest of the image still references it: to free an image's layers, delete its blobs first and its manifest last. Once the manifest is gone, its blobs answer `404` and cannot be removed through the registry API. Deleting a blob that a remaining manifest references makes that manifest unpullable.

The registry API deletes only on local (`docker`) repositories. On `docker-proxy` and `docker-group` repositories the same requests answer `405 UNSUPPORTED` (so `skopeo delete` and `crane delete` report the operation as unsupported, not the image as missing). Docker repositories have no `immutable` setting and no `DELETE /<repo>/<path>` file delete; moving an existing tag is governed by the `overwrite` action.

Alternatively, delete a tag from a local repository in the UI (repository browser), or with the REST API (needs `api_repository_permissions: delete`):

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
| `skopeo delete` / `crane delete` reports `unsupported` (405 `UNSUPPORTED`) | The target is a `docker-proxy` or `docker-group` repository | Delete against the local (`docker`) repository directly |
| `skopeo delete` / `crane delete` reports `denied` (403) | The user lacks the `delete` action on the image | Ask the admin to grant `delete` in `docker_repository_permissions` |
| Blob `DELETE` answers `404 BLOB_UNKNOWN` although the blob exists | No manifest of that image references the digest (the manifest was already deleted, or the blob was never referenced) | Delete blobs before the manifest that references them |
| Blob `DELETE` answers `409 DENIED` | Another image in the registry references the same blob | Nothing to do: the blob is still in use. Delete the other images first if the blob must go |
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
