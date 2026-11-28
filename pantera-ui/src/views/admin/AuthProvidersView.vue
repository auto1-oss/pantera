<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import InputSwitch from 'primevue/inputswitch'
import Chips from 'primevue/chips'
import Tag from 'primevue/tag'
import Dialog from 'primevue/dialog'
import Select from 'primevue/select'
import {
  getSettings,
  toggleAuthProvider,
  updateAuthProviderConfig,
  createAuthProvider,
  deleteAuthProvider,
} from '@/api/settings'
import { listRoles } from '@/api/roles'
import {
  getPasswordSignInSettings,
  updatePasswordSignInSettings,
  PASSWORD_SIGN_IN_TYPES,
} from '@/api/passwordSignIn'
import type {
  PasswordSignInMode,
  PasswordSignInSettings,
  PasswordSignInType,
} from '@/api/passwordSignIn'
import { apiErrorMessage } from '@/utils/apiError'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import {
  CREATABLE_SCHEMAS,
  PROTECTED_TYPES,
  schemaFor,
  type ProviderSchema,
  type ProviderField,
} from '@/utils/authProviderSchemas'

interface Provider {
  id: number
  type: string
  priority: number
  enabled: boolean
  config: Record<string, unknown>
}

interface GroupRolePair { group: string; role: string }

/**
 * Per-provider draft state. The structured fields (config, allowedGroups,
 * groupRoles, customExtras) are derived from the provider's schema on load
 * and re-merged into a single config object on save.
 */
interface Draft {
  /** Map of declared schema field key → input value */
  fields: Record<string, string>
  /** Chips state for fields of type='chips' */
  chips: Record<string, string[]>
  /** group-roles editor state */
  groupRoles: GroupRolePair[]
  /** Unknown legacy fields preserved as-is and merged back on save */
  customExtras: Record<string, unknown>
  expanded: boolean
}

const notify = useNotificationStore()
const { visible: delVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()

const providers = ref<Provider[]>([])
const loading = ref(true)
const savingId = ref<number | null>(null)
const drafts = ref<Record<number, Draft>>({})

/**
 * Full list of Pantera role names, fetched on mount. Used to populate
 * the Group → Role mapping dropdowns so admins pick an existing role
 * instead of typing a name that may not exist. The dropdown is kept
 * editable so operators can still forward-declare a role that will
 * be created later (e.g. imported from SSO for the first time).
 */
const availableRoles = ref<string[]>([])

/* ---------- Schema helpers ---------- */

function buildDraft(p: Provider): Draft {
  const schema = schemaFor(p.type)
  const declaredKeys = new Set(schema.fields.map(f => f.key))
  const fields: Record<string, string> = {}
  const chips: Record<string, string[]> = {}
  let groupRoles: GroupRolePair[] = []
  const extras: Record<string, unknown> = {}
  for (const f of schema.fields) {
    const val = p.config[f.key]
    if (f.type === 'chips') {
      chips[f.key] = Array.isArray(val) ? [...(val as string[])] : []
    } else if (f.type === 'grouproles') {
      groupRoles = groupRolesFromValue(val)
    } else {
      fields[f.key] = typeof val === 'string' ? val : (val == null ? '' : String(val))
    }
  }
  for (const [k, v] of Object.entries(p.config)) {
    if (!declaredKeys.has(k)) {
      extras[k] = v
    }
  }
  return { fields, chips, groupRoles, customExtras: extras, expanded: false }
}

function groupRolesFromValue(raw: unknown): GroupRolePair[] {
  const out: GroupRolePair[] = []
  if (Array.isArray(raw)) {
    for (const entry of raw) {
      if (entry && typeof entry === 'object') {
        for (const [group, role] of Object.entries(entry)) {
          out.push({ group, role: String(role ?? '') })
        }
      }
    }
  } else if (raw && typeof raw === 'object') {
    for (const [group, role] of Object.entries(raw)) {
      out.push({ group, role: String(role ?? '') })
    }
  }
  return out
}

function groupRolesToValue(pairs: GroupRolePair[]): Array<Record<string, string>> {
  return pairs
    .filter(p => p.group.trim() && p.role.trim())
    .map(p => ({ [p.group.trim()]: p.role.trim() }))
}

function draftToConfig(p: Provider, draft: Draft): Record<string, unknown> {
  const schema = schemaFor(p.type)
  const cfg: Record<string, unknown> = { ...draft.customExtras }
  for (const f of schema.fields) {
    if (f.type === 'chips') {
      const arr = draft.chips[f.key] ?? []
      if (arr.length > 0) cfg[f.key] = arr
      else delete cfg[f.key]
    } else if (f.type === 'grouproles') {
      const pairs = groupRolesToValue(draft.groupRoles)
      if (pairs.length > 0) cfg[f.key] = pairs
      else delete cfg[f.key]
    } else {
      const val = (draft.fields[f.key] ?? '').trim()
      // Strip masked secrets so we don't overwrite the real value with "ab***cd"
      if (f.type === 'password' && val.includes('***')) {
        delete cfg[f.key]
      } else if (val) {
        cfg[f.key] = val
      } else {
        delete cfg[f.key]
      }
    }
  }
  return cfg
}

/* ---------- Load ---------- */

async function load() {
  loading.value = true
  try {
    // Fetch providers and the role catalogue in parallel. Roles feed
    // the Group → Role mapping dropdowns so admins pick from actual
    // Pantera roles instead of free-typing names that might be typos.
    const [s, rolesResp] = await Promise.all([
      getSettings(),
      listRoles({ size: 500 }).catch(() => ({ items: [] as { name: string }[] })),
    ])
    providers.value = ((s.credentials as unknown as Provider[]) ?? []).map(p => ({
      ...p,
      config: p.config ?? {},
    }))
    providers.value.forEach(p => { drafts.value[p.id] = buildDraft(p) })
    availableRoles.value = (rolesResp.items ?? [])
      .map(r => r.name)
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b))
  } catch (e: unknown) {
    notify.error('Failed to load auth providers', e instanceof Error ? e.message : '')
  } finally {
    loading.value = false
  }
  // Loaded on its own: a failure there must not hide the providers.
  await loadPasswordSignIn()
}

