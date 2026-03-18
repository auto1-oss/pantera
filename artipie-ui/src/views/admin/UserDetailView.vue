<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getUser, putUser, changePassword, enableUser, disableUser } from '@/api/users'
import { listRoles } from '@/api/roles'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import MultiSelect from 'primevue/multiselect'
import Dialog from 'primevue/dialog'
import type { User } from '@/types'

const props = defineProps<{ name: string }>()
const router = useRouter()
const notify = useNotificationStore()

const user = ref<User | null>(null)
const loading = ref(true)

// Role assignment
const availableRoles = ref<string[]>([])
const selectedRoles = ref<string[]>([])
const savingRoles = ref(false)

// Password change
const pwdVisible = ref(false)
const oldPassword = ref('')
const newPassword = ref('')

onMounted(async () => {
  try {
    const [u, rolesResp] = await Promise.all([
      getUser(props.name),
      listRoles({ size: 200 }),
    ])
    user.value = u
    availableRoles.value = rolesResp.items.map(r => r.name)
    selectedRoles.value = u.roles ?? []
  } catch {
    notify.error('User not found')
    router.push('/admin/users')
  } finally {
    loading.value = false
  }
})

async function saveRoles() {
  if (!user.value) return
  savingRoles.value = true
  try {
    await putUser(props.name, { roles: selectedRoles.value })
    user.value = await getUser(props.name)
    selectedRoles.value = user.value.roles ?? []
    notify.success('Roles updated')
  } catch {
    notify.error('Failed to update roles')
  } finally {
    savingRoles.value = false
  }
}

async function handlePasswordChange() {
  try {
    await changePassword(props.name, oldPassword.value, newPassword.value)
    notify.success('Password changed')
    pwdVisible.value = false
    oldPassword.value = ''
    newPassword.value = ''
  } catch { notify.error('Failed to change password') }
}

async function toggleEnabled() {
  if (!user.value) return
  try {
    if (user.value.enabled !== false) {
      await disableUser(props.name)
      notify.info('User disabled')
    } else {
      await enableUser(props.name)
      notify.info('User enabled')
    }
    user.value = await getUser(props.name)
  } catch { notify.error('Failed to toggle user') }
}
</script>

<template>
  <AppLayout>
    <div class="max-w-2xl space-y-5">
      <router-link to="/admin/users" class="text-blue-500 hover:underline text-sm">
        <i class="pi pi-arrow-left mr-1" /> Users
      </router-link>

      <Card v-if="user" class="shadow-sm">
        <template #title>
          <div class="flex items-center gap-3">
            {{ user.name }}
            <Tag :value="user.enabled !== false ? 'Active' : 'Disabled'"
              :severity="user.enabled !== false ? 'success' : 'danger'" />
          </div>
        </template>
        <template #content>
          <div class="space-y-3 text-sm">
            <div><strong>Email:</strong> {{ user.email || '—' }}</div>
            <div><strong>Auth Provider:</strong> {{ user.auth_provider || 'artipie' }}</div>
          </div>
          <div class="flex gap-2 mt-6">
            <Button label="Change Password" icon="pi pi-key" severity="secondary" @click="pwdVisible = true" />
            <Button :label="user.enabled !== false ? 'Disable' : 'Enable'"
              :icon="user.enabled !== false ? 'pi pi-ban' : 'pi pi-check-circle'"
              :severity="user.enabled !== false ? 'warn' : 'success'" @click="toggleEnabled" />
          </div>
        </template>
      </Card>

      <Card v-if="user" class="shadow-sm">
        <template #title>Roles</template>
        <template #content>
          <div class="space-y-3">
            <MultiSelect
              v-model="selectedRoles"
              :options="availableRoles"
              placeholder="Select roles"
              class="w-full"
              display="chip"
            />
            <Button
              label="Save Roles"
              icon="pi pi-save"
              :loading="savingRoles"
              @click="saveRoles"
            />
          </div>
        </template>
      </Card>

      <Dialog v-model:visible="pwdVisible" header="Change Password" modal class="w-96">
        <div class="space-y-3">
          <InputText v-model="oldPassword" type="password" placeholder="Current password" class="w-full" />
          <InputText v-model="newPassword" type="password" placeholder="New password" class="w-full" />
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="pwdVisible = false" />
          <Button label="Change" :disabled="!oldPassword || !newPassword" @click="handlePasswordChange" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
