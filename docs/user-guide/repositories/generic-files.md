# Generic Files

> **Guide:** User Guide | **Section:** Repositories / Generic Files

This page covers how to upload, download, and browse arbitrary files stored in Pantera's generic file repositories.

---

## Prerequisites

- curl, wget, or any HTTP client
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Store Credentials

Keep the token off the command line with `~/.netrc` (`machine` is the host name without the port). curl reads it with `--netrc`; wget reads it automatically:

```bash
cat >> ~/.netrc <<'EOF'
machine pantera-host
login your-username
password your-api-token
EOF
chmod 600 ~/.netrc
```

Without `~/.netrc`, pass `-u 'your-username:your-api-token'` to curl or `--user='your-username' --password='your-api-token'` to wget on each command.

---

## Upload via curl

Upload any file to a generic file repository. `-T` sends an HTTP PUT; the server answers `201 Created`:

```bash
curl -f --netrc -T myfile.tar.gz \
  http://pantera-host:8080/bin/path/to/myfile.tar.gz
```

The path after the repository name (`bin/`) becomes the storage path. You can organize files into directories:

```bash
# Upload with directory structure
curl -f --netrc -T release-1.0.0.zip \
  http://pantera-host:8080/bin/releases/v1.0.0/release-1.0.0.zip
```

With wget 1.15 or later:

```bash
wget -nv -O /dev/null --method=PUT --body-file=myfile.tar.gz \
  http://pantera-host:8080/bin/path/to/myfile.tar.gz
```

---

## Download via curl

Anonymous reads are denied by default, so downloads authenticate too:

```bash
curl -fL --netrc -O http://pantera-host:8080/bin/path/to/myfile.tar.gz
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
curl -fL --netrc -o tool.tar.gz \
  http://pantera-host:8080/file-proxy/path/to/tool.tar.gz
```

---

## Directory Browsing

Pantera supports directory listing for file repositories. Request a directory path ending with `/` to get a plain-text list of the files under it (one repository-relative path per line, including files in subdirectories):

```bash
# List root contents
curl -u your-username:your-jwt-token http://pantera-host:8080/bin/

# List a subdirectory
curl -u your-username:your-jwt-token http://pantera-host:8080/bin/releases/

# Same listing as a JSON array
curl -u your-username:your-jwt-token -H 'Accept: application/json' http://pantera-host:8080/bin/releases/
```

The `Accept` header selects the format: `text/plain` (the default for a path ending with `/`, including `Accept: */*`), `application/json`, or exactly `text/html` (a flat list of links). A browser, whose `Accept` header lists `text/html` among other types, gets the HTML directory index page, one directory level at a time.

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