/* ---------- Password sign-in ---------- */

// Where a provider that checks passwords outside Pantera (okta, keycloak,
// github) takes them. One setting per provider TYPE, saved at once and on
// its own: it is not the provider's enable switch, not part of its config
// and not a setting of its sign-in button.
const PASSWORD_SIGN_IN_MODES: Array<{ label: string; value: PasswordSignInMode }> = [
  { label: 'Sign-in form and repositories', value: 'all' },
  { label: 'Sign-in form only', value: 'form' },
  { label: 'Off', value: 'off' },
]

const PASSWORD_SIGN_IN_MEANING: Record<PasswordSignInMode, string> = {
  all: 'The provider checks a password on the sign-in form, on POST /api/v1/auth/token, '
    + 'and with repository requests (mvn, npm, docker login, curl -u).',
  form: 'The provider checks a password on the sign-in form and on POST /api/v1/auth/token only. '
    + 'A repository request with a directory password is answered 401 and the provider is not asked; '
    + 'clients use an API key.',
  off: 'The provider checks no password, anywhere.',
}

type ModesByType = Partial<Record<PasswordSignInType, PasswordSignInMode>>

/** `forbidden`: the account may not read the setting, so no control is shown. */
const passwordSignInState = ref<'idle' | 'loading' | 'ready' | 'forbidden' | 'failed'>('idle')
/** The mode of each type as the server has it. */
const storedModes = ref<ModesByType>({})
/** The mode each control shows: the stored one, or a pick that waits for its confirmation or its save. */
const shownModes = ref<ModesByType>({})
const passwordSignInErrors = ref<Partial<Record<PasswordSignInType, string>>>({})
const passwordSignInSaving = ref<PasswordSignInType | null>(null)
/** The change the confirmation asks about; kept after it closes so its text does not blank out. */
const pendingMode = ref<{ type: PasswordSignInType; mode: PasswordSignInMode; provider: string }>(
  { type: 'okta', mode: 'all', provider: '' },
)
const confirmModeVisible = ref(false)

