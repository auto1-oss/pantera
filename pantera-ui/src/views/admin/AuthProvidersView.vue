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
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import {
  PROVIDER_SCHEMAS,
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
              :modelValue="p.enabled"
              :disabled="isProtected(p)"
              :title="isProtected(p) ? 'Protected providers cannot be disabled' : ''"
              @update:modelValue="(v: boolean) => handleToggle(p, v)"
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
              optionLabel="label"
              optionValue="type"
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
