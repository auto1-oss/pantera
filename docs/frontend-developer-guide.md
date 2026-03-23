# Pantera Frontend Developer Guide

**Version:** 2.0.0
**Maintained by:** Auto1 Group DevOps Team
**Repository:** [auto1-oss/pantera](https://github.com/auto1-oss/pantera)

This guide covers the architecture, patterns, and workflows for contributing to the Pantera Management UI — the Vue.js web application in the `pantera-ui/` module. For the Java backend, see the [Developer Guide](developer-guide.md).

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Development Setup](#2-development-setup)
3. [Project Structure](#3-project-structure)
4. [Architecture Patterns](#4-architecture-patterns)
5. [Adding a New Page](#5-adding-a-new-page)
6. [Adding a New API Endpoint](#6-adding-a-new-api-endpoint)
7. [Testing](#7-testing)
8. [Code Style and Conventions](#8-code-style-and-conventions)

---

## 1. Introduction

The Pantera Management UI is a standalone single-page application that communicates with the Pantera REST API over HTTP.

### Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | Vue 3 (Composition API, `<script setup>`) | 3.5 |
| Language | TypeScript (strict mode) | 5.7 |
| Build Tool | Vite | 6.2 |
| Components | PrimeVue (Aura theme preset) | 4.3 |
| Styling | Tailwind CSS | 4.1 |
| State | Pinia | 2.3 |
| HTTP Client | Axios | 1.7 |
| Testing | Vitest + happy-dom | 3.0 |
| Linting | ESLint (flat config) | 9.0 |

For quick-start commands and Docker setup, see the [pantera-ui README](../pantera-ui/README.md).

---

## 2. Development Setup

### Prerequisites

- Node.js 22+
- npm 10+
- A running Pantera backend with the API on port 8086 (see [Developer Guide: Development Setup](developer-guide.md#11-development-setup))

### Getting Started

```bash
cd pantera-ui
npm install
npm run dev
```

The Vite dev server starts on `http://localhost:8090` and proxies `/api` requests to `http://localhost:8086` (configurable in `vite.config.ts`).

### Local Configuration

For local development without Docker, edit `public/config.json` directly:

```json
{
  "apiBaseUrl": "/api/v1",
  "grafanaUrl": "",
  "appTitle": "Pantera",
  "defaultPageSize": 20
}
```

This file is loaded by `fetch('/config.json')` in `main.ts` before Vue mounts. It is not processed by the Vite build pipeline.

### Available Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start Vite dev server with hot reload |
| `npm run build` | Type-check with `vue-tsc`, then Vite production build to `dist/` |
| `npm run preview` | Preview the production build locally |
| `npm test` | Run unit tests (Vitest, single run) |
| `npm run test:watch` | Run tests in watch mode |
| `npm run type-check` | TypeScript type checking only |
| `npm run lint` | ESLint |

---

## 3. Project Structure

```
pantera-ui/
  src/
    main.ts              # Bootstrap: loads config.json, initializes Pinia, Vue, Router
    App.vue              # Root component, renders router-view
    api/                 # Axios HTTP client and per-domain API modules
      client.ts          # Shared Axios instance, JWT interceptor, 401 handling
      auth.ts            # Auth endpoints (login, SSO, token management)
      repos.ts           # Repository CRUD, tree navigation, artifact details
      search.ts          # Full-text artifact search
      users.ts           # User management
      roles.ts           # Role management
      settings.ts        # System settings, health, dashboard, storage aliases
    assets/
      main.css           # Global Tailwind + PrimeIcons styles
    components/
      layout/
        AppLayout.vue    # Master layout with header and sidebar
        AppHeader.vue    # Top navigation bar
        AppSidebar.vue   # Left sidebar with nav links and permission guards
      common/
        HealthIndicator.vue  # Backend health status dot
        RepoTypeBadge.vue    # Repository type badge
    composables/         # Reusable reactive logic
      usePermission.ts   # Permission checking
      usePagination.ts   # Offset-based pagination
      useCursorPagination.ts  # Cursor-based pagination
      useDebouncedSearch.ts   # Search input debounce
      useConfirmDelete.ts     # Delete confirmation dialog
    router/
      index.ts           # Route definitions and auth guards
    stores/              # Pinia state management
      auth.ts            # JWT, user info, permissions, admin status
      config.ts          # Runtime config from config.json
      theme.ts           # Dark mode toggle
      notifications.ts   # Toast notifications
    types/
      index.ts           # TypeScript interfaces (User, Repo, Role, Settings, etc.)
    views/               # Page components, one per route
      admin/             # Admin-only: repo mgmt, users, roles, storage, cooldown, settings
      auth/              # Login, OAuth callback
      dashboard/         # Dashboard with stats and charts
      profile/           # User profile and API token management
      repos/             # Repository list and detail browser
      search/            # Full-text artifact search
  public/
    config.json          # Runtime config (loaded at startup)
    config.json.template # Docker envsubst template
```

**Organization principles:**
- Views are grouped by feature domain, not by technical layer.
- API modules mirror backend resource groups (one module per REST resource).
- One Pinia store per concern (auth, config, theme, notifications).
- Composables extract reusable reactive logic shared across 2+ views.

---

## 4. Architecture Patterns

### 4.1 API Client Layer

All HTTP communication goes through a shared Axios instance created in `src/api/client.ts`:

```typescript
// src/api/client.ts -- simplified
export function initApiClient(baseUrl: string): AxiosInstance {
  apiClient = axios.create({ baseURL: baseUrl, timeout: 10_000 })

  // Auto-inject JWT bearer token
  apiClient.interceptors.request.use((config) => {
    const token = sessionStorage.getItem('jwt')
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })

  // Auto-logout on 401 (except public auth endpoints)
  apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
      if (error.response?.status === 401) {
        const url = error.config?.url ?? ''
        if (!url.includes('/auth/token') && !url.includes('/auth/providers') && !url.includes('/auth/callback')) {
          sessionStorage.removeItem('jwt')
          window.location.href = '/login'
        }
      }
      return Promise.reject(error)
    },
  )
  return apiClient
}
```

Per-domain modules (`auth.ts`, `repos.ts`, `users.ts`, etc.) import `getApiClient()` and export typed async functions:

```typescript
// src/api/repos.ts -- example
import { getApiClient } from './client'
import type { RepoListResponse } from '@/types'

export async function listRepos(page: number, size: number, query?: string): Promise<RepoListResponse> {
  const { data } = await getApiClient().get('/repositories', { params: { page, size, q: query } })
  return data
}
```

**To add a new API module:** Create `src/api/newresource.ts`, import `getApiClient()`, export typed functions, and add the response types to `src/types/index.ts`.

### 4.2 Pinia Stores

| Store | Import | Responsibility |
|-------|--------|---------------|
| `auth` | `useAuthStore()` | JWT in sessionStorage, current user, permissions, `isAdmin` computed, `hasAction(key, action)` |
| `config` | `useConfigStore()` | Runtime config loaded from `config.json` at bootstrap |
| `theme` | `useThemeStore()` | Dark mode toggle (currently dark-only) |
| `notifications` | `useNotificationStore()` | Toast notifications (success, error, info) |

**Permission model:** The auth store's `user.permissions` is a `Record<string, string[]>` where keys follow the pattern `api_<resource>_permissions` and values are action arrays like `['read']`, `['read', 'write']`, or `['*']` (wildcard).

```typescript
// src/stores/auth.ts -- key methods
function hasAction(key: string, action: string): boolean {
  const perms = user.value?.permissions ?? {}
  const val = perms[key]
  return Array.isArray(val) && (val.includes(action) || val.includes('*'))
}

const isAdmin = computed(() => {
  if (!user.value) return false
  // OR condition: either permission grants admin status
  return hasAction('api_user_permissions', 'write')
    || hasAction('api_role_permissions', 'write')
})
```

### 4.3 Router and Auth Guards

Routes are defined in `src/router/index.ts` with meta fields controlling access:

| Meta Field | Type | Effect |
|-----------|------|--------|
| `meta.public` | `boolean` | Skips auth check (login, OAuth callback) |
| `meta.requiredPermission` | `string` | Permission key needed (e.g., `api_user_permissions`) |
| `meta.requiresAdmin` | `boolean` | Must be admin (settings page) |

The global `beforeEach` guard (async) enforces this:

1. If route is public -- allow.
2. If not authenticated -- redirect to `/login?redirect=<original>`.
3. If token exists but user object is null (page refresh) -- await `auth.fetchUser()` to restore session. If the JWT expired, `fetchUser` triggers logout and the guard re-checks, redirecting to login.
4. If route has `requiresAdmin` and user is not admin -- redirect to dashboard.
5. If route has `requiredPermission` -- admin routes (`/admin/*`) require write access; other routes require read access. Redirect to dashboard if insufficient.

```typescript
// src/router/index.ts:120-153 -- guard logic
router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const auth = useAuthStore()
  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.user) {
    await auth.fetchUser()
    if (!auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'dashboard' }
  }
  const requiredPerm = to.meta.requiredPermission as string | undefined
  if (requiredPerm) {
    const needsWrite = to.path.startsWith('/admin/')
    if (needsWrite && !auth.hasAction(requiredPerm, 'write')) return { name: 'dashboard' }
    if (!needsWrite && !auth.hasAction(requiredPerm, 'read')) return { name: 'dashboard' }
  }
  return true
})
```

### 4.4 Composables

| Composable | Purpose | Used In |
|------------|---------|---------|
| `usePermission(resource)` | Returns `hasPermission` computed for the given resource | Admin views, action button visibility |
| `usePagination()` | Offset-based pagination state and API integration | User list, role list |
| `useCursorPagination()` | Cursor-based pagination for large datasets | Repo list, search results |
| `useDebouncedSearch()` | Debounced search input (300ms) with reactive query | Search page, repo filter |
| `useConfirmDelete()` | Delete confirmation dialog state and callbacks | All admin CRUD views |

**When to create a composable:** When the same reactive logic appears in 2+ views. Keep each composable focused on a single concern.

### 4.5 PrimeVue and Tailwind

- **PrimeVue** provides interactive components (DataTable, Dialog, Button, InputText, Dropdown, etc.) with the Aura theme preset and an Auto1 custom color override.
- **Tailwind CSS** handles layout, spacing, and utility styling.

**Convention:** Use PrimeVue for interactive elements (buttons, forms, tables, dialogs). Use Tailwind for structural styling (flex, grid, padding, margins). Do not mix PrimeVue's built-in style utilities with Tailwind for the same property.

---

## 5. Adding a New Page

This walkthrough adds a hypothetical admin page. Adapt the pattern for non-admin pages by omitting the permission meta fields.

### Step 1: Define types

Add request/response interfaces to `src/types/index.ts`:

```typescript
export interface Widget {
  id: string
  name: string
  status: 'active' | 'inactive'
}
export interface WidgetListResponse {
  items: Widget[]
  total: number
}
```

### Step 2: Create the API module

Create `src/api/widgets.ts`:

```typescript
import { getApiClient } from './client'
import type { WidgetListResponse } from '@/types'

export async function listWidgets(page: number, size: number): Promise<WidgetListResponse> {
  const { data } = await getApiClient().get('/widgets', { params: { page, size } })
  return data
}
```

### Step 3: Create the view component

Create `src/views/admin/WidgetListView.vue`:

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useConfigStore } from '@/stores/config'
import { useNotificationStore } from '@/stores/notifications'
import { listWidgets } from '@/api/widgets'
import type { Widget } from '@/types'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'

const config = useConfigStore()
const notifications = useNotificationStore()
const widgets = ref<Widget[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const resp = await listWidgets(0, config.defaultPageSize)
    widgets.value = resp.items
  } catch (e) {
    notifications.error('Failed to load widgets')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="p-4">
    <h1 class="text-2xl font-bold mb-4">Widgets</h1>
    <DataTable :value="widgets" :loading="loading">
      <Column field="name" header="Name" />
      <Column field="status" header="Status" />
    </DataTable>
  </div>
</template>
```

### Step 4: Add the route

In `src/router/index.ts`, add:

```typescript
{
  path: '/admin/widgets',
  name: 'admin-widgets',
  component: () => import('@/views/admin/WidgetListView.vue'),
  meta: { requiredPermission: 'api_widget_permissions' },
},
```

### Step 5: Add the sidebar link

In `src/components/layout/AppSidebar.vue`, add a `router-link` under the admin section, guarded by a permission check:

```vue
<router-link v-if="auth.hasAction('api_widget_permissions', 'write')" to="/admin/widgets">
  <i class="pi pi-cog" /> Widgets
</router-link>
```

### Step 6: Test

Write `src/views/admin/WidgetListView.spec.ts` (see [Testing](#7-testing) for patterns).

---

## 6. Adding a New API Endpoint

To call a new backend endpoint from an existing view:

1. **Add the type** to `src/types/index.ts`.
2. **Add the function** to the appropriate `src/api/*.ts` module:

```typescript
export async function deleteWidget(id: string): Promise<void> {
  await getApiClient().delete(`/widgets/${id}`)
}
```

3. **Call from the view** inside an event handler:

```typescript
import { deleteWidget } from '@/api/widgets'
import { useNotificationStore } from '@/stores/notifications'

const notifications = useNotificationStore()

async function handleDelete(id: string) {
  try {
    await deleteWidget(id)
    notifications.success('Widget deleted')
    await load() // refresh the list
  } catch {
    notifications.error('Failed to delete widget')
  }
}
```

---

## 7. Testing

### Framework

Vitest with happy-dom provides a virtual DOM environment — no browser is needed.

### Running Tests

| Command | Description |
|---------|-------------|
| `npm test` | Single run, CI-friendly |
| `npm run test:watch` | Watch mode for development |

### Test Location

Tests are co-located with source files as `*.spec.ts` or `*.test.ts`:

```
src/
  composables/
    usePermission.ts
    usePermission.spec.ts
  views/admin/
    WidgetListView.vue
    WidgetListView.spec.ts
```

### Testing Patterns

**Composables:** Create a Pinia instance, set up the store state, then call the composable:

```typescript
import { describe, it, expect } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePermission } from '@/composables/usePermission'
import { useAuthStore } from '@/stores/auth'

describe('usePermission', () => {
  it('returns false when user has no permissions', () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = { name: 'test', permissions: {} } as any
    const { hasPermission } = usePermission('repository')
    expect(hasPermission.value).toBe(false)
  })
})
```

**API modules:** Mock Axios using `vi.mock` or test against the actual types.

**Views:** Mount with `@vue/test-utils`, provide a router and Pinia store, and assert rendered output.

---

## 8. Code Style and Conventions

- **Composition API only** -- All components use `<script setup lang="ts">`. No Options API.
- **TypeScript strict** -- All variables and function parameters must be typed.
- **Path alias** -- `@/` resolves to `src/`. Use it in all imports.
- **File naming** -- PascalCase for `.vue` files (e.g., `RepoListView.vue`). camelCase for `.ts` files (e.g., `usePermission.ts`). Views end with `View.vue`. Composables start with `use`.
- **Linting** -- Run `npm run lint` before committing. ESLint 9.x flat config is used.
- **No Options API** -- Do not introduce `defineComponent()` or options-style code in new files.

---

## Related Pages

- [Developer Guide](developer-guide.md) -- Backend (Java) architecture and contributor guide
- [REST API Reference](rest-api-reference.md) -- All API endpoints called by the UI
- [Admin Guide: UI Deployment](admin-guide/ui-deployment.md) -- Deploying and operating the UI
- [User Guide: Management UI](user-guide/ui-guide.md) -- End-user feature walkthrough
