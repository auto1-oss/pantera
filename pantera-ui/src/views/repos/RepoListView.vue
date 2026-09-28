<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, defineAsyncComponent } from 'vue'
import { useRouter } from 'vue-router'
import { listRepos } from '@/api/repos'
import { REPO_TYPE_FILTERS } from '@/utils/repoTypes'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Paginator from 'primevue/paginator'
import type { RepoListItem } from '@/types'
import { techForRepoType } from '@/utils/techSetup'

const SetMeUpDrawer = defineAsyncComponent(() => import('@/components/setup/SetMeUpDrawer.vue'))

const router = useRouter()
const typeFilter = ref<string | null>(null)

const items = ref<RepoListItem[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const searchQuery = ref('')
let debounceTimer: ReturnType<typeof setTimeout> | null = null
let fetchAbortCtrl: AbortController | null = null

watch(searchQuery, () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => { page.value = 0; fetchRepos() }, 300)
})

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (fetchAbortCtrl) fetchAbortCtrl.abort()
})

function onTypeChange() {
  page.value = 0
  fetchRepos()
}

function onPageChange(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
  fetchRepos()
}

async function fetchRepos() {
  if (fetchAbortCtrl) fetchAbortCtrl.abort()
  fetchAbortCtrl = new AbortController()
  const ctrl = fetchAbortCtrl
  loading.value = true
  try {
    const resp = await listRepos({
      page: page.value,
      size: size.value,
      type: typeFilter.value ?? undefined,
      q: searchQuery.value || undefined,
    }, ctrl.signal)
    if (ctrl.signal.aborted) return
    items.value = resp.items
    total.value = resp.total
  } catch (err: unknown) {
    if (ctrl.signal.aborted) return
    items.value = []
  } finally {
    if (!ctrl.signal.aborted) loading.value = false
  }
}

onMounted(fetchRepos)

// Set Me Up drawer for one row
const setupOpen = ref(false)
const setupRepo = ref<{ name: string; tech: string } | null>(null)

function openSetup(repo: RepoListItem) {
  const tech = techForRepoType(repo.type)
  if (!tech) return
  setupRepo.value = { name: repo.name, tech: tech.key }
  setupOpen.value = true
}
</script>

<template>
  <AppLayout>
    <div class="space-y-4">
      <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Repositories</h1>

      <!-- Toolbar -->
      <div class="flex flex-wrap items-center gap-3">
        <span class="relative">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <InputText v-model="searchQuery" placeholder="Filter repositories..." class="!pl-10 w-64" />
        </span>
        <Select
          v-model="typeFilter"
          :options="REPO_TYPE_FILTERS"
          option-label="label"
          option-value="value"
          placeholder="All Types"
          class="w-44"
          @change="onTypeChange"
        />
        <span class="ml-auto text-sm text-gray-500">{{ total }} repositories</span>
      </div>

      <!-- File browser table -->
      <div class="rounded-xl border border-gray-200 dark:border-gray-700 overflow-hidden bg-white dark:bg-gray-800">
        <div class="flex items-center px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-500 border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/80">
          <span class="flex-1">Name</span>
          <span class="w-44 text-center">Type</span>
          <span class="w-28" />
        </div>

        <div v-if="loading && items.length === 0" class="text-center py-12 text-gray-400">
          <i class="pi pi-spin pi-spinner text-2xl" />
        </div>

        <div v-else-if="items.length === 0" class="text-center py-12 text-gray-400">
          No repositories found
        </div>

        <div
          v-for="repo in items"
          :key="repo.name"
          class="flex items-center px-4 py-3 border-b border-gray-100 dark:border-gray-800/50 cursor-pointer transition-colors hover:bg-gray-50 dark:hover:bg-gray-800/50"
          @click="router.push(`/repositories/${repo.name}`)"
        >
          <div class="flex items-center gap-3 flex-1 min-w-0">
            <i class="pi pi-box text-sm text-blue-400" />
            <span class="font-medium text-sm text-gray-900 dark:text-gray-100 truncate">{{ repo.name }}</span>
          </div>
          <div class="w-44 flex items-center justify-center">
            <RepoTypeBadge :type="repo.type" />
          </div>
          <div class="w-28 flex items-center justify-end gap-3">
            <button
              v-if="techForRepoType(repo.type)"
              type="button"
              class="px-1.5 py-0.5 rounded text-xs text-gray-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/20 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
              :aria-label="`Set Me Up for ${repo.name}`"
              title="Set Me Up"
              :data-testid="`set-me-up-${repo.name}`"
              @click.stop="openSetup(repo)"
            >
              <i class="pi pi-bolt" />
            </button>
            <i class="pi pi-chevron-right text-gray-400 text-xs" />
          </div>
        </div>
      </div>

      <Paginator
        v-if="total > size"
        :rows="size"
        :total-records="total"
        :first="page * size"
        :rows-per-page-options="[10, 20, 50]"
        @page="onPageChange"
      />
    </div>
    <SetMeUpDrawer
      v-if="setupRepo"
      v-model:visible="setupOpen"
      :tech="setupRepo.tech"
      :repo="setupRepo.name"
    />
  </AppLayout>
</template>