function hasPasswordSignIn(type: string): type is PasswordSignInType {
  return (PASSWORD_SIGN_IN_TYPES as readonly string[]).includes(type)
}

function passwordSignInLabel(mode: PasswordSignInMode): string {
  return PASSWORD_SIGN_IN_MODES.find(m => m.value === mode)?.label ?? mode
}

function shownMode(p: Provider): PasswordSignInMode | undefined {
  return hasPasswordSignIn(p.type) ? shownModes.value[p.type] : undefined
}

function passwordSignInMeaning(p: Provider): string {
  const mode = shownMode(p)
  return mode ? PASSWORD_SIGN_IN_MEANING[mode] ?? '' : ''
}

function passwordSignInError(p: Provider): string {
  return hasPasswordSignIn(p.type) ? passwordSignInErrors.value[p.type] ?? '' : ''
}

async function loadPasswordSignIn() {
  if (!providers.value.some(p => hasPasswordSignIn(p.type))) return
  passwordSignInState.value = 'loading'
  try {
    const settings = await getPasswordSignInSettings()
    const modes: ModesByType = {}
    for (const type of PASSWORD_SIGN_IN_TYPES) {
      modes[type] = settings[`password_sign_in_${type}`]
    }
    storedModes.value = modes
    shownModes.value = { ...modes }
    passwordSignInState.value = 'ready'
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } }).response?.status
    passwordSignInState.value = status === 403 ? 'forbidden' : 'failed'
  }
}

function onPasswordSignInPicked(p: Provider, picked: PasswordSignInMode) {
  if (!hasPasswordSignIn(p.type)) return
  const type = p.type
  shownModes.value[type] = picked
  if (picked === storedModes.value[type]) return
  const provider = schemaFor(type).label
  if (picked === 'all') {
    // Back to the default takes nothing away from anybody: no question.
    void savePasswordSignIn(type, picked, provider)
    return
  }
  pendingMode.value = { type, mode: picked, provider }
  confirmModeVisible.value = true
}

function cancelPasswordSignIn() {
  if (!confirmModeVisible.value) return
  confirmModeVisible.value = false
  const { type } = pendingMode.value
  shownModes.value[type] = storedModes.value[type]
}

async function confirmPasswordSignIn() {
  const { type, mode, provider } = pendingMode.value
  await savePasswordSignIn(type, mode, provider)
  confirmModeVisible.value = false
}

async function savePasswordSignIn(type: PasswordSignInType, mode: PasswordSignInMode, provider: string) {
  passwordSignInSaving.value = type
  passwordSignInErrors.value[type] = ''
  try {
    // Only the key that changed: the other types keep what they have.
    const change: Partial<PasswordSignInSettings> = {}
    change[`password_sign_in_${type}`] = mode
    await updatePasswordSignInSettings(change)
    storedModes.value[type] = mode
    shownModes.value[type] = mode
    notify.success('Password sign-in changed', `${provider}: ${passwordSignInLabel(mode)}`)
  } catch (e: unknown) {
    const message = apiErrorMessage(e, 'Unknown error')
    shownModes.value[type] = storedModes.value[type]
    passwordSignInErrors.value[type] = message
    notify.error('Failed to change password sign-in', message)
  } finally {
    passwordSignInSaving.value = null
  }
}

/* ---------- Toggle / save / delete ---------- */

async function handleToggle(p: Provider, enabled: boolean) {
  if (PROTECTED_TYPES.has(p.type) && !enabled) {
    notify.error(`Cannot disable the '${p.type}' provider`,
      'It is required for fallback access and stays enabled.')
    // Force the switch back on visually
    p.enabled = true
    return
  }
  try {
    await toggleAuthProvider(p.id, enabled)
    p.enabled = enabled
    notify.success(`${p.type} provider ${enabled ? 'enabled' : 'disabled'}`)
  } catch (e: unknown) {
    notify.error('Failed to toggle provider', e instanceof Error ? e.message : '')
  }
}

