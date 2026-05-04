<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import InputSwitch from 'primevue/inputswitch'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { useNotificationStore } from '@/stores/notifications'
import {
  listRuntimeSettings,
  patchRuntimeSetting,
  resetRuntimeSetting,
  type RuntimeSetting,
  type RuntimeSettingKey,
  type RuntimeValue,
} from '@/api/runtimeSettings'

/**
 * Admin-only Performance Tuning page (Phase 5 Task 24).
 *
 * Surfaces the eleven runtime-tunable keys catalogued in
 * {@code SettingsKey} (server: pantera-main/.../SettingsKey.java) as
 * editable fields grouped into two cards:
 *
 *   1. HTTP Upstream Client — protocol + H2 pool/multiplexing knobs.
 *   2. Pre-fetch — global toggle, concurrency caps, queue depth,
 *      worker threads, and circuit-breaker thresholds.
 *
 * Each field tracks its dirty state so "Save" only PATCHes what has
 * actually changed. "Reset" issues DELETE for the key, reverting to
 * the spec default. The {@code source} chip ("db" / "default") shows
 * ops which fields are actively overridden vs. running on defaults.
 */

const notify = useNotificationStore()

const loading = ref(true)
const loadError = ref('')

// Catalog rows keyed by setting key for O(1) lookup. We mutate the
// {@code value} field in-place during editing — the {@code default}
// and {@code source} fields update only after a successful PATCH/DELETE.
const rows = reactive<Record<string, RuntimeSetting>>({})

// Edit buffer — separate from the persisted row so we can display the
// dirty marker without overwriting the canonical value until the user
// hits Save. Maps key -> the in-flight edited value.
const edited = reactive<Record<string, RuntimeValue>>({})

// Per-row saving spinner state. Keeps the per-row Save and Reset
// buttons independently controllable.
const saving = reactive<Record<string, boolean>>({})

const PROTOCOL_OPTIONS = [
  { label: 'HTTP/2', value: 'h2' },
  { label: 'HTTP/1.1', value: 'h1' },
  { label: 'Auto', value: 'auto' },
]

/**
 * Documented ranges for each key — must mirror the server-side
 * {@code validateRuntime} in SettingsHandler so the UI catches
 * invalid input before the round-trip.
 */
interface IntRange { min: number; max: number }
const INT_RANGES: Record<RuntimeSettingKey, IntRange | null> = {
  'http_client.protocol': null,
  'http_client.http2_max_pool_size': { min: 1, max: 8 },
  'http_client.http2_multiplexing_limit': { min: 1, max: 1000 },
  'prefetch.enabled': null,
  'prefetch.concurrency.global': { min: 1, max: 512 },
  'prefetch.concurrency.per_upstream': { min: 1, max: 128 },
  'prefetch.queue.capacity': { min: 128, max: 16_384 },
  'prefetch.worker_threads': { min: 1, max: 32 },
  'prefetch.circuit_breaker.drop_threshold_per_sec': { min: 1, max: 10_000 },
  'prefetch.circuit_breaker.window_seconds': { min: 1, max: 600 },
  'prefetch.circuit_breaker.disable_minutes': { min: 1, max: 1440 },
}

const HTTP_KEYS: RuntimeSettingKey[] = [
  'http_client.protocol',
  'http_client.http2_max_pool_size',
  'http_client.http2_multiplexing_limit',
]

const PREFETCH_KEYS: RuntimeSettingKey[] = [
  'prefetch.enabled',
  'prefetch.concurrency.global',
  'prefetch.concurrency.per_upstream',
  'prefetch.queue.capacity',
  'prefetch.worker_threads',
  'prefetch.circuit_breaker.drop_threshold_per_sec',
  'prefetch.circuit_breaker.window_seconds',
  'prefetch.circuit_breaker.disable_minutes',
]

