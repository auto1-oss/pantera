<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { getCooldownOverview, getCooldownBlocked, getCooldownHistory } from '@/api/settings'
import { unblockArtifact, unblockAll } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { REPO_TYPE_FILTERS } from '@/utils/repoTypes'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Dialog from 'primevue/dialog'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import Paginator from 'primevue/paginator'
import type { CooldownRepo, BlockedArtifact, HistoryArtifact } from '@/types'

type Mode = 'active' | 'history'

const notify = useNotificationStore()
const auth = useAuthStore()
const canWrite = auth.hasAction('api_cooldown_permissions', 'write')
// The history feed is gated by its own narrower permission
// (api_cooldown_history_permissions.read). A user can have api_cooldown.read
// to see the live blocked list without necessarily being allowed to browse
// the long-term archive. Per-repo AdapterBasicPermission continues to filter
// rows server-side on top of this API-level gate.
const canReadHistory = computed(() =>
  auth.hasAction('api_cooldown_history_permissions', 'read'),
)

const repos = ref<CooldownRepo[]>([])
const blocked = ref<Array<BlockedArtifact | HistoryArtifact>>([])
const mode = ref<Mode>('active')
const blockedPage = ref(0)
const blockedSize = ref(50)
const blockedTotal = ref(0)
const loading = ref(false)
const search = ref('')
const repoFilter = ref<string | null>(null)
const typeFilter = ref<string | null>(null)
const sortField = ref<string | null>(null)
const sortOrder = ref<number>(-1) // -1=desc (default: newest first)
let searchTimeout: ReturnType<typeof setTimeout> | null = null
let blockedAbortCtrl: AbortController | null = null

// Client-side pagination for the cooldown-enabled repositories tile grid.
// 10 tiles = 2 rows × 5 cols on xl, which matches the compact card layout.
const repoPage = ref(0)
const repoPageSize = 10

// Strip "-proxy" / "-group" / "-hosted" suffix to get the base tech type.
// Mirrors parseType() in utils/repoTypes.ts so `typeFilter="docker"` matches
// both `docker-proxy` and `docker-group`.
function typeBase(fullType: string): string {
  const t = (fullType ?? '').toLowerCase()
  if (t.endsWith('-proxy')) return t.slice(0, -'-proxy'.length)
  if (t.endsWith('-group')) return t.slice(0, -'-group'.length)
  if (t.endsWith('-hosted')) return t.slice(0, -'-hosted'.length)
  return t
}

// Apply the same search / repo / type filter triple that drives the blocked
// artifacts table, so the tile grid stays in sync with the unified filter bar.
const filteredRepos = computed(() => {
  const q = search.value?.trim().toLowerCase() ?? ''
  return repos.value.filter(r => {
    if (q && !r.name.toLowerCase().includes(q)) return false
    if (repoFilter.value && r.name !== repoFilter.value) return false
    if (typeFilter.value && typeBase(r.type) !== typeFilter.value) return false
    return true
  })
})

const pagedRepos = computed(() =>
  filteredRepos.value.slice(
    repoPage.value * repoPageSize,
    (repoPage.value + 1) * repoPageSize,
  ),
)

// PrimeVue Paginator emits the "first row index", not the page index, so we
// wrap repoPage in a computed that translates both directions.
const repoPaginatorFirst = computed({
  get: () => repoPage.value * repoPageSize,
  set: (first: number) => {
    repoPage.value = Math.floor(first / repoPageSize)
  },
})

// Any change to the unified filters collapses the grid back to page 0 so the
// user never lands on an empty page of a shrunken result set.
watch([search, repoFilter, typeFilter], () => {
  repoPage.value = 0
})

// Confirmation dialog for the per-repo "unblock all" eraser icon. Reuses the
// same useConfirmDelete composable / Dialog pattern used elsewhere in admin
// views (RepoManagementView, StorageAliasView) so behaviour stays consistent.
const {
  visible: unblockAllVisible,
  targetName: unblockAllTarget,
  confirm: confirmUnblockAllDialog,
  accept: acceptUnblockAll,
  reject: rejectUnblockAll,
} = useConfirmDelete()

// Repo dropdown options: derived from the overview endpoint (which is
// already permission-scoped server-side), with an "All repos" sentinel.
const repoOptions = computed(() => [
  { label: 'All repos', value: null as string | null },
  ...repos.value.map(r => ({ label: r.name, value: r.name as string | null })),
])

// Repo-type dropdown options: reuse the shared filter list with an
// "All types" sentinel prepended.
const typeOptions = computed(() => [
  { label: 'All types', value: null as string | null },
  ...REPO_TYPE_FILTERS.filter(o => o.value !== null),
])

// Debounced server-side search: reset to page 0 and reload
watch(search, () => {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    blockedPage.value = 0
    loadBlocked()
  }, 400)
})

// Repo + repo-type filters: reset to page 0 and reload immediately.
watch([repoFilter, typeFilter], () => {
  blockedPage.value = 0
  loadBlocked()
})

