<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { search as searchApi } from '@/api/search'
import { repoTypeIcon, repoTypeColorClass, repoTypeBaseLabel } from '@/utils/repoTypes'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Paginator from 'primevue/paginator'
import type { SearchResult } from '@/types'

const query = ref('')
const items = ref<SearchResult[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const typeFilter = ref<string | null>(null)
let debounceTimer: ReturnType<typeof setTimeout> | null = null

watch(query, (val) => {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (!val.trim()) {
    items.value = []
    total.value = 0
    return
  }
  debounceTimer = setTimeout(() => { page.value = 0; doSearch() }, 300)
})

function relevanceScore(item: SearchResult, q: string): number {
  const path = normalizePath(item.artifact_path).toLowerCase()
  const name = artifactName(item.artifact_path).toLowerCase()
  const ql = q.toLowerCase()
  if (name === ql || path.endsWith('/' + ql)) return 0
  const lastSeg = ql.includes('/') ? ql.substring(ql.lastIndexOf('/') + 1) : ql
  if (name === lastSeg) return 1
  if (path.endsWith(ql)) return 2
  if (path.includes(ql)) return 3
  if (name.includes(lastSeg)) return 4
  return 5
}

async function doSearch() {
  if (!query.value.trim()) return
  loading.value = true
  try {
    const resp = await searchApi({ q: query.value, page: page.value, size: size.value })
    const q = query.value
    items.value = [...resp.items].sort((a, b) => {
      const ra = relevanceScore(a, q)
      const rb = relevanceScore(b, q)
      if (ra !== rb) return ra - rb
      const nameA = artifactName(a.artifact_path)
      const nameB = artifactName(b.artifact_path)
      if (nameA !== nameB) return nameA.localeCompare(nameB)
      if (a.repo_name !== b.repo_name) return a.repo_name.localeCompare(b.repo_name)
      return (b.version ?? '').localeCompare(a.version ?? '')
    })
    total.value = resp.total
  } catch {
    items.value = []
  } finally {
    loading.value = false
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

// Derived filter data from results
const typeCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const item of items.value) {
    const base = (item.repo_type ?? 'unknown').replace(/-proxy$/, '').replace(/-group$/, '')
    counts[base] = (counts[base] ?? 0) + 1
  }
  return Object.entries(counts).sort(([, a], [, b]) => b - a)
})

const repoCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const item of items.value) {
    counts[item.repo_name] = (counts[item.repo_name] ?? 0) + 1
  }
  return Object.entries(counts).sort(([, a], [, b]) => b - a)
})

const filteredItems = computed(() => {
  if (!typeFilter.value) return items.value
  return items.value.filter(i => {
    const base = (i.repo_type ?? '').replace(/-proxy$/, '').replace(/-group$/, '')
    return base === typeFilter.value
  })
})

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
        </div>
        <div v-if="query && items.length > 0" class="text-sm text-gray-500 mt-3">
          Showing <strong class="text-gray-300">{{ filteredItems.length }}</strong> results for "{{ query }}"
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
                :class="typeFilter === null ? 'text-blue-400 font-medium bg-blue-500/5' : 'text-gray-400 hover:text-gray-200 hover:bg-white/5'"
                @click="selectType(null)"
              >
                <div class="w-3.5 h-3.5 rounded-full border-2 flex-shrink-0" :class="typeFilter === null ? 'border-blue-500 bg-blue-500 shadow-[inset_0_0_0_2px_#111827]' : 'border-gray-600'" />
                All
                <span class="ml-auto text-xs text-gray-600">{{ items.length }}</span>
              </div>
              <div
                v-for="[type, count] in typeCounts"
                :key="type"
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm cursor-pointer transition-colors"
                :class="typeFilter === type ? 'text-blue-400 font-medium bg-blue-500/5' : 'text-gray-400 hover:text-gray-200 hover:bg-white/5'"
                @click="selectType(type)"
              >
                <div class="w-3.5 h-3.5 rounded-full border-2 flex-shrink-0" :class="typeFilter === type ? 'border-blue-500 bg-blue-500 shadow-[inset_0_0_0_2px_#111827]' : 'border-gray-600'" />
                {{ repoTypeBaseLabel(type) }}
                <span class="ml-auto text-xs text-gray-600">{{ count }}</span>
              </div>
            </div>
          </div>

          <!-- Repo filter -->
          <div>
            <div class="text-[10px] font-semibold uppercase tracking-wider text-gray-500 mb-2 pb-1.5 border-b border-gray-200 dark:border-gray-800">Repository</div>
            <div class="space-y-0.5">
              <div
                v-for="[repo, count] in repoCounts"
                :key="repo"
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-blue-400 bg-blue-500/5 cursor-default"
              >
                <div class="w-3.5 h-3.5 rounded border-2 border-blue-500 bg-blue-500 flex items-center justify-center flex-shrink-0">
                  <i class="pi pi-check text-white" style="font-size: 8px;" />
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
            v-for="item in filteredItems"
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
        <p class="text-xs text-gray-600 mt-2">Try a different search term or check spelling</p>
      </div>
    </div>
  </AppLayout>
</template>
