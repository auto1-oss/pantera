<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore, type ThemeMode } from '@/stores/theme'
import { useNotificationStore } from '@/stores/notifications'
import { listTokens, revokeToken, type ApiToken } from '@/api/auth'
import { changePassword } from '@/api/users'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import PasswordComplexityForm from '@/components/auth/PasswordComplexityForm.vue'

const auth = useAuthStore()
const theme = useThemeStore()
const notify = useNotificationStore()

const themeOptions: { value: ThemeMode; label: string; icon: string }[] = [
  { value: 'system', label: 'System', icon: 'pi pi-desktop' },
  { value: 'dark', label: 'Dark', icon: 'pi pi-moon' },
  { value: 'light', label: 'Light', icon: 'pi pi-sun' },
]

const permissionEntries = computed(() =>
  Object.entries(auth.user?.permissions ?? {}).filter(([, v]) => v),
)

// Existing tokens
const tokens = ref<ApiToken[]>([])
const tokensLoading = ref(false)

onMounted(() => {
  loadTokens()
})

async function loadTokens() {
  tokensLoading.value = true
  try {
    tokens.value = await listTokens()
  } catch {
    // ignore — DB may not be available
  } finally {
    tokensLoading.value = false
  }
}

async function handleRevoke(tokenId: string) {
  try {
    await revokeToken(tokenId)
    tokens.value = tokens.value.filter((t) => t.id !== tokenId)
    notify.success('Token revoked', 'The token has been revoked and can no longer be used.')
  } catch {
    notify.error('Revoke failed', 'Could not revoke the token.')
  }
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function expiryLabel(token: ApiToken): string {
  if (token.permanent) return 'Permanent'
  if (!token.expires_at) return '—'
  if (token.expired) return `Expired ${formatDate(token.expires_at)}`
  return `Expires ${formatDate(token.expires_at)}`
}

function expirySeverity(token: ApiToken): string {
  if (token.permanent) return 'info'
  if (token.expired) return 'danger'
  return 'success'
}

/* ---------- Self-service password change (local users only) ---------- */

const isLocalUser = computed(() => (auth.user?.context ?? '') === 'local')

const pwOld = ref('')
const pwNew = ref('')
const pwValid = ref(false)
const pwSubmitting = ref(false)
const pwError = ref<string | null>(null)

async function submitPasswordChange() {
  if (!pwValid.value || pwSubmitting.value) return
  pwError.value = null
  pwSubmitting.value = true
  try {
    await changePassword(auth.username, pwOld.value, pwNew.value)
    notify.success('Password changed', 'Your new password is active immediately.')
    pwOld.value = ''
    pwNew.value = ''
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    pwError.value = err.response?.data?.message
      ?? (e instanceof Error ? e.message : 'Failed to change password')
  } finally {
    pwSubmitting.value = false
  }
}
</script>

<template>
  <AppLayout>
    <div class="max-w-2xl space-y-6">
      <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Profile</h1>

      <Card class="shadow-sm">
        <template #title>
          <div class="flex items-center gap-3">
            <i class="pi pi-user text-2xl text-blue-500" />
            {{ auth.username }}
            <Tag v-if="auth.isAdmin" value="Admin" severity="info" />
          </div>
        </template>
        <template #content>
          <div class="space-y-2 text-sm">
            <div><strong>Email:</strong> {{ auth.user?.email || '—' }}</div>
            <div><strong>Context:</strong> {{ auth.user?.context || '—' }}</div>
          </div>
        </template>
      </Card>

      <Card class="shadow-sm">
        <template #title>Effective Permissions</template>
        <template #content>
          <div v-if="permissionEntries.length === 0" class="text-gray-400 text-sm">No permissions assigned</div>
          <div v-else class="space-y-1">
            <div v-for="[key, value] in permissionEntries" :key="key" class="flex items-center gap-2 text-sm">
              <span class="font-mono text-gray-700 dark:text-gray-300">{{ key }}:</span>
              <Tag :value="String(value)" />
            </div>
          </div>
        </template>
      </Card>

      <Card v-if="isLocalUser" class="shadow-sm">
        <template #title>Change Password</template>
        <template #subtitle>
          <p class="text-xs text-gray-500 mt-1">
            Only available for local (username/password) accounts. SSO users
            manage their password via the identity provider.
          </p>
        </template>
        <template #content>
          <form class="space-y-4" @submit.prevent="submitPasswordChange">
            <PasswordComplexityForm
              v-model:oldPassword="pwOld"
              v-model:password="pwNew"
              :username="auth.username"
              :disabled="pwSubmitting"
              @valid="(v) => pwValid = v"
            />
            <Message v-if="pwError" severity="error" :closable="false">
              {{ pwError }}
            </Message>
            <Button
              type="submit"
              label="Change password"
              icon="pi pi-check"
              :loading="pwSubmitting"
              :disabled="!pwValid"
            />
          </form>
        </template>
      </Card>

      <Card class="shadow-sm">
        <template #title>Active Tokens</template>
        <template #content>
          <div v-if="tokensLoading" class="text-gray-400 text-center py-4">
            <i class="pi pi-spin pi-spinner" />
          </div>
          <div v-else-if="tokens.length === 0" class="text-gray-400 text-sm">
            No active API tokens
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="t in tokens"
              :key="t.id"
              class="flex items-center justify-between p-3 bg-gray-50 dark:bg-gray-700 rounded"
            >
              <div class="flex-1">
                <div class="text-sm font-medium text-gray-800 dark:text-gray-200">
                  {{ t.label }}
                </div>
                <div class="text-xs text-gray-400 mt-0.5">
                  Created {{ formatDate(t.created_at) }}
                </div>
              </div>
              <Tag :value="expiryLabel(t)" :severity="expirySeverity(t)" class="mr-3" />
              <Button
                icon="pi pi-trash"
                severity="danger"
                text
                size="small"
                @click="handleRevoke(t.id)"
                v-tooltip.top="'Revoke token'"
              />
            </div>
          </div>
        </template>
      </Card>

      <Card class="shadow-sm">
        <template #title>Preferences</template>
        <template #content>
          <div class="flex items-center justify-between">
            <span class="text-sm">Theme</span>
            <div class="flex gap-1 rounded-lg border border-gray-200 dark:border-gray-700 p-0.5">
              <Button
                v-for="opt in themeOptions"
                :key="opt.value"
                :label="opt.label"
                :icon="opt.icon"
                :severity="theme.mode === opt.value ? 'secondary' : 'secondary'"
                size="small"
                :text="theme.mode !== opt.value"
                :class="theme.mode === opt.value ? 'bg-gray-100 dark:bg-gray-700 !text-gray-900 dark:!text-white' : '!text-gray-500 dark:!text-gray-400'"
                @click="theme.setMode(opt.value)"
              />
            </div>
          </div>
        </template>
      </Card>
    </div>
  </AppLayout>
</template>
