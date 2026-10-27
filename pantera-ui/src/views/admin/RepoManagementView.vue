<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listRepos } from '@/api/repos'
import type { BulkAccessPolicyResult, RepoSortField } from '@/api/repos'
import type { RepoListItem, RepoMode } from '@/types'
import { REPO_TYPE_FILTERS, REPO_MODE_FILTERS, repoModeLabel } from '@/utils/repoTypes'
import { relativeTime } from '@/utils/relativeTime'
import { useDebouncedSearch } from '@/composables/useDebouncedSearch'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import SelectionBar from '@/components/common/SelectionBar.vue'
import BulkAccessPolicyDialog from '@/components/admin/BulkAccessPolicyDialog.vue'
import BulkDeleteReposDialog from '@/components/admin/BulkDeleteReposDialog.vue'
import type { BulkDeleteResult } from '@/components/admin/BulkDeleteReposDialog.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Paginator from 'primevue/paginator'
import Tag from 'primevue/tag'

const router = useRouter()
const route = useRoute()
const notify = useNotificationStore()
const auth = useAuthStore()

const can = (action: string) => auth.hasAction('api_repository_permissions', action)

// ---------------------------------------------------------------------------
// List state — filters, sort and page are mirrored into the URL query so a
// reload or the browser's back button keeps them.
// ---------------------------------------------------------------------------
function queryString(key: string): string {
  const v = route.query[key]
  return typeof v === 'string' ? v : ''
}
const SORT_FIELDS: RepoSortField[] = ['name', 'type', 'updated_at']
function initialSort(): RepoSortField {
  const v = queryString('sort') as RepoSortField
  return SORT_FIELDS.includes(v) ? v : 'name'
}

const rows = ref<RepoListItem[]>([])
const page = ref(Number(queryString('page')) || 0)
const size = ref(20)
const total = ref(0)
const error = ref('')
const typeFilter = ref<string | null>(queryString('type') || null)
const modeFilter = ref<RepoMode | null>((queryString('mode') as RepoMode) || null)
const sortField = ref<RepoSortField>(initialSort())
const sortOrder = ref<1 | -1>(queryString('order') === 'desc' ? -1 : 1)
const highlight = ref<string | null>(queryString('highlight') || null)
// PrimeVue DataTable v-model:selection. Rows are objects keyed by `name`
// (data-key) — with string rows and a bogus key every row compared equal and
// selecting one highlighted all of them.
const selected = ref<RepoListItem[]>([])

const search = useDebouncedSearch(async (signal) => {
  error.value = ''
  try {
    const resp = await listRepos({
      page: page.value,
      size: size.value,
      q: search.query.value || undefined,
      type: typeFilter.value ?? undefined,
      mode: modeFilter.value ?? undefined,
      sort: sortField.value,
      order: sortOrder.value === 1 ? 'asc' : 'desc',
    }, signal)
    if (signal.aborted) return
    rows.value = resp.items
    total.value = resp.total
    // Never keep a selection the operator can no longer see.
    selected.value = []
    syncQuery()
  } catch (e: unknown) {
    if (signal.aborted) return
    rows.value = []
    error.value = (e as Error).message || 'Failed to load repositories'
  }
})
search.query.value = queryString('q')
const loading = search.loading

function syncQuery() {
  const q: Record<string, string> = {}
  if (search.query.value) q.q = search.query.value
  if (typeFilter.value) q.type = typeFilter.value
  if (modeFilter.value) q.mode = modeFilter.value
  if (sortField.value !== 'name') q.sort = sortField.value
  if (sortOrder.value === -1) q.order = 'desc'
  if (page.value > 0) q.page = String(page.value)
  router.replace({ query: q }).catch(() => { /* navigation duplicates are fine */ })
}

function reload() {
  page.value = 0
  selected.value = []
  void search.run()
}
watch([typeFilter, modeFilter], reload)
watch(search.query, () => { page.value = 0 })

