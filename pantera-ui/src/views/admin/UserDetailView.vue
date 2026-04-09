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
import Message from 'primevue/message'
import MultiSelect from 'primevue/multiselect'
import Dialog from 'primevue/dialog'
import PasswordComplexityForm from '@/components/auth/PasswordComplexityForm.vue'
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

// Password reset (admin path: no old password required — the caller
// is changing someone ELSE's password and the route-level change_password
// permission is authorization enough).
const pwdVisible = ref(false)
const newPassword = ref('')
const pwdValid = ref(false)
const pwdSubmitting = ref(false)
const pwdError = ref<string | null>(null)
// Unused sentinel for PasswordComplexityForm's required oldPassword prop
// — the form ignores this value when hide-old-password is set.
const unusedOldPassword = ref('')

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
  if (!pwdValid.value || pwdSubmitting.value) return
  pwdError.value = null
  pwdSubmitting.value = true
  try {
    // Admin reset: pass null for oldPass so the API omits it from
    // the request body. The backend sees uname !== caller and skips
    // the current-password check.
    await changePassword(props.name, null, newPassword.value)
    notify.success('Password reset', `New password set for ${props.name}.`)
    pwdVisible.value = false
    newPassword.value = ''
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    pwdError.value = err.response?.data?.message
      ?? (e instanceof Error ? e.message : 'Failed to reset password')
  } finally {
    pwdSubmitting.value = false
  }
}

function closePwdDialog() {
  pwdVisible.value = false
  newPassword.value = ''
  pwdError.value = null
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
            <div><strong>Auth Provider:</strong> {{ user.auth_provider || 'local' }}</div>
          </div>
          <div class="flex gap-2 mt-6">
            <Button label="Reset Password" icon="pi pi-key" severity="secondary" @click="pwdVisible = true" />
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

      <Dialog
        v-model:visible="pwdVisible"
        :header="`Reset password for ${user?.name ?? ''}`"
        modal
        class="w-[28rem]"
        :closable="!pwdSubmitting"
        @hide="closePwdDialog"
      >
        <div class="space-y-3">
          <p class="text-xs text-gray-500">
            You are resetting another user's password. The current password
            is not required — your admin permission is authorization enough.
            The user should change this password on their next sign-in.
          </p>
          <PasswordComplexityForm
            v-model:oldPassword="unusedOldPassword"
            v-model:password="newPassword"
            :username="user?.name ?? ''"
            :disabled="pwdSubmitting"
            hide-old-password
            @valid="(v) => pwdValid = v"
          />
          <Message v-if="pwdError" severity="error" :closable="false">
            {{ pwdError }}
          </Message>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" text :disabled="pwdSubmitting" @click="closePwdDialog" />
          <Button
            label="Reset password"
            icon="pi pi-key"
            :loading="pwdSubmitting"
            :disabled="!pwdValid"
            @click="handlePasswordChange"
          />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
