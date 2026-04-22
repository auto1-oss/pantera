<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { search as searchApi } from '@/api/search'
import { repoTypeIcon, repoTypeColorClass, repoTypeBaseLabel } from '@/utils/repoTypes'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import Paginator from 'primevue/paginator'
import type { SearchResult } from '@/types'

const syntaxHelp = ref()
const query = ref('')
const items = ref<SearchResult[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const typeFilter = ref<string | null>(null)
const repoFilter = ref<string | null>(null)
const sortBy = ref<string>('relevance')
const sortDir = ref<'asc' | 'desc'>('asc')

// Sidebar aggregations stored from backend responses
// type counts come from unfiltered searches; repo counts from type-filtered searches
const sidebarTypeCounts = ref<[string, number][]>([])
const sidebarRepoCounts = ref<[string, number][]>([])

let debounceTimer: ReturnType<typeof setTimeout> | null = null
let searchAbortCtrl: AbortController | null = null
// Guard flag: suppresses filter-watcher searches during query-change resets
let resettingFilters = false

watch(query, (val) => {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (!val.trim()) {
    if (searchAbortCtrl) { searchAbortCtrl.abort(); searchAbortCtrl = null }
    items.value = []
    total.value = 0
    loading.value = false
    sidebarTypeCounts.value = []
    sidebarRepoCounts.value = []
    resettingFilters = true
    typeFilter.value = null
    repoFilter.value = null
    resettingFilters = false
    return
  }
  debounceTimer = setTimeout(() => {
    page.value = 0
    sidebarTypeCounts.value = []
    sidebarRepoCounts.value = []
    resettingFilters = true
    typeFilter.value = null
    repoFilter.value = null
    resettingFilters = false
    doSearch()
  }, 300)
})

// When type filter changes, reset repo filter and re-search from page 0
watch(typeFilter, () => {
  if (resettingFilters) return
  repoFilter.value = null
  if (query.value.trim()) {
    page.value = 0
    doSearch()
  }
})

// When repo filter changes, re-search from page 0
watch(repoFilter, (val, old) => {
  if (resettingFilters) return
  if (val === old) return
  if (query.value.trim()) {
    page.value = 0
    doSearch()
  }
})

// When sort changes, re-search from page 0
watch([sortBy, sortDir], () => {
  if (query.value.trim()) {
    page.value = 0
    doSearch()
  }
})

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (searchAbortCtrl) searchAbortCtrl.abort()
})

async function doSearch() {
  if (!query.value.trim()) return
  if (searchAbortCtrl) searchAbortCtrl.abort()
  searchAbortCtrl = new AbortController()
  const ctrl = searchAbortCtrl
  loading.value = true
  try {
    const params: Parameters<typeof searchApi>[0] = {
      q: query.value,
      page: page.value,
      size: size.value,
      sort: sortBy.value,
      sort_dir: sortDir.value,
    }
    if (typeFilter.value) params.type = typeFilter.value
    if (repoFilter.value) params.repo = repoFilter.value

    const resp = await searchApi(params, ctrl.signal)
    if (ctrl.signal.aborted) return

    items.value = resp.items
    total.value = resp.total

    // Update sidebar aggregations from backend
    if (resp.type_counts) {
      const counts = Object.entries(resp.type_counts) as [string, number][]
      // Only update type sidebar when no type filter is active (preserve full distribution)
      if (!typeFilter.value) {
        sidebarTypeCounts.value = counts.sort(([, a], [, b]) => b - a)
      }
    }
    if (resp.repo_counts && !repoFilter.value) {
      const counts = Object.entries(resp.repo_counts) as [string, number][]
      sidebarRepoCounts.value = counts.sort(([, a], [, b]) => b - a)
    }
  } catch (err: unknown) {
    if (ctrl.signal.aborted) return
    items.value = []
  } finally {
    if (!ctrl.signal.aborted) loading.value = false
  }
}

function onPageChange(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
  doSearch()
}

function selectType(t: string | null) {
  typeFilter.value = t
}

function selectRepo(r: string | null) {
  repoFilter.value = r
}

function toggleSortDir() {
  sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
}