const hasFilters = computed(() => !!search.query.value || !!typeFilter.value || !!modeFilter.value)
function clearFilters() {
  // Setting the filters triggers the watcher's reload; the query reset is
  // debounced, so run once explicitly for an immediate refresh.
  search.query.value = ''
  typeFilter.value = null
  modeFilter.value = null
  reload()
}

function onSort(e: { sortField: string | ((item: unknown) => string) | undefined; sortOrder: number | null | undefined }) {
  const field = typeof e.sortField === 'string' ? e.sortField as RepoSortField : 'name'
  sortField.value = SORT_FIELDS.includes(field) ? field : 'name'
  sortOrder.value = e.sortOrder === -1 ? -1 : 1
  reload()
}

function onPage(e: { page: number; rows: number }) {
  page.value = e.page
  size.value = e.rows
  void search.run()
}

// A repository's name opens its edit page; rename and delete live there,
// delete also as a bulk action.
function editPath(name: string): string {
  return `/admin/repositories/${encodeURIComponent(name)}/edit`
}

// Anywhere on a row opens it, except the selection checkbox and the name link.
function onRowClick(e: { originalEvent: Event; data: RepoListItem }) {
  const target = e.originalEvent.target as HTMLElement | null
  if (target?.closest('a, button, input, .p-checkbox, .p-datatable-column-selection')) return
  void router.push(editPath(e.data.name))
}

// ---------------------------------------------------------------------------
// Bulk actions
// ---------------------------------------------------------------------------
const bulkPolicyVisible = ref(false)
const bulkDeleteVisible = ref(false)
const selectedNames = computed(() => selected.value.map(r => r.name))

function onBulkApplied(result: BulkAccessPolicyResult) {
  const updated = result.updated.length
  const skipped = result.skipped.length
  const summary = `${updated} updated, ${skipped} skipped`
  if (updated === 0) notify.warn('Anonymous access', summary)
  else if (skipped > 0) notify.info('Anonymous access', summary)
  else notify.success('Anonymous access', summary)
  selected.value = []
  void search.run()
}

function onBulkDeleted(result: BulkDeleteResult) {
  const parts = [`${result.deleted.length} deleted`]
  if (result.deleting.length) parts.push(`${result.deleting.length} deleting in background`)
  if (result.failed.length) parts.push(`${result.failed.length} failed`)
  const summary = parts.join(', ')
  if (result.failed.length) notify.warn('Delete repositories', summary)
  else if (result.deleting.length) notify.info('Delete repositories', summary)
  else notify.success('Delete repositories', summary)
  selected.value = []
  void search.run()
}

function rowClass(r: RepoListItem): string {
  return r.name === highlight.value ? 'cursor-pointer bg-yellow-50 dark:bg-yellow-900/20' : 'cursor-pointer'
}

onMounted(() => { void search.run() })
onBeforeUnmount(search.dispose)

defineExpose({ selected, typeFilter, modeFilter, sortField, sortOrder, rows, error, onBulkDeleted })
</script>

