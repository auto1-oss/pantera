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
helm repo add pantera http://pantera-host:8080/helm-repo \
  --username your-username \
  --password your-jwt-token
```

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
curl -X PUT \
  -H "Authorization: Basic $(echo -n your-username:your-jwt-token | base64)" \
  --data-binary @my-chart-1.0.0.tgz \
  http://pantera-host:8080/helm-repo/my-chart-1.0.0.tgz
```

### Step 3: Update the Repository Index

After pushing, update your local Helm repository cache:

```bash
helm repo update
```

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Expired JWT token | Re-add the repo with a fresh token: `helm repo remove pantera && helm repo add ...` |
| `Error: looks like "http://..." is not a valid chart repository` | Wrong URL or server not reachable | Verify the URL includes the correct repository name and port |
| Chart not found after push | Local index not updated | Run `helm repo update` after pushing a new chart |
| `Error: chart requires kubeVersion` | Kubernetes version mismatch | Not a Pantera issue; check chart requirements |
| Push returns `405 Method Not Allowed` | Pushing to a non-Helm repository | Verify you are pushing to a repository with `type: helm` |

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