function formatSize(bytes: number): string {
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function normalizePath(path: string): string {
  if (path.includes('/')) return path
  const lastDot = path.lastIndexOf('.')
  if (lastDot <= 0) return path
  const ext = path.substring(lastDot)
  if (ext.length > 1 && ext.length <= 8) {
    const withoutExt = path.substring(0, lastDot)
    if (withoutExt.includes('.')) {
      return withoutExt.replace(/\./g, '/') + ext
    }
  }
  return path
}

function artifactName(path: string): string {
  const normalized = normalizePath(path)
  const parts = normalized.split('/').filter(Boolean)
  return parts.length > 0 ? parts[parts.length - 1] : path
}

function parentDir(path: string): string {
  const normalized = normalizePath(path)
  const parts = normalized.split('/').filter(Boolean)
  parts.pop()
  return parts.join('/')
}

// repoTypeIcon and repoTypeColorClass imported from @/utils/repoTypes

function browseUrl(data: SearchResult): string {
  const rtype = (data.repo_type ?? '').toLowerCase()
  const name = data.artifact_path
  const version = data.version
  let path: string

  if (rtype.startsWith('maven') || rtype.startsWith('gradle')) {
    const slashPath = name.replace(/\./g, '/')
    path = version ? `/${slashPath}/${version}` : `/${slashPath}`
  } else if (rtype === 'php-proxy') {
    path = '/dist/' + name
  } else if (rtype === 'php') {
    path = '/artifacts'
  } else if (
    rtype.startsWith('npm') || rtype.startsWith('pypi') ||
    rtype.startsWith('go') ||
    rtype.startsWith('gem') || rtype.startsWith('hex') ||
    rtype.startsWith('conan') || rtype.startsWith('conda') ||
    rtype.startsWith('nuget')
  ) {
    path = '/' + name
  } else if (rtype === 'file' || rtype === 'file-proxy' || rtype === 'file-group') {
    const normalized = normalizePath(name)
    const dir = normalized.split('/').filter(Boolean).slice(0, -1).join('/')
    path = '/' + (dir || '')
  } else if (rtype.startsWith('docker')) {
    path = '/'
  } else {
    const dir = parentDir(name)
    path = dir || '/'
  }
  return `/repositories/${data.repo_name}?path=${encodeURIComponent(path)}&from=search`
}

const SORT_OPTIONS = [
  { value: 'relevance', label: 'Relevance' },
  { value: 'name', label: 'Name' },
  { value: 'version', label: 'Version' },
  { value: 'created_at', label: 'Date' },
]
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <!-- Search hero -->
      <div>
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white mb-4">Search Artifacts</h1>
        <div class="flex items-center gap-0 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 p-1 transition-all focus-within:border-orange-500 focus-within:ring-2 focus-within:ring-orange-500/10">
          <i class="pi pi-search px-3.5 text-gray-400" />
          <input
            v-model="query"
            data-testid="search-input"
            type="text"
            placeholder="Search by artifact name, package, or path..."
            class="flex-1 bg-transparent border-none outline-none text-sm text-gray-900 dark:text-white py-2.5 placeholder:text-gray-400"
          />
          <Button label="Search" icon="pi pi-search" size="small" class="!rounded-lg" :loading="loading" @click="doSearch" />
          <button
            class="ml-1 mr-1 w-8 h-8 flex items-center justify-center rounded-lg text-gray-400 hover:text-orange-500 hover:bg-orange-500/10 transition-colors"
            title="Search syntax help"
            @click="(e: MouseEvent) => syntaxHelp.toggle(e)"
          >
            <i class="pi pi-question-circle text-base" />
          </button>
          <Popover ref="syntaxHelp">
            <div class="w-80 p-3 text-sm">
              <div class="font-semibold text-gray-900 dark:text-white mb-2">Search syntax</div>
              <table class="w-full text-xs">
                <tbody>
                  <tr v-for="row in [
                    ['pydantic', 'Full-text search'],
                    ['name:pydantic', 'Filter by package name'],
                    ['version:2.12', 'Filter by version'],
                    ['repo:pypi-proxy', 'Filter by repository'],
                    ['type:maven', 'Filter by repo type'],
                  ]" :key="row[0]" class="border-b border-gray-100 dark:border-gray-800 last:border-0">
                    <td class="py-1.5 pr-3 font-mono text-orange-500 whitespace-nowrap">{{ row[0] }}</td>
                    <td class="py-1.5 text-gray-500">{{ row[1] }}</td>
                  </tr>
                </tbody>
              </table>
              <div class="mt-3 pt-2 border-t border-gray-100 dark:border-gray-800">
                <div class="text-[10px] uppercase tracking-wider text-gray-500 font-semibold mb-1.5">Combine with AND / OR</div>
                <div class="space-y-1 font-mono text-xs text-gray-400">
                  <div>name:pydantic AND version:2.12</div>
                  <div>name:foo AND (version:1.0 OR version:2.0)</div>
                </div>
              </div>
            </div>
          </Popover>
        </div>
        <div v-if="query && items.length > 0" class="flex items-center justify-between mt-3">
          <div class="text-sm text-gray-500">
            Showing <strong class="text-gray-300">{{ total }}</strong> results for "{{ query }}"
          </div>
          <!-- Sort controls -->
          <div class="flex items-center gap-1.5">
            <span class="text-xs text-gray-500">Sort:</span>
            <div class="flex items-center gap-0.5 bg-gray-100 dark:bg-gray-800 rounded-lg p-0.5">
              <button
                v-for="opt in SORT_OPTIONS"
                :key="opt.value"
                class="px-2.5 py-1 text-xs rounded-md transition-colors"
                :class="sortBy === opt.value
                  ? 'bg-white dark:bg-gray-700 text-gray-900 dark:text-white font-medium shadow-sm'
                  : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'"
                @click="sortBy = opt.value"
              >
                {{ opt.label }}
              </button>
            </div>
            <button
              v-if="sortBy !== 'relevance'"
              class="w-6 h-6 flex items-center justify-center rounded text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
              :title="sortDir === 'asc' ? 'Ascending' : 'Descending'"
              @click="toggleSortDir"
            >
              <i :class="sortDir === 'asc' ? 'pi pi-sort-amount-up-alt' : 'pi pi-sort-amount-down'" style="font-size: 12px;" />
            </button>
          </div>
        </div>
      </div>

      <!-- Content: filters + results -->
      <div v-if="items.length > 0" class="flex gap-6">
        <!-- Filter sidebar -->
        <div class="w-48 flex-shrink-0 space-y-6">
          <!-- Type filter -->
          <div>
            <div class="text-[10px] font-semibold uppercase tracking-wider text-gray-500 mb-2 pb-1.5 border-b border-gray-200 dark:border-gray-800">Type</div>
            <div class="space-y-0.5">
              <div
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm cursor-pointer transition-colors"
                :class="typeFilter === null ? 'text-blue-400 font-medium bg-blue-500/5' : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-white/5'"
                @click="selectType(null)"
              >
                <div class="w-3.5 h-3.5 rounded-full border-2 flex-shrink-0" :class="typeFilter === null ? 'border-blue-500 bg-blue-500 shadow-[inset_0_0_0_2px_#f9fafb] dark:shadow-[inset_0_0_0_2px_#111827]' : 'border-gray-400 dark:border-gray-600'" />
                All
              </div>
              <div
                v-for="[type, count] in sidebarTypeCounts"
                :key="type"
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm cursor-pointer transition-colors"
                :class="typeFilter === type ? 'text-blue-400 font-medium bg-blue-500/5' : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-white/5'"
                @click="selectType(type)"
              >
                <div class="w-3.5 h-3.5 rounded-full border-2 flex-shrink-0" :class="typeFilter === type ? 'border-blue-500 bg-blue-500 shadow-[inset_0_0_0_2px_#f9fafb] dark:shadow-[inset_0_0_0_2px_#111827]' : 'border-gray-400 dark:border-gray-600'" />
                {{ repoTypeBaseLabel(type) }}
                <span class="ml-auto text-xs text-gray-600">{{ count }}</span>
              </div>
            </div>
          </div>

          <!-- Repo filter -->
          <div v-if="typeFilter">
            <div class="text-[10px] font-semibold uppercase tracking-wider text-gray-500 mb-2 pb-1.5 border-b border-gray-200 dark:border-gray-800">Repository</div>
            <div class="space-y-0.5">
              <div
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm cursor-pointer transition-colors"
                :class="repoFilter === null ? 'text-blue-400 font-medium bg-blue-500/5' : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-white/5'"
                @click="selectRepo(null)"
              >
                <div class="w-3.5 h-3.5 rounded border-2 flex-shrink-0 flex items-center justify-center" :class="repoFilter === null ? 'border-blue-500 bg-blue-500' : 'border-gray-600'">
                  <i v-if="repoFilter === null" class="pi pi-check text-white" style="font-size: 8px;" />
                </div>
                All
                <span class="ml-auto text-xs text-gray-600">{{ sidebarRepoCounts.reduce((s, [, c]) => s + c, 0) }}</span>
              </div>
              <div
                v-for="[repo, count] in sidebarRepoCounts"
                :key="repo"
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm cursor-pointer transition-colors"
                :class="repoFilter === repo ? 'text-blue-400 font-medium bg-blue-500/5' : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-white/5'"
                @click="selectRepo(repo)"
              >
                <div class="w-3.5 h-3.5 rounded border-2 flex-shrink-0 flex items-center justify-center" :class="repoFilter === repo ? 'border-blue-500 bg-blue-500' : 'border-gray-600'">
                  <i v-if="repoFilter === repo" class="pi pi-check text-white" style="font-size: 8px;" />
                </div>
                <span class="truncate">{{ repo }}</span>
                <span class="ml-auto text-xs text-gray-600 flex-shrink-0">{{ count }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Result cards -->
        <div class="flex-1 min-w-0 space-y-2.5">
          <div
            v-for="item in items"
            :key="`${item.repo_name}-${item.artifact_path}-${item.version}`"
            class="flex items-start gap-3.5 p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 hover:border-gray-300 dark:hover:border-gray-700 transition-colors cursor-pointer"
          >
            <!-- Type icon -->
            <div class="w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5" :class="repoTypeColorClass(item.repo_type)">
              <i :class="repoTypeIcon(item.repo_type)" class="text-lg" />
            </div>

            <!-- Body -->
            <div class="flex-1 min-w-0">
              <div class="font-semibold text-sm text-gray-900 dark:text-white font-mono">
                {{ artifactName(item.artifact_path) }}
              </div>
              <div class="text-xs text-gray-400 font-mono mt-0.5 truncate">
                {{ normalizePath(item.artifact_path) }}
              </div>
              <div class="flex items-center gap-2 mt-2 flex-wrap">
                <RepoTypeBadge :type="item.repo_type" />
                <span class="text-[10px] font-medium px-1.5 py-0.5 rounded bg-gray-100 dark:bg-gray-800 text-gray-500">{{ item.repo_name }}</span>
                <span v-if="item.version" class="text-[10px] font-medium px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-500">v{{ item.version }}</span>
                <span class="text-xs text-gray-500">{{ formatSize(item.size) }}</span>
              </div>
            </div>

            <!-- Browse -->
            <router-link :to="browseUrl(item)" custom v-slot="{ navigate }">
              <Button
                icon="pi pi-folder-open"
                label="Browse"
                severity="secondary"
                outlined
                size="small"
                class="!rounded-lg flex-shrink-0 self-center"
                @click.stop="navigate"
              />
            </router-link>
          </div>

          <!-- Pagination -->
          <Paginator
            v-if="total > size"
            :rows="size"
            :totalRecords="total"
            :first="page * size"
            @page="onPageChange"
            :rowsPerPageOptions="[10, 20, 50]"
          />
        </div>
      </div>

      <!-- Empty states -->
      <div v-if="loading && items.length === 0" class="text-center py-16 text-gray-400">
        <i class="pi pi-spin pi-spinner text-2xl" />
      </div>
      <div v-else-if="!query" class="text-center py-16">
        <i class="pi pi-search text-4xl text-gray-700 mb-4" />
        <p class="text-gray-500 text-sm">Enter a search term to find artifacts across all repositories</p>
      </div>
      <div v-else-if="query && items.length === 0 && !loading" class="text-center py-16">
        <i class="pi pi-inbox text-4xl text-gray-700 mb-4" />
        <p class="text-gray-400">No results found for "{{ query }}"</p>
        <p class="text-xs text-gray-600 mt-2">Try a different search term, check spelling, or use field syntax: <span class="font-mono text-orange-500">name:</span> <span class="font-mono text-orange-500">version:</span> <span class="font-mono text-orange-500">repo:</span></p>
      </div>
    </div>
  </AppLayout>
</template>