<template>
  <AppLayout>
    <div class="space-y-4">
      <div class="flex items-center justify-between">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Manage Repositories</h1>
        <Button
          v-if="can('create')"
          label="Create Repository"
          icon="pi pi-plus"
          data-testid="create-repo-btn"
          @click="router.push('/admin/repositories/create')"
        />
      </div>

      <!-- Toolbar -->
      <div class="flex flex-wrap items-center gap-3">
        <span class="relative">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <InputText
            v-model="search.query.value"
            placeholder="Search repositories…"
            class="!pl-10 w-64"
            data-testid="repo-search"
            @keyup.enter="reload"
          />
        </span>
        <Select
          v-model="typeFilter"
          :options="REPO_TYPE_FILTERS"
          option-label="label"
          option-value="value"
          placeholder="All formats"
          class="w-44"
          data-testid="format-filter"
        />
        <Select
          v-model="modeFilter"
          :options="REPO_MODE_FILTERS"
          option-label="label"
          option-value="value"
          placeholder="All modes"
          class="w-40"
          data-testid="mode-filter"
        />
        <span class="ml-auto text-sm text-gray-500">{{ total }} repositories</span>
      </div>

      <!-- Selection bar: only when something is selected -->
      <SelectionBar :count="selected.length" @clear="selected = []">
        <Button
          v-if="can('update')"
          label="Anonymous access…"
          icon="pi pi-shield"
          size="small"
          severity="secondary"
          outlined
          data-testid="bulk-anonymous-btn"
          @click="bulkPolicyVisible = true"
        />
        <Button
          v-if="can('delete')"
          label="Delete…"
          icon="pi pi-trash"
          size="small"
          severity="danger"
          outlined
          data-testid="bulk-delete-btn"
          @click="bulkDeleteVisible = true"
        />
      </SelectionBar>

      <!-- Error state -->
      <div
        v-if="error"
        data-testid="list-error"
        class="flex items-center justify-between rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
      >
        <span>Failed to load repositories: {{ error }}</span>
        <Button label="Retry" size="small" severity="danger" outlined data-testid="list-retry" @click="search.run()" />
      </div>

      <DataTable
        v-else
        v-model:selection="selected"
        :value="rows"
        :loading="loading"
        data-key="name"
        lazy
        striped-rows
        :sort-field="sortField"
        :sort-order="sortOrder"
        :row-class="rowClass"
        class="shadow-sm"
        row-hover
        @sort="onSort"
        @row-click="onRowClick"
      >
        <Column selection-mode="multiple" header-style="width: 3rem" />
        <Column field="name" header="Name" sortable>
          <template #body="{ data }">
            <RouterLink :to="editPath(data.name)" class="font-medium text-blue-600 hover:underline dark:text-blue-400">
              {{ data.name }}
            </RouterLink>
          </template>
        </Column>
        <Column field="type" header="Format" sortable>
          <template #body="{ data }">
            <RepoTypeBadge :type="data.type" />
          </template>
        </Column>
        <Column header="Mode">
          <template #body="{ data }">
            <span class="text-sm">{{ repoModeLabel(data.mode, data.type) }}</span>
          </template>
        </Column>
        <Column header="Storage">
          <template #body="{ data }">
            <span v-if="data.storage" class="font-mono text-xs">{{ data.storage }}</span>
            <span v-else class="text-gray-400">&mdash;</span>
          </template>
        </Column>
        <Column header="Anonymous">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Tag v-if="data.anonymous_read" value="read" severity="warn" />
              <Tag v-if="data.anonymous_write" value="write" severity="danger" />
            </div>
          </template>
        </Column>
        <Column field="updated_at" header="Updated" sortable>
          <template #body="{ data }">
            <div v-if="data.updated_at" class="text-sm leading-tight">
              <div :title="data.updated_at">{{ relativeTime(data.updated_at) }}</div>
              <div v-if="data.updated_by" class="text-xs text-gray-500">by {{ data.updated_by }}</div>
            </div>
            <span v-else class="text-gray-400">&mdash;</span>
          </template>
        </Column>
        <template #empty>
          <div class="py-10 text-center text-gray-500">
            <template v-if="hasFilters">
              <p>No repositories match the current filters.</p>
              <Button label="Clear filters" text size="small" data-testid="clear-filters" @click="clearFilters" />
            </template>
            <template v-else>
              <p>No repositories yet.</p>
              <Button
                v-if="can('create')"
                label="Create repository"
                text
                size="small"
                @click="router.push('/admin/repositories/create')"
              />
            </template>
          </div>
        </template>
      </DataTable>

      <Paginator
        v-if="total > size"
        :rows="size"
        :total-records="total"
        :first="page * size"
        :rows-per-page-options="[10, 20, 50]"
        @page="onPage"
      />

      <BulkAccessPolicyDialog
        v-model:visible="bulkPolicyVisible"
        selector-type="all"
        :selected-names="selectedNames"
        :scope-count="selected.length"
        @applied="onBulkApplied"
      />
      <BulkDeleteReposDialog
        v-model:visible="bulkDeleteVisible"
        :names="selectedNames"
        @deleted="onBulkDeleted"
      />
    </div>
  </AppLayout>
</template>
