<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
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
import Message from 'primevue/message'
import PasswordComplexityForm from '@/components/auth/PasswordComplexityForm.vue'
import { apiErrorMessage } from '@/utils/apiError'
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
// The server enforces the password policy (PasswordPolicy.java) and the
// role ceiling; the form mirrors the policy for live feedback and the
// server's rejection message is shown inline so the admin can fix it.
const newPasswordValid = ref(false)
const createError = ref<string | null>(null)
// Required prop of PasswordComplexityForm; ignored with hide-old-password.
const unusedOldPassword = ref('')
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
    } catch (err) { notify.error('Failed to delete user', apiErrorMessage(err, name)) }
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
  } catch (err) { notify.error('Failed to toggle user', apiErrorMessage(err, user.name)) }
}

function resetCreateForm() {
  newUsername.value = ''
  newPassword.value = ''
  newEmail.value = ''
  newRoles.value = []
  createError.value = null
}

watch(createVisible, visible => { if (!visible) resetCreateForm() })

async function handleCreate() {
  creating.value = true
  createError.value = null
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
    load()
  } catch (err) {
    createError.value = apiErrorMessage(err, 'Failed to create user')
    notify.error('Failed to create user', createError.value)
  } finally { creating.value = false }
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

      <InputText v-model="search" placeholder="Search users..." class="w-64" @keyup.enter="load" />

      <DataTable :value="users" :loading="loading" striped-rows class="shadow-sm">
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
              <Button
                v-if="auth.hasAction('api_user_permissions', 'enable')" :icon="data.enabled !== false ? 'pi pi-ban' : 'pi pi-check-circle'" text size="small"
                :severity="data.enabled !== false ? 'warn' : 'success'" @click="toggleUser(data)"
              />
              <Button v-if="auth.hasAction('api_user_permissions', 'delete')" icon="pi pi-trash" text size="small" severity="danger" @click="handleDelete(data.name)" />
            </div>
          </template>
        </Column>
      </DataTable>

      <Paginator
        v-if="total > size" :rows="size" :total-records="total" :first="page * size"
        :rows-per-page-options="[10, 20, 50]" @page="(e: any) => { page = e.page; size = e.rows; load() }"
      />

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
          <InputText v-model="newUsername" placeholder="Username" class="w-full" autocomplete="off" />
          <PasswordComplexityForm
            v-model:old-password="unusedOldPassword"
            v-model:password="newPassword"
            :username="newUsername"
            :disabled="creating"
            hide-old-password
            @valid="(v: boolean) => newPasswordValid = v"
          />
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
          <Message v-if="createError" severity="error" :closable="false" data-testid="create-user-error">
            {{ createError }}
          </Message>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="createVisible = false" />
          <Button label="Create" :loading="creating" :disabled="!newUsername || !newPasswordValid" @click="handleCreate" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
