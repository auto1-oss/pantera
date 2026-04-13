<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { getDashboardStats, getUiSettings } from '@/api/settings'
import { useConfigStore } from '@/stores/config'
import { useAuthStore } from '@/stores/auth'
import { repoTypeColor } from '@/utils/repoTypes'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import Button from 'primevue/button'
import type { DashboardStats } from '@/types'

const config = useConfigStore()
const auth = useAuthStore()
const stats = ref<DashboardStats>({ repo_count: 0, artifact_count: 0, total_storage: 0, blocked_count: 0 })
const loading = ref(true)

onMounted(async () => {
  try {
    stats.value = await getDashboardStats()
  } catch {
    // Stats unavailable
  } finally {
    loading.value = false
  }
  // UI settings are available to all authenticated users.
  try {
    const uiSettings = await getUiSettings()
    if (uiSettings.ui?.grafana_url) config.grafanaUrl = uiSettings.ui.grafana_url
    if (uiSettings.ui?.registry_url) config.registryUrl = uiSettings.ui.registry_url
  } catch {
    // Grafana link unavailable — not critical
  }
})

const storageDisplay = computed(() => {
  const raw = stats.value.total_storage
  const bytes = typeof raw === 'string' ? parseFloat(raw) || 0 : raw
  if (bytes >= 1_125_899_906_842_624) return `${(bytes / 1_125_899_906_842_624).toFixed(1)} PB`
  if (bytes >= 1_099_511_627_776) return `${(bytes / 1_099_511_627_776).toFixed(1)} TB`
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`
  return `${bytes} B`
})

const topRepos = computed(() =>
  [...(stats.value.top_repos ?? [])]
    .sort((a, b) => (b.size ?? 0) - (a.size ?? 0) || b.artifact_count - a.artifact_count)
    .slice(0, 5),
)
const maxSize = computed(() => topRepos.value.length > 0 ? (topRepos.value[0].size ?? 1) : 1)

function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return String(n)
}

const showPgCronNotice = computed(
  () => !loading.value && stats.value.artifact_count === 0 && stats.value.repo_count > 0,
)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return 'Good morning'
  if (h < 18) return 'Good afternoon'
  return 'Good evening'
})

function formatSize(bytes: number): string {
  if (!bytes) return '—'
  if (bytes >= 1_125_899_906_842_624) return `${(bytes / 1_125_899_906_842_624).toFixed(1)} PB`
  if (bytes >= 1_099_511_627_776) return `${(bytes / 1_099_511_627_776).toFixed(1)} TB`
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

const statCards = computed(() => [
  {
    key: 'repos',
    label: 'Repositories',
    value: String(stats.value.repo_count),
    sub: `Across ${new Set((stats.value.top_repos ?? []).map(r => r.type.replace(/-proxy$/, '').replace(/-group$/, ''))).size || '—'} package formats`,
    icon: 'pi pi-box',
    accent: 'border-t-blue-500',
    iconBg: 'bg-blue-500/10 text-blue-500',
  },
  {
    key: 'artifacts',
    label: 'Artifacts',
    value: formatCount(typeof stats.value.artifact_count === 'number' ? stats.value.artifact_count : 0),
    sub: 'Total indexed artifacts',
    icon: 'pi pi-file',
    accent: 'border-t-emerald-500',
    iconBg: 'bg-emerald-500/10 text-emerald-500',
  },
  {
    key: 'storage',
    label: 'Storage Used',
    value: storageDisplay.value,
    sub: 'Across all repositories',
    icon: 'pi pi-database',
    accent: 'border-t-purple-500',
    iconBg: 'bg-purple-500/10 text-purple-500',
  },
  {
    key: 'blocked',
    label: 'Blocked',
    value: String(stats.value.blocked_count),
    sub: 'Artifacts in cooldown',
    icon: 'pi pi-ban',
    accent: 'border-t-red-500',
    iconBg: 'bg-red-500/10 text-red-500',
  },
])
</script>

<template>
  <AppLayout>
    <div class="space-y-6">
      <!-- Page header -->
      <div class="flex items-end justify-between">
        <div>
          <div class="text-sm text-gray-500">{{ greeting }}, {{ auth.username }}</div>
          <h1 class="text-xl font-bold text-gray-900 dark:text-white mt-1">
            Your <span class="text-amber-500">artifact registry</span> at a glance
          </h1>
        </div>
        <a
          v-if="config.grafanaUrl"
          :href="config.grafanaUrl"
          target="_blank"
          class="text-sm text-amber-500 hover:bg-amber-500/5 flex items-center gap-1.5 px-3.5 py-2 rounded-lg border border-amber-500/20 transition-colors"
        >
          <i class="pi pi-external-link text-xs" /> Grafana
        </a>
      </div>

      <!-- pg_cron notice: shown when repos exist but artifact stats are all zero -->
      <div
        v-if="showPgCronNotice"
        class="flex items-start gap-3 rounded-xl border border-amber-500/30 bg-amber-500/5 px-4 py-3 text-sm text-amber-600 dark:text-amber-400"
      >
        <i class="pi pi-exclamation-triangle mt-0.5 flex-shrink-0" />
        <span>
          Dashboard statistics show zero because the database materialized views have not been refreshed yet.
          <strong>pg_cron must be configured</strong> to keep statistics up to date.
          See the <a href="https://github.com/auto1-oss/pantera/blob/master/docs/admin-guide/installation.md#database-setup-dashboard-materialized-views" target="_blank" class="underline hover:text-amber-500">Admin Guide — Database Setup</a> for setup instructions.
        </span>
      </div>

      <!-- Stat cards -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div
          v-for="card in statCards"
          :key="card.key"
          class="rounded-xl border border-gray-200 dark:border-b-gray-700 dark:border-x-gray-700 bg-white dark:bg-gray-800 p-5 border-t-[3px]"
          :class="card.accent"
        >
          <div class="flex items-center justify-between mb-3">
            <span class="text-xs font-medium uppercase tracking-wider text-gray-500">{{ card.label }}</span>
            <div class="w-9 h-9 rounded-lg flex items-center justify-center" :class="card.iconBg">
              <i :class="card.icon" class="text-base" />
            </div>
          </div>
          <div class="text-3xl font-extrabold text-gray-900 dark:text-white tracking-tight leading-none">
            {{ card.value }}
          </div>
          <div class="text-xs text-gray-500 mt-1.5">{{ card.sub }}</div>
        </div>
      </div>

      <!-- Top 5 Repositories -->
      <div v-if="topRepos.length > 0" class="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 overflow-hidden">
        <div class="px-5 py-3.5 border-b border-gray-200 dark:border-gray-700 flex items-center justify-between">
          <h3 class="text-sm font-semibold text-gray-900 dark:text-white">Top Repositories</h3>
          <router-link to="/repositories" class="text-xs text-amber-500 hover:underline">View all</router-link>
        </div>
        <!-- Column headers -->
        <div class="flex items-center gap-3 px-5 py-2 text-[10px] font-semibold uppercase tracking-wider text-gray-500 border-b border-gray-200 dark:border-gray-700/50">
          <span class="w-7" />
          <span class="flex-1">Repository</span>
          <span class="w-36 text-center">Usage</span>
          <span class="w-20 text-right">Artifacts</span>
          <span class="w-20 text-right">Size</span>
        </div>
        <div>
          <div
            v-for="(repo, idx) in topRepos"
            :key="repo.name"
            class="flex items-center gap-3 px-5 py-3 border-b border-gray-100 dark:border-gray-700/30 last:border-b-0 hover:bg-gray-50 dark:hover:bg-gray-700/20 transition-colors"
          >
            <!-- Rank -->
            <div
              class="w-7 h-7 rounded-lg flex items-center justify-center text-xs font-bold flex-shrink-0"
              :class="idx < 3 ? 'bg-amber-500/10 text-amber-500' : 'bg-gray-100 dark:bg-gray-700 text-gray-500'"
            >
              {{ idx + 1 }}
            </div>

            <!-- Name + type -->
            <div class="flex-1 min-w-0">
              <router-link
                :to="`/repositories/${repo.name}`"
                class="text-sm font-semibold text-gray-900 dark:text-gray-100 hover:text-amber-500 transition-colors"
              >
                {{ repo.name }}
              </router-link>
              <RepoTypeBadge :type="repo.type" class="mt-1" />
            </div>

            <!-- Bar -->
            <div class="w-36 flex-shrink-0">
              <div class="h-2 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
                <div
                  class="h-full rounded-full"
                  :style="{ width: `${Math.max(((repo.size ?? 0) / maxSize) * 100, 6)}%`, background: repoTypeColor(repo.type) }"
                />
              </div>
            </div>

            <!-- Artifacts column -->
            <div class="w-20 text-right flex-shrink-0 tabular-nums text-sm font-semibold text-gray-700 dark:text-gray-300">
              {{ formatCount(repo.artifact_count) }}
            </div>

            <!-- Size column -->
            <div class="w-20 text-right flex-shrink-0 tabular-nums text-sm text-gray-500 dark:text-gray-400">
              {{ repo.size ? formatSize(repo.size) : '—' }}
            </div>
          </div>
        </div>
      </div>

      <!-- Loading -->
      <div v-if="loading" class="text-center text-gray-400 py-12">
        <i class="pi pi-spin pi-spinner text-2xl" />
      </div>
    </div>
  </AppLayout>
</template>
