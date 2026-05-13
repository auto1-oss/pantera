<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getSettings, updatePrefixes, updateSettingsSection,
  getCooldownConfig, updateCooldownConfig,
} from '@/api/settings'
import {
  getAuthSettings, updateAuthSettings,
  getCircuitBreakerSettings, updateCircuitBreakerSettings,
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
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import type { Settings, CooldownConfig } from '@/types'

const config = useConfigStore()
const notify = useNotificationStore()
const uiVersion = __APP_VERSION__

const settings = ref<Settings | null>(null)
const loading = ref(true)
const saving = ref<string | null>(null)

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

// Circuit breaker (rate-over-sliding-window, 2.2.0)
const cbFailureRatePercent = ref(50)     // stored as 0.0-1.0 on server, shown as % here
const cbMinCalls = ref(20)
const cbWindowSeconds = ref(30)
const cbInitialBlockSeconds = ref(20)
const cbMaxBlockSeconds = ref(300)

// Cooldown config
const cooldownConfig = ref<CooldownConfig | null>(null)
const cooldownEnabled = ref(false)
const cooldownAge = ref('7d')
const cooldownHistoryRetentionDays = ref(90)
const cooldownCleanupBatchLimit = ref(10000)
const newRepoType = ref('')

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

// Runtime tunables (HTTP/2 client). Loaded into the same view so admins
// have one place for everything that lives in the settings DB.
const runtime = useRuntimeSettings()

const PROTOCOL_OPTIONS = [
  { label: 'HTTP/2', value: 'h2' },
  { label: 'HTTP/1.1', value: 'h1' },
  { label: 'Auto', value: 'auto' },
]

interface IntRange { min: number; max: number }
const RUNTIME_INT_RANGES: Record<RuntimeSettingKey, IntRange | null> = {
  'http_client.protocol': null,
  'http_client.http2_max_pool_size': { min: 1, max: 8 },
  'http_client.http2_multiplexing_limit': { min: 1, max: 1000 },
}

const HTTP_RUNTIME_KEYS: RuntimeSettingKey[] = [
  'http_client.protocol',
  'http_client.http2_max_pool_size',
  'http_client.http2_multiplexing_limit',
]

const RUNTIME_LABELS: Record<RuntimeSettingKey, string> = {
  'http_client.protocol': 'Protocol',
  'http_client.http2_max_pool_size': 'HTTP/2 max pool size',
  'http_client.http2_multiplexing_limit': 'HTTP/2 multiplexing limit',
}

const RUNTIME_HELP: Partial<Record<RuntimeSettingKey, string>> = {
  'http_client.protocol':
    'Default upstream HTTP protocol. h2 multiplexes many requests over one '
    + 'connection. h1 forces classic HTTP/1.1. auto negotiates per upstream.',
  'http_client.http2_max_pool_size':
    'Max concurrent HTTP/2 connections per destination. Most upstreams '
    + 'multiplex thousands of streams over one connection — leave at 1 '
    + 'unless you have a specific need to fan out.',
  'http_client.http2_multiplexing_limit':
    'Max concurrent streams per HTTP/2 connection.',
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
    }
    getAuthSettings().then(s => {
      authAccessTtl.value = parseInt(s.access_token_ttl_seconds ?? '3600')
      authRefreshTtl.value = parseInt(s.refresh_token_ttl_seconds ?? '604800')
      authApiMaxTtl.value = parseInt(s.api_token_max_ttl_seconds ?? '7776000')
      authAllowPermanent.value = s.api_token_allow_permanent === 'true'
    }).catch(() => {})
    getCircuitBreakerSettings().then(s => {
      cbFailureRatePercent.value = Math.round(
        parseFloat(s.circuit_breaker_failure_rate_threshold ?? '0.5') * 100,
      )
      cbMinCalls.value = parseInt(s.circuit_breaker_minimum_number_of_calls ?? '20')
      cbWindowSeconds.value = parseInt(s.circuit_breaker_sliding_window_seconds ?? '30')
      cbInitialBlockSeconds.value = parseInt(s.circuit_breaker_initial_block_seconds ?? '20')
      cbMaxBlockSeconds.value = parseInt(s.circuit_breaker_max_block_seconds ?? '300')
    }).catch(() => {})
    runtime.load().catch(() => {})
  } catch {
    notify.error('Failed to load settings')
  } finally {
    loading.value = false
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
        <template #title>Global Path Prefixes</template>
        <template #content>
          <p class="text-sm text-gray-500 mb-3">
            Comma-separated list of path prefixes for repository routing
          </p>
          <div class="flex gap-3">
            <InputText v-model="prefixes" class="flex-1" placeholder="e.g. maven, docker, npm" />
            <Button
              label="Save"
              icon="pi pi-save"
              :loading="saving === 'prefixes'"
              @click="savePrefixes"
            />
          </div>
        </template>
      </Card>

      <!-- JWT -->
      <Card class="shadow-sm">
        <template #title>JWT / Session</template>
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
            <Button
              label="Save JWT"
              icon="pi pi-save"
              size="small"
              :loading="saving === 'jwt'"
              @click="saveJwt"
            />
          </div>
        </template>
      </Card>

      <!-- Authentication Policy -->
      <Card class="shadow-sm">
        <template #title>Authentication Policy</template>
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
            <Button
              label="Save Auth Settings"
              icon="pi pi-save"
              size="small"
              :loading="saving === 'auth'"
              @click="saveAuthSettings"
            />
          </div>
        </template>
      </Card>

      <!-- Upstream Failure Circuit Breaker (rate-over-sliding-window) -->
      <Card class="shadow-sm">
        <template #title>Upstream Failure Circuit Breaker</template>
        <template #subtitle>
          Rate-over-sliding-window breaker for proxy upstream calls. Opens when
          the failure rate inside the window exceeds the threshold AND the window
          has seen at least the minimum number of calls — the volume gate
          protects against cold-start false positives.
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
            <Button
              label="Save Circuit Breaker Settings"
              icon="pi pi-save"
              size="small"
              :loading="saving === 'circuit-breaker'"
              @click="saveCircuitBreakerSettings"
            />
          </div>
        </template>
      </Card>

      <!-- Cooldown Configuration -->
      <Card class="shadow-sm">
        <template #title>Cooldown Configuration</template>
        <template #subtitle>
          Controls artifact freshness enforcement for proxy repositories.
          Changes apply immediately (hot reload).
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
                    :modelValue="rt.enabled"
                    @update:modelValue="toggleRepoType(rt.name)"
                  />
                  <span class="text-xs text-gray-500">{{ rt.enabled ? 'Enabled' : 'Disabled' }}</span>
                  <InputText
                    :modelValue="rt.minimum_allowed_age"
                    class="w-24 text-sm"
                    placeholder="7d"
                    @update:modelValue="(v: string) => updateRepoTypeAge(rt.name, v)"
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
                  @complete="searchProxyTypes"
                  :completeOnFocus="true"
                  class="w-56 text-sm"
                  inputClass="w-full"
                  placeholder="Select proxy type..."
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

            <Button
              label="Save Cooldown"
              icon="pi pi-save"
              :loading="saving === 'cooldown'"
              @click="saveCooldown"
            />
          </div>
        </template>
      </Card>

      <!-- HTTP Client -->
      <Card class="shadow-sm">
        <template #title>HTTP Client (Proxy)</template>
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
            <Button
              label="Save HTTP Client"
              icon="pi pi-save"
              size="small"
              :loading="saving === 'http_client'"
              @click="saveHttpClient"
            />
          </div>
        </template>
      </Card>

      <!-- HTTP/2 Upstream Tuning (live runtime knobs) -->
      <Card class="shadow-sm">
        <template #title>HTTP/2 Upstream Tuning</template>
        <template #subtitle>
          Live runtime knobs for the upstream HTTP client — protocol selection
          and HTTP/2 pool/multiplexing limits. Changes apply within a few
          hundred milliseconds; no restart required.
        </template>
        <template #content>
          <div v-if="runtime.loading.value" class="text-sm text-gray-500">Loading…</div>
          <div
            v-else-if="runtime.loadError.value"
            class="text-sm text-red-700 dark:text-red-300"
          >
            Failed to load runtime tunables: {{ runtime.loadError.value }}
          </div>
          <div v-else class="space-y-5">
            <div
              v-for="key in HTTP_RUNTIME_KEYS"
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
              <Select
                v-if="key === 'http_client.protocol'"
                :id="`field-${key}`"
                v-model="runtime.edited[key]"
                :options="PROTOCOL_OPTIONS"
                optionLabel="label"
                optionValue="value"
                class="w-full max-w-xs"
                :data-testid="`runtime-input-${key}`"
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
                Default: {{ runtime.rows[key]?.default }}.
              </div>
              <div class="flex gap-2 mt-1">
                <Button
                  label="Save"
                  icon="pi pi-save"
                  size="small"
                  :loading="runtime.saving[key]"
                  :disabled="!runtime.isDirty(key) || runtime.saving[key]"
                  :data-testid="`runtime-save-${key}`"
                  @click="runtime.saveOne(key)"
                />
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
        <template #title>HTTP Server</template>
        <template #content>
          <div class="space-y-3">
            <div>
              <label class="text-sm text-gray-500 block mb-1">
                Request Timeout (ISO-8601, e.g. PT2M)
              </label>
              <InputText v-model="httpServerTimeout" class="w-full" placeholder="PT2M" />
            </div>
            <Button
              label="Save HTTP Server"
              icon="pi pi-save"
              size="small"
              :loading="saving === 'http_server'"
              @click="saveHttpServer"
            />
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
        <template #title>External Links</template>
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
            <Button
              label="Save Links"
              icon="pi pi-save"
              size="small"
              @click="saveExternalLinks"
            />
          </div>
        </template>
      </Card>
    </div>
  </AppLayout>
</template>
