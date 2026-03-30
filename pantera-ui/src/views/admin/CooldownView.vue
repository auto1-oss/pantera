<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { getCooldownOverview, getCooldownBlocked } from '@/api/settings'
import { unblockArtifact, unblockAll } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import Paginator from 'primevue/paginator'
import type { CooldownRepo, BlockedArtifact } from '@/types'

const notify = useNotificationStore()
const auth = useAuthStore()
const canWrite = auth.hasAction('api_cooldown_permissions', 'write')

const repos = ref<CooldownRepo[]>([])
const blocked = ref<BlockedArtifact[]>([])
const blockedPage = ref(0)
const blockedSize = ref(50)
const blockedTotal = ref(0)
const loading = ref(false)
const search = ref('')
const sortField = ref<string | null>(null)
const sortOrder = ref<number>(-1) // -1=desc (default: newest first)
let searchTimeout: ReturnType<typeof setTimeout> | null = null
let blockedAbortCtrl: AbortController | null = null

// Debounced server-side search: reset to page 0 and reload
watch(search, () => {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    blockedPage.value = 0
    loadBlocked()
  }, 400)
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
    if (sortField.value) {
      params.sort_by = sortField.value
      params.sort_dir = sortOrder.value >= 0 ? 'asc' : 'desc'
    }
    const resp = await getCooldownBlocked(params as any, ctrl.signal)
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
      <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Cooldown</h1>

      <Card class="shadow-sm">
        <template #title>Cooldown-Enabled Repositories</template>
        <template #content>
          <div v-if="repos.length === 0" class="text-gray-400 text-sm">
            No cooldown-enabled repositories
          </div>
          <div v-else class="divide-y divide-gray-100 dark:divide-gray-700">
            <div v-for="r in repos" :key="r.name" class="flex items-center justify-between py-2">
              <div class="flex items-center gap-2">
                <span class="font-medium">{{ r.name }}</span>
                <RepoTypeBadge :type="r.type" />
                <Tag
                  v-if="r.active_blocks != null && r.active_blocks > 0"
                  :value="`${r.active_blocks} blocked`"
                  severity="danger"
                />
              </div>
              <div class="flex items-center gap-3">
                <span class="text-sm text-gray-400 font-mono">{{ r.cooldown }}</span>
                <Button
                  v-if="canWrite"
                  label="Unblock All"
                  size="small"
                  severity="warn"
                  text
                  @click="handleUnblockAll(r.name)"
                />
              </div>
            </div>
          </div>
        </template>
      </Card>

      <Card class="shadow-sm">
        <template #title>
          <div class="flex items-center justify-between">
            <span>Blocked Artifacts</span>
            <span class="text-sm font-normal text-gray-400">{{ blockedTotal }} total</span>
          </div>
        </template>
        <template #content>
          <div class="relative mb-3">
            <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 pointer-events-none" />
            <InputText
              v-model="search"
              placeholder="Search by package, version or repo..."
              class="w-full !pl-10"
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
            <Column field="remaining_hours" header="Remaining" sortable>
              <template #body="{ data }">
                <span>{{ formatRemaining(data.blocked_until) }}</span>
              </template>
            </Column>
            <Column v-if="canWrite" header="" class="w-16">
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
            <template #empty>
              <div class="text-center text-gray-400 py-4">No blocked artifacts</div>
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
