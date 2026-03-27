# Generic Files

> **Guide:** User Guide | **Section:** Repositories / Generic Files

This page covers how to upload, download, and browse arbitrary files stored in Pantera's generic file repositories.

---

## Prerequisites

- curl, wget, or any HTTP client
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Upload via curl

Upload any file to a generic file repository:

```bash
curl -X PUT \
  -H "Authorization: Basic $(echo -n your-username:your-jwt-token | base64)" \
  --data-binary @myfile.tar.gz \
  http://pantera-host:8080/bin/path/to/myfile.tar.gz
```

The path after the repository name (`bin/`) becomes the storage path. You can organize files into directories:

```bash
# Upload with directory structure
curl -X PUT \
  -H "Authorization: Basic $(echo -n your-username:your-jwt-token | base64)" \
  --data-binary @release-1.0.0.zip \
  http://pantera-host:8080/bin/releases/v1.0.0/release-1.0.0.zip
```

---

## Download via curl

Download a file:

```bash
curl -o myfile.tar.gz \
  http://pantera-host:8080/bin/path/to/myfile.tar.gz
```

With authentication (if required by your repository):

```bash
curl -o myfile.tar.gz \
  -H "Authorization: Basic $(echo -n your-username:your-jwt-token | base64)" \
  http://pantera-host:8080/bin/path/to/myfile.tar.gz
```

Using wget:

```bash
wget http://pantera-host:8080/bin/path/to/myfile.tar.gz
```

---

## Using Proxy

A file proxy repository caches files from an upstream HTTP server:

```bash
# Fetch through the proxy (cached after first request)
curl -o tool.tar.gz \
  http://pantera-host:8080/file-proxy/path/to/tool.tar.gz
```

---

## Directory Browsing

Pantera supports directory listing for file repositories. Access a directory path in your browser or via curl to see its contents:

```bash
# List root contents
curl http://pantera-host:8080/bin/

# List a subdirectory
curl http://pantera-host:8080/bin/releases/
```

You can also browse file repositories through the Management UI by navigating to the repository detail page.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Token missing or expired | Regenerate your JWT token |
| `404 Not Found` on download | File does not exist at the specified path | Verify the exact file path (paths are case-sensitive) |
| `405 Method Not Allowed` | Using POST instead of PUT for upload | Use `PUT` method for uploads |
| Upload succeeds but file cannot be downloaded | Different repository name for upload and download | Ensure both operations target the same repository |
| Large file upload times out | Proxy or server timeout | Ask admin to increase `proxy_timeout` and Nginx `client_max_body_size` |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Local repository:**

```yaml
# bin.yaml
repo:
  type: file
  storage:
    type: fs
    path: /var/pantera/data/bin
```

**Proxy repository:**

```yaml
# file-proxy.yaml
repo:
  type: file-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://releases.example.com
```

**Group repository:**

```yaml
# file-group.yaml
repo:
  type: file-group
  members:
    - bin
    - file-proxy
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