// Mode toggle: reset pagination and reload from the appropriate endpoint.
watch(mode, () => {
  blockedPage.value = 0
  blocked.value = []
  blockedTotal.value = 0
  sortField.value = null
  sortOrder.value = -1
  loadBlocked()
})

onBeforeUnmount(() => {
  if (searchTimeout) clearTimeout(searchTimeout)
  if (blockedAbortCtrl) blockedAbortCtrl.abort()
})

async function loadOverview() {
  try {
    repos.value = await getCooldownOverview()
  } catch {
    repos.value = []
  }
}

async function loadBlocked() {
  if (blockedAbortCtrl) blockedAbortCtrl.abort()
  blockedAbortCtrl = new AbortController()
  const ctrl = blockedAbortCtrl
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      page: blockedPage.value,
      size: blockedSize.value,
    }
    if (search.value.trim()) {
      params.search = search.value.trim()
    }
    if (repoFilter.value) {
      params.repo = repoFilter.value
    }
    if (typeFilter.value) {
      params.repo_type = typeFilter.value
    }
    if (sortField.value) {
      params.sort_by = sortField.value
      params.sort_dir = sortOrder.value >= 0 ? 'asc' : 'desc'
    }
    const resp = mode.value === 'active'
      ? await getCooldownBlocked(params as any, ctrl.signal)
      : await getCooldownHistory(params as any, ctrl.signal)
    if (ctrl.signal.aborted) return
    blocked.value = resp.items
    blockedTotal.value = resp.total
  } catch (err: unknown) {
    if (ctrl.signal.aborted) return
    blocked.value = []
  } finally {
    if (!ctrl.signal.aborted) loading.value = false
  }
}

function formatRemaining(blockedUntil: string): string {
  const secs = Math.max(0, Math.floor((new Date(blockedUntil).getTime() - Date.now()) / 1000))
  if (secs === 0) return '<1m'
  const days = Math.floor(secs / 86400)
  const hours = Math.floor((secs % 86400) / 3600)
  const mins = Math.floor((secs % 3600) / 60)
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`
  if (hours > 0) return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`
  return `${mins}m`
}

function onSort(event: { sortField: string; sortOrder: number }) {
  sortField.value = event.sortField
  sortOrder.value = event.sortOrder
  blockedPage.value = 0
  loadBlocked()
}

async function handleUnblock(art: BlockedArtifact) {
  try {
    await unblockArtifact(art.repo, { artifact: art.package_name, version: art.version })
    notify.success('Artifact unblocked')
    loadBlocked()
    loadOverview()
  } catch {
    notify.error('Failed to unblock')
  }
}

async function handleUnblockAll(repoName: string) {
  try {
    await unblockAll(repoName)
    notify.success('All artifacts unblocked', repoName)
    loadBlocked()
    loadOverview()
  } catch {
    notify.error('Failed to unblock all')
  }
}

// Prompt the user before issuing the destructive "unblock all" action on a
// repo tile. Resolves via the shared confirm-delete dialog.
async function confirmUnblockAll(repo: CooldownRepo) {
  const confirmed = await confirmUnblockAllDialog(repo.name)
  if (confirmed) {
    await handleUnblockAll(repo.name)
  }
}

onMounted(() => {
  loadOverview()
  loadBlocked()
})
</script>

