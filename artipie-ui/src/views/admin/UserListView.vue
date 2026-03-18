<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listUsers, deleteUser, enableUser, disableUser, putUser } from '@/api/users'
import { listRoles } from '@/api/roles'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import Dialog from 'primevue/dialog'
import Paginator from 'primevue/paginator'
import MultiSelect from 'primevue/multiselect'
import type { User, Role } from '@/types'

const notify = useNotificationStore()
const auth = useAuthStore()
const { visible: delVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()

const users = ref<User[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const search = ref('')

// Create dialog
const createVisible = ref(false)
const newUsername = ref('')
const newPassword = ref('')
const newEmail = ref('')
const newRoles = ref<string[]>([])
const creating = ref(false)
const availableRoles = ref<Role[]>([])

async function load() {
  loading.value = true
  try {
    const resp = await listUsers({ page: page.value, size: size.value, q: search.value || undefined })
    users.value = resp.items
    total.value = resp.total
  } finally {
    loading.value = false
  }
}

async function handleDelete(name: string) {
  if (await confirmDel(name)) {
    try {
      await deleteUser(name)
      notify.success('User deleted', name)
      load()
    } catch { notify.error('Failed to delete user') }
  }
}

async function toggleUser(user: User) {
  try {
    if (user.enabled) {
      await disableUser(user.name)
      notify.info('User disabled', user.name)
    } else {
      await enableUser(user.name)
      notify.info('User enabled', user.name)
    }
    load()
  } catch { notify.error('Failed to toggle user') }
}

async function handleCreate() {
  creating.value = true
  try {
    const body: Record<string, unknown> = {
      password: newPassword.value,
      email: newEmail.value || undefined,
    }
    if (newRoles.value.length > 0) {
      body.roles = newRoles.value
    }
    await putUser(newUsername.value, body)
    notify.success('User created', newUsername.value)
    createVisible.value = false
    newUsername.value = ''
    newPassword.value = ''
    newEmail.value = ''
    newRoles.value = []
    load()
  } catch { notify.error('Failed to create user') }
  finally { creating.value = false }
}

async function loadRoles() {
  try {
    const resp = await listRoles({ size: 100 })
    availableRoles.value = resp.items
  } catch { /* ignore */ }
}

onMounted(() => { load(); loadRoles() })
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="flex items-center justify-between">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">User Management</h1>
        <Button v-if="auth.hasAction('api_user_permissions', 'create')" label="Create User" icon="pi pi-plus" @click="createVisible = true" />
      </div>

      <InputText v-model="search" placeholder="Search users..." @keyup.enter="load" class="w-64" />

      <DataTable :value="users" :loading="loading" stripedRows class="shadow-sm">
        <Column field="name" header="Username" sortable />
        <Column field="email" header="Email" />
        <Column field="enabled" header="Status">
          <template #body="{ data }">
            <Tag :value="data.enabled !== false ? 'Active' : 'Disabled'" :severity="data.enabled !== false ? 'success' : 'danger'" />
          </template>
        </Column>
        <Column header="Actions" class="w-48">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Button v-if="auth.hasAction('api_user_permissions', 'update')" icon="pi pi-pencil" text size="small" @click="$router.push(`/admin/users/${data.name}`)" />
              <Button v-if="auth.hasAction('api_user_permissions', 'enable')" :icon="data.enabled !== false ? 'pi pi-ban' : 'pi pi-check-circle'" text size="small"
                :severity="data.enabled !== false ? 'warn' : 'success'" @click="toggleUser(data)" />
              <Button v-if="auth.hasAction('api_user_permissions', 'delete')" icon="pi pi-trash" text size="small" severity="danger" @click="handleDelete(data.name)" />
            </div>
          </template>
        </Column>
      </DataTable>

      <Paginator v-if="total > size" :rows="size" :totalRecords="total" :first="page * size"
        @page="(e: any) => { page = e.page; size = e.rows; load() }" :rowsPerPageOptions="[10, 20, 50]" />

      <!-- Delete Dialog -->
      <Dialog v-model:visible="delVisible" header="Confirm Delete" modal class="w-96">
        <p>Delete user <strong>{{ targetName }}</strong>?</p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectDel" />
          <Button label="Delete" severity="danger" @click="acceptDel" />
        </template>
      </Dialog>

      <!-- Create Dialog -->
      <Dialog v-model:visible="createVisible" header="Create User" modal class="w-[450px]">
        <div class="space-y-3">
          <InputText v-model="newUsername" placeholder="Username" class="w-full" />
          <InputText v-model="newPassword" type="password" placeholder="Password" class="w-full" />
          <InputText v-model="newEmail" placeholder="Email (optional)" class="w-full" />
          <div>
            <label class="block text-sm font-medium mb-1">Roles (optional)</label>
            <MultiSelect
              v-model="newRoles"
              :options="availableRoles.map(r => r.name).filter(Boolean)"
              placeholder="Select roles"
              class="w-full"
              display="chip"
            />
          </div>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="createVisible = false" />
          <Button label="Create" :loading="creating" :disabled="!newUsername || !newPassword" @click="handleCreate" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
