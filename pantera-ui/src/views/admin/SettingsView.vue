<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  getSettings, updatePrefixes, updateSettingsSection,
  getCooldownConfig, updateCooldownConfig,
} from '@/api/settings'
import {
  getAuthSettings, updateAuthSettings,
  getCircuitBreakerSettings, updateCircuitBreakerSettings,
  getUpstreamBreakerSettings, updateUpstreamBreakerSettings,
  getClientBaseUrlSettings, updateClientBaseUrlSettings,
} from '@/api/auth'
import type { RuntimeSettingKey } from '@/api/runtimeSettings'
import { useRuntimeSettings } from '@/composables/useRuntimeSettings'
import { useConfigStore } from '@/stores/config'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import InputSwitch from 'primevue/inputswitch'
import AutoComplete from 'primevue/autocomplete'
import Tag from 'primevue/tag'
import type { Settings, CooldownConfig } from '@/types'

const config = useConfigStore()
const notify = useNotificationStore()
const uiVersion = __APP_VERSION__

const settings = ref<Settings | null>(null)
const loading = ref(true)
const saving = ref<string | null>(null)

/**
 * Section identifier used by the unified save bar to track per-section
 * dirty state and tell admins which parts of the page took effect
 * immediately vs require a restart. The id keys into {@link SECTION_META}.
 */
type SectionId =
  | 'prefixes' | 'jwt' | 'auth' | 'circuit_breaker' | 'upstream_breaker'
  | 'cooldown' | 'http_client' | 'bulkhead' | 'http_server'
  | 'external_links' | 'client_base_url'

interface SectionMeta {
  /** Human-readable name shown in toasts and the dirty list. */
  label: string
  /**
   * True when a server-side listener applies the value to live state
   * (e.g. {@code cooldown} + {@code http_client.bulkhead.*} have explicit
   * {@code addListener} hooks in {@code VertxMain.java}), OR the value
   * is read through a supplier on the request path. False when the
   * value is consumed once at startup and the new value is dormant
   * until the next process boot.
   */
  hotReload: boolean
  /**
   * Short reason shown in the inline restart pill + the post-save
   * summary. Only meaningful when {@code hotReload} is false.
   */
  restartReason?: string
}

/**
 * Source-of-truth for hot-reload vs restart-required behaviour per
 * settings section. Driven by the actual server-side listener wiring
 * in {@code VertxMain.java} ({@code settingsCache.addListener}) and
 * the supplier patterns in {@code RepositorySlices} /
 * {@code CooldownSupport} / {@code JwtTokens}. When you wire a new
 * hot-reload listener on the server, flip {@code hotReload} here
 * and drop the {@code restartReason}.
 */
const SECTION_META: Record<SectionId, SectionMeta> = {
  prefixes: {
    label: 'Global Path Prefixes',
    hotReload: true,
  },
  jwt: {
    label: 'JWT / Session',
    hotReload: true,
  },
  auth: {
    label: 'Authentication Policy',
    hotReload: true,
  },
  circuit_breaker: {
    label: 'Group Member Circuit Breaker',
    hotReload: true,
  },
  upstream_breaker: {
    label: 'Upstream HTTP Circuit Breaker',
    hotReload: true,
  },
  cooldown: {
    label: 'Cooldown Configuration',
    hotReload: true,
  },
  http_client: {
    label: 'HTTP Client (Proxy)',
    hotReload: true,
  },
  bulkhead: {
    label: 'Bulkhead (Adaptive Concurrency)',
    hotReload: true,
  },
  http_server: {
    label: 'HTTP Server',
    hotReload: false,
    restartReason:
      'request_timeout is read once at startup via VertxMain.listenOn; '
      + 'the server must be restarted for the new value to bind.',
  },
  external_links: {
    label: 'External Links',
    hotReload: true,
  },
  client_base_url: {
    label: 'Client-Facing Base URL (Canonical Override / Forwarded Headers / Host Allowlist)',
    hotReload: true,
  },
}

// Editable state
const prefixes = ref('')
const jwtExpires = ref(false)
const jwtExpirySeconds = ref(86400)
const httpProxyTimeout = ref(60)
const httpConnTimeout = ref(15000)
const httpIdleTimeout = ref(30000)
const httpFollowRedirects = ref(true)
const httpAcquireTimeout = ref(30000)
const httpMaxConns = ref(64)
const httpMaxQueued = ref(256)
const httpServerTimeout = ref('PT2M')

// Auth policy
const authAccessTtl = ref(3600)
const authRefreshTtl = ref(604800)
const authApiMaxTtl = ref(7776000)
const authAllowPermanent = ref(true)

// Group-member circuit breaker (rate-over-sliding-window, 2.2.0)
const cbFailureRatePercent = ref(50)     // stored as 0.0-1.0 on server, shown as % here
const cbMinCalls = ref(20)
const cbWindowSeconds = ref(30)
const cbInitialBlockSeconds = ref(20)
const cbMaxBlockSeconds = ref(300)

// Upstream HTTP circuit breaker (outbound per-endpoint breaker) —
// distinct from the group-member breaker above: this one gates
// outbound HTTP calls per upstream scheme://host:port.
const ubRatePct = ref(50)                // stored as 0.0-1.0 on server, shown as % here
const ubMinCalls = ref(10)
const ubWindowSeconds = ref(30)
const ubSeedBackoffSeconds = ref(2)
const ubMaxBackoffSeconds = ref(3600)

// Client-facing base URL derivation (ClientBaseUrl, pantera-core) — governs
// absolute URLs Pantera emits (e.g. npm dist.tarball) when a repository has
// no explicit url: configured. Three settings: the canonical override
// (clientBaseUrl) takes precedence over the other two entirely when set —
// it enforces the origin for every repository without url: and stops Host /
// X-Forwarded-* from being consulted at all for those repos. Otherwise:
// whether to trust reverse-proxy X-Forwarded-* headers, and which Host
// values may be used at all. clientBaseHostAllowlist is edited as a
// comma-separated string and split/joined the same way `prefixes` is.
const trustForwardedHeaders = ref(false)
const clientBaseHostAllowlist = ref('')
const clientBaseUrl = ref('')

// Cooldown config
const cooldownConfig = ref<CooldownConfig | null>(null)
const cooldownEnabled = ref(false)
const cooldownAge = ref('7d')
const cooldownHistoryRetentionDays = ref(90)
const cooldownCleanupBatchLimit = ref(10000)
const newRepoType = ref('')
// SNAPSHOT-only cooldown — applies a stricter window to Maven/Gradle SNAPSHOT
// timestamped artifacts. Empty fields fall through to the global cooldown.
const cooldownSnapshotEnabled = ref<boolean | null>(null)
const cooldownSnapshotAge = ref('')

// Proxy repo types for autocomplete
const allProxyTypes = [
  'maven-proxy', 'docker-proxy', 'npm-proxy', 'pypi-proxy',
  'helm-proxy', 'go-proxy', 'nuget-proxy', 'debian-proxy',
  'rpm-proxy', 'conda-proxy', 'gem-proxy', 'conan-proxy',
  'hexpm-proxy', 'php-proxy', 'file-proxy',
]
const proxyTypeSuggestions = ref<string[]>([])
function searchProxyTypes(event: { query: string }) {
  const q = (event.query ?? '').toLowerCase()
  const existing = new Set(Object.keys(cooldownConfig.value?.repo_types ?? {}))
  const available = allProxyTypes.filter(t => !existing.has(t))
  if (!q) {
    proxyTypeSuggestions.value = available
  } else {
    proxyTypeSuggestions.value = available.filter(t => t.includes(q))
  }
}

// External links
const grafanaUrl = ref('')
const registryUrl = ref('')

// Runtime tunables (per-repo bulkhead). Loaded into the same view so
// admins have one place for everything that lives in the settings DB.
const runtime = useRuntimeSettings()

interface IntRange { min: number; max: number }
const RUNTIME_INT_RANGES: Record<RuntimeSettingKey, IntRange | null> = {
  'http_client.bulkhead.adaptive': null,
  'http_client.bulkhead.min_permits': { min: 1, max: 1000 },
  'http_client.bulkhead.max_permits': { min: 1, max: 5000 },
  'http_client.bulkhead.initial_permits': { min: 1, max: 5000 },
  'http_client.bulkhead.target_p99_ms': { min: 1, max: 60_000 },
  'http_client.bulkhead.window_seconds': { min: 1, max: 600 },
  'http_client.bulkhead.ramp_up_step': { min: 1, max: 100 },
  'http_client.bulkhead.ramp_down_factor': null,
}

