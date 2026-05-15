<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listRepos, deleteRepo, moveRepo } from '@/api/repos'
import type { BulkAccessPolicyResult } from '@/api/repos'
import BulkAccessPolicyDialog from '@/components/admin/BulkAccessPolicyDialog.vue'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Dialog from 'primevue/dialog'
import Paginator from 'primevue/paginator'

const router = useRouter()
const notify = useNotificationStore()
const auth = useAuthStore()
const { visible: deleteVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()

const repos = ref<string[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const search = ref('')

// Move dialog
const moveVisible = ref(false)
const moveSource = ref('')
const moveTarget = ref('')

// ---------------------------------------------------------------------------
// Bulk anonymous-access policy
// ---------------------------------------------------------------------------
// PrimeVue DataTable v-model:selection holds the selected rows. With
// `selectAll` two-way-bound and selectionMode="multiple" enabled, the
// header checkbox toggles every row currently rendered on the page —
// the standard "select all" UX.
//
// Permission gate (api_repository_permissions.update) matches the
// REST endpoint POST /api/v1/repositories/access-policy/bulk, which
// is auth'd by ApiRepositoryPermission(RepositoryAction.UPDATE).
const canUpdate = computed(() => auth.hasAction('api_repository_permissions', 'update'))
const selected = ref<string[]>([])
const selectAll = ref(false)
const bulkDialogVisible = ref(false)

function onSelectAllChange(event: { originalEvent: Event; checked: boolean }) {
  selectAll.value = event.checked
  selected.value = event.checked ? [...repos.value] : []
}

function onRowSelection() {
  selectAll.value = selected.value.length === repos.value.length
              && repos.value.length > 0
}

function openBulkDialog() {
  bulkDialogVisible.value = true
}

function onBulkApplied(result: BulkAccessPolicyResult) {
  const updated = result.updated.length
  const skipped = result.skipped.length
  const summary = `${updated} updated, ${skipped} skipped`
  // Three-way severity. Warn when nothing landed (likely a no-op patch);
  // info when some rows were filtered out; success otherwise.
  if (updated === 0) notify.warn('Bulk access policy', summary)
  else if (skipped > 0) notify.info('Bulk access policy', summary)
  else notify.success('Bulk access policy', summary)
  selected.value = []
  selectAll.value = false
  load()
}

async function load() {
  loading.value = true
  try {
    const resp = await listRepos({ page: page.value, size: size.value, q: search.value || undefined })
    repos.value = resp.items.map(r => typeof r === 'string' ? r : r.name)
    total.value = resp.total
    // Reset selection on page/search change to avoid acting on hidden
    // selections that the operator can't see.
    selected.value = []
    selectAll.value = false
  } finally {
    loading.value = false
  }
}

async function handleDelete(name: string) {
  const confirmed = await confirmDel(name)
  if (!confirmed) return
  try {
    await deleteRepo(name)
    notify.success('Repository deleted', name)
    load()
  } catch {
    notify.error('Failed to delete repository')
  }
}

function openMove(name: string) {
  moveSource.value = name
  moveTarget.value = ''
  moveVisible.value = true
}

async function handleMove() {
  try {
    await moveRepo(moveSource.value, moveTarget.value)
    notify.success('Repository renamed', `${moveSource.value} → ${moveTarget.value}`)
    moveVisible.value = false
    load()
  } catch {
    notify.error('Failed to rename repository')
  }
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="flex items-center justify-between">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Manage Repositories</h1>
        <Button v-if="auth.hasAction('api_repository_permissions', 'create')" label="Create Repository" icon="pi pi-plus" @click="router.push('/admin/repositories/create')" />
      </div>

      <div class="flex flex-wrap items-center gap-3">
        <span class="relative">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <InputText v-model="search" placeholder="Search..." class="pl-10" @keyup.enter="load" />
        </span>
        <Button
          v-if="canUpdate"
          icon="pi pi-shield"
          label="Set access policy"
          severity="secondary"
          outlined
          size="small"
          :disabled="selected.length === 0"
          data-testid="bulk-access-policy-btn"
          @click="openBulkDialog"
        />
        <span v-if="selected.length > 0" class="text-xs text-gray-500">
          {{ selected.length }} selected
        </span>
      </div>

      <DataTable
        :value="repos"
        :loading="loading"
        v-model:selection="selected"
        :selectAll="selectAll"
        @select-all-change="onSelectAllChange"
        @row-select="onRowSelection"
        @row-unselect="onRowSelection"
        dataKey="."
        stripedRows
        class="shadow-sm"
      >
        <Column v-if="canUpdate" selectionMode="multiple" headerStyle="width: 3rem" />
        <Column header="Name" sortable>
          <template #body="{ data }">
            <span class="font-medium">{{ data }}</span>
          </template>
        </Column>
        <Column header="Actions" class="w-48">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Button v-if="auth.hasAction('api_repository_permissions', 'update')" icon="pi pi-pencil" text size="small" @click="router.push(`/admin/repositories/${data}/edit`)" />
              <Button v-if="auth.hasAction('api_repository_permissions', 'move')" icon="pi pi-arrows-h" text size="small" severity="info" @click="openMove(data)" />
              <Button v-if="auth.hasAction('api_repository_permissions', 'delete')" icon="pi pi-trash" text size="small" severity="danger" @click="handleDelete(data)" />
            </div>
          </template>
        </Column>
      </DataTable>

      <Paginator v-if="total > size" :rows="size" :totalRecords="total" :first="page * size"
        @page="(e: any) => { page = e.page; size = e.rows; load() }" :rowsPerPageOptions="[10, 20, 50]" />

      <!-- Delete Confirmation -->
      <Dialog v-model:visible="deleteVisible" header="Confirm Delete" modal class="w-96">
        <p>Delete repository <strong>{{ targetName }}</strong>? This cannot be undone.</p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectDel" />
          <Button label="Delete" severity="danger" @click="acceptDel" />
        </template>
      </Dialog>

      <!-- Move/Rename Dialog -->
      <Dialog v-model:visible="moveVisible" header="Rename Repository" modal class="w-96">
        <p class="mb-3">Rename <strong>{{ moveSource }}</strong> to:</p>
        <InputText v-model="moveTarget" placeholder="New name" class="w-full" />
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="moveVisible = false" />
          <Button label="Rename" :disabled="!moveTarget" @click="handleMove" />
        </template>
      </Dialog>

      <!-- Bulk access policy -->
      <BulkAccessPolicyDialog
        v-model:visible="bulkDialogVisible"
        selector-type="all"
        :selected-names="selected"
        :scope-count="selected.length"
        @applied="onBulkApplied"
      />
    </div>
  </AppLayout>
</template>
