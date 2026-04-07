<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { changePassword } from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const router = useRouter()
const auth = useAuthStore()
const notify = useNotificationStore()

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const serverError = ref<string | null>(null)

/**
 * Mirror of pantera-main/.../PasswordPolicy.java. The backend is the
 * source of truth; this exists only for live UI feedback. Every
 * submission is still validated server-side before the password is
 * persisted, so a user who tampers with this file cannot bypass it.
 */
const MIN_LENGTH = 12
const SPECIAL = /[!@#$%^&*()\-_=+[\]{};:,.<>?/|~`'"\\]/

interface Rule {
  label: string
  ok: boolean
}

const rules = computed<Rule[]>(() => {
  const p = newPassword.value
  return [
    { label: `At least ${MIN_LENGTH} characters`, ok: p.length >= MIN_LENGTH },
    { label: 'At least one uppercase letter', ok: /[A-Z]/.test(p) },
    { label: 'At least one lowercase letter', ok: /[a-z]/.test(p) },
    { label: 'At least one digit', ok: /\d/.test(p) },
    { label: 'At least one special character', ok: SPECIAL.test(p) },
    {
      label: 'Not equal to the username',
      ok: p.length > 0 && p.toLowerCase() !== auth.username.toLowerCase(),
    },
    { label: 'Confirmation matches', ok: p.length > 0 && p === confirmPassword.value },
  ]
})

const allValid = computed(() => rules.value.every(r => r.ok))

const canSubmit = computed(
  () => oldPassword.value.length > 0 && allValid.value && !submitting.value,
)

onMounted(async () => {
  // Make sure we have a fresh user state — the router guard may have
  // sent us here directly after login so `mustChangePassword` should
  // be set, but we refresh just in case.
  if (!auth.user) await auth.fetchUser()
  // If somehow the user is NOT required to change password, bounce home
  if (!auth.mustChangePassword) {
    router.replace({ name: 'dashboard' })
  }
})

async function submit() {
  if (!canSubmit.value) return
  serverError.value = null
  submitting.value = true
  try {
    await changePassword(auth.username, oldPassword.value, newPassword.value)
    // Re-fetch /me so must_change_password is cleared in the store
    await auth.fetchUser()
    notify.success('Password changed successfully')
    router.replace({ name: 'dashboard' })
  } catch (e: unknown) {
    // Surface backend WEAK_PASSWORD / auth errors in-form
    const err = e as { response?: { data?: { message?: string; error?: string } } }
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
          <div>
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
              Current password
            </label>
            <InputText
              v-model="oldPassword"
              type="password"
              class="w-full"
              autocomplete="current-password"
              :disabled="submitting"
            />
          </div>

          <div>
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
              New password
            </label>
            <InputText
              v-model="newPassword"
              type="password"
              class="w-full"
              autocomplete="new-password"
              :disabled="submitting"
            />
          </div>

          <div>
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
              Confirm new password
            </label>
            <InputText
              v-model="confirmPassword"
              type="password"
              class="w-full"
              autocomplete="new-password"
              :disabled="submitting"
            />
          </div>

          <!-- Live complexity checklist -->
          <ul class="text-xs space-y-1">
            <li
              v-for="r in rules"
              :key="r.label"
              :class="r.ok ? 'text-green-500' : 'text-gray-500'"
            >
              <i :class="r.ok ? 'pi pi-check-circle' : 'pi pi-circle'" class="mr-1.5" />
              {{ r.label }}
            </li>
          </ul>

          <Message v-if="serverError" severity="error" :closable="false">
            {{ serverError }}
          </Message>

          <Button
            type="submit"
            label="Change password"
            icon="pi pi-check"
            class="w-full"
            :loading="submitting"
            :disabled="!canSubmit"
          />
        </form>
      </template>
    </Card>
  </div>
</template>