interface DoubleRange { min: number; max: number; step: number }
const RUNTIME_DOUBLE_RANGES: Partial<Record<RuntimeSettingKey, DoubleRange>> = {
  'http_client.bulkhead.ramp_down_factor': { min: 0.05, max: 0.95, step: 0.05 },
}

const BULKHEAD_RUNTIME_KEYS: RuntimeSettingKey[] = [
  'http_client.bulkhead.adaptive',
  'http_client.bulkhead.min_permits',
  'http_client.bulkhead.max_permits',
  'http_client.bulkhead.initial_permits',
  'http_client.bulkhead.target_p99_ms',
  'http_client.bulkhead.window_seconds',
  'http_client.bulkhead.ramp_up_step',
  'http_client.bulkhead.ramp_down_factor',
]

const RUNTIME_LABELS: Record<RuntimeSettingKey, string> = {
  'http_client.bulkhead.adaptive': 'Adaptive (AIMD)',
  'http_client.bulkhead.min_permits': 'Minimum permits',
  'http_client.bulkhead.max_permits': 'Maximum permits',
  'http_client.bulkhead.initial_permits': 'Initial permits',
  'http_client.bulkhead.target_p99_ms': 'Target p99 latency (ms)',
  'http_client.bulkhead.window_seconds': 'Evaluation window (seconds)',
  'http_client.bulkhead.ramp_up_step': 'Ramp-up step',
  'http_client.bulkhead.ramp_down_factor': 'Ramp-down factor',
}

const RUNTIME_HELP: Partial<Record<RuntimeSettingKey, string>> = {
  'http_client.bulkhead.adaptive':
    'When on, each per-repo bulkhead AIMD-tunes its in-flight permit ceiling '
    + 'on every evaluation window. When off, the ceiling stays at "Initial permits".',
  'http_client.bulkhead.min_permits':
    'Lower bound on the dynamic permit ceiling. AIMD never shrinks below this.',
  'http_client.bulkhead.max_permits':
    'Hard cap on concurrent in-flight requests per repository. AIMD never grows above this.',
  'http_client.bulkhead.initial_permits':
    'Starting permit ceiling when a bulkhead is (re)created. Must lie between min and max.',
  'http_client.bulkhead.target_p99_ms':
    'Upstream latency target. Windows whose peak latency exceeds 2× this trigger a soft ramp-down; '
    + 'windows at or below this and free of errors trigger ramp-up.',
  'http_client.bulkhead.window_seconds':
    'How often the AIMD controller evaluates the last window of outcomes.',
  'http_client.bulkhead.ramp_up_step':
    'Permits added to the ceiling per healthy window (additive increase).',
  'http_client.bulkhead.ramp_down_factor':
    'Multiplier on the ceiling when a window contains errors (multiplicative decrease). '
    + 'Lower = more aggressive back-off.',
}

onMounted(async () => {
  try {
    const [s, cd] = await Promise.all([
      getSettings(),
      getCooldownConfig().catch(() => null),
    ])
    settings.value = s
    prefixes.value = (s.prefixes ?? []).join(', ')
    grafanaUrl.value = s.ui?.grafana_url ?? config.grafanaUrl
    registryUrl.value = s.ui?.registry_url ?? config.registryUrl
    if (s.jwt) {
      jwtExpires.value = s.jwt.expires
      jwtExpirySeconds.value = s.jwt.expiry_seconds
    }
    if (s.http_client) {
      httpProxyTimeout.value = s.http_client.proxy_timeout
      httpConnTimeout.value = s.http_client.connection_timeout
      httpIdleTimeout.value = s.http_client.idle_timeout
      httpFollowRedirects.value = s.http_client.follow_redirects
      httpAcquireTimeout.value = s.http_client.connection_acquire_timeout
      httpMaxConns.value = s.http_client.max_connections_per_destination
      httpMaxQueued.value = s.http_client.max_requests_queued_per_destination
    }
    if (s.http_server) {
      httpServerTimeout.value = s.http_server.request_timeout
    }
    if (cd) {
      cooldownConfig.value = cd
      cooldownEnabled.value = cd.enabled
      cooldownAge.value = cd.minimum_allowed_age
      cooldownHistoryRetentionDays.value = cd.history_retention_days ?? 90
      cooldownCleanupBatchLimit.value = cd.cleanup_batch_limit ?? 10000
      cooldownSnapshotEnabled.value = cd.snapshots?.enabled ?? null
      cooldownSnapshotAge.value = cd.snapshots?.minimum_allowed_age ?? ''
    }
    // Run the secondary loaders together and AWAIT them before
    // snapshotting the baseline; otherwise the baseline captures
    // un-filled defaults and the dirty bar flashes on every mount.
    await Promise.allSettled([
      getAuthSettings().then(s => {
        authAccessTtl.value = parseInt(s.access_token_ttl_seconds ?? '3600')
        authRefreshTtl.value = parseInt(s.refresh_token_ttl_seconds ?? '604800')
        authApiMaxTtl.value = parseInt(s.api_token_max_ttl_seconds ?? '7776000')
        authAllowPermanent.value = s.api_token_allow_permanent === 'true'
      }),
      getCircuitBreakerSettings().then(s => {
        cbFailureRatePercent.value = Math.round(
          parseFloat(s.circuit_breaker_failure_rate_threshold ?? '0.5') * 100,
        )
        cbMinCalls.value = parseInt(s.circuit_breaker_minimum_number_of_calls ?? '20')
        cbWindowSeconds.value = parseInt(s.circuit_breaker_sliding_window_seconds ?? '30')
        cbInitialBlockSeconds.value = parseInt(s.circuit_breaker_initial_block_seconds ?? '20')
        cbMaxBlockSeconds.value = parseInt(s.circuit_breaker_max_block_seconds ?? '300')
      }),
      getUpstreamBreakerSettings().then(s => {
        ubRatePct.value = Math.round(
          parseFloat(s.upstream_breaker_failure_rate_threshold ?? '0.5') * 100,
        )
        ubMinCalls.value = parseInt(s.upstream_breaker_minimum_calls ?? '10')
        ubWindowSeconds.value = parseInt(s.upstream_breaker_window_seconds ?? '30')
        ubSeedBackoffSeconds.value = parseInt(s.upstream_breaker_seed_backoff_seconds ?? '2')
        ubMaxBackoffSeconds.value = parseInt(s.upstream_breaker_max_backoff_seconds ?? '3600')
      }),
      getClientBaseUrlSettings().then(s => {
        trustForwardedHeaders.value = s.trust_forwarded_headers === 'true'
        clientBaseHostAllowlist.value = s.client_base_host_allowlist ?? ''
        clientBaseUrl.value = s.client_base_url ?? ''
      }),
      runtime.load(),
    ])
  } catch {
    notify.error('Failed to load settings')
  } finally {
    loading.value = false
    // Once every field has its loaded value, snapshot the baseline so
    // the unified save bar starts with dirtyCount=0.
    baseline.value = snapshot()
  }
})

const jwtExpiryHours = computed(() => {
  const s = jwtExpirySeconds.value
  if (s >= 86400 && s % 86400 === 0) return `${s / 86400}d`
  if (s >= 3600 && s % 3600 === 0) return `${s / 3600}h`
  if (s >= 60 && s % 60 === 0) return `${s / 60}m`
  return `${s}s`
})

const repoTypeOverrides = computed(() => {
  if (!cooldownConfig.value?.repo_types) return []
  return Object.entries(cooldownConfig.value.repo_types).map(([name, cfg]) => ({
    name,
    enabled: cfg.enabled,
    minimum_allowed_age: cfg.minimum_allowed_age ?? cooldownAge.value,
  }))
})

async function savePrefixes() {
  saving.value = 'prefixes'
  try {
    const list = prefixes.value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    await updatePrefixes(list)
    notify.success('Prefixes updated')
  } catch {
    notify.error('Failed to update prefixes')
  } finally {
    saving.value = null
  }
}

async function saveSection(section: string, data: Record<string, unknown>) {
  saving.value = section
  try {
    await updateSettingsSection(section, data)
    notify.success(`${section} settings saved`)
  } catch {
    notify.error(`Failed to save ${section} settings`)
  } finally {
    saving.value = null
  }
}

function saveJwt() {
  saveSection('jwt', {
    expires: jwtExpires.value,
    expiry_seconds: jwtExpirySeconds.value,
  })
}

