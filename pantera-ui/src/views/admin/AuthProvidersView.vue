<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputSwitch from 'primevue/inputswitch'
import Chips from 'primevue/chips'
import Textarea from 'primevue/textarea'
import Tag from 'primevue/tag'
import { getSettings, toggleAuthProvider, updateAuthProviderConfig } from '@/api/settings'
import { useNotificationStore } from '@/stores/notifications'

interface Provider {
  id: number
  type: string
  priority: number
  enabled: boolean
  config: Record<string, unknown>
}

const notify = useNotificationStore()
const providers = ref<Provider[]>([])
const loading = ref(true)
const savingId = ref<number | null>(null)
// Per-provider draft state (keyed by provider id)
interface GroupRolePair { group: string; role: string }
const drafts = ref<Record<number, {
  config: Record<string, unknown>
  allowedGroups: string[]
  groupRoles: GroupRolePair[]
  rawJson: string
  advanced: boolean
  expanded: boolean
}>>({})

/**
 * group-roles in YAML/DB is an array of single-key objects:
 *   [{ pantera_readers: "reader" }, { pantera_admins: "admin" }]
 * Convert to/from a flat list of pairs for the UI.
 */
function groupRolesFromConfig(cfg: Record<string, unknown>): GroupRolePair[] {
  const raw = cfg['group-roles']
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
    // Legacy nested-object form: { group: role, ... }
    for (const [group, role] of Object.entries(raw)) {
      out.push({ group, role: String(role ?? '') })
    }
  }
  return out
}

function groupRolesToConfig(pairs: GroupRolePair[]): Array<Record<string, string>> {
  return pairs
    .filter(p => p.group.trim() && p.role.trim())
    .map(p => ({ [p.group.trim()]: p.role.trim() }))
}

const SENSITIVE_KEYS = ['secret', 'password', 'token', 'key', 'credential']
function isSensitive(key: string): boolean {
  const lower = key.toLowerCase()
  return SENSITIVE_KEYS.some(s => lower.includes(s))
}

// Known structured keys that get dedicated widgets
const ARRAY_KEYS = ['allowed-groups', 'user-domains']
const GROUP_ROLES_KEY = 'group-roles'

function isArrayKey(key: string): boolean {
  return ARRAY_KEYS.includes(key)
}

async function load() {
  loading.value = true
  try {
    const s = await getSettings()
    providers.value = ((s.credentials as unknown as Provider[]) ?? []).map(p => ({
      ...p,
      config: p.config ?? {},
    }))
    // Initialize draft state for each provider
    providers.value.forEach(p => {
      drafts.value[p.id] = {
        config: JSON.parse(JSON.stringify(p.config)),
        allowedGroups: Array.isArray(p.config['allowed-groups'])
          ? [...(p.config['allowed-groups'] as string[])]
          : [],
        groupRoles: groupRolesFromConfig(p.config),
        rawJson: JSON.stringify(p.config, null, 2),
        advanced: false,
        expanded: false,
      }
    })
  } catch (e: unknown) {
    notify.error('Failed to load auth providers', e instanceof Error ? e.message : '')
  } finally {
    loading.value = false
  }
}

async function handleToggle(p: Provider, enabled: boolean) {
  try {
    await toggleAuthProvider(p.id, enabled)
    p.enabled = enabled
    notify.success(`${p.type} provider ${enabled ? 'enabled' : 'disabled'}`)
  } catch (e: unknown) {
    notify.error('Failed to toggle provider', e instanceof Error ? e.message : '')
  }
}

function simpleEntries(p: Provider): Array<[string, unknown]> {
  const draft = drafts.value[p.id]
  if (!draft) return []
  return Object.entries(draft.config).filter(([k]) =>
    !isArrayKey(k) && k !== GROUP_ROLES_KEY
  )
}

function updateSimpleField(p: Provider, key: string, value: string) {
  const draft = drafts.value[p.id]
  if (draft) {
    draft.config[key] = value
  }
}

function addSimpleField(p: Provider, key: string) {
  const draft = drafts.value[p.id]
  if (draft && key && !(key in draft.config)) {
    draft.config[key] = ''
  }
}

function removeSimpleField(p: Provider, key: string) {
  const draft = drafts.value[p.id]
  if (draft) {
    delete draft.config[key]
  }
}

