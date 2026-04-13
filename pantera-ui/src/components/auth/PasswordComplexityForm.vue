<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import InputText from 'primevue/inputtext'

/**
 * Reusable password-change form with a live complexity checklist
 * that mirrors pantera-main/.../auth/PasswordPolicy.java exactly.
 * The backend is the source of truth — this component exists only
 * for UX feedback. Every submission is validated server-side.
 *
 * Emits `valid` (boolean) so the parent can enable/disable its
 * submit button, and `update:password` / `update:oldPassword` for
 * two-way binding of the field values.
 *
 * Use in:
 *   - ForcePasswordChangeView (admin first-login flow)
 *   - ProfileView (self-service password change)
 */

const props = defineProps<{
  /** Current password value (v-model bind) */
  oldPassword: string
  /** New password value (v-model bind) */
  password: string
  /** The logged-in username, used by the "not equal to username" rule */
  username: string
  /** Hide the old-password field (e.g. first-login admin path can skip it) */
  hideOldPassword?: boolean
  /** Disable all inputs while parent is submitting */
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:oldPassword': [value: string]
  'update:password': [value: string]
  'valid': [value: boolean]
}>()

const confirmPassword = ref('')

// These regexes / constants MUST match PasswordPolicy.java server-side.
const MIN_LENGTH = 12
const SPECIAL = /[!@#$%^&*()\-_=+[\]{};:,.<>?/|~`'"\\]/

interface Rule {
  label: string
  ok: boolean
}

const rules = computed<Rule[]>(() => {
  const p = props.password
  return [
    { label: `At least ${MIN_LENGTH} characters`, ok: p.length >= MIN_LENGTH },
    { label: 'At least one uppercase letter', ok: /[A-Z]/.test(p) },
    { label: 'At least one lowercase letter', ok: /[a-z]/.test(p) },
    { label: 'At least one digit', ok: /\d/.test(p) },
    { label: 'At least one special character', ok: SPECIAL.test(p) },
    {
      label: 'Not equal to the username',
      ok: p.length > 0 && p.toLowerCase() !== props.username.toLowerCase(),
    },
    {
      label: 'Confirmation matches',
      ok: p.length > 0 && p === confirmPassword.value,
    },
  ]
})

const allValid = computed(() => rules.value.every(r => r.ok))
const hasOldPassword = computed(
  () => props.hideOldPassword || props.oldPassword.length > 0,
)
const formValid = computed(() => allValid.value && hasOldPassword.value)

watch(formValid, v => emit('valid', v), { immediate: true })
</script>

<template>
  <div class="space-y-4">
    <div v-if="!hideOldPassword">
      <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
        Current password
      </label>
      <InputText
        :modelValue="oldPassword"
        @update:modelValue="(v: string) => emit('update:oldPassword', v)"
        type="password"
        class="w-full"
        autocomplete="current-password"
        :disabled="disabled"
      />
    </div>

    <div>
      <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
        New password
      </label>
      <InputText
        :modelValue="password"
        @update:modelValue="(v: string) => emit('update:password', v)"
        type="password"
        class="w-full"
        autocomplete="new-password"
        :disabled="disabled"
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
        :disabled="disabled"
      />
    </div>

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
  </div>
</template>