async function saveAuthSettings() {
  saving.value = 'auth'
  try {
    await updateAuthSettings({
      access_token_ttl_seconds: String(authAccessTtl.value),
      refresh_token_ttl_seconds: String(authRefreshTtl.value),
      api_token_max_ttl_seconds: String(authApiMaxTtl.value),
      api_token_allow_permanent: String(authAllowPermanent.value),
    })
    notify.success('Authentication settings saved')
  } catch {
    notify.error('Failed to save authentication settings')
  } finally {
    saving.value = null
  }
}

/**
 * Save rate-over-sliding-window circuit breaker settings. Server-side
 * invariants (rate in (0,1], minCalls>=1, initial<=max) are also
 * validated client-side below to give immediate feedback — the server
 * does the same checks again and rejects with 400 if anything slips
 * through, so nothing gets persisted in an invalid state.
 */
async function saveCircuitBreakerSettings() {
  const ratePct = cbFailureRatePercent.value
  if (ratePct <= 0 || ratePct > 100) {
    notify.error('Failure rate must be between 1 and 100%')
    return
  }
  if (cbMinCalls.value < 1) {
    notify.error('Minimum number of calls must be at least 1')
    return
  }
  if (cbWindowSeconds.value < 1) {
    notify.error('Sliding window must be at least 1 second')
    return
  }
  if (cbInitialBlockSeconds.value < 1
      || cbMaxBlockSeconds.value < cbInitialBlockSeconds.value) {
    notify.error('Initial block must be >= 1s and <= max block duration')
    return
  }
  saving.value = 'circuit-breaker'
  try {
    await updateCircuitBreakerSettings({
      circuit_breaker_failure_rate_threshold: (ratePct / 100).toFixed(3),
      circuit_breaker_minimum_number_of_calls: String(cbMinCalls.value),
      circuit_breaker_sliding_window_seconds: String(cbWindowSeconds.value),
      circuit_breaker_initial_block_seconds: String(cbInitialBlockSeconds.value),
      circuit_breaker_max_block_seconds: String(cbMaxBlockSeconds.value),
    })
    notify.success('Circuit breaker settings saved')
  } catch {
    notify.error('Failed to save circuit breaker settings')
  } finally {
    saving.value = null
  }
}

/**
 * Save upstream HTTP circuit breaker settings (outbound per-endpoint
 * breaker — NOT the group-member breaker above). Same convention:
 * rate is edited as a percent and persisted as a 0-1 fraction string;
 * the server re-validates and rejects with 400 if anything slips
 * through, so nothing gets persisted in an invalid state.
 */
async function saveUpstreamBreakerSettings() {
  const ratePct = ubRatePct.value
  if (ratePct <= 0 || ratePct > 100) {
    notify.error('Failure rate must be between 1 and 100%')
    return
  }
  if (ubMinCalls.value < 1) {
    notify.error('Minimum calls in window must be at least 1')
    return
  }
  if (ubWindowSeconds.value < 1) {
    notify.error('Sliding window must be at least 1 second')
    return
  }
  if (ubSeedBackoffSeconds.value < 1
      || ubMaxBackoffSeconds.value < ubSeedBackoffSeconds.value) {
    notify.error('Initial backoff must be >= 1s and <= max backoff duration')
    return
  }
  saving.value = 'upstream-breaker'
  try {
    await updateUpstreamBreakerSettings({
      upstream_breaker_failure_rate_threshold: (ratePct / 100).toFixed(3),
      upstream_breaker_minimum_calls: String(ubMinCalls.value),
      upstream_breaker_window_seconds: String(ubWindowSeconds.value),
      upstream_breaker_seed_backoff_seconds: String(ubSeedBackoffSeconds.value),
      upstream_breaker_max_backoff_seconds: String(ubMaxBackoffSeconds.value),
    })
    notify.success('Upstream HTTP breaker settings saved')
  } catch {
    notify.error('Failed to save upstream HTTP breaker settings')
  } finally {
    saving.value = null
  }
}

/**
 * Client-side sanity check mirroring (not replacing) the server-side
 * validation in `ClientBaseUrlSettings`'s compact constructor: must parse as
 * an absolute URL with an `http`/`https` scheme and a host. Purely for an
 * inline hint before save — the server is the source of truth and rejects
 * an invalid value with 400 regardless of what this returns.
 */
function isValidHttpUrl(value: string): boolean {
  try {
    const parsed = new URL(value.trim())
    return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && !!parsed.host
  } catch {
    return false
  }
}

/**
 * Save the client-facing base URL derivation settings (canonical override,
 * forwarded-header trust, host allowlist). All apply on the very next
 * request — every `ClientBaseUrl` reads through the DB-backed loader on
 * each construction, no restart required. An invalid `clientBaseUrl` (not
 * an absolute http/https URL) is rejected by the server with 400 before
 * anything is written.
 */
async function saveClientBaseUrlSettings() {
  saving.value = 'client-base-url'
  try {
    const allowlist = clientBaseHostAllowlist.value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .join(',')
    const canonical = clientBaseUrl.value.trim()
    await updateClientBaseUrlSettings({
      trust_forwarded_headers: String(trustForwardedHeaders.value),
      client_base_host_allowlist: allowlist,
      client_base_url: canonical,
    })
    clientBaseHostAllowlist.value = allowlist
    clientBaseUrl.value = canonical
    notify.success('Client-facing base URL settings saved')
  } catch {
    notify.error('Failed to save client-facing base URL settings')
  } finally {
    saving.value = null
  }
}

function saveHttpClient() {
  saveSection('http_client', {
    proxy_timeout: httpProxyTimeout.value,
    connection_timeout: httpConnTimeout.value,
    idle_timeout: httpIdleTimeout.value,
    follow_redirects: httpFollowRedirects.value,
    connection_acquire_timeout: httpAcquireTimeout.value,
    max_connections_per_destination: httpMaxConns.value,
    max_requests_queued_per_destination: httpMaxQueued.value,
  })
}

function saveHttpServer() {
  saveSection('http_server', {
    request_timeout: httpServerTimeout.value,
  })
}

async function saveCooldown() {
  saving.value = 'cooldown'
  try {
    const payload: CooldownConfig = {
      enabled: cooldownEnabled.value,
      minimum_allowed_age: cooldownAge.value,
      history_retention_days: cooldownHistoryRetentionDays.value,
      cleanup_batch_limit: cooldownCleanupBatchLimit.value,
      repo_types: {},
    }
    if (cooldownConfig.value?.repo_types) {
      payload.repo_types = { ...cooldownConfig.value.repo_types }
    }
    const snapAge = cooldownSnapshotAge.value.trim()
    const snapEnabled = cooldownSnapshotEnabled.value
    if (snapEnabled !== null || snapAge.length > 0) {
      payload.snapshots = {}
      if (snapEnabled !== null) payload.snapshots.enabled = snapEnabled
      if (snapAge.length > 0) payload.snapshots.minimum_allowed_age = snapAge
    } else {
      // Send empty {} so the backend resets any prior override to inherit.
      payload.snapshots = {}
    }
    if (cooldownConfig.value?.repo_name_snapshots) {
      payload.repo_name_snapshots = { ...cooldownConfig.value.repo_name_snapshots }
    }
    await updateCooldownConfig(payload)
    cooldownConfig.value = payload
    notify.success('Cooldown settings saved (hot reloaded)')
  } catch {
    notify.error('Failed to save cooldown settings')
  } finally {
    saving.value = null
  }
}

function ensureCooldownConfig(): CooldownConfig {
  if (!cooldownConfig.value) {
    cooldownConfig.value = {
      enabled: cooldownEnabled.value,
      minimum_allowed_age: cooldownAge.value,
      repo_types: {},
    }
  }
  return cooldownConfig.value
}

function toggleRepoType(name: string) {
  const cfg = ensureCooldownConfig()
  const existing = cfg.repo_types ?? {}
  const current = existing[name]
  if (!current) return
  cooldownConfig.value = {
    ...cfg,
    repo_types: { ...existing, [name]: { ...current, enabled: !current.enabled } },
  }
}

function updateRepoTypeAge(name: string, age: string) {
  const cfg = ensureCooldownConfig()
  const existing = cfg.repo_types ?? {}
  const current = existing[name]
  if (!current) return
  cooldownConfig.value = {
    ...cfg,
    repo_types: { ...existing, [name]: { ...current, minimum_allowed_age: age } },
  }
}

function removeRepoType(name: string) {
  if (!cooldownConfig.value?.repo_types) return
  const copy = { ...cooldownConfig.value.repo_types }
  delete copy[name]
  cooldownConfig.value = { ...cooldownConfig.value, repo_types: copy }
}

