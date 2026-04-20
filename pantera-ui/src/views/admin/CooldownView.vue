<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { getCooldownOverview, getCooldownBlocked, getCooldownHistory } from '@/api/settings'
import { unblockArtifact, unblockAll } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { REPO_TYPE_FILTERS } from '@/utils/repoTypes'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Card from 'primevue/card'
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

      <Card class="shadow-sm">
        <template #title>Cooldown-Enabled Repositories</template>
        <template #content>
          <DataTable
            :value="repos"
            stripedRows
            :paginator="repos.length > 25"
            :rows="25"
            :rows-per-page-options="[25, 50, 100]"
          >
            <Column field="name" header="Name" sortable>
              <template #body="{ data }">
                <span class="font-medium">{{ data.name }}</span>
              </template>
            </Column>
            <Column field="type" header="Type" sortable>
              <template #body="{ data }">
                <RepoTypeBadge :type="data.type" />
              </template>
            </Column>
            <Column field="cooldown" header="Cooldown" sortable>
              <template #body="{ data }">
                <span class="text-sm text-gray-400 font-mono">{{ data.cooldown }}</span>
              </template>
            </Column>
            <Column field="active_blocks" header="Active blocks" sortable>
              <template #body="{ data }">
                <Tag
                  v-if="data.active_blocks != null && data.active_blocks > 0"
                  :value="String(data.active_blocks)"
                  severity="danger"
                />
                <span v-else class="text-gray-400">0</span>
              </template>
            </Column>
            <Column
              v-if="canWrite"
              header=""
              class="w-32"
            >
              <template #body="{ data }">
                <Button
                  v-if="data.active_blocks != null && data.active_blocks > 0"
                  label="Unblock all"
                  size="small"
                  severity="warn"
                  text
                  @click="handleUnblockAll(data.name)"
                />
              </template>
            </Column>
            <template #empty>
              <div class="text-center text-gray-400 py-4">
                No cooldown-enabled repositories
              </div>
            </template>
          </DataTable>
        </template>
      </Card>

      <Card class="shadow-sm">
        <template #title>
          <div class="flex items-center justify-between">
            <span>{{ mode === 'active' ? 'Blocked Artifacts' : 'Archived Artifacts' }}</span>
            <span class="text-sm font-normal text-gray-400">{{ blockedTotal }} total</span>
          </div>
        </template>
        <template #content>
          <div class="flex flex-wrap items-end gap-3 mb-3">
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
