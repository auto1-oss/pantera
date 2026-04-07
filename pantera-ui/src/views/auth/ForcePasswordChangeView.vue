<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Card from 'primevue/card'
import Button from 'primevue/button'
import Message from 'primevue/message'
import PasswordComplexityForm from '@/components/auth/PasswordComplexityForm.vue'
import { changePassword } from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const router = useRouter()
const auth = useAuthStore()
const notify = useNotificationStore()

const oldPassword = ref('')
const newPassword = ref('')
const formValid = ref(false)
const submitting = ref(false)
const serverError = ref<string | null>(null)

onMounted(async () => {
  if (!auth.user) await auth.fetchUser()
  if (!auth.mustChangePassword) {
    router.replace({ name: 'dashboard' })
  }
})

async function submit() {
  if (!formValid.value || submitting.value) return
  serverError.value = null
  submitting.value = true
  try {
    await changePassword(auth.username, oldPassword.value, newPassword.value)
    await auth.fetchUser()
    notify.success('Password changed successfully')
    router.replace({ name: 'dashboard' })
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    serverError.value = err.response?.data?.message
      ?? (e instanceof Error ? e.message : 'Failed to change password')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-gray-950 p-4">
    <Card class="w-full max-w-md shadow-xl">
      <template #title>
        <div class="flex items-center gap-2">
          <i class="pi pi-lock text-orange-500" />
          <span>Set a new password</span>
        </div>
      </template>
      <template #subtitle>
        <p class="text-xs text-gray-500 mt-1">
          Your account is flagged for a mandatory password change before
          you can use Pantera. Pick a strong password below.
        </p>
      </template>
      <template #content>
        <form class="space-y-4" @submit.prevent="submit">
          <PasswordComplexityForm
            v-model:oldPassword="oldPassword"
            v-model:password="newPassword"
            :username="auth.username"
            :disabled="submitting"
            @valid="(v) => formValid = v"
          />

          <Message v-if="serverError" severity="error" :closable="false">
            {{ serverError }}
          </Message>

          <Button
            type="submit"
            label="Change password"
            icon="pi pi-check"
            class="w-full"
            :loading="submitting"
            :disabled="!formValid"
          />
        </form>
      </template>
    </Card>
  </div>
</template>
