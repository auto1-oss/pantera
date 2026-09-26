<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import Select from 'primevue/select'
import Password from 'primevue/password'
import Button from 'primevue/button'
import { generateTokenForSession } from '@/api/auth'
import { copyText } from './clipboard'

/**
 * Credentials for the rendered snippets. A generated or pasted token lives
 * only in this component's memory: it is never written to browser storage
 * and disappears when the panel closes.
 */
const props = defineProps<{
  /** Set Me Up technology key, used in the token label */
  format: string
  /** Resolve repository, used in the token label */
  repo: string
  username: string
}>()

const emit = defineEmits<{ token: [value: string] }>()

type Mode = 'generate' | 'own' | 'none'

const MODES: { value: Mode; label: string; icon: string }[] = [
  { value: 'generate', label: 'Generate token', icon: 'pi pi-key' },
  { value: 'own', label: 'Use my own token', icon: 'pi pi-pencil' },
  { value: 'none', label: 'Placeholders', icon: 'pi pi-eye-slash' },
]

const EXPIRY_OPTIONS = [
  { label: '1 day', value: 1 },
  { label: '7 days', value: 7 },
  { label: '30 days', value: 30 },
  { label: '90 days', value: 90 },
]

const mode = ref<Mode>('generate')
const expiryDays = ref(30)
const generated = ref('')
const generatedExpiry = ref('')
const ownToken = ref('')
const generating = ref(false)
const error = ref('')
const revealed = ref(false)
const copied = ref(false)

const hasAt = computed(() => props.username.includes('@'))
const label = computed(() => `set-me-up:${props.format}:${props.repo || 'any'}`)
const effective = computed(() => {
  if (mode.value === 'generate') return generated.value
  if (mode.value === 'own') return ownToken.value.trim()
  return ''
})
const masked = computed(() => '•'.repeat(Math.min(generated.value.length, 32)))

watch(effective, value => emit('token', value), { immediate: true })

async function generate() {
  generating.value = true
  error.value = ''
  try {
    const resp = await generateTokenForSession(expiryDays.value, label.value)
    generated.value = resp.token
    generatedExpiry.value = resp.permanent || !resp.expires_at
      ? 'never expires'
      : `expires ${new Date(resp.expires_at).toLocaleDateString()}`
    revealed.value = false
  } catch (e: unknown) {
    const data = (e as { response?: { data?: { message?: string; error?: string } } }).response?.data
    error.value = data?.message ?? data?.error ?? 'Token generation failed.'
  } finally {
    generating.value = false
  }
}

async function copyToken() {
  if (!(await copyText(generated.value))) return
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}
</script>

<template>
  <div class="p-4 rounded-xl bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700" data-testid="token-card">
    <div class="flex flex-wrap items-center justify-between gap-3 mb-3">
      <div>
        <h3 class="text-xs font-semibold uppercase tracking-wide text-gray-400">Credentials</h3>
        <p class="text-sm text-gray-700 dark:text-gray-300 mt-0.5">
          Username <code class="font-mono bg-gray-100 dark:bg-gray-900 px-1.5 py-0.5 rounded" data-testid="token-username">{{ username || 'YOUR_USERNAME' }}</code>
        </p>
      </div>
      <div class="flex flex-wrap max-w-full rounded-lg border border-gray-200 dark:border-gray-700 p-0.5 bg-gray-50 dark:bg-gray-900" role="radiogroup" aria-label="Credential source">
        <button
          v-for="m in MODES"
          :key="m.value"
          type="button"
          role="radio"
          :aria-checked="mode === m.value"
          class="px-2.5 py-1 rounded-md text-xs font-medium flex items-center gap-1.5 whitespace-nowrap transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          :class="mode === m.value
            ? 'bg-white dark:bg-gray-700 text-gray-900 dark:text-white shadow-sm'
            : 'text-gray-500 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-200'"
          :data-testid="`token-mode-${m.value}`"
          @click="mode = m.value"
        >
          <i :class="m.icon" class="text-[11px]" />{{ m.label }}
        </button>
      </div>
    </div>

    <!-- Generate -->
    <div v-if="mode === 'generate'">
      <div v-if="!generated" class="flex flex-wrap items-end gap-3">
        <div>
          <label for="smu-expiry" class="block text-xs text-gray-500 dark:text-gray-400 mb-1">Expires after</label>
          <Select
            v-model="expiryDays"
            input-id="smu-expiry"
            :options="EXPIRY_OPTIONS"
            option-label="label"
            option-value="value"
            class="w-36"
          />
        </div>
        <Button
          label="Generate token & fill in snippets"
          icon="pi pi-key"
          size="small"
          :loading="generating"
          data-testid="generate-token"
          @click="generate"
        />
        <p class="basis-full text-xs text-gray-400">
          Creates an API token labelled <code class="font-mono">{{ label }}</code>. You can revoke it on your Profile page.
        </p>
      </div>
      <div v-else data-testid="generated-token">
        <div class="flex flex-wrap items-center gap-2">
          <code
            class="flex-1 min-w-0 font-mono text-xs bg-gray-100 dark:bg-gray-900 px-2 py-1.5 rounded break-all text-gray-800 dark:text-gray-200"
            data-testid="token-value"
          >{{ revealed ? generated : masked }}</code>
          <button
            type="button"
            class="px-2 py-1 rounded text-xs border border-gray-200 dark:border-gray-600 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            :aria-label="revealed ? 'Hide token' : 'Reveal token'"
            :aria-pressed="revealed"
            data-testid="reveal-token"
            @click="revealed = !revealed"
          >
            <i :class="revealed ? 'pi pi-eye-slash' : 'pi pi-eye'" />
          </button>
          <button
            type="button"
            class="px-2 py-1 rounded text-xs border focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            :class="copied ? 'bg-green-500 border-green-500 text-white' : 'border-gray-200 dark:border-gray-600 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700'"
            aria-label="Copy token"
            @click="copyToken"
          >
            <i :class="copied ? 'pi pi-check' : 'pi pi-copy'" />
          </button>
        </div>
        <p class="mt-2 text-xs text-amber-600 dark:text-amber-400" role="status" data-testid="token-once-notice">
          <i class="pi pi-exclamation-triangle mr-1" />
          Copy it now: this token ({{ generatedExpiry }}) is shown only while this panel is open and is filled into the snippets below.
        </p>
      </div>
      <p v-if="error" class="mt-2 text-xs text-red-500" role="alert" data-testid="token-error">{{ error }}</p>
    </div>

    <!-- Own token -->
    <div v-else-if="mode === 'own'">
      <label for="smu-own-token" class="block text-xs text-gray-500 dark:text-gray-400 mb-1">API token</label>
      <Password
        v-model="ownToken"
        input-id="smu-own-token"
        :feedback="false"
        toggle-mask
        class="w-full max-w-md"
        input-class="w-full"
        placeholder="Paste an existing API token"
        autocomplete="off"
      />
      <p class="mt-1 text-xs text-gray-400">Used only to fill in the snippets; it is not sent anywhere or saved.</p>
    </div>

    <p v-else class="text-xs text-gray-400">
      Snippets show <code class="font-mono">YOUR_TOKEN</code>; replace it with an API token from your Profile page.
    </p>

    <p v-if="hasAt" class="mt-3 text-xs text-blue-600 dark:text-blue-300" data-testid="username-at-note">
      <i class="pi pi-info-circle mr-1" />
      Your username contains <code class="font-mono">@</code>. Snippets that put credentials in a URL use the
      encoded form <code class="font-mono">{{ encodeURIComponent(username) }}</code>.
    </p>
  </div>
</template>