const LABELS: Record<RuntimeSettingKey, string> = {
  'http_client.protocol': 'Protocol',
  'http_client.http2_max_pool_size': 'HTTP/2 max pool size',
  'http_client.http2_multiplexing_limit': 'HTTP/2 multiplexing limit',
  'prefetch.enabled': 'Pre-fetch enabled',
  'prefetch.concurrency.global': 'Global concurrency',
  'prefetch.concurrency.per_upstream': 'Per-upstream concurrency',
  'prefetch.queue.capacity': 'Queue capacity',
  'prefetch.worker_threads': 'Worker threads',
  'prefetch.circuit_breaker.drop_threshold_per_sec':
    'Circuit-breaker drop threshold (drops/sec)',
  'prefetch.circuit_breaker.window_seconds':
    'Circuit-breaker window (seconds)',
  'prefetch.circuit_breaker.disable_minutes':
    'Circuit-breaker disable (minutes)',
}

const HELP: Partial<Record<RuntimeSettingKey, string>> = {
  'http_client.protocol':
    'Default upstream HTTP protocol. h2 is preferred — multiplexing reuses '
    + 'a single connection. h1 forces classic HTTP/1.1 (one request per '
    + 'connection). auto negotiates per upstream.',
  'http_client.http2_max_pool_size':
    'Max concurrent HTTP/2 connections per destination. Most upstreams '
    + 'multiplex thousands of streams over a single connection — leave at 1 '
    + 'unless you have a specific need to fan out.',
  'http_client.http2_multiplexing_limit':
    'Max concurrent streams per HTTP/2 connection. Higher values increase '
    + 'parallelism on a single connection at the cost of head-of-line '
    + 'blocking risk.',
  'prefetch.enabled':
    'Master toggle. When off, no prefetch tasks are scheduled regardless of '
    + 'per-repo settings. Useful as a kill-switch.',
  'prefetch.concurrency.global':
    'Maximum number of in-flight prefetch tasks across all upstreams.',
  'prefetch.concurrency.per_upstream':
    'Maximum number of in-flight prefetch tasks against any single upstream.',
  'prefetch.queue.capacity':
    'Bounded queue size. New tasks dropped when full — see the per-repo '
    + 'panel for queue-full counters.',
  'prefetch.worker_threads':
    'Worker thread-pool size for the dispatcher.',
  'prefetch.circuit_breaker.drop_threshold_per_sec':
    'When the global drop rate exceeds this threshold, the breaker opens.',
  'prefetch.circuit_breaker.window_seconds':
    'Sliding window over which drop rate is measured.',
  'prefetch.circuit_breaker.disable_minutes':
    'How long the breaker stays open once tripped before re-evaluating.',
}

// ---------------------------------------------------------------------------
// Load
// ---------------------------------------------------------------------------
onMounted(async () => {
  try {
    const settings = await listRuntimeSettings()
    for (const s of settings) {
      rows[s.key] = s
      edited[s.key] = s.value
    }
  } catch (err: unknown) {
    const ax = err as { response?: { data?: { message?: string } }; message?: string }
    loadError.value = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
  } finally {
    loading.value = false
  }
})

// ---------------------------------------------------------------------------
// Field state helpers
// ---------------------------------------------------------------------------
function isDirty(key: RuntimeSettingKey): boolean {
  const row = rows[key]
  if (!row) return false
  return edited[key] !== row.value
}

function valueOrDefault(key: RuntimeSettingKey): RuntimeValue | undefined {
  return rows[key]?.value
}

const anyDirty = computed(() => {
  for (const key of Object.keys(rows) as RuntimeSettingKey[]) {
    if (isDirty(key)) return true
  }
  return false
})

// ---------------------------------------------------------------------------
// Save / reset actions
// ---------------------------------------------------------------------------
async function saveOne(key: RuntimeSettingKey) {
  if (!isDirty(key)) return
  saving[key] = true
  try {
    const updated = await patchRuntimeSetting(key, edited[key])
    rows[key] = updated
    edited[key] = updated.value
    notify.success('Setting saved', key)
  } catch (err: unknown) {
    const ax = err as { response?: { data?: { message?: string } }; message?: string }
    const detail = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
    notify.error(`Failed to save ${key}`, detail)
    // Roll the editor back to the persisted value so the dirty marker
    // clears — otherwise the user is stuck with a value the server
    // rejected.
    edited[key] = rows[key].value
  } finally {
    saving[key] = false
  }
}

