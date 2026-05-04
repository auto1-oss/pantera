<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import Card from 'primevue/card'
import InputSwitch from 'primevue/inputswitch'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import { useNotificationStore } from '@/stores/notifications'
import {
  getPrefetchStats,
  patchPrefetchEnabled,
  type PrefetchStats,
} from '@/api/prefetch'

/**
 * Per-repo "Performance" panel rendered on the repo edit page (Phase 5
 * Task 23). Surfaces two pieces of state:
 *
 *  1. A pre-fetch toggle — round-trips through {@code PATCH
 *     /api/v1/repositories/:name {"settings":{"prefetch":bool}}}.
 *  2. A 24h stats grid — refreshed on mount and every 30s while the
 *     panel is mounted.
 *
 * The panel does not own the repo-config envelope; the parent owns it.
 * The {@code initialEnabled} prop seeds the toggle from the loaded
 * config and the panel emits {@code update:enabled} after a successful
 * PATCH so the parent's view of the config can stay in sync without
 * forcing a full reload.
 */
const props = defineProps<{
  /** Repo name (path param). */
  name: string
  /**
   * Initial value of the prefetch toggle as decoded from the parent's
   * loaded config. May be undefined while the parent is still loading.
   */
  initialEnabled?: boolean
}>()

const emit = defineEmits<{
  'update:enabled': [value: boolean]
}>()

const notify = useNotificationStore()

// Local toggle mirror — kept in sync with the prop until the user
// flips it; after a successful PATCH the parent re-emits the new
// value via the prop binding.
const enabled = ref<boolean>(props.initialEnabled ?? false)
const saving = ref(false)

watch(
  () => props.initialEnabled,
  (val) => {
    if (val !== undefined) enabled.value = val
  },
)

const stats = ref<PrefetchStats | null>(null)
const statsLoading = ref(false)
const statsError = ref('')

// 30s auto-refresh — cleared on unmount to avoid leaking the timer
// across route changes. We intentionally skip the refresh while a
// previous fetch is still in-flight so a slow API doesn't pile up
// pending requests.
let refreshTimer: ReturnType<typeof setInterval> | null = null

async function loadStats() {
  if (statsLoading.value) return
  statsLoading.value = true
  statsError.value = ''
  try {
    stats.value = await getPrefetchStats(props.name)
  } catch (err: unknown) {
    const ax = err as { response?: { data?: { message?: string } }; message?: string }
    statsError.value = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
  } finally {
    statsLoading.value = false
  }
}

async function onTogglePrefetch(next: boolean) {
  // PrimeVue InputSwitch already updates v-model before this fires;
  // we treat the new value as the desired state and roll back on failure.
  saving.value = true
  try {
    await patchPrefetchEnabled(props.name, next)
    emit('update:enabled', next)
    notify.success(
      `Prefetch ${next ? 'enabled' : 'disabled'}`,
      props.name,
    )
  } catch (err: unknown) {
    const ax = err as { response?: { data?: { message?: string } }; message?: string }
    const detail = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
    notify.error('Failed to update prefetch', detail)
    // Roll back the optimistic UI flip so the toggle reflects the
    // server-side reality.
    enabled.value = !next
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadStats()
  refreshTimer = setInterval(loadStats, 30_000)
})

onBeforeUnmount(() => {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})

// Relative-time renderer mirroring the helper in ArtifactTreeTable
// — kept inline to avoid a refactor of an unrelated module. Format:
// "Ns ago" / "Nm ago" / "Nh ago" / "Nd ago" / "—" if missing.
function formatRelative(iso?: string): string {
  if (!iso) return '—'
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return '—'
  const sec = Math.max(0, Math.round((Date.now() - ts) / 1000))
  if (sec < 60) return `${sec}s ago`
  const min = Math.round(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.round(min / 60)
  if (hr < 48) return `${hr}h ago`
  const day = Math.round(hr / 24)
  return `${day}d ago`
}

const lastFetchRel = computed(() => formatRelative(stats.value?.last_fetch_at))
const lastFetchAbs = computed(() => {
  const iso = stats.value?.last_fetch_at
  if (!iso) return ''
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return ''
  return new Date(ts).toLocaleString()
})
</script>

<template>
  <Card class="shadow-sm" data-testid="prefetch-panel">
    <template #title>
      <div class="flex items-center gap-2">
        <i class="pi pi-bolt text-amber-500" />
        <span>Performance</span>
      </div>
    </template>
    <template #subtitle>
      Pre-fetch eagerly warms the cache for related artifacts (e.g.
      Maven POM dependencies, npm tarballs) when an upstream miss is
      written. Toggle off to disable per repo without a server restart.
    </template>
    <template #content>
      <div class="space-y-5">
        <!-- Toggle row -->
        <div
          class="flex items-center justify-between p-3 bg-gray-50 dark:bg-gray-800 rounded-lg"
        >
          <div>
            <div class="font-medium text-sm">Pre-fetch</div>
            <div class="text-xs text-gray-500">
              When enabled, related artifacts are queued for warm-up after
              a successful proxy cache write.
            </div>
          </div>
          <InputSwitch
            v-model="enabled"
            :disabled="saving"
            data-testid="prefetch-toggle"
            aria-label="Toggle prefetch for this repository"
            @update:modelValue="onTogglePrefetch"
          />
        </div>

        <!-- Stats grid -->
        <div>
          <div class="flex items-center justify-between mb-2">
            <h4 class="text-sm font-medium">Last 24 hours</h4>
            <Button
              icon="pi pi-refresh"
              severity="secondary"
              text
              rounded
              size="small"
              :loading="statsLoading"
              aria-label="Refresh prefetch stats"
              data-testid="prefetch-refresh"
              @click="loadStats"
            />
          </div>

          <div
            v-if="statsError"
            class="text-xs text-red-600 dark:text-red-400 mb-2"
            data-testid="prefetch-stats-error"
          >
            Failed to load stats: {{ statsError }}
          </div>

          <div
            class="grid grid-cols-2 sm:grid-cols-4 gap-3"
            data-testid="prefetch-stats-grid"
          >
            <div
              class="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg"
              data-testid="prefetch-stat-prefetched"
            >
              <div class="text-xs text-gray-500">Prefetched</div>
              <div class="text-xl font-semibold tabular-nums">
                {{ stats?.prefetched ?? 0 }}
              </div>
            </div>
            <div
              class="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg"
              data-testid="prefetch-stat-cooldown"
            >
              <div class="text-xs text-gray-500">Cooldown blocked</div>
              <div class="text-xl font-semibold tabular-nums">
                {{ stats?.cooldown_blocked ?? 0 }}
              </div>
            </div>
            <div
              class="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg"
              data-testid="prefetch-stat-queue-full"
            >
              <div class="text-xs text-gray-500">Dropped: queue full</div>
              <div class="text-xl font-semibold tabular-nums">
                {{ stats?.dropped_queue_full ?? 0 }}
              </div>
            </div>
            <div
              class="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg"
              data-testid="prefetch-stat-last-fetch"
            >
              <div class="text-xs text-gray-500">Last fetch</div>
              <div
                class="text-xl font-semibold"
                :title="lastFetchAbs"
              >
                {{ lastFetchRel }}
              </div>
            </div>
          </div>

          <div class="mt-3 flex items-center gap-2 text-xs text-gray-500">
            <Tag :value="stats?.window ?? '24h'" severity="info" />
            <span>
              Auto-refreshes every 30s. Stats reset when the
              prefetch metrics are sampled.
            </span>
          </div>
        </div>
      </div>
    </template>
  </Card>
</template>