async function save(p: Provider) {
  const draft = drafts.value[p.id]
  if (!draft) return
  const schema = schemaFor(p.type)
  // Required-field check
  for (const f of schema.fields) {
    if (!f.required) continue
    if (f.type === 'chips' || f.type === 'grouproles') continue
    const val = (draft.fields[f.key] ?? '').trim()
    if (!val) {
      notify.error(`'${f.label}' is required`)
      return
    }
  }
  savingId.value = p.id
  try {
    const cfg = draftToConfig(p, draft)
    await updateAuthProviderConfig(p.id, cfg)
    p.config = cfg
    drafts.value[p.id] = buildDraft({ ...p, config: cfg })
    notify.success(`${p.type} configuration updated`)
  } catch (e: unknown) {
    notify.error('Failed to update provider', e instanceof Error ? e.message : '')
  } finally {
    savingId.value = null
  }
}

async function handleDelete(p: Provider) {
  if (PROTECTED_TYPES.has(p.type)) {
    notify.error(`Cannot delete the '${p.type}' provider`,
      'It is required for fallback access.')
    return
  }
  const ok = await confirmDel(`${p.type} (id ${p.id})`)
  if (!ok) return
  try {
    await deleteAuthProvider(p.id)
    notify.success(`Deleted ${p.type} provider`)
    providers.value = providers.value.filter(x => x.id !== p.id)
    delete drafts.value[p.id]
  } catch (e: unknown) {
    notify.error('Failed to delete provider', e instanceof Error ? e.message : '')
  }
}

function addGroupRolePair(p: Provider) {
  drafts.value[p.id]?.groupRoles.push({ group: '', role: '' })
}

function removeGroupRolePair(p: Provider, idx: number) {
  drafts.value[p.id]?.groupRoles.splice(idx, 1)
}

/* ---------- Add provider dialog ---------- */

const addVisible = ref(false)
const addSaving = ref(false)
const addType = ref<string>(CREATABLE_SCHEMAS[0]?.type ?? '')
const addPriority = ref<number>(CREATABLE_SCHEMAS[0]?.defaultPriority ?? 100)
const addFields = ref<Record<string, string>>({})
const addChips = ref<Record<string, string[]>>({})
const addGroupRoles = ref<GroupRolePair[]>([])

const addSchema = computed<ProviderSchema>(() => schemaFor(addType.value))

function resetAddForm(type: string) {
  addType.value = type
  const schema = schemaFor(type)
  addPriority.value = schema.defaultPriority
  addFields.value = {}
  addChips.value = {}
  addGroupRoles.value = []
  for (const f of schema.fields) {
    if (f.type === 'chips') addChips.value[f.key] = []
    else if (f.type !== 'grouproles') addFields.value[f.key] = ''
  }
}

function openAddDialog() {
  resetAddForm(CREATABLE_SCHEMAS[0]?.type ?? '')
  addVisible.value = true
}

function onAddTypeChange() {
  resetAddForm(addType.value)
}

async function submitAdd() {
  if (!addType.value) return
  if (providers.value.some(p => p.type === addType.value)) {
    notify.error(`Provider '${addType.value}' already exists`,
      'Edit the existing one instead.')
    return
  }
  // Required-field check
  for (const f of addSchema.value.fields) {
    if (!f.required) continue
    if (f.type === 'chips' || f.type === 'grouproles') continue
    const val = (addFields.value[f.key] ?? '').trim()
    if (!val) {
      notify.error(`'${f.label}' is required`)
      return
    }
  }
  // Build config from fields/chips/groupRoles
  const cfg: Record<string, unknown> = {}
  for (const f of addSchema.value.fields) {
    if (f.type === 'chips') {
      const arr = addChips.value[f.key] ?? []
      if (arr.length > 0) cfg[f.key] = arr
    } else if (f.type === 'grouproles') {
      const pairs = groupRolesToValue(addGroupRoles.value)
      if (pairs.length > 0) cfg[f.key] = pairs
    } else {
      const val = (addFields.value[f.key] ?? '').trim()
      if (val) cfg[f.key] = val
    }
  }
  addSaving.value = true
  try {
    await createAuthProvider({
      type: addType.value,
      priority: addPriority.value,
      config: cfg,
    })
    notify.success(`Created ${addType.value} provider`)
    addVisible.value = false
    await load()
  } catch (e: unknown) {
    notify.error('Failed to create provider', e instanceof Error ? e.message : '')
  } finally {
    addSaving.value = false
  }
}