async function save(p: Provider) {
  const draft = drafts.value[p.id]
  if (!draft) return
  savingId.value = p.id
  try {
    let cfg: Record<string, unknown>
    if (draft.advanced) {
      try {
        cfg = JSON.parse(draft.rawJson)
      } catch {
        notify.error('Invalid JSON — fix syntax before saving')
        return
      }
    } else {
      // Merge structured widgets back into config
      cfg = { ...draft.config }
      if (draft.allowedGroups.length > 0) {
        cfg['allowed-groups'] = draft.allowedGroups
      } else {
        delete cfg['allowed-groups']
      }
      const grPairs = groupRolesToConfig(draft.groupRoles)
      if (grPairs.length > 0) {
        cfg['group-roles'] = grPairs
      } else {
        delete cfg['group-roles']
      }
      // Strip masked secret values so we don't overwrite real values with "ab***cd"
      for (const [k, v] of Object.entries(cfg)) {
        if (isSensitive(k) && typeof v === 'string' && v.includes('***')) {
          delete cfg[k]
        }
      }
    }
    await updateAuthProviderConfig(p.id, cfg)
    p.config = cfg
    draft.config = JSON.parse(JSON.stringify(cfg))
    draft.allowedGroups = Array.isArray(cfg['allowed-groups'])
      ? [...(cfg['allowed-groups'] as string[])]
      : []
    draft.groupRoles = groupRolesFromConfig(cfg)
    draft.rawJson = JSON.stringify(cfg, null, 2)
    notify.success(`${p.type} configuration updated`)
    draft.expanded = false
  } catch (e: unknown) {
    notify.error('Failed to update provider', e instanceof Error ? e.message : '')
  } finally {
    savingId.value = null
  }
}

function toggleAdvanced(p: Provider) {
  const draft = drafts.value[p.id]
  if (!draft) return
  if (draft.advanced) {
    // Leaving advanced mode — parse JSON back into structured state
    try {
      const parsed = JSON.parse(draft.rawJson)
      draft.config = parsed
      draft.allowedGroups = Array.isArray(parsed['allowed-groups'])
        ? parsed['allowed-groups']
        : []
      draft.groupRoles = groupRolesFromConfig(parsed)
    } catch {
      notify.error('Cannot leave advanced mode: invalid JSON')
      return
    }
  } else {
    // Entering advanced mode — serialize structured state to JSON
    const cfg = { ...draft.config }
    if (draft.allowedGroups.length > 0) {
      cfg['allowed-groups'] = draft.allowedGroups
    }
    const grPairs = groupRolesToConfig(draft.groupRoles)
    if (grPairs.length > 0) {
      cfg['group-roles'] = grPairs
    }
    draft.rawJson = JSON.stringify(cfg, null, 2)
  }
  draft.advanced = !draft.advanced
}

function addGroupRolePair(p: Provider) {
  const draft = drafts.value[p.id]
  if (draft) {
    draft.groupRoles.push({ group: '', role: '' })
  }
}

function removeGroupRolePair(p: Provider, idx: number) {
  const draft = drafts.value[p.id]
  if (draft) {
    draft.groupRoles.splice(idx, 1)
  }
}

const newFieldKey = ref<Record<number, string>>({})

onMounted(load)

const sortedProviders = computed(() =>
  [...providers.value].sort((a, b) => a.priority - b.priority),
)
</script>

