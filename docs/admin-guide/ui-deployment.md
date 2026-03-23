# UI Deployment & Operations

> **Guide:** Admin Guide | **Section:** UI Deployment

The Pantera Management UI is a Vue.js single-page application served by nginx inside a Docker container. It communicates with the Pantera REST API (port 8086) and stores no state server-side — the JWT session token lives in the browser's session storage.

```
Browser -> nginx (port 8090) -> /api/ proxy -> Pantera API (port 8086)
```

---

## Deployment Options

### Docker Compose (Recommended)

The UI is included in the standard Docker Compose stack. Running `docker compose up -d` starts it alongside the backend:

```yaml
pantera-ui:
  build:
    context: ../../pantera-ui
    dockerfile: Dockerfile
  container_name: pantera-ui
  restart: unless-stopped
  depends_on:
    - pantera
  environment:
    - API_BASE_URL=http://localhost:8086/api/v1
    - GRAFANA_URL=http://localhost:3000/goto/bfgfvn3efggsge?orgId=1
    - APP_TITLE=Pantera
    - DEFAULT_PAGE_SIZE=25
  ports:
    - "8090:80"
  networks:
    - pantera-net
```

The UI is available at `http://localhost:8090`. The compose nginx service also exposes it at `http://localhost:8081/ui/`.

### Standalone Docker

For deploying the UI separately from the backend:

```bash
# Build the image
cd pantera-ui
docker build -t pantera-ui .

# Run with environment variables
docker run -d -p 8090:80 \
  -e API_BASE_URL=https://registry.example.com/api/v1 \
  -e GRAFANA_URL=https://grafana.example.com \
  -e APP_TITLE="My Registry" \
  -e DEFAULT_PAGE_SIZE=50 \
  --name pantera-ui \
  pantera-ui
```

---

## Environment Variable Reference

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `API_BASE_URL` | Base URL for API requests (relative or absolute) | `/api/v1` | `https://registry.example.com/api/v1` |
| `GRAFANA_URL` | Link to Grafana dashboard (shown on Dashboard page) | _(empty)_ | `https://grafana.example.com/d/pantera` |
| `APP_TITLE` | Application title in header and login page | `Artipie` | `Pantera` |
| `DEFAULT_PAGE_SIZE` | Default page size for paginated lists | `20` | `50` |

The `APP_TITLE` container default is `Artipie` (inherited from the upstream project). The Docker Compose file overrides this to `Pantera`. Similarly, `DEFAULT_PAGE_SIZE` defaults to `20` in the container but the compose file sets it to `25`.

**How it works:** On container startup, `docker-entrypoint.sh` runs `envsubst` on `config.json.template` to produce `config.json`. The Vue app loads this file via `fetch('/config.json')` before mounting. No rebuild is needed to change configuration — restart the container with new env vars.

---

## Connecting to the Backend API

### Relative Path (Default)

When `API_BASE_URL` is a relative path like `/api/v1`, the browser sends API requests to the same origin as the UI. The UI's built-in nginx proxies `/api/` to `http://pantera:8086/api/`:

```nginx
location /api/ {
    proxy_pass http://pantera:8086/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 10s;
}
```

This works out of the box with Docker Compose where both containers share a network.

### Absolute URL

When the UI and API are on different hosts (separate Kubernetes services, CDN for static assets, etc.), set `API_BASE_URL` to the full API URL:

```bash
-e API_BASE_URL=https://api.registry.example.com/api/v1
```

In this mode, the browser makes cross-origin requests directly to the API. You may need to configure CORS headers on the backend if the origins differ.

---

## Reverse Proxy Configuration

### Path-Based Routing

The Docker Compose stack includes an nginx reverse proxy that routes `/ui/` to the UI container and `/` to the main Pantera server:

```nginx
# Routes /ui/* to UI container
location /ui/ {
    proxy_pass http://pantera-ui:80/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

# Routes /* to main Pantera server (repository traffic)
location / {
    proxy_pass http://pantera:8080;
    # ... headers, timeouts, buffering config
}
```

The trailing slash in `proxy_pass http://pantera-ui:80/` is important — it strips the `/ui/` prefix so the SPA receives clean paths.

### Subdomain Routing

If the UI runs on its own subdomain (e.g., `ui.registry.example.com`), proxy everything to the UI container:

```nginx
server {
    server_name ui.registry.example.com;

    location / {
        proxy_pass http://pantera-ui:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

In both cases, set `API_BASE_URL` so the browser can reach the backend — either as a relative path (if the same proxy forwards `/api/`) or as an absolute URL.

---

## SSO Redirect URIs

When configuring Okta or Keycloak for SSO, register the UI's OAuth callback URL as a valid redirect URI in the identity provider:

| Deployment | Redirect URI |
|-----------|-------------|
| Path-based proxy | `https://registry.example.com/ui/auth/callback` |
| Subdomain | `https://ui.registry.example.com/auth/callback` |
| Direct access | `http://pantera-host:8090/auth/callback` |
| Local development | `http://localhost:8090/auth/callback` |

The UI constructs the callback URL automatically using `window.location.origin + '/auth/callback'`. Ensure this matches exactly what is registered in the provider — a mismatch causes the SSO redirect to fail.

See [Authentication](authentication.md) for provider-specific configuration.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Blank page after login | `API_BASE_URL` incorrect or unreachable | Open browser DevTools (Network tab), verify API calls reach the backend. Check that the URL resolves from the browser, not just from the server. |
| SSO login redirects fail | Callback URL not registered in IdP | Add the UI's public `/auth/callback` URL to the provider's allowed redirect URIs. The URL must match exactly, including protocol and port. |
| 502 Bad Gateway on API calls | Nginx cannot reach the Pantera backend | Verify the backend container is running (`docker compose ps`). Check they share the same Docker network. Test with `docker exec pantera-ui curl http://pantera:8086/api/v1/health`. |
| CORS errors in browser console | UI and API on different origins | Use a relative `API_BASE_URL` behind a shared reverse proxy, or configure CORS on the backend to allow the UI's origin. |
| Config changes not taking effect | Container using stale `config.json` | Restart the container — `docker-entrypoint.sh` regenerates `config.json` from env vars on each start. |
| UI loads but shows "API client not initialized" | `config.json` missing or malformed | Check that `config.json.template` exists in the image and env vars don't contain invalid characters. Inspect the generated file: `docker exec pantera-ui cat /usr/share/nginx/html/config.json`. |

---

## Related Pages

- [Installation](installation.md) -- Full stack deployment including the UI
- [Authentication](authentication.md) -- SSO provider configuration
- [Monitoring](monitoring.md) -- Grafana dashboards linked from the UI
- [User Guide: Management UI](../user-guide/ui-guide.md) -- End-user feature walkthrough
