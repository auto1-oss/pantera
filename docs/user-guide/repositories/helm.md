# Helm

> **Guide:** User Guide | **Section:** Repositories / Helm

This page covers how to configure the Helm client to search, install, and push charts to Pantera.

---

## Prerequisites

- Helm 3.x
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Add Repository

Register the Pantera Helm repository with your Helm client:

```bash
printf '%s' 'your-api-token' | helm repo add pantera http://pantera-host:8080/helm-repo \
  --username 'your-username' --password-stdin
```

The token is read from stdin so it stays out of the process list. A repository without any chart has no `index.yaml` yet, so `helm repo add` answers `404` until the first chart is uploaded.

Update the local repository index:

```bash
helm repo update
```

Verify connectivity:

```bash
helm repo list
```

---

## Search Charts

Search for charts in the Pantera repository:

```bash
# Search for a chart by name
helm search repo pantera/my-chart

# List all charts
helm search repo pantera/

# Search with version constraints
helm search repo pantera/my-chart --version ">=1.0.0"
```

---

## Install Charts

Install a chart from Pantera:

```bash
helm install my-release pantera/my-chart

# With a specific version
helm install my-release pantera/my-chart --version 1.2.0

# With custom values
helm install my-release pantera/my-chart -f values.yaml

# Dry-run first
helm install my-release pantera/my-chart --dry-run
```

Upgrade an existing release:

```bash
helm upgrade my-release pantera/my-chart --version 1.3.0
```

---

## Push Charts

### Step 1: Package the Chart

```bash
helm package ./my-chart/
# Creates: my-chart-1.0.0.tgz
```

### Step 2: Upload to Pantera

Use curl to upload the packaged chart:

```bash
curl -fsS -u 'your-username:your-api-token' \
  --upload-file my-chart-1.0.0.tgz \
  http://pantera-host:8080/helm-repo/my-chart-1.0.0.tgz
```

The chart is stored as `<chart-name>/<chart-name>-<version>.tgz`, whatever file name the upload URL uses.

### Step 3: Update the Repository Index

After pushing, update your local Helm repository cache:

```bash
helm repo update
```

### Re-pushing a Version

In a repository with the **Immutable artifacts** setting on (the default for new repositories), pushing a chart version that is already stored answers `409 Conflict` (`Chart <name> version <version> already exists and the repository is immutable`), even when the archive is identical. When the setting is off, a push by a user with `write` replaces the archive and its `index.yaml` entry (digest, creation time, URLs). Helm repositories that existed before Pantera 2.2.10 in a database-backed installation keep accepting re-pushes until the administrator turns the setting on. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

### Delete a Chart

With the `delete` permission, delete one version, or every version of a chart, through the chart API:

```bash
# One version
curl -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/helm-repo/charts/my-chart/1.0.0

# Every version of the chart
curl -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/helm-repo/charts/my-chart
```

An HTTP `DELETE` of the archive's storage path (`/helm-repo/my-chart/my-chart-1.0.0.tgz`) or of the chart's directory (`/helm-repo/my-chart`) works too; it answers `204`, or `404` when nothing is stored there. Either way the deleted versions are removed from `index.yaml`. See [Delete an artifact](../getting-started.md#delete-an-artifact).

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Expired JWT token | Re-add the repo with a fresh token: `helm repo remove pantera && helm repo add ...` |
| `Error: looks like "http://..." is not a valid chart repository` | Wrong URL or server not reachable | Verify the URL includes the correct repository name and port |
| Chart not found after push | Local index not updated | Run `helm repo update` after pushing a new chart |
| `Error: chart requires kubeVersion` | Kubernetes version mismatch | Not a Pantera issue; check chart requirements |
| Push returns `405 Method Not Allowed` | Pushing to a non-Helm repository | Verify you are pushing to a repository with `type: helm` |
| Push returns `409 Conflict` | That chart version is already stored and the repository is immutable | Bump the chart `version`, or delete the stored version first |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Local repository:**

```yaml
# helm-repo.yaml
repo:
  type: helm
  url: "http://pantera-host:8080/helm-repo/"
  storage:
    type: fs
    path: /var/pantera/data
```

Note: The `url` field is required for Helm repositories so that `index.yaml` contains the correct download URLs.

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