<template>
  <AppLayout>
    <div class="max-w-5xl space-y-5">
      <div>
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Authentication Providers</h1>
        <p class="text-sm text-gray-500 mt-1">
          Configure SSO providers (Okta, Keycloak) and local auth. Provider data
          is stored in the database — changes here take effect immediately.
          Setting <span class="font-mono text-orange-500">allowed-groups</span>
          restricts SSO login to users in the listed IdP groups.
        </p>
      </div>

      <div v-if="loading" class="text-center py-10 text-gray-400">
        <i class="pi pi-spin pi-spinner text-2xl" />
      </div>

      <div v-else-if="sortedProviders.length === 0" class="text-center py-16 text-gray-400">
        <i class="pi pi-user-plus text-4xl text-gray-700 mb-3" />
        <p>No auth providers configured</p>
        <p class="text-xs text-gray-600 mt-1">Providers are initially imported from pantera.yml on first startup.</p>
      </div>

      <Card v-for="p in sortedProviders" :key="p.id" class="shadow-sm">
        <template #content>
          <!-- Header row -->
          <div class="flex items-center gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-3">
                <span class="font-semibold text-gray-900 dark:text-white text-lg">{{ p.type }}</span>
                <Tag :value="`priority ${p.priority}`" severity="secondary" />
                <Tag
                  :value="p.enabled ? 'enabled' : 'disabled'"
                  :severity="p.enabled ? 'success' : 'secondary'"
                />
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
              @update:modelValue="(v: boolean) => handleToggle(p, v)"
            />
            <Button
              :icon="drafts[p.id]?.expanded ? 'pi pi-chevron-up' : 'pi pi-pencil'"
              :label="drafts[p.id]?.expanded ? 'Close' : 'Edit'"
              size="small"
              outlined
              @click="drafts[p.id].expanded = !drafts[p.id].expanded"
            />
          </div>

          <!-- Edit section -->
          <div v-if="drafts[p.id]?.expanded" class="mt-5 pt-5 border-t border-gray-200 dark:border-gray-800 space-y-4">
            <div class="flex items-center gap-2">
              <InputSwitch :modelValue="drafts[p.id].advanced" @update:modelValue="toggleAdvanced(p)" />
              <label class="text-sm text-gray-500">Advanced (raw JSON)</label>
            </div>

            <!-- Advanced JSON mode -->
            <div v-if="drafts[p.id].advanced">
              <Textarea
                v-model="drafts[p.id].rawJson"
                rows="12"
                class="w-full font-mono text-xs"
                :pt="{ root: 'font-mono' }"
              />
            </div>

            <!-- Structured form mode -->
            <div v-else class="space-y-4">
              <!-- Allowed groups (the access gate) -->
              <div v-if="p.type === 'okta' || p.type === 'keycloak'">
                <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
                  Allowed Groups
                  <span class="text-orange-500 normal-case font-normal ml-1">(access gate)</span>
                </label>
                <Chips v-model="drafts[p.id].allowedGroups" separator="," placeholder="Type a group name and press Enter" class="w-full" />
                <p class="text-xs text-gray-500 mt-1">
                  If set, the user's id_token must carry at least one of these groups or SSO login is rejected. Leave empty to allow any authenticated IdP user.
                </p>
              </div>

              <!-- Simple string fields -->
              <div>
                <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
                  Configuration
                </label>
                <div class="space-y-2">
                  <div v-for="[key, val] in simpleEntries(p)" :key="key" class="flex items-center gap-2">
                    <span class="w-44 text-xs font-mono text-gray-500 truncate">{{ key }}</span>
                    <InputText
                      :modelValue="String(val ?? '')"
                      @update:modelValue="(v: string) => updateSimpleField(p, key, v)"
                      :type="isSensitive(key) ? 'password' : 'text'"
                      class="flex-1 text-sm"
                      :placeholder="isSensitive(key) ? 'Leave masked value to keep current secret' : ''"
                    />
                    <Button
                      icon="pi pi-trash"
                      text
                      size="small"
                      severity="danger"
                      @click="removeSimpleField(p, key)"
                    />
                  </div>
                </div>
                <div class="flex items-center gap-2 mt-2">
                  <InputText
                    v-model="newFieldKey[p.id]"
                    placeholder="New field key (e.g. issuer, client-id)"
                    class="flex-1 text-sm"
                  />
                  <Button
                    label="Add field"
                    icon="pi pi-plus"
                    size="small"
                    outlined
                    :disabled="!newFieldKey[p.id]"
                    @click="() => { addSimpleField(p, newFieldKey[p.id]); newFieldKey[p.id] = '' }"
                  />
                </div>
              </div>

              <!-- Group → Role mapping (separate from access gate) -->
              <div v-if="p.type === 'okta' || p.type === 'keycloak'">
                <label class="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1.5">
                  Group → Role Mapping
                  <span class="text-blue-400 normal-case font-normal ml-1">(role assignment)</span>
                </label>
                <p class="text-xs text-gray-500 mb-2">
                  Maps an IdP group name to a Pantera role name. The Pantera role
                  must already exist (create one under
                  <router-link to="/admin/roles" class="text-orange-500 hover:underline">Roles &amp; Permissions</router-link>).
                  Distinct from <span class="font-mono text-orange-500">allowed-groups</span>:
                  the access gate decides <em>who</em> can log in,
                  this mapping decides <em>which permissions they get</em>.
                </p>
                <div class="space-y-2">
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
                    <InputText
                      v-model="pair.role"
                      placeholder="Pantera role (e.g. admin)"
                      class="flex-1 text-sm font-mono"
                    />
                    <Button
                      icon="pi pi-trash"
                      text
                      size="small"
                      severity="danger"
                      @click="removeGroupRolePair(p, idx)"
                    />
                  </div>
                </div>
                <Button
                  label="Add mapping"
                  icon="pi pi-plus"
                  size="small"
                  outlined
                  class="mt-2"
                  @click="addGroupRolePair(p)"
                />
              </div>
            </div>

            <div class="flex justify-end gap-2 pt-2">
              <Button
                label="Cancel"
                severity="secondary"
                size="small"
                outlined
                @click="drafts[p.id].expanded = false"
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
    </div>
  </AppLayout>
</template>
