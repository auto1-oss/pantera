<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listRepos } from '@/api/repos'
import { REPO_TYPE_FILTERS } from '@/utils/repoTypes'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import BulkAccessPolicyDialog from '@/components/admin/BulkAccessPolicyDialog.vue'
import { useNotificationStore } from '@/stores/notifications'
import type { BulkAccessPolicyResult } from '@/api/repos'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Paginator from 'primevue/paginator'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import type { RepoListItem } from '@/types'

const router = useRouter()
const notify = useNotificationStore()
const typeFilter = ref<string | null>(null)

const items = ref<RepoListItem[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const searchQuery = ref('')
let debounceTimer: ReturnType<typeof setTimeout> | null = null
let fetchAbortCtrl: AbortController | null = null

// Multi-select state for the bulk access-policy action. Selection is stored
// as a Set<string> of repo names so toggles are O(1) and survive page
// changes only if the operator re-selects on the new page (intentional —
// applying a bulk action to a hidden selection would be surprising).
const selectedNames = ref<Set<string>>(new Set())

function isSelected(name: string): boolean {
  return selectedNames.value.has(name)
}

function toggleSelection(name: string) {
  const next = new Set(selectedNames.value)
  if (next.has(name)) next.delete(name)
  else next.add(name)
  selectedNames.value = next
}

const selectionCount = computed(() => selectedNames.value.size)

// Bulk dialog wiring. selectorType derives from the current type filter when
// no explicit selection is set, so "filter to maven-proxy then click Set
// access policy" applies to that scope rather than the whole registry.
const bulkDialogVisible = ref(false)
const bulkSelectorType = computed<'hosted' | 'proxy' | 'group' | 'all'>(() => {
  const t = typeFilter.value ?? ''
  if (t.endsWith('-proxy')) return 'proxy'
  if (t.endsWith('-group')) return 'group'
  return 'all'
})
const bulkSelectedNames = computed<string[] | undefined>(() =>
  selectionCount.value > 0 ? [...selectedNames.value] : undefined,
)

function openBulkDialog() {
  bulkDialogVisible.value = true
}

function onBulkApplied(result: BulkAccessPolicyResult) {
  const updated = result.updated.length
  const skipped = result.skipped.length
  const summary = `${updated} updated, ${skipped} skipped`
  // Three-way severity: warn when nothing landed (likely operator picked a
  // no-op patch), info when some rows were filtered out, success otherwise.
  if (updated === 0) notify.warn('Bulk access policy', summary)
  else if (skipped > 0) notify.info('Bulk access policy', summary)
  else notify.success('Bulk access policy', summary)
  selectedNames.value = new Set()
  fetchRepos()
}

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
          optionLabel="label"
          optionValue="value"
          placeholder="All Types"
          class="w-44"
          @change="onTypeChange"
        />
        <Button
          icon="pi pi-shield"
          label="Set access policy"
          severity="secondary"
          outlined
          size="small"
          data-testid="bulk-access-policy-btn"
          @click="openBulkDialog"
        />
        <span v-if="selectionCount > 0" class="text-xs text-gray-500">
          {{ selectionCount }} selected
        </span>
        <span class="ml-auto text-sm text-gray-500">{{ total }} repositories</span>
      </div>

      <!-- File browser table -->
      <div class="rounded-xl border border-gray-200 dark:border-gray-700 overflow-hidden bg-white dark:bg-gray-800">
        <div class="flex items-center px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-500 border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/80">
          <span class="w-10" />
          <span class="flex-1">Name</span>
          <span class="w-44 text-center">Type</span>
          <span class="w-20" />
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
          <div class="w-10 flex items-center justify-center" @click.stop>
            <Checkbox
              :model-value="isSelected(repo.name)"
              :binary="true"
              :aria-label="`Select ${repo.name}`"
              @update:model-value="toggleSelection(repo.name)"
            />
          </div>
          <div class="flex items-center gap-3 flex-1 min-w-0">
            <i class="pi pi-box text-sm text-blue-400" />
            <span class="font-medium text-sm text-gray-900 dark:text-gray-100 truncate">{{ repo.name }}</span>
          </div>
          <div class="w-44 flex items-center justify-center">
            <RepoTypeBadge :type="repo.type" />
          </div>
          <div class="w-20 text-right">
            <i class="pi pi-chevron-right text-gray-400 text-xs" />
          </div>
        </div>
      </div>

      <BulkAccessPolicyDialog
        v-model:visible="bulkDialogVisible"
        :selector-type="bulkSelectorType"
        :selected-names="bulkSelectedNames"
        :scope-count="total"
        @applied="onBulkApplied"
      />

      <Paginator
        v-if="total > size"
        :rows="size"
        :totalRecords="total"
        :first="page * size"
        @page="onPageChange"
        :rowsPerPageOptions="[10, 20, 50]"
      />
    </div>
  </AppLayout>
</template>
