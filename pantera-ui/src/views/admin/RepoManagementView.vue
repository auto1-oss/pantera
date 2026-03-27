<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listRepos, deleteRepo, moveRepo } from '@/api/repos'
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

async function load() {
  loading.value = true
  try {
    const resp = await listRepos({ page: page.value, size: size.value, q: search.value || undefined })
    repos.value = resp.items.map(r => typeof r === 'string' ? r : r.name)
    total.value = resp.total
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

      <div class="flex gap-3">
        <span class="relative">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <InputText v-model="search" placeholder="Search..." class="pl-10" @keyup.enter="load" />
        </span>
      </div>

      <DataTable :value="repos" :loading="loading" stripedRows class="shadow-sm">
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
    </div>
  </AppLayout>
</template>
