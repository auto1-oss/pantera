# Artipie UI

Web management console for [Artipie](https://github.com/artipie/artipie) — a binary artifact repository manager.

Built with Vue 3, TypeScript, Vite, PrimeVue 4, and Tailwind CSS 4.

## Prerequisites

- Node.js 22+
- npm 10+
- A running Artipie instance with the `/api/v1/*` endpoints (AsyncApiVerticle on port 8086)

## Quick Start (Development)

```bash
# Install dependencies
npm install

# Start dev server (http://localhost:3000)
npm run dev
```

The dev server proxies `/api` requests to `http://localhost:8086` (configurable in `vite.config.ts`).

## Available Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start Vite dev server with hot reload |
| `npm run build` | Type-check and build for production |
| `npm run preview` | Preview the production build locally |
| `npm test` | Run unit tests (Vitest) |
| `npm run test:watch` | Run tests in watch mode |
| `npm run type-check` | Run TypeScript type checking |

## Production Build

```bash
npm run build
```

Output is written to `dist/`. The build runs `vue-tsc --noEmit` for type checking before `vite build`.

## Docker

### Standalone

```bash
# Build the image
docker build -t artipie-ui .

# Run (assumes Artipie is reachable at http://artipie:8086 from within Docker network)
docker run -d -p 8090:80 --name artipie-ui artipie-ui
```

### With Docker Compose

The UI is included in the main Artipie `docker-compose.yaml`. It builds from this directory and is available at **http://localhost:8090**.

```bash
cd ../artipie-main/docker-compose
docker compose up -d
```

The compose nginx service also exposes the UI at **http://localhost:8081/ui/**.

## Runtime Configuration

The UI reads `config.json` at startup (before Vue mounts). In Docker, configuration is driven by **environment variables** — no rebuild or volume mount required.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `API_BASE_URL` | Base URL for API requests (relative or absolute) | `/api/v1` |
| `GRAFANA_URL` | Link to Grafana dashboard (shown on Dashboard page) | _(empty)_ |
| `APP_TITLE` | Application title in header and login page | `Artipie` |
| `DEFAULT_PAGE_SIZE` | Default page size for paginated lists | `20` |

```bash
docker run -d -p 8090:80 \
  -e API_BASE_URL=https://registry.example.com/api/v1 \
  -e GRAFANA_URL=https://grafana.example.com \
  -e APP_TITLE="My Registry" \
  -e DEFAULT_PAGE_SIZE=50 \
  artipie-ui
```

The Docker entrypoint generates `config.json` from these variables at container startup using `envsubst`. When no variables are set, the defaults above apply.

### Docker Compose

In `docker-compose.yaml`, pass variables with the `UI_` prefix to avoid collisions:

```yaml
artipie-ui:
  environment:
    - API_BASE_URL=${UI_API_BASE_URL:-/api/v1}
    - GRAFANA_URL=${UI_GRAFANA_URL:-http://localhost:3000}
    - APP_TITLE=${UI_APP_TITLE:-Artipie}
    - DEFAULT_PAGE_SIZE=${UI_DEFAULT_PAGE_SIZE:-20}
```

### Local Development

For local development without Docker, edit `public/config.json` directly. This file is loaded by `fetch('/config.json')` in `main.ts` and is not processed by Vite's build pipeline.

## Architecture

```
src/
  api/           # Axios HTTP client and per-domain API modules
  assets/        # Global CSS (Tailwind + PrimeIcons)
  components/    # Reusable components (layout shell, health indicator)
  composables/   # Vue composables (pagination, search, permissions)
  router/        # Vue Router with auth guards
  stores/        # Pinia stores (auth, config, theme, notifications)
  types/         # TypeScript interfaces matching API responses
  views/         # Page components organized by feature
    admin/       # Admin-only pages (repo mgmt, users, roles, settings)
    auth/        # Login, OAuth callback
    dashboard/   # Dashboard with stats
    profile/     # User profile
    repos/       # Repository list and detail
    search/      # Global artifact search
```

## Nginx Proxy

The built-in nginx config (`nginx/default.conf`) handles:

- **`/api/`** -- Proxied to `http://artipie:8086/api/` (Artipie REST API)
- **`/assets/`** -- Immutable hashed assets with 1-year cache
- **`/`** -- SPA fallback (`try_files` to `index.html`)

## Tech Stack

- **Vue 3.5** -- Composition API with `<script setup>`
- **TypeScript 5** -- Strict mode
- **Vite 6** -- Build toolchain
- **PrimeVue 4** -- Component library (Aura theme preset)
- **Tailwind CSS 4** -- Utility-first styling
- **Pinia** -- State management
- **Axios** -- HTTP client with JWT interceptors
- **Vitest** -- Unit testing with happy-dom