function addRepoType() {
  const name = newRepoType.value.trim().toLowerCase()
  if (!name) return
  const cfg = ensureCooldownConfig()
  const existing = cfg.repo_types ?? {}
  cooldownConfig.value = {
    ...cfg,
    repo_types: {
      ...existing,
      [name]: { enabled: true, minimum_allowed_age: cooldownAge.value },
    },
  }
  newRepoType.value = ''
}

async function saveExternalLinks() {
  try {
    await updateSettingsSection('ui', { grafana_url: grafanaUrl.value, registry_url: registryUrl.value })
    config.grafanaUrl = grafanaUrl.value
    config.registryUrl = registryUrl.value
    notify.success('External links updated')
  } catch {
    notify.error('Failed to save external links')
  }
}

// ─────────────────────────────────────────────────────────────────────
// Unified save infrastructure: one Save Changes bar at the bottom
// drives every editable section. Each section snapshots its
// "last-saved" values in {@link baseline} on load + after a successful
// save; the section's dirty bit compares the live ref against that
// baseline. The sticky bar reads {@link dirtySections} so the admin
// sees the changed-section count + restart-required pills before
// committing.
// ─────────────────────────────────────────────────────────────────────

interface Baseline {
  prefixes: string
  jwtExpires: boolean
  jwtExpirySeconds: number
  authAccessTtl: number
  authRefreshTtl: number
  authApiMaxTtl: number
  authAllowPermanent: boolean
  cbFailureRatePercent: number
  cbMinCalls: number
  cbWindowSeconds: number
  cbInitialBlockSeconds: number
  cbMaxBlockSeconds: number
  ubRatePct: number
  ubMinCalls: number
  ubWindowSeconds: number
  ubSeedBackoffSeconds: number
  ubMaxBackoffSeconds: number
  trustForwardedHeaders: boolean
  clientBaseHostAllowlist: string
  clientBaseUrl: string
  cooldownEnabled: boolean
  cooldownAge: string
  cooldownHistoryRetentionDays: number
  cooldownCleanupBatchLimit: number
  cooldownSnapshotEnabled: boolean | null
  cooldownSnapshotAge: string
  cooldownRepoTypesJson: string
  httpProxyTimeout: number
  httpConnTimeout: number
  httpIdleTimeout: number
  httpFollowRedirects: boolean
  httpAcquireTimeout: number
  httpMaxConns: number
  httpMaxQueued: number
  httpServerTimeout: string
  grafanaUrl: string
  registryUrl: string
}

const baseline = ref<Baseline | null>(null)

function snapshot(): Baseline {
  return {
    prefixes: prefixes.value,
    jwtExpires: jwtExpires.value,
    jwtExpirySeconds: jwtExpirySeconds.value,
    authAccessTtl: authAccessTtl.value,
    authRefreshTtl: authRefreshTtl.value,
    authApiMaxTtl: authApiMaxTtl.value,
    authAllowPermanent: authAllowPermanent.value,
    cbFailureRatePercent: cbFailureRatePercent.value,
    cbMinCalls: cbMinCalls.value,
    cbWindowSeconds: cbWindowSeconds.value,
    cbInitialBlockSeconds: cbInitialBlockSeconds.value,
    cbMaxBlockSeconds: cbMaxBlockSeconds.value,
    ubRatePct: ubRatePct.value,
    ubMinCalls: ubMinCalls.value,
    ubWindowSeconds: ubWindowSeconds.value,
    ubSeedBackoffSeconds: ubSeedBackoffSeconds.value,
    ubMaxBackoffSeconds: ubMaxBackoffSeconds.value,
    trustForwardedHeaders: trustForwardedHeaders.value,
    clientBaseHostAllowlist: clientBaseHostAllowlist.value,
    clientBaseUrl: clientBaseUrl.value,
    cooldownEnabled: cooldownEnabled.value,
    cooldownAge: cooldownAge.value,
    cooldownHistoryRetentionDays: cooldownHistoryRetentionDays.value,
    cooldownCleanupBatchLimit: cooldownCleanupBatchLimit.value,
    cooldownSnapshotEnabled: cooldownSnapshotEnabled.value,
    cooldownSnapshotAge: cooldownSnapshotAge.value,
    // The Cooldown repo_types override map is tracked via JSON so
    // any edit to the rows (add/remove/toggle/age) flips the dirty
    // bit without per-field watchers.
    cooldownRepoTypesJson: JSON.stringify(cooldownConfig.value?.repo_types ?? {}),
    httpProxyTimeout: httpProxyTimeout.value,
    httpConnTimeout: httpConnTimeout.value,
    httpIdleTimeout: httpIdleTimeout.value,
    httpFollowRedirects: httpFollowRedirects.value,
    httpAcquireTimeout: httpAcquireTimeout.value,
    httpMaxConns: httpMaxConns.value,
    httpMaxQueued: httpMaxQueued.value,
    httpServerTimeout: httpServerTimeout.value,
    grafanaUrl: grafanaUrl.value,
    registryUrl: registryUrl.value,
  }
}

const isDirtyPrefixes = computed(() =>
  !!baseline.value && baseline.value.prefixes !== prefixes.value)
const isDirtyJwt = computed(() =>
  !!baseline.value
    && (baseline.value.jwtExpires !== jwtExpires.value
      || baseline.value.jwtExpirySeconds !== jwtExpirySeconds.value))
const isDirtyAuth = computed(() =>
  !!baseline.value
    && (baseline.value.authAccessTtl !== authAccessTtl.value
      || baseline.value.authRefreshTtl !== authRefreshTtl.value
      || baseline.value.authApiMaxTtl !== authApiMaxTtl.value
      || baseline.value.authAllowPermanent !== authAllowPermanent.value))
const isDirtyCircuitBreaker = computed(() =>
  !!baseline.value
    && (baseline.value.cbFailureRatePercent !== cbFailureRatePercent.value
      || baseline.value.cbMinCalls !== cbMinCalls.value
      || baseline.value.cbWindowSeconds !== cbWindowSeconds.value
      || baseline.value.cbInitialBlockSeconds !== cbInitialBlockSeconds.value
      || baseline.value.cbMaxBlockSeconds !== cbMaxBlockSeconds.value))
const isDirtyUpstreamBreaker = computed(() =>
  !!baseline.value
    && (baseline.value.ubRatePct !== ubRatePct.value
      || baseline.value.ubMinCalls !== ubMinCalls.value
      || baseline.value.ubWindowSeconds !== ubWindowSeconds.value
      || baseline.value.ubSeedBackoffSeconds !== ubSeedBackoffSeconds.value
      || baseline.value.ubMaxBackoffSeconds !== ubMaxBackoffSeconds.value))
const isDirtyClientBaseUrl = computed(() =>
  !!baseline.value
    && (baseline.value.trustForwardedHeaders !== trustForwardedHeaders.value
      || baseline.value.clientBaseHostAllowlist !== clientBaseHostAllowlist.value
      || baseline.value.clientBaseUrl !== clientBaseUrl.value))
const isDirtyCooldown = computed(() =>
  !!baseline.value
    && (baseline.value.cooldownEnabled !== cooldownEnabled.value
      || baseline.value.cooldownAge !== cooldownAge.value
      || baseline.value.cooldownHistoryRetentionDays !== cooldownHistoryRetentionDays.value
      || baseline.value.cooldownCleanupBatchLimit !== cooldownCleanupBatchLimit.value
      || baseline.value.cooldownSnapshotEnabled !== cooldownSnapshotEnabled.value
      || baseline.value.cooldownSnapshotAge !== cooldownSnapshotAge.value
      || baseline.value.cooldownRepoTypesJson
        !== JSON.stringify(cooldownConfig.value?.repo_types ?? {})))
const isDirtyHttpClient = computed(() =>
  !!baseline.value
    && (baseline.value.httpProxyTimeout !== httpProxyTimeout.value
      || baseline.value.httpConnTimeout !== httpConnTimeout.value
      || baseline.value.httpIdleTimeout !== httpIdleTimeout.value
      || baseline.value.httpFollowRedirects !== httpFollowRedirects.value
      || baseline.value.httpAcquireTimeout !== httpAcquireTimeout.value
      || baseline.value.httpMaxConns !== httpMaxConns.value
      || baseline.value.httpMaxQueued !== httpMaxQueued.value))
const isDirtyHttpServer = computed(() =>
  !!baseline.value && baseline.value.httpServerTimeout !== httpServerTimeout.value)
const isDirtyExternalLinks = computed(() =>
  !!baseline.value
    && (baseline.value.grafanaUrl !== grafanaUrl.value
      || baseline.value.registryUrl !== registryUrl.value))

/**
 * Bulkhead dirty bit comes from the existing {@code useRuntimeSettings}
 * composable's per-key tracking. We treat the whole bulkhead block as
 * one section in the summary so the dirty list stays readable.
 */