<template>
  <AppLayout>
    <div class="space-y-6">
      <div class="flex items-center justify-between">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Cooldown</h1>
      </div>

      <!--
        Global filter bar: single source of truth for search, repo, type
        and active/history mode. Drives both the tile grid (client-side
        filter via filteredRepos) and the blocked-artifacts DataTable
        (server-side via loadBlocked params).
      -->
      <div class="flex flex-wrap items-end gap-3">
        <div class="flex flex-col gap-1 flex-1 min-w-[16rem]">
          <label class="text-sm text-gray-500" for="cooldown-filter-search">Search</label>
          <span class="relative">
            <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 pointer-events-none" />
            <InputText
              id="cooldown-filter-search"
              v-model="search"
              placeholder="Search by package, version or repo..."
              class="w-full !pl-10"
            />
          </span>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm text-gray-500" for="cooldown-filter-repo">Repository</label>
          <Select
            id="cooldown-filter-repo"
            v-model="repoFilter"
            :options="repoOptions"
            option-label="label"
            option-value="value"
            placeholder="All repos"
            class="w-48"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm text-gray-500" for="cooldown-filter-type">Type</label>
          <Select
            id="cooldown-filter-type"
            v-model="typeFilter"
            :options="typeOptions"
            option-label="label"
            option-value="value"
            placeholder="All types"
            class="w-40"
          />
        </div>
        <SelectButton
          v-if="canReadHistory"
          v-model="mode"
          :options="[
            { label: 'Active', value: 'active' },
            { label: 'History', value: 'history' },
          ]"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          aria-label="Toggle active vs. history view"
        />
      </div>

      <Card class="shadow-sm">
        <template #title>
          <div class="flex items-center gap-2">
            <span>Cooldown-Enabled Repositories</span>
            <span class="text-sm font-normal text-gray-500">
              ({{ filteredRepos.length }} of {{ repos.length }})
            </span>
          </div>
        </template>
        <template #content>
          <div
            v-if="repos.length === 0"
            class="text-center text-gray-400 py-4"
          >
            No cooldown-enabled repositories
          </div>
          <div
            v-else-if="filteredRepos.length === 0"
            class="text-sm text-gray-500 py-2"
          >
            No repositories match current filters.
          </div>
          <div
            v-else
            class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-3"
          >
            <div
              v-for="repo in pagedRepos"
              :key="repo.name"
              class="border border-surface-200 dark:border-surface-700 rounded-lg p-3 flex flex-col gap-2 bg-surface-card"
              data-testid="cooldown-repo-tile"
            >
              <div class="flex items-center gap-2 min-w-0">
                <span
                  class="font-semibold truncate text-color"
                  :title="repo.name"
                >
                  {{ repo.name }}
                </span>
              </div>
              <div class="flex items-center gap-2 min-w-0">
                <RepoTypeBadge :type="repo.type" />
              </div>
              <div class="flex items-center justify-between mt-1">
                <span class="text-xs text-gray-500">
                  {{ repo.cooldown }} &middot; {{ repo.active_blocks ?? 0 }} active
                </span>
                <Button
                  v-if="(repo.active_blocks ?? 0) > 0 && canWrite"
                  v-tooltip="'Unblock all'"
                  icon="pi pi-eraser"
                  severity="danger"
                  text
                  rounded
                  size="small"
                  :aria-label="`Unblock all ${repo.active_blocks} blocks in ${repo.name}`"
                  @click="confirmUnblockAll(repo)"
                />
              </div>
            </div>
          </div>
          <Paginator
            v-if="filteredRepos.length > repoPageSize"
            v-model:first="repoPaginatorFirst"
            :rows="repoPageSize"
            :total-records="filteredRepos.length"
            class="mt-3"
          />
        </template>
      </Card>

      <Dialog v-model:visible="unblockAllVisible" header="Confirm Unblock All" modal class="w-96">
        <p>
          Unblock all active artifacts in
          <strong>{{ unblockAllTarget }}</strong>? This cannot be undone.
        </p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectUnblockAll" />
          <Button label="Unblock all" severity="danger" @click="acceptUnblockAll" />
        </template>
      </Dialog>

      <Card class="shadow-sm">
        <template #title>
          <div class="flex items-center justify-between">
            <span>{{ mode === 'active' ? 'Blocked Artifacts' : 'Archived Artifacts' }}</span>
            <span class="text-sm font-normal text-gray-400">{{ blockedTotal }} total</span>
          </div>
        </template>
        <template #content>
          <DataTable
            :value="blocked"
            :loading="loading"
            stripedRows
            :lazy="true"
            :sortField="sortField ?? undefined"
            :sortOrder="sortOrder"
            @sort="onSort"
          >
            <Column field="package_name" header="Package" sortable />
            <Column field="version" header="Version" sortable />
            <Column field="repo" header="Repository" sortable />
            <Column field="repo_type" header="Type" sortable>
              <template #body="{ data }">
                <RepoTypeBadge :type="data.repo_type" />
              </template>
            </Column>
            <Column field="reason" header="Reason" sortable>
              <template #body="{ data }">
                <Tag :value="data.reason" severity="info" />
              </template>
            </Column>
            <Column
              v-if="mode === 'active'"
              field="remaining_hours"
              header="Remaining"
              sortable
            >
              <template #body="{ data }">
                <span>{{ formatRemaining(data.blocked_until) }}</span>
              </template>
            </Column>
            <Column
              v-if="mode === 'active' && canWrite"
              header="Actions"
              class="w-16"
            >
              <template #body="{ data }">
                <Button
                  icon="pi pi-unlock"
                  text
                  size="small"
                  severity="danger"
                  v-tooltip="'Unblock'"
                  @click="handleUnblock(data)"
                />
              </template>
            </Column>
            <Column
              v-if="mode === 'history'"
              field="blocked_date"
              header="Originally blocked at"
              sortable
            />
            <Column
              v-if="mode === 'history'"
              field="archived_at"
              header="Archived at"
              sortable
            />
            <Column
              v-if="mode === 'history'"
              field="archive_reason"
              header="Archive reason"
              sortable
            >
              <template #body="{ data }">
                <Tag :value="data.archive_reason" severity="secondary" />
              </template>
            </Column>
            <Column
              v-if="mode === 'history'"
              field="archived_by"
              header="Archived by"
              sortable
            />
            <template #empty>
              <div class="text-center text-gray-400 py-4">
                {{ mode === 'active' ? 'No blocked artifacts' : 'No archived artifacts' }}
              </div>
            </template>
          </DataTable>
          <Paginator
            v-if="blockedTotal > blockedSize"
            :rows="blockedSize"
            :totalRecords="blockedTotal"
            :first="blockedPage * blockedSize"
            @page="
              (e: any) => {
                blockedPage = e.page
                blockedSize = e.rows
                loadBlocked()
              }
            "
          />
        </template>
      </Card>
    </div>
  </AppLayout>
</template>
