// Pagination
export interface PaginatedResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
  hasMore: boolean
}

export interface CursorResponse<T> {
  items: T[]
  marker: string | null
  hasMore: boolean
}

export interface ApiError {
  error: string
  message: string
  status: number
}

// Auth
export interface AuthProvider {
  type: string
  enabled: boolean
  priority?: number
}

export interface AuthProvidersResponse {
  providers: AuthProvider[]
}

export interface TokenResponse {
  token: string
}

export interface UserInfo {
  name: string
  context: string
  permissions: Record<string, string[]>
  can_delete_artifacts?: boolean
  email?: string
  groups?: string[]
}

export interface RepoListItem {
  name: string
  type: string
}

// Repository
export interface Repository {
  name: string
  type: string
  config: Record<string, unknown>
  enabled?: boolean
  created_at?: string
  updated_at?: string
}

export interface RepoMember {
  name: string
  type: string
  enabled: boolean
}

// Artifact
export interface TreeEntry {
  name: string
  path: string
  type: 'file' | 'directory'
  size?: number
  modified?: string
}

export interface ArtifactDetail {
  path: string
  name: string
  size: number
  modified?: string
  checksums?: Record<string, string>
}

export interface PullInstructions {
  type: string
  instructions: string[]
}

// Search
export interface SearchResult {
  repo_type: string
  repo_name: string
  artifact_path: string
  artifact_name?: string
  version?: string
  size: number
  created_at?: string
  owner?: string
}

export interface LocateResponse {
  repositories: string[]
  count: number
}

export interface ReindexResponse {
  status: string
  message: string
}

// Dashboard
export interface DashboardStats {
  repo_count: number
  artifact_count: number
  total_storage: number | string
  blocked_count: number
  top_repos?: { name: string; type: string; artifact_count: number; size?: number }[]
}

export interface ReposByType {
  types: Record<string, number>
}

// User
export interface User {
  name: string
  email?: string
  enabled?: boolean
  auth_provider?: string
  roles?: string[]
  created_at?: string
}

// Role
export interface Role {
  name: string
  permissions: Record<string, unknown>
  enabled?: boolean
  created_at?: string
}

// Storage Alias
export interface StorageAlias {
  name: string
  repo_name?: string | null
  config: Record<string, unknown>
  type?: string
}

// Cooldown
export interface CooldownRepo {
  name: string
  type: string
  cooldown: string
  active_blocks?: number
}

export interface BlockedArtifact {
  package_name: string
  version: string
  repo: string
  repo_type: string
  reason: string
  blocked_date: string
  blocked_until: string
  remaining_hours: number
}

// Cooldown config
export interface CooldownConfig {
  enabled: boolean
  minimum_allowed_age: string
  repo_types?: Record<string, {
    enabled: boolean
    minimum_allowed_age?: string
  }>
}

// Settings
export interface Settings {
  port: number
  version: string
  prefixes: string[]
  jwt?: {
    expires: boolean
    expiry_seconds: number
  }
  http_client?: {
    proxy_timeout: number
    connection_timeout: number
    idle_timeout: number
    follow_redirects: boolean
    connection_acquire_timeout: number
    max_connections_per_destination: number
    max_requests_queued_per_destination: number
  }
  http_server?: {
    request_timeout: string
  }
  metrics?: {
    enabled: boolean
    endpoint?: string
    port?: number
    jvm: boolean
    http: boolean
    storage: boolean
  }
  cooldown?: {
    enabled: boolean
    minimum_allowed_age: string
  }
  credentials?: Array<{
    id: number
    type: string
    priority: number
    enabled: boolean
    config?: Record<string, string>
  }>
  database?: {
    configured: boolean
  }
  caches?: {
    valkey_configured: boolean
  }
  ui?: {
    grafana_url?: string
  }
}

// Health
export interface HealthResponse {
  status: string
}

// Runtime config (config.json)
export interface RuntimeConfig {
  apiBaseUrl: string
  grafanaUrl: string
  appTitle: string
  defaultPageSize: number
  apmEnabled: boolean
  apmServerUrl: string
  apmServiceName: string
  apmEnvironment: string
  registryUrl: string
}