async function saveAllDirty() {
  const dirty = (Object.keys(rows) as RuntimeSettingKey[]).filter(isDirty)
  // Per-key PATCH preserves ordering and lets us surface per-key errors
  // without one failure taking out the entire batch.
  for (const key of dirty) {
    await saveOne(key)
  }
}

async function resetOne(key: RuntimeSettingKey) {
  saving[key] = true
  try {
    await resetRuntimeSetting(key)
    // Local state: revert to spec default and mark source 'default'.
    if (rows[key]) {
      rows[key] = {
        ...rows[key],
        value: rows[key].default,
        source: 'default',
      }
      edited[key] = rows[key].value
    }
    notify.success('Reverted to default', key)
  } catch (err: unknown) {
    const ax = err as { response?: { data?: { message?: string } }; message?: string }
    const detail = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
    notify.error(`Failed to reset ${key}`, detail)
  } finally {
    saving[key] = false
  }
}
</script>

<template>
  <AppLayout>
    <div class="max-w-4xl space-y-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">
          Performance Tuning
        </h1>
        <p class="text-sm text-gray-500 mt-1">
          Live runtime knobs for the upstream HTTP client and the
          pre-fetch subsystem. Changes apply within a few hundred
          milliseconds — no restart required. Out-of-range values are
          rejected by the server.
        </p>
      </div>

      <div v-if="loading" class="text-sm text-gray-500">Loading…</div>

      <div
        v-else-if="loadError"
        class="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-4 text-sm text-red-700 dark:text-red-300"
      >
        Failed to load runtime settings: {{ loadError }}
      </div>

      <template v-else>
        <!-- HTTP Upstream Client -->
        <Card class="shadow-sm">
          <template #title>HTTP Upstream Client</template>
          <template #subtitle>
            How pantera connects to upstream registries when proxying
            artifact requests.
          </template>
          <template #content>
            <div class="space-y-5">
              <div
                v-for="key in HTTP_KEYS"
                :key="key"
                class="flex flex-col gap-1"
                :data-testid="`runtime-row-${key}`"
              >
                <div class="flex items-center justify-between gap-2">
                  <label
                    :for="`field-${key}`"
                    class="text-sm font-medium text-gray-700 dark:text-gray-200"
                  >
                    {{ LABELS[key] }}
                  </label>
                  <Tag
                    :value="rows[key]?.source ?? 'default'"
                    :severity="rows[key]?.source === 'db' ? 'success' : 'secondary'"
                    class="text-xs"
                    :data-testid="`runtime-source-${key}`"
                  />
                </div>

                <!-- Protocol dropdown -->
                <Select
                  v-if="key === 'http_client.protocol'"
                  :id="`field-${key}`"
                  v-model="edited[key]"
                  :options="PROTOCOL_OPTIONS"
                  optionLabel="label"
                  optionValue="value"
                  class="w-full max-w-xs"
                  :data-testid="`runtime-input-${key}`"
                />

                <!-- Integer inputs for the H2 knobs -->
                <InputNumber
                  v-else
                  :id="`field-${key}`"
                  v-model="edited[key] as number"
                  :min="INT_RANGES[key]?.min"
                  :max="INT_RANGES[key]?.max"
                  show-buttons
                  class="w-48"
                  :input-props="{ 'data-testid': `runtime-input-${key}` }"
                />

                <div
                  v-if="HELP[key]"
                  class="text-xs text-gray-500"
                >
                  {{ HELP[key] }}
                  <template v-if="INT_RANGES[key]">
                    Allowed range:
                    {{ INT_RANGES[key]?.min }}–{{ INT_RANGES[key]?.max }}.
                  </template>
                  Default: {{ rows[key]?.default }}.
                </div>

                <div class="flex gap-2 mt-1">
                  <Button
                    label="Save"
                    icon="pi pi-save"
                    size="small"
                    :loading="saving[key]"
                    :disabled="!isDirty(key) || saving[key]"
                    :data-testid="`runtime-save-${key}`"
                    @click="saveOne(key)"
                  />
                  <Button
                    v-if="rows[key]?.source === 'db'"
                    label="Reset to default"
                    icon="pi pi-undo"
                    size="small"
                    severity="secondary"
                    text
                    :loading="saving[key]"
                    :data-testid="`runtime-reset-${key}`"
                    @click="resetOne(key)"
                  />
                </div>
              </div>
            </div>
          </template>
        </Card>

        <!-- Pre-fetch -->
        <Card class="shadow-sm">
          <template #title>Pre-fetch</template>
          <template #subtitle>
            Background warm-up of related artifacts after a proxy cache
            write. Per-repo opt-out is on the repository edit page.
          </template>
          <template #content>
            <div class="space-y-5">
              <div
                v-for="key in PREFETCH_KEYS"
                :key="key"
                class="flex flex-col gap-1"
                :data-testid="`runtime-row-${key}`"
              >
                <div class="flex items-center justify-between gap-2">
                  <label
                    :for="`field-${key}`"
                    class="text-sm font-medium text-gray-700 dark:text-gray-200"
                  >
                    {{ LABELS[key] }}
                  </label>
                  <Tag
                    :value="rows[key]?.source ?? 'default'"
                    :severity="rows[key]?.source === 'db' ? 'success' : 'secondary'"
                    class="text-xs"
                    :data-testid="`runtime-source-${key}`"
                  />
                </div>

                <!-- Boolean toggle -->
                <InputSwitch
                  v-if="key === 'prefetch.enabled'"
                  :id="`field-${key}`"
                  v-model="edited[key] as boolean"
                  :data-testid="`runtime-input-${key}`"
                />

                <!-- Integer knobs -->
                <InputNumber
                  v-else
                  :id="`field-${key}`"
                  v-model="edited[key] as number"
                  :min="INT_RANGES[key]?.min"
                  :max="INT_RANGES[key]?.max"
                  show-buttons
                  class="w-48"
                  :input-props="{ 'data-testid': `runtime-input-${key}` }"
                />

                <div
                  v-if="HELP[key]"
                  class="text-xs text-gray-500"
                >
                  {{ HELP[key] }}
                  <template v-if="INT_RANGES[key]">
                    Allowed range:
                    {{ INT_RANGES[key]?.min }}–{{ INT_RANGES[key]?.max }}.
                  </template>
                  Default: {{ rows[key]?.default }}.
                </div>

                <div class="flex gap-2 mt-1">
                  <Button
                    label="Save"
                    icon="pi pi-save"
                    size="small"
                    :loading="saving[key]"
                    :disabled="!isDirty(key) || saving[key]"
                    :data-testid="`runtime-save-${key}`"
                    @click="saveOne(key)"
                  />
                  <Button
                    v-if="rows[key]?.source === 'db'"
                    label="Reset to default"
                    icon="pi pi-undo"
                    size="small"
                    severity="secondary"
                    text
                    :loading="saving[key]"
                    :data-testid="`runtime-reset-${key}`"
                    @click="resetOne(key)"
                  />
                </div>
              </div>
            </div>
          </template>
        </Card>

        <!-- Save-all bar -->
        <div
          v-if="anyDirty"
          class="sticky bottom-4 flex items-center gap-3 bg-amber-50 dark:bg-amber-900/30 border border-amber-300 dark:border-amber-700 rounded-lg p-3"
        >
          <i class="pi pi-info-circle text-amber-600 dark:text-amber-400" />
          <span class="text-sm">
            You have unsaved changes.
          </span>
          <Button
            label="Save all"
            icon="pi pi-save"
            size="small"
            class="ml-auto"
            data-testid="runtime-save-all"
            @click="saveAllDirty"
          />
        </div>

        <!-- Suppress unused-binding lint when valueOrDefault is not
             reached (e.g. all rows in default state). The function is
             kept exported for future use by sub-components. -->
        <span class="hidden">{{ valueOrDefault }}</span>
      </template>
    </div>
  </AppLayout>
</template>