function addAddGroupRolePair() {
  addGroupRoles.value.push({ group: '', role: '' })
}

function removeAddGroupRolePair(idx: number) {
  addGroupRoles.value.splice(idx, 1)
}

/* ---------- Sorted view ---------- */

const sortedProviders = computed(() =>
  [...providers.value].sort((a, b) => a.priority - b.priority),
)

function fieldsFor(p: Provider): ProviderField[] {
  return schemaFor(p.type).fields
}

function isProtected(p: Provider): boolean {
  return PROTECTED_TYPES.has(p.type)
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <div class="max-w-5xl space-y-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Authentication Providers</h1>
          <p class="text-sm text-gray-500 mt-1">
            Configure SSO providers (Okta, Keycloak) and built-in auth.
            <span class="font-mono text-orange-500">local</span> and
            <span class="font-mono text-orange-500">jwt-password</span> are
            protected — they cannot be disabled or deleted because they
            guarantee fallback access.
          </p>
        </div>
        <Button
          label="Add Provider"
          icon="pi pi-plus"
          size="small"
          class="!rounded-lg flex-shrink-0"
          @click="openAddDialog"
        />
      </div>

      <div v-if="loading" class="text-center py-10 text-gray-400">
        <i class="pi pi-spin pi-spinner text-2xl" />
      </div>

      <div v-else-if="sortedProviders.length === 0" class="text-center py-16 text-gray-400">
        <i class="pi pi-user-plus text-4xl text-gray-700 mb-3" />
        <p>No auth providers configured</p>
      </div>

      <Card v-for="p in sortedProviders" :key="p.id" class="shadow-sm">
        <template #content>
          <!-- Header row -->
          <div class="flex items-center gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-3 flex-wrap">
                <span class="font-semibold text-gray-900 dark:text-white text-lg">{{ schemaFor(p.type).label }}</span>
                <Tag :value="`priority ${p.priority}`" severity="secondary" />
                <Tag
                  :value="p.enabled ? 'enabled' : 'disabled'"
                  :severity="p.enabled ? 'success' : 'secondary'"
                />
                <Tag v-if="isProtected(p)" value="protected" severity="info" />
              </div>
              <div v-if="Array.isArray(p.config['allowed-groups']) && (p.config['allowed-groups'] as string[]).length > 0" class="text-xs text-gray-500 mt-1">
                Access gate: only users in
                <span
                  v-for="(g, i) in (p.config['allowed-groups'] as string[])"
                  :key="g"
                  class="font-mono text-orange-500"
                >{{ g }}<span v-if="i < (p.config['allowed-groups'] as string[]).length - 1">, </span></span>
              </div>
              <div v-else-if="p.type === 'okta' || p.type === 'keycloak'" class="text-xs text-orange-400 mt-1">
                ⚠ No allowed-groups configured — any authenticated user will be provisioned
              </div>
            </div>
            <InputSwitch
              :model-value="p.enabled"
              :disabled="isProtected(p)"
              :title="isProtected(p) ? 'Protected providers cannot be disabled' : ''"
              @update:model-value="(v: boolean) => handleToggle(p, v)"
            />
            <Button
              v-if="fieldsFor(p).length > 0"
              :icon="drafts[p.id]?.expanded ? 'pi pi-chevron-up' : 'pi pi-pencil'"
              :label="drafts[p.id]?.expanded ? 'Close' : 'Edit'"
              size="small"
              outlined
              @click="drafts[p.id].expanded = !drafts[p.id].expanded"
            />
            <Button
              icon="pi pi-trash"
              severity="danger"
              size="small"
              outlined
              :disabled="isProtected(p)"
              :title="isProtected(p) ? 'Protected providers cannot be deleted' : 'Delete provider'"
              @click="handleDelete(p)"
            />
          </div>

          <!-- Password sign-in: where the provider takes passwords (okta, keycloak, github) -->
          <div
            v-if="hasPasswordSignIn(p.type) && passwordSignInState !== 'idle' && passwordSignInState !== 'forbidden'"
            class="mt-4 pt-4 border-t border-gray-200 dark:border-gray-800"
            data-testid="password-sign-in"
            :data-provider-type="p.type"
          >
            <div class="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
              <div class="min-w-0">
                <label
                  :for="`passwordSignIn-${p.id}`"
                  class="block text-sm font-medium text-gray-900 dark:text-white"
                  data-testid="password-sign-in-label"
                >Password sign-in</label>
                <div class="text-xs text-gray-500">
                  Where this provider checks a user name and password.
                </div>
              </div>
              <Select
                :model-value="shownMode(p)"
                :label-id="`passwordSignIn-${p.id}`"
                :options="PASSWORD_SIGN_IN_MODES"
                option-label="label"
                option-value="value"
                class="w-72 text-sm"
                :disabled="passwordSignInState !== 'ready' || passwordSignInSaving !== null"
                :loading="passwordSignInState === 'loading' || passwordSignInSaving === p.type"
                :placeholder="passwordSignInState === 'loading' ? 'Loading…' : 'Not loaded'"
                data-testid="password-sign-in-mode"
                @update:model-value="(mode: PasswordSignInMode) => onPasswordSignInPicked(p, mode)"
              />
            </div>
            <div class="mt-2 space-y-1 text-xs text-gray-500">
              <p
                v-if="passwordSignInState === 'failed'"
                class="flex flex-wrap items-center gap-2 text-red-600 dark:text-red-400"
                data-testid="password-sign-in-load-error"
              >
                <span>The setting could not be loaded.</span>
                <Button
                  label="Retry"
                  icon="pi pi-refresh"
                  size="small"
                  severity="secondary"
                  text
                  data-testid="password-sign-in-retry"
                  @click="loadPasswordSignIn"
                />
              </p>
              <p
                v-if="passwordSignInError(p)"
                class="text-red-600 dark:text-red-400"
                data-testid="password-sign-in-save-error"
              >
                Not changed: {{ passwordSignInError(p) }}
              </p>
              <p
                v-if="passwordSignInState === 'ready' && passwordSignInMeaning(p)"
                class="text-gray-700 dark:text-gray-300"
                data-testid="password-sign-in-description"
              >
                {{ passwordSignInMeaning(p) }}
              </p>
              <p data-testid="password-sign-in-notes">
                This does not affect the provider's sign-in button or API keys. A change applies
                to the next login, without a restart.
              </p>
              <p v-if="p.type === 'github'" data-testid="password-sign-in-github-note">
                GitHub takes a personal access token as the password.
              </p>
              <p v-if="!p.enabled" data-testid="password-sign-in-provider-disabled">
                This provider is disabled. The setting is kept and applies once the provider is enabled.
              </p>
            </div>
          </div>

          <!-- Edit section -->
          <div v-if="drafts[p.id]?.expanded && fieldsFor(p).length > 0" class="mt-5 pt-5 border-t border-gray-200 dark:border-gray-800 space-y-4">
            <div v-for="f in fieldsFor(p)" :key="f.key">
              <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
                {{ f.label }}
                <span v-if="f.required" class="text-red-400 normal-case">*</span>
              </label>

              <!-- Text / URL / password inputs -->
              <InputText
                v-if="f.type === 'text' || f.type === 'url'"
                v-model="drafts[p.id].fields[f.key]"
                :placeholder="f.placeholder"
                class="w-full text-sm"
              />
              <InputText
                v-else-if="f.type === 'password'"
                v-model="drafts[p.id].fields[f.key]"
                :placeholder="f.placeholder ?? 'Leave masked value to keep current secret'"
                type="password"
                class="w-full text-sm"
              />

              <!-- Chips for arrays -->
              <Chips
                v-else-if="f.type === 'chips'"
                v-model="drafts[p.id].chips[f.key]"
                separator=","
                class="w-full"
                placeholder="Type a value and press Enter"
              />

              <!-- Group → Role mapping -->
              <div v-else-if="f.type === 'grouproles'" class="space-y-2">
                <div
                  v-for="(pair, idx) in drafts[p.id].groupRoles"
                  :key="idx"
                  class="flex items-center gap-2"
                >
                  <InputText
                    v-model="pair.group"
                    placeholder="IdP group (e.g. pantera_admins)"
                    class="flex-1 text-sm font-mono"
                  />
                  <span class="text-gray-500 text-xs">→</span>
                  <Select
                    v-model="pair.role"
                    :options="availableRoles"
                    placeholder="Select a Pantera role"
                    filter
                    editable
                    class="flex-1 text-sm"
                    :pt="{ root: { class: 'w-full' } }"
                  />
                  <Button
                    icon="pi pi-trash"
                    text
                    size="small"
                    severity="danger"
                    @click="removeGroupRolePair(p, idx)"
                  />
                </div>
                <Button
                  label="Add mapping"
                  icon="pi pi-plus"
                  size="small"
                  outlined
                  @click="addGroupRolePair(p)"
                />
                <p class="text-xs text-gray-500">
                  Role choices come from your Pantera role catalog. You
                  can type a name that isn't in the list yet if you plan
                  to create the role afterwards.
                </p>
              </div>

              <p v-if="f.help" class="text-xs text-gray-500 mt-1">{{ f.help }}</p>
            </div>

            <!-- Custom legacy fields (preserved but not editable) -->
            <div v-if="Object.keys(drafts[p.id]?.customExtras ?? {}).length > 0">
              <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
                Custom fields (preserved from import)
              </label>
              <div class="text-xs font-mono bg-gray-100 dark:bg-gray-800 rounded p-2 text-gray-500">
                {{ JSON.stringify(drafts[p.id].customExtras) }}
              </div>
            </div>

            <div class="flex justify-end gap-2 pt-2">
              <Button
                label="Cancel"
                severity="secondary"
                size="small"
                outlined
                @click="drafts[p.id] = buildDraft(p)"
              />
              <Button
                label="Save"
                icon="pi pi-check"
                size="small"
                :loading="savingId === p.id"
                @click="save(p)"
              />
            </div>
          </div>
        </template>
      </Card>

      <!-- Add Provider Dialog -->
      <Dialog v-model:visible="addVisible" header="Add Auth Provider" modal class="w-[600px]">
        <div class="space-y-4 pt-2">
          <div>
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
              Provider Type
            </label>
            <Select
              v-model="addType"
              :options="CREATABLE_SCHEMAS"
              option-label="label"
              option-value="type"
              class="w-full"
              @change="onAddTypeChange"
            />
            <p class="text-xs text-gray-500 mt-1">{{ addSchema.description }}</p>
          </div>

          <div>
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
              Priority
            </label>
            <InputNumber v-model="addPriority" :min="0" :max="999" class="w-full" />
            <p class="text-xs text-gray-500 mt-1">
              Lower values are tried first. local=0, jwt-password=1.
            </p>
          </div>

          <div v-for="f in addSchema.fields" :key="f.key">
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
              {{ f.label }}
              <span v-if="f.required" class="text-red-400 normal-case">*</span>
            </label>
            <InputText
              v-if="f.type === 'text' || f.type === 'url'"
              v-model="addFields[f.key]"
              :placeholder="f.placeholder"
              class="w-full text-sm"
            />
            <InputText
              v-else-if="f.type === 'password'"
              v-model="addFields[f.key]"
              :placeholder="f.placeholder"
              type="password"
              class="w-full text-sm"
            />
            <Chips
              v-else-if="f.type === 'chips'"
              v-model="addChips[f.key]"
              separator=","
              class="w-full"
              placeholder="Type a value and press Enter"
            />
            <div v-else-if="f.type === 'grouproles'" class="space-y-2">
              <div
                v-for="(pair, idx) in addGroupRoles"
                :key="idx"
                class="flex items-center gap-2"
              >
                <InputText
                  v-model="pair.group"
                  placeholder="IdP group"
                  class="flex-1 text-sm font-mono"
                />
                <span class="text-gray-500 text-xs">→</span>
                <Select
                  v-model="pair.role"
                  :options="availableRoles"
                  placeholder="Select a Pantera role"
                  filter
                  editable
                  class="flex-1 text-sm"
                  :pt="{ root: { class: 'w-full' } }"
                />
                <Button
                  icon="pi pi-trash"
                  text
                  size="small"
                  severity="danger"
                  @click="removeAddGroupRolePair(idx)"
                />
              </div>
              <Button
                label="Add mapping"
                icon="pi pi-plus"
                size="small"
                outlined
                @click="addAddGroupRolePair"
              />
              <p class="text-xs text-gray-500">
                Role choices come from your Pantera role catalog. You
                can type a name that isn't in the list yet.
              </p>
            </div>
            <p v-if="f.help" class="text-xs text-gray-500 mt-1">{{ f.help }}</p>
          </div>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" outlined @click="addVisible = false" />
          <Button label="Create" icon="pi pi-check" :loading="addSaving" @click="submitAdd" />
        </template>
      </Dialog>

      <!-- Password sign-in: confirmation of a change that takes password sign-in away -->
      <Dialog
        :visible="confirmModeVisible"
        :header="`Password sign-in: ${pendingMode.provider}`"
        modal
        class="w-[520px]"
        :closable="passwordSignInSaving === null"
        :close-on-escape="passwordSignInSaving === null"
        @update:visible="(open: boolean) => { if (!open) cancelPasswordSignIn() }"
      >
        <div class="space-y-3 text-sm" data-testid="password-sign-in-confirm-dialog">
          <p>
            Change password sign-in of <strong>{{ pendingMode.provider }}</strong> to
            <strong>{{ passwordSignInLabel(pendingMode.mode) }}</strong>?
          </p>
          <ul class="list-disc pl-5 space-y-1 text-gray-600 dark:text-gray-300">
            <li v-if="pendingMode.mode === 'off'">
              Directory passwords stop working in build tools and other repository clients, and on
              the sign-in form.
            </li>
            <li v-else>
              Directory passwords stop working in build tools and other repository clients.
            </li>
            <li>
              Such a login then counts as a failed login towards the login delay and the account lock.
            </li>
            <li>
              Users need API keys for their build tools and repository clients. Hand them out first.
            </li>
          </ul>
          <p v-if="pendingMode.type === 'github'" class="text-gray-600 dark:text-gray-300">
            With GitHub the password is a personal access token.
          </p>
          <p class="text-xs text-gray-500">
            The provider's sign-in button and API keys keep working.
          </p>
        </div>
        <template #footer>
          <Button
            label="Cancel"
            severity="secondary"
            outlined
            :disabled="passwordSignInSaving !== null"
            data-testid="password-sign-in-cancel"
            @click="cancelPasswordSignIn"
          />
          <Button
            :label="`Change to ${passwordSignInLabel(pendingMode.mode)}`"
            severity="warn"
            icon="pi pi-check"
            :loading="passwordSignInSaving !== null"
            data-testid="password-sign-in-confirm"
            @click="confirmPasswordSignIn"
          />
        </template>
      </Dialog>

      <!-- Delete Confirmation Dialog -->
      <Dialog v-model:visible="delVisible" header="Delete Auth Provider" modal class="w-[420px]">
        <p class="text-sm text-gray-300">
          Delete provider <span class="font-mono text-orange-500">{{ targetName }}</span>?
        </p>
        <p class="text-xs text-gray-500 mt-2">
          Existing user sessions are not affected. Users currently logged in via
          this provider stay logged in until their tokens expire — but they will
          not be able to refresh or re-authenticate.
        </p>
        <template #footer>
          <Button label="Cancel" severity="secondary" outlined @click="rejectDel" />
          <Button label="Delete" severity="danger" icon="pi pi-trash" @click="acceptDel" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
