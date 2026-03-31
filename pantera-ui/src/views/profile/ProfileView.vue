<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore, type ThemeMode } from '@/stores/theme'
import { useNotificationStore } from '@/stores/notifications'
import { generateTokenForSession, listTokens, revokeToken, getAuthSettings, type ApiToken } from '@/api/auth'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Select from 'primevue/select'

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

// Token generation
const generatedToken = ref('')
const tokenLoading = ref(false)
const selectedExpiry = ref(30)
const expiryOptions = ref([
  { label: '30 days', value: 30 },
  { label: '90 days', value: 90 },
])

// Existing tokens
const tokens = ref<ApiToken[]>([])
const tokensLoading = ref(false)

onMounted(async () => {
  loadTokens()
  try {
    const settings = await getAuthSettings()
    const maxTtlDays = Math.floor(parseInt(settings.api_token_max_ttl_seconds ?? '7776000') / 86400)
    const allowPermanent = settings.api_token_allow_permanent === 'true'
    const opts = []
    if (maxTtlDays >= 30) opts.push({ label: '30 days', value: 30 })
    if (maxTtlDays >= 90) opts.push({ label: '90 days', value: 90 })
    if (maxTtlDays >= 365) opts.push({ label: '1 year', value: 365 })
    if (allowPermanent) opts.push({ label: 'Permanent (no expiry)', value: 0 })
    if (opts.length > 0) expiryOptions.value = opts
  } catch {
    // Keep defaults if settings unavailable
  }
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

async function handleGenerateToken() {
  tokenLoading.value = true
  generatedToken.value = ''
  try {
    const resp = await generateTokenForSession(selectedExpiry.value)
    generatedToken.value = resp.token
    notify.success('Token generated', 'Copy it now — it will not be shown again.')
    await loadTokens()
  } catch {
    notify.error('Token generation failed', 'Could not generate token. Try logging in again.')
  } finally {
    tokenLoading.value = false
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

function copyToken() {
  navigator.clipboard.writeText(generatedToken.value)
  notify.success('Copied', 'Token copied to clipboard.')
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

      <Card class="shadow-sm">
        <template #title>Generate API Token</template>
        <template #content>
          <p class="text-sm text-gray-500 dark:text-gray-400 mb-3">
            Generate an API token for CLI and CI/CD use. The token inherits your account permissions.
          </p>
          <div class="flex items-end gap-3">
            <div>
              <label class="text-xs text-gray-500 dark:text-gray-400 mb-1 block">Expiry</label>
              <Select
                v-model="selectedExpiry"
                :options="expiryOptions"
                optionLabel="label"
                optionValue="value"
                class="w-48"
              />
            </div>
            <Button
              label="Generate"
              icon="pi pi-key"
              :loading="tokenLoading"
              @click="handleGenerateToken"
            />
          </div>
          <div v-if="generatedToken" class="mt-3">
            <div class="flex items-center gap-2">
              <code class="flex-1 p-2 bg-gray-100 dark:bg-gray-700 rounded text-sm font-mono break-all">
                {{ generatedToken }}
              </code>
              <Button icon="pi pi-copy" severity="secondary" text @click="copyToken" />
            </div>
            <p class="text-xs text-orange-500 mt-1">Copy this token now — it will not be shown again.</p>
          </div>
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
