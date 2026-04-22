<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { useNotificationStore } from '@/stores/notifications'
import { generateTokenForSession } from '@/api/auth'
import HealthIndicator from '@/components/common/HealthIndicator.vue'
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'

defineProps<{ sidebarCollapsed: boolean }>()
const emit = defineEmits<{ toggleSidebar: [] }>()

const router = useRouter()
const auth = useAuthStore()
const config = useConfigStore()
const notify = useNotificationStore()

// User menu
const userMenu = ref()
const menuItems = [
  { label: 'Generate API Token', icon: 'pi pi-key', command: () => { tokenDialogVisible.value = true } },
  { separator: true },
  { label: 'Sign out', icon: 'pi pi-sign-out', command: () => { auth.logout(); router.push('/login') } },
]
function toggleMenu(event: Event) {
  userMenu.value.toggle(event)
}

// Token generation dialog
const tokenDialogVisible = ref(false)
const tokenLabel = ref('API Token')
const tokenExpiryDays = ref(30)
const generating = ref(false)
const generatedToken = ref<string | null>(null)

const expiryOptions = ref([
  { label: '30 days', value: 30 },
  { label: '90 days', value: 90 },
])

// Auth settings come from /auth/me (populated by the auth store on login),
// not from the admin-only /admin/auth-settings endpoint — that one 403s
// for read-only users, which silently left the dropdown pinned to the
// hardcoded 30/90-day fallback. Reading from the already-fetched user
// object gives every authenticated user the same options the server
// actually accepts from them, with no extra network round-trip.
// Watched (not onMounted) so a late /auth/me resolution — e.g. after a
// silent JWT refresh — still updates the dropdown.
watch(
  () => auth.user?.auth_settings,
  (settings) => {
    if (!settings) {
      return
    }
    const maxTtlDays = Math.floor(parseInt(settings.api_token_max_ttl_seconds ?? '31536000') / 86400)
    const allowPermanent = settings.api_token_allow_permanent === 'true'
    const opts = []
    if (maxTtlDays >= 30) opts.push({ label: '30 days', value: 30 })
    if (maxTtlDays >= 90) opts.push({ label: '90 days', value: 90 })
    if (maxTtlDays >= 180) opts.push({ label: '180 days', value: 180 })
    if (maxTtlDays >= 365) opts.push({ label: '365 days', value: 365 })
    if (allowPermanent) opts.push({ label: 'Permanent', value: 0 })
    if (opts.length > 0) {
      expiryOptions.value = opts
      tokenExpiryDays.value = opts[0].value
    }
  },
  { immediate: true },
)

async function handleGenerateToken() {
  generating.value = true
  try {
    const resp = await generateTokenForSession(tokenExpiryDays.value, tokenLabel.value)
    generatedToken.value = resp.token
    notify.success('API token generated')
  } catch {
    notify.error('Failed to generate token')
  } finally {
    generating.value = false
  }
}

function copyToken() {
  if (generatedToken.value) {
    navigator.clipboard.writeText(generatedToken.value)
    notify.success('Token copied to clipboard')
  }
}

function closeTokenDialog() {
  tokenDialogVisible.value = false
  generatedToken.value = null
  tokenLabel.value = 'API Token'
  tokenExpiryDays.value = 30
}
</script>

<template>
  <header class="fixed top-0 left-0 right-0 h-14 bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 flex items-center px-4 z-50">
    <Button
      icon="pi pi-bars"
      text
      rounded
      @click="emit('toggleSidebar')"
      class="mr-3"
      aria-label="Toggle sidebar"
    />
    <div class="flex items-center gap-2.5">
      <img src="/pantera-128.png" alt="Pantera" class="w-8 h-8 rounded-lg" />
      <span class="text-base font-semibold text-gray-900 dark:text-white">
        {{ config.appTitle }}
      </span>
    </div>
    <HealthIndicator class="ml-3" />

    <div class="ml-auto flex items-center gap-2">
      <div v-if="auth.isAuthenticated" class="flex items-center">
        <Button
          :label="auth.username"
          icon="pi pi-chevron-down"
          iconPos="right"
          text
          size="small"
          class="!text-gray-600 dark:!text-gray-300"
          @click="toggleMenu"
          aria-haspopup="true"
        />
        <Menu ref="userMenu" :model="menuItems" :popup="true" />
      </div>
    </div>

    <!-- Generate Token Dialog -->
    <Dialog
      v-model:visible="tokenDialogVisible"
      header="Generate API Token"
      modal
      class="w-[480px]"
      @hide="closeTokenDialog"
    >
      <!-- Before generation -->
      <div v-if="!generatedToken" class="space-y-4">
        <div>
          <label class="block text-sm font-medium mb-1.5 text-gray-700 dark:text-gray-300">Label</label>
          <InputText v-model="tokenLabel" placeholder="e.g. CI/CD Pipeline" class="w-full" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1.5 text-gray-700 dark:text-gray-300">Expires in</label>
          <Select
            v-model="tokenExpiryDays"
            :options="expiryOptions"
            optionLabel="label"
            optionValue="value"
            class="w-full"
          />
        </div>
      </div>

      <!-- After generation -->
      <div v-else class="space-y-4">
        <div class="p-3 rounded-lg bg-amber-500/10 border border-amber-500/20">
          <p class="text-xs text-amber-400 font-medium mb-2">
            <i class="pi pi-exclamation-triangle mr-1" />
            Copy this token now. You won't be able to see it again.
          </p>
        </div>
        <div class="flex items-center gap-2">
          <InputText :modelValue="generatedToken" readonly class="w-full font-mono text-sm" />
          <Button icon="pi pi-copy" severity="secondary" @click="copyToken" v-tooltip="'Copy'" />
        </div>
      </div>

      <template #footer>
        <Button v-if="!generatedToken" label="Cancel" severity="secondary" text @click="closeTokenDialog" />
        <Button v-if="!generatedToken" label="Generate" icon="pi pi-key" :loading="generating" @click="handleGenerateToken" />
        <Button v-else label="Done" @click="closeTokenDialog" />
      </template>
    </Dialog>
  </header>
</template>