const isDirtyBulkhead = computed(() => Boolean(runtime.anyDirty?.value))

const DIRTY_PER_SECTION: Record<SectionId, { value: boolean }> = {
  prefixes: isDirtyPrefixes,
  jwt: isDirtyJwt,
  auth: isDirtyAuth,
  circuit_breaker: isDirtyCircuitBreaker,
  upstream_breaker: isDirtyUpstreamBreaker,
  client_base_url: isDirtyClientBaseUrl,
  cooldown: isDirtyCooldown,
  http_client: isDirtyHttpClient,
  bulkhead: isDirtyBulkhead,
  http_server: isDirtyHttpServer,
  external_links: isDirtyExternalLinks,
}

const dirtySections = computed<SectionId[]>(() =>
  (Object.keys(DIRTY_PER_SECTION) as SectionId[])
    .filter(id => DIRTY_PER_SECTION[id].value))

const dirtyCount = computed(() => dirtySections.value.length)

/**
 * Per-section save dispatch. Reuses the existing per-section save
 * functions so behaviour matches what the old individual Save buttons
 * did — only the trigger is unified.
 */
async function saveSectionById(id: SectionId): Promise<void> {
  switch (id) {
    case 'prefixes': await savePrefixes(); break
    case 'jwt': await Promise.resolve(saveJwt()); break
    case 'auth': await saveAuthSettings(); break
    case 'circuit_breaker': await saveCircuitBreakerSettings(); break
    case 'upstream_breaker': await saveUpstreamBreakerSettings(); break
    case 'client_base_url': await saveClientBaseUrlSettings(); break
    case 'cooldown': await saveCooldown(); break
    case 'http_client': await Promise.resolve(saveHttpClient()); break
    case 'bulkhead': await runtime.saveAllDirty(); break
    case 'http_server': await Promise.resolve(saveHttpServer()); break
    case 'external_links': await saveExternalLinks(); break
  }
}

const savingAll = ref(false)

/**
 * Submit every dirty section in parallel, refresh the baseline so the
 * dirty bar clears, and emit one consolidated toast. Hot-reload
 * sections take effect immediately; restart-required ones are flagged
 * with their reason from {@link SECTION_META} so the admin knows the
 * old value is still live until the next process boot.
 */
async function saveAll() {
  const ids = dirtySections.value.slice()
  if (ids.length === 0) return
  savingAll.value = true
  const restartRequired = ids.filter(id => !SECTION_META[id].hotReload)
  try {
    // saveSectionById delegates to the existing per-section funcs,
    // each of which already manages its own try/catch + per-toast.
    // The unified toasts below summarise the batch outcome.
    await Promise.all(ids.map(saveSectionById))
    baseline.value = snapshot()
    if (restartRequired.length > 0) {
      notify.warn(
        'Some changes need a restart',
        restartRequired
          .map(id => `${SECTION_META[id].label}: ${SECTION_META[id].restartReason ?? 'requires restart'}`)
          .join(' • '),
      )
    } else {
      notify.success(
        `Saved ${ids.length} section${ids.length === 1 ? '' : 's'}`,
        'All changes took effect immediately (hot reload).',
      )
    }
  } finally {
    savingAll.value = false
  }
}

function discardAll() {
  if (!baseline.value) return
  const b = baseline.value
  prefixes.value = b.prefixes
  jwtExpires.value = b.jwtExpires
  jwtExpirySeconds.value = b.jwtExpirySeconds
  authAccessTtl.value = b.authAccessTtl
  authRefreshTtl.value = b.authRefreshTtl
  authApiMaxTtl.value = b.authApiMaxTtl
  authAllowPermanent.value = b.authAllowPermanent
  cbFailureRatePercent.value = b.cbFailureRatePercent
  cbMinCalls.value = b.cbMinCalls
  cbWindowSeconds.value = b.cbWindowSeconds
  cbInitialBlockSeconds.value = b.cbInitialBlockSeconds
  cbMaxBlockSeconds.value = b.cbMaxBlockSeconds
  ubRatePct.value = b.ubRatePct
  ubMinCalls.value = b.ubMinCalls
  ubWindowSeconds.value = b.ubWindowSeconds
  ubSeedBackoffSeconds.value = b.ubSeedBackoffSeconds
  ubMaxBackoffSeconds.value = b.ubMaxBackoffSeconds
  trustForwardedHeaders.value = b.trustForwardedHeaders
  clientBaseHostAllowlist.value = b.clientBaseHostAllowlist
  clientBaseUrl.value = b.clientBaseUrl
  cooldownEnabled.value = b.cooldownEnabled
  cooldownAge.value = b.cooldownAge
  cooldownHistoryRetentionDays.value = b.cooldownHistoryRetentionDays
  cooldownCleanupBatchLimit.value = b.cooldownCleanupBatchLimit
  cooldownSnapshotEnabled.value = b.cooldownSnapshotEnabled
  cooldownSnapshotAge.value = b.cooldownSnapshotAge
  if (cooldownConfig.value) {
    cooldownConfig.value = {
      ...cooldownConfig.value,
      repo_types: JSON.parse(b.cooldownRepoTypesJson),
    }
  }
  httpProxyTimeout.value = b.httpProxyTimeout
  httpConnTimeout.value = b.httpConnTimeout
  httpIdleTimeout.value = b.httpIdleTimeout
  httpFollowRedirects.value = b.httpFollowRedirects
  httpAcquireTimeout.value = b.httpAcquireTimeout
  httpMaxConns.value = b.httpMaxConns
  httpMaxQueued.value = b.httpMaxQueued
  httpServerTimeout.value = b.httpServerTimeout
  grafanaUrl.value = b.grafanaUrl
  registryUrl.value = b.registryUrl
  // Bulkhead reset uses its own discard path: revert each per-key
  // edit back to the loaded row value so the composable's anyDirty
  // flag clears.
  for (const key of Object.keys(runtime.rows) as RuntimeSettingKey[]) {
    runtime.edited[key] = runtime.rows[key].value
  }
}

/**
 * Functional component used inside every Card title row. Renders the
 * section's display label plus (when dirty) a pill telling the admin
 * whether the unified save will hot-reload the change or only persist
 * it. Co-located in this script so it can read the SECTION_META map
 * directly without prop-drilling.
 */
const SectionHeader = (props: { id: SectionId; dirty: boolean }) => {
  const meta = SECTION_META[props.id]
  const children: Array<ReturnType<typeof h> | string> = [meta.label]
  if (props.dirty) {
    children.push(
      h(
        'span',
        {
          class: meta.hotReload
            ? 'ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[0.7rem] font-semibold bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-200'
            : 'ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[0.7rem] font-semibold bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200',
          title: meta.restartReason ?? 'Applies immediately on save',
          'data-testid': `section-pill-${props.id}`,
        },
        [
          h('i', { class: meta.hotReload ? 'pi pi-bolt' : 'pi pi-refresh' }),
          meta.hotReload ? 'Hot reload' : 'Restart required',
        ],
      ),
    )
  }
  return h('span', { class: 'inline-flex items-center' }, children)
}

</script>

<template>
  <AppLayout>
    <div class="max-w-5xl space-y-6">
      <h1 class="text-2xl font-bold text-gray-900 dark:text-white">System Settings</h1>

      <!-- Server Info -->
      <Card v-if="settings" class="shadow-sm">
        <template #title>Server Info</template>
        <template #content>
          <div class="grid grid-cols-2 gap-4 text-sm">
            <div><span class="text-gray-500">Port:</span> {{ settings.port }}</div>
            <div><span class="text-gray-500">Version:</span> {{ settings.version?.includes('${') ? 'unknown' : settings.version }} <span class="text-gray-400">(UI {{ uiVersion }})</span></div>
            <div>
              <span class="text-gray-500">Database:</span>
              <Tag
                :value="settings.database?.configured ? 'Connected' : 'Not configured'"
                :severity="settings.database?.configured ? 'success' : 'warn'"
                class="ml-2"
              />
            </div>
            <div>
              <span class="text-gray-500">Valkey Cache:</span>
              <Tag
                :value="settings.caches?.valkey_configured ? 'Connected' : 'Not configured'"
                :severity="settings.caches?.valkey_configured ? 'success' : 'warn'"
                class="ml-2"
              />
            </div>
          </div>
        </template>
      </Card>

      <!-- Prefixes -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="prefixes" :dirty="isDirtyPrefixes" />
        </template>
        <template #content>
          <p class="text-sm text-gray-500 mb-3">
            Comma-separated list of path prefixes for repository routing
          </p>
          <InputText v-model="prefixes" class="w-full" placeholder="e.g. maven, docker, npm" />
        </template>
      </Card>

      <!-- JWT -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="jwt" :dirty="isDirtyJwt" />
        </template>
        <template #content>
          <div class="space-y-4">
            <div class="flex items-center gap-3">
              <InputSwitch v-model="jwtExpires" />
              <span class="text-sm">Tokens expire</span>
            </div>
            <div v-if="jwtExpires" class="flex items-center gap-3">
              <label class="text-sm text-gray-500 w-40">Expiry (seconds)</label>
              <InputNumber v-model="jwtExpirySeconds" :min="60" :max="2592000" class="flex-1" />
              <Tag :value="jwtExpiryHours" severity="info" />
            </div>
          </div>
        </template>
      </Card>

      <!-- Authentication Policy -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="auth" :dirty="isDirtyAuth" />
        </template>
        <template #content>
          <div class="space-y-4">
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="text-sm text-gray-500 block mb-1">Access Token TTL (seconds)</label>
                <InputNumber v-model="authAccessTtl" :min="60" :max="86400" class="w-full" />
                <span class="text-xs text-gray-400">Default: 3600 (1 hour)</span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Refresh Token TTL (seconds)</label>
                <InputNumber v-model="authRefreshTtl" :min="3600" :max="2592000" class="w-full" />
                <span class="text-xs text-gray-400">Default: 604800 (7 days)</span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">API Token Max TTL (seconds)</label>
                <InputNumber v-model="authApiMaxTtl" :min="86400" :max="31536000" class="w-full" />
                <span class="text-xs text-gray-400">Default: 7776000 (90 days)</span>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <InputSwitch v-model="authAllowPermanent" />
              <span class="text-sm">Allow permanent API tokens (no expiry)</span>
            </div>
          </div>
        </template>
      </Card>

      <!-- Group Member Circuit Breaker (rate-over-sliding-window) -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="circuit_breaker" :dirty="isDirtyCircuitBreaker" />
        </template>
        <template #subtitle>
          Rate-over-sliding-window breaker that blocks a member repository
          during group resolution after sustained failures. Opens when
          the failure rate inside the window exceeds the threshold AND the window
          has seen at least the minimum number of calls — the volume gate
          protects against cold-start false positives. Distinct from the
          Upstream HTTP Circuit Breaker below, which gates outbound HTTP
          calls per upstream endpoint (scheme://host:port).
        </template>
        <template #content>
          <div class="space-y-4">
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="text-sm text-gray-500 block mb-1">Failure Rate Threshold (%)</label>
                <InputNumber v-model="cbFailureRatePercent" :min="1" :max="100" suffix="%" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 50%. Breaker opens when failure rate ≥ this value across the window.
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Minimum Number of Calls</label>
                <InputNumber v-model="cbMinCalls" :min="1" :max="10000" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 20. No trip until the window has seen this many outcomes (rate + volume gate).
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Sliding Window (seconds)</label>
                <InputNumber v-model="cbWindowSeconds" :min="1" :max="600" suffix=" s" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 30s. Rolling window over which failure rate is computed.
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Initial Block Duration (seconds)</label>
                <InputNumber v-model="cbInitialBlockSeconds" :min="1" :max="3600" suffix=" s" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 20s. First block after the breaker opens; Fibonacci-scaled on repeat trips.
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Max Block Duration (seconds)</label>
                <InputNumber v-model="cbMaxBlockSeconds" :min="1" :max="86400" suffix=" s" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 300s (5 min). Upper bound on the Fibonacci back-off.
                </span>
              </div>
            </div>
            <div class="text-xs text-amber-400 bg-amber-500/10 border border-amber-500/20 rounded p-2">
              <i class="pi pi-info-circle mr-1" />
              Settings take effect on the next recorded outcome across every proxy
              upstream — no restart needed. A very low rate threshold combined with
              a low minimum-calls value makes the breaker trip-happy under cold-start
              bursts; the defaults are tuned to avoid that.
            </div>
          </div>
        </template>
      </Card>

      <!-- Upstream HTTP Circuit Breaker (outbound per-endpoint) -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="upstream_breaker" :dirty="isDirtyUpstreamBreaker" />
        </template>
        <template #subtitle>
          Gates outbound HTTP calls per upstream endpoint (scheme://host:port).
          Distinct from the Group Member Circuit Breaker above, which gates
          member repositories during group resolution. Opens after sustained
          5xx/connection failures to an endpoint and recovers via a HEAD probe
          with Fibonacci backoff.
        </template>
        <template #content>
          <div class="space-y-4">
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="text-sm text-gray-500 block mb-1">Failure rate threshold (%)</label>
                <InputNumber v-model="ubRatePct" :min="1" :max="100" suffix="%" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 50%. Breaker opens when the outbound failure rate to an
                  endpoint reaches this value across the window.
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Minimum calls in window</label>
                <InputNumber v-model="ubMinCalls" :min="1" :max="10000" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 10. No trip until the window has seen this many outcomes
                  for the endpoint (rate + volume gate).
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Sliding window (seconds)</label>
                <InputNumber v-model="ubWindowSeconds" :min="1" :max="600" suffix=" s" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 30s. Rolling window over which the failure rate is computed.
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Initial backoff (seconds)</label>
                <InputNumber v-model="ubSeedBackoffSeconds" :min="1" :max="3600" suffix=" s" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 2s. Seed of the Fibonacci backoff between HEAD recovery probes.
                </span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Max backoff (seconds)</label>
                <InputNumber v-model="ubMaxBackoffSeconds" :min="1" :max="86400" suffix=" s" class="w-full" />
                <span class="text-xs text-gray-400">
                  Default: 3600s (1 hour). Upper bound on the Fibonacci backoff.
                </span>
              </div>
            </div>
            <div class="text-xs text-amber-400 bg-amber-500/10 border border-amber-500/20 rounded p-2">
              <i class="pi pi-info-circle mr-1" />
              Settings take effect on the next recorded outcome for every upstream
              endpoint — no restart needed. While an endpoint is blocked, outbound
              requests to it fail fast until a HEAD probe succeeds.
            </div>
          </div>
        </template>
      </Card>

      <!-- Client-Facing Base URL (forwarded-header trust + Host allowlist) -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="client_base_url" :dirty="isDirtyClientBaseUrl" />
        </template>
        <template #subtitle>
          Governs how Pantera derives the absolute base URL it embeds in links it
          emits (e.g. npm <code>dist.tarball</code>) for a repository with no
          explicit <code>url:</code> configured. A repository's own
          <code>url:</code> always wins over all three settings below. Distinct
          from — and unrelated to — the circuit breaker cards above.
        </template>
        <template #content>
          <div class="space-y-5">
            <div class="flex flex-col gap-2">
              <label class="text-sm text-gray-500">
                Canonical base URL (overrides Host / forwarded-header derivation)
              </label>
              <InputText
                v-model="clientBaseUrl"
                class="w-full"
                placeholder="e.g. https://reg.example.com or https://reg.example.com/artifactory"
              />
              <div
                v-if="clientBaseUrl.trim() && !isValidHttpUrl(clientBaseUrl)"
                class="text-xs text-red-400 bg-red-500/10 border border-red-500/20 rounded p-2"
              >
                <i class="pi pi-times-circle mr-1" />
                Must be an absolute <code>http://</code> or <code>https://</code> URL.
              </div>
              <div
                v-else-if="clientBaseUrl.trim()"
                class="text-xs text-blue-400 bg-blue-500/10 border border-blue-500/20 rounded p-2"
              >
                <i class="pi pi-info-circle mr-1" />
                ENFORCED for every repository without an explicit <code>url:</code>:
                the client-supplied <code>Host</code> and <code>X-Forwarded-*</code>
                headers below are no longer consulted at all for those
                repositories — only the repository's own <code>url:</code> (if
                set) still wins. The repository-relative path is still derived
                from the request.
              </div>
              <span v-else class="text-xs text-gray-400">
                Empty (default) leaves derivation to <code>Host</code> /
                <code>X-Forwarded-*</code> below, exactly as before this setting
                existed.
              </span>
            </div>
            <div class="flex items-center gap-3">
              <InputSwitch v-model="trustForwardedHeaders" />
              <div>
                <div class="text-sm">Trust reverse-proxy forwarded headers</div>
                <div class="text-xs text-gray-500">
                  Honour <code>X-Forwarded-Proto</code>/<code>-Host</code>/<code>-Prefix</code>.
                  Enable ONLY when a fronting reverse proxy overwrites these on
                  every inbound request — they are otherwise client-suppliable.
                  Default: off. Ignored entirely while the canonical base URL
                  above is set.
                </div>
              </div>
            </div>
            <div class="flex flex-col gap-2">
              <label class="text-sm text-gray-500">Host allowlist (comma-separated)</label>
              <InputText
                v-model="clientBaseHostAllowlist"
                class="w-full"
                placeholder="e.g. registry.example.com, registry.example.com:8443"
              />
              <div
                v-if="!clientBaseHostAllowlist.trim()"
                class="text-xs text-amber-400 bg-amber-500/10 border border-amber-500/20 rounded p-2"
              >
                <i class="pi pi-exclamation-triangle mr-1" />
                Empty is PERMISSIVE: the client-supplied <code>Host</code> header is
                trusted as-is for any repository without an explicit <code>url:</code>.
                A client can steer emitted URLs at any host it likes. Add at least
                one entry to restrict which <code>Host</code> values are honoured
                — a non-matching <code>Host</code> falls back exactly like an
                absent one, never emitted verbatim.
              </div>
              <span v-else class="text-xs text-gray-400">
                Exact match, case-insensitive, including port if the client sends one.
              </span>
            </div>
          </div>
        </template>
      </Card>

      <!-- Cooldown Configuration -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="cooldown" :dirty="isDirtyCooldown" />
        </template>
        <template #subtitle>
          Controls artifact freshness enforcement for proxy repositories.
        </template>
        <template #content>
          <div class="space-y-5">
            <!-- Global toggle -->
            <div class="flex items-center justify-between p-3 bg-gray-50 dark:bg-gray-800 rounded-lg">
              <div>
                <div class="font-medium text-sm">Global Cooldown</div>
                <div class="text-xs text-gray-500">Enable cooldown enforcement for all proxy repos</div>
              </div>
              <InputSwitch v-model="cooldownEnabled" />
            </div>

            <!-- Global age -->
            <div class="flex items-center gap-3">
              <label class="text-sm text-gray-500 w-44">Default minimum age</label>
              <InputText
                v-model="cooldownAge"
                class="w-32"
                placeholder="7d"
              />
              <span class="text-xs text-gray-400">e.g. 7d, 24h, 30m</span>
            </div>

            <!-- SNAPSHOT cooldown -->
            <div
              class="border-l-4 border-blue-200 dark:border-blue-800 pl-3 space-y-3"
              data-testid="snapshot-cooldown-section"
            >
              <div>
                <div class="font-medium text-sm">SNAPSHOT cooldown</div>
                <div class="text-xs text-gray-500">
                  Stricter cooldown for SNAPSHOT artifacts (Maven/Gradle). Overrides the
                  global minimum_allowed_age for any cache-write whose version matches
                  a SNAPSHOT timestamp pattern. Leave blank to inherit from the global.
                </div>
              </div>
              <div class="flex items-center gap-3">
                <label class="text-sm text-gray-500 w-44">Enabled (override)</label>
                <select
                  v-model="cooldownSnapshotEnabled"
                  class="px-2 py-1 border rounded text-sm dark:bg-gray-800"
                  data-testid="snapshot-enabled-select"
                >
                  <option :value="null">inherit</option>
                  <option :value="true">true</option>
                  <option :value="false">false</option>
                </select>
              </div>
              <div class="flex items-center gap-3">
                <label class="text-sm text-gray-500 w-44">SNAPSHOT minimum age</label>
                <InputText
                  v-model="cooldownSnapshotAge"
                  class="w-32"
                  placeholder="(inherit)"
                  data-testid="snapshot-age-input"
                />
                <span class="text-xs text-gray-400">e.g. 14d, 30d</span>
              </div>
            </div>

            <!-- History retention -->
            <div class="flex flex-col gap-2">
              <label for="cooldown-retention" class="text-sm text-gray-500">
                History retention (days)
              </label>
              <InputNumber
                id="cooldown-retention"
                v-model="cooldownHistoryRetentionDays"
                :min="1"
                :max="3650"
                show-buttons
                class="w-40"
              />
              <small class="text-gray-500">
                Days to retain archived cooldown blocks before auto-purge.
              </small>
            </div>

            <!-- Cleanup batch limit -->
            <div class="flex flex-col gap-2">
              <label for="cooldown-batch" class="text-sm text-gray-500">
                Cleanup batch limit
              </label>
              <InputNumber
                id="cooldown-batch"
                v-model="cooldownCleanupBatchLimit"
                :min="1"
                :max="100000"
                show-buttons
                class="w-40"
              />
              <small class="text-gray-500">
                Max rows archived per cleanup tick.
              </small>
            </div>

            <!-- Per-repo-type overrides -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <h4 class="text-sm font-medium">Per-Repository-Type Overrides</h4>
              </div>
              <div v-if="repoTypeOverrides.length === 0" class="text-gray-400 text-xs mb-3">
                No per-type overrides. Global settings apply to all proxy repo types.
              </div>
              <div v-else class="space-y-2 mb-3">
                <div
                  v-for="rt in repoTypeOverrides"
                  :key="rt.name"
                  class="flex items-center gap-3 p-2 bg-gray-50 dark:bg-gray-800 rounded"
                >
                  <Tag :value="rt.name" class="min-w-[120px]" />
                  <InputSwitch
                    :model-value="rt.enabled"
                    @update:model-value="toggleRepoType(rt.name)"
                  />
                  <span class="text-xs text-gray-500">{{ rt.enabled ? 'Enabled' : 'Disabled' }}</span>
                  <InputText
                    :model-value="rt.minimum_allowed_age"
                    class="w-24 text-sm"
                    placeholder="7d"
                    @update:model-value="(v: string) => updateRepoTypeAge(rt.name, v)"
                  />
                  <Button
                    icon="pi pi-trash"
                    text
                    size="small"
                    severity="danger"
                    @click="removeRepoType(rt.name)"
                  />
                </div>
              </div>
              <div class="flex gap-2">
                <AutoComplete
                  v-model="newRepoType"
                  :suggestions="proxyTypeSuggestions"
                  :complete-on-focus="true"
                  class="w-56 text-sm"
                  input-class="w-full"
                  placeholder="Select proxy type..."
                  @complete="searchProxyTypes"
                  @keyup.enter="addRepoType"
                />
                <Button
                  label="Add Override"
                  icon="pi pi-plus"
                  size="small"
                  outlined
                  @click="addRepoType"
                />
              </div>
            </div>
          </div>
        </template>
      </Card>

      <!-- HTTP Client -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="http_client" :dirty="isDirtyHttpClient" />
        </template>
        <template #content>
          <div class="space-y-3">
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="text-sm text-gray-500 block mb-1">Proxy Timeout (s)</label>
                <InputNumber v-model="httpProxyTimeout" :min="1" :max="600" class="w-full" />
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Connection Timeout (ms)</label>
                <InputNumber v-model="httpConnTimeout" :min="1000" :max="120000" class="w-full" />
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Idle Timeout (ms)</label>
                <InputNumber v-model="httpIdleTimeout" :min="0" :max="300000" class="w-full" />
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Acquire Timeout (ms)</label>
                <InputNumber v-model="httpAcquireTimeout" :min="0" :max="300000" class="w-full" />
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Max Connections / Dest</label>
                <InputNumber v-model="httpMaxConns" :min="1" :max="2048" class="w-full" />
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Max Queued / Dest</label>
                <InputNumber v-model="httpMaxQueued" :min="1" :max="10000" class="w-full" />
              </div>
            </div>
            <div class="flex items-center gap-3">
              <InputSwitch v-model="httpFollowRedirects" />
              <span class="text-sm">Follow redirects</span>
            </div>
          </div>
        </template>
      </Card>

      <!-- Bulkhead (Adaptive Concurrency) -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="bulkhead" :dirty="isDirtyBulkhead" />
        </template>
        <template #subtitle>
          Per-repository in-flight concurrency budget. When adaptive mode is on
          an AIMD controller grows the ceiling on healthy windows and halves it
          on the first error or sustained high-latency window. Changes take
          effect on the next bulkhead acquire (existing in-flight requests are
          unaffected).
        </template>
        <template #content>
          <div v-if="runtime.loading.value" class="text-sm text-gray-500">Loading…</div>
          <div
            v-else-if="runtime.loadError.value"
            class="text-sm text-red-700 dark:text-red-300"
          >
            Failed to load bulkhead settings: {{ runtime.loadError.value }}
          </div>
          <div v-else class="space-y-5">
            <div
              v-for="key in BULKHEAD_RUNTIME_KEYS"
              :key="key"
              class="flex flex-col gap-1"
              :data-testid="`runtime-row-${key}`"
            >
              <label
                :for="`field-${key}`"
                class="text-sm font-medium text-gray-700 dark:text-gray-200"
              >
                {{ RUNTIME_LABELS[key] }}
              </label>
              <InputSwitch
                v-if="key === 'http_client.bulkhead.adaptive'"
                :id="`field-${key}`"
                v-model="runtime.edited[key] as boolean"
                :data-testid="`runtime-input-${key}`"
              />
              <InputNumber
                v-else-if="RUNTIME_DOUBLE_RANGES[key]"
                :id="`field-${key}`"
                v-model="runtime.edited[key] as number"
                :min="RUNTIME_DOUBLE_RANGES[key]?.min"
                :max="RUNTIME_DOUBLE_RANGES[key]?.max"
                :step="RUNTIME_DOUBLE_RANGES[key]?.step"
                :min-fraction-digits="2"
                :max-fraction-digits="2"
                show-buttons
                class="w-48"
                :input-props="{ 'data-testid': `runtime-input-${key}` }"
              />
              <InputNumber
                v-else
                :id="`field-${key}`"
                v-model="runtime.edited[key] as number"
                :min="RUNTIME_INT_RANGES[key]?.min"
                :max="RUNTIME_INT_RANGES[key]?.max"
                show-buttons
                class="w-48"
                :input-props="{ 'data-testid': `runtime-input-${key}` }"
              />
              <div v-if="RUNTIME_HELP[key]" class="text-xs text-gray-500">
                {{ RUNTIME_HELP[key] }}
                <template v-if="RUNTIME_INT_RANGES[key]">
                  Allowed range:
                  {{ RUNTIME_INT_RANGES[key]?.min }}–{{ RUNTIME_INT_RANGES[key]?.max }}.
                </template>
                <template v-else-if="RUNTIME_DOUBLE_RANGES[key]">
                  Allowed range:
                  {{ RUNTIME_DOUBLE_RANGES[key]?.min }}–{{ RUNTIME_DOUBLE_RANGES[key]?.max }}.
                </template>
                Default: {{ runtime.rows[key]?.default }}.
              </div>
              <div class="flex items-center gap-3 mt-1">
                <!-- Dirty marker so admins can see at-a-glance which
                     per-row changes are part of the unified save. The
                     "Reset to default" button is per-row because the
                     server-side default lives on the row itself and a
                     reset is a distinct action from saving an edit. -->
                <span
                  v-if="runtime.isDirty(key)"
                  class="text-xs text-blue-500 dark:text-blue-400 flex items-center gap-1"
                  :data-testid="`runtime-dirty-${key}`"
                >
                  <span class="inline-block w-1.5 h-1.5 rounded-full bg-blue-500" />
                  Unsaved
                </span>
                <Button
                  v-if="runtime.isOverridden(key)"
                  label="Reset to default"
                  icon="pi pi-undo"
                  size="small"
                  severity="secondary"
                  text
                  :loading="runtime.saving[key]"
                  :data-testid="`runtime-reset-${key}`"
                  @click="runtime.resetOne(key)"
                />
              </div>
            </div>
          </div>
        </template>
      </Card>

      <!-- HTTP Server -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="http_server" :dirty="isDirtyHttpServer" />
        </template>
        <template #content>
          <div class="space-y-3">
            <div>
              <label class="text-sm text-gray-500 block mb-1">
                Request Timeout (ISO-8601, e.g. PT2M)
              </label>
              <InputText v-model="httpServerTimeout" class="w-full" placeholder="PT2M" />
            </div>
          </div>
        </template>
      </Card>

      <!-- Metrics (read-only) -->
      <Card v-if="settings?.metrics" class="shadow-sm">
        <template #title>Metrics</template>
        <template #content>
          <div class="space-y-2 text-sm">
            <div>
              <span class="text-gray-500">Enabled:</span>
              <Tag
                :value="settings.metrics.enabled ? 'Yes' : 'No'"
                :severity="settings.metrics.enabled ? 'success' : 'warn'"
                class="ml-2"
              />
            </div>
            <div v-if="settings.metrics.enabled">
              <span class="text-gray-500">Endpoint:</span>
              <span class="font-mono ml-2">{{ settings.metrics.endpoint }}</span>
              <span class="text-gray-400 ml-2">port {{ settings.metrics.port }}</span>
            </div>
            <div v-if="settings.metrics.enabled" class="flex gap-2">
              <Tag v-if="settings.metrics.jvm" value="JVM" severity="info" />
              <Tag v-if="settings.metrics.http" value="HTTP" severity="info" />
              <Tag v-if="settings.metrics.storage" value="Storage" severity="info" />
            </div>
          </div>
        </template>
      </Card>

      <!-- External Links -->
      <Card class="shadow-sm">
        <template #title>
          <SectionHeader id="external_links" :dirty="isDirtyExternalLinks" />
        </template>
        <template #content>
          <div class="space-y-3">
            <div>
              <label class="text-sm text-gray-500 block mb-1">Grafana URL</label>
              <InputText v-model="grafanaUrl" class="w-full" placeholder="https://grafana.example.com" />
            </div>
            <div>
              <label class="text-sm text-gray-500 block mb-1">Registry URL</label>
              <InputText v-model="registryUrl" class="w-full" placeholder="https://pantera.example.com" />
            </div>
            <div>
              <span class="text-sm text-gray-500">Health Endpoint:</span>
              <a
                :href="`${config.apiBaseUrl}/health`"
                target="_blank"
                class="text-blue-500 hover:underline ml-2 text-sm"
              >
                {{ config.apiBaseUrl }}/health
              </a>
            </div>
          </div>
        </template>
      </Card>

      <!--
        Bottom padding so the sticky save bar never covers the last
        card's content when scrolled to the end. 4rem matches the bar
        height + breathing room.
      -->
      <div class="h-16" aria-hidden="true" />
    </div>

    <!--
      Sticky unified Save bar. Pinned to the bottom of the viewport so
      admins see the dirty count + Save / Discard wherever they scroll.
      Hidden when nothing is dirty so it never obscures content during
      a read-only review of settings.
    -->
    <Transition
      enter-from-class="translate-y-full opacity-0"
      enter-active-class="transition-all duration-200"
      leave-to-class="translate-y-full opacity-0"
      leave-active-class="transition-all duration-200"
    >
      <div
        v-if="dirtyCount > 0"
        class="fixed bottom-0 left-0 right-0 z-30 border-t border-gray-200 dark:border-gray-700 bg-white/95 dark:bg-gray-900/95 backdrop-blur shadow-lg"
        role="region"
        aria-label="Unsaved settings changes"
        data-testid="settings-save-bar"
      >
        <div class="max-w-5xl mx-auto px-6 py-3 flex items-center gap-4">
          <div class="flex flex-col gap-1 flex-1 min-w-0">
            <div class="flex items-center gap-2 text-sm font-medium">
              <i class="pi pi-pencil text-blue-500" />
              {{ dirtyCount }}
              unsaved
              {{ dirtyCount === 1 ? 'change' : 'changes' }}
            </div>
            <div class="flex flex-wrap items-center gap-2 text-xs text-gray-500">
              <span
                v-for="id in dirtySections"
                :key="id"
                v-tooltip.top="SECTION_META[id].hotReload
                  ? 'Applies immediately on save (hot reload)'
                  : SECTION_META[id].restartReason"
                class="inline-flex items-center gap-1 px-2 py-0.5 rounded"
                :class="SECTION_META[id].hotReload
                  ? 'bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300'
                  : 'bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300'"
                :data-testid="`save-bar-chip-${id}`"
              >
                <i
                  :class="SECTION_META[id].hotReload
                    ? 'pi pi-bolt text-[0.7rem]'
                    : 'pi pi-refresh text-[0.7rem]'"
                />
                {{ SECTION_META[id].label }}
                <span
                  v-if="!SECTION_META[id].hotReload"
                  class="ml-1 text-[0.65rem] uppercase tracking-wide font-semibold"
                >
                  restart
                </span>
              </span>
            </div>
          </div>
          <Button
            label="Discard"
            severity="secondary"
            text
            :disabled="savingAll"
            data-testid="settings-discard"
            @click="discardAll"
          />
          <Button
            :label="savingAll
              ? 'Saving…'
              : `Save changes${dirtyCount > 1 ? ` (${dirtyCount})` : ''}`"
            icon="pi pi-check"
            severity="primary"
            :loading="savingAll"
            :disabled="savingAll"
            data-testid="settings-save-all"
            @click="saveAll"
          />
        </div>
      </div>
    </Transition>
  </AppLayout>
</template>
