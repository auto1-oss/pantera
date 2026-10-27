<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { getRepo, putRepo, moveRepo, deleteRepo } from '@/api/repos'
import { getCooldown, putCooldown } from '@/api/settings'
import { getCooldownExemptions, putCooldownExemptions } from '@/api/cooldown'
import type { CooldownExemption, CooldownExemptionInput } from '@/api/cooldown'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import { repoModeLabel } from '@/utils/repoTypes'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import RepoConfigForm from '@/components/admin/RepoConfigForm.vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import DatePicker from 'primevue/datepicker'
import Dialog from 'primevue/dialog'
import InputSwitch from 'primevue/inputswitch'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import type { RepoConfigEnvelope } from '@/types/repo'
import type {
  CooldownConfig, CooldownRepoOverride, CooldownSnapshotPolicy,
} from '@/types'

const props = defineProps<{ name: string }>()
const router = useRouter()
const notify = useNotificationStore()
const auth = useAuthStore()

const canUpdate = computed(() => auth.hasAction('api_repository_permissions', 'update'))
const canMove = computed(() => auth.hasAction('api_repository_permissions', 'move'))
const canDelete = computed(() => auth.hasAction('api_repository_permissions', 'delete'))

// ---------------------------------------------------------------------------
// Repository config
// ---------------------------------------------------------------------------
const initialConfig = ref<RepoConfigEnvelope | null>(null)
const config = ref<RepoConfigEnvelope | null>(null)
const repoType = ref('')
const isValid = ref(false)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')
// Baseline of the last loaded/saved state for dirty tracking. RepoConfigForm
// re-emits a normalised envelope as soon as it mounts (owned keys reordered,
// storage reshaped, anonymous_*/immutable defaulted), so the baseline is the
// form's FIRST emission after a load or reset, never the raw GET body, and
// comparison is key-order insensitive.
const savedConfig = ref('')
// Reactive on purpose: the dirty computed must re-track once the baseline lands.
const awaitingBaseline = ref(false)

function stable(value: unknown): string {
  return JSON.stringify(value, (_k, v) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? Object.keys(v as Record<string, unknown>).sort()
        .reduce<Record<string, unknown>>((acc, k) => { acc[k] = (v as Record<string, unknown>)[k]; return acc }, {})
      : v,
  )
}

watch(config, (next) => {
  // Our own `config = envelope` assignment on load/reset is the very object
  // held in initialConfig; the form's emission is always a fresh object.
  if (awaitingBaseline.value && next && next !== initialConfig.value) {
    savedConfig.value = stable(next)
    awaitingBaseline.value = false
  }
})

const storageLabel = computed(() => {
  const st = config.value?.repo?.storage
  if (!st) return ''
  return typeof st === 'string' ? st : st.type
})

// ---------------------------------------------------------------------------
// Cooldown override (saved by the same Save button, only when it changed)
// ---------------------------------------------------------------------------
const canEditCooldown = computed(() => auth.hasAction('api_cooldown_permissions', 'write'))
const cooldownConfig = ref<CooldownConfig | null>(null)
const cooldownLoadError = ref('')
const overrideEnabled = ref(false)
const repoCooldownEnabled = ref(true)
const repoCooldownAge = ref('')
const repoSnapshotEnabled = ref<boolean | null>(null)
const repoSnapshotAge = ref('')
const savedOverride = ref('null')

const globalAgePlaceholder = computed(() => cooldownConfig.value?.minimum_allowed_age ?? '7d')
const globalSnapshotAgePlaceholder = computed(() => {
  const snap = cooldownConfig.value?.snapshots?.minimum_allowed_age
  if (snap && snap.length > 0) return snap
  return cooldownConfig.value?.minimum_allowed_age ?? '7d'
})

function applyOverride(override: CooldownRepoOverride | undefined) {
  if (!override) {
    overrideEnabled.value = false
    repoCooldownEnabled.value = cooldownConfig.value?.enabled ?? true
    repoCooldownAge.value = ''
    repoSnapshotEnabled.value = null
    repoSnapshotAge.value = ''
    return
  }
  overrideEnabled.value = true
  repoCooldownEnabled.value = override.enabled ?? cooldownConfig.value?.enabled ?? true
  repoCooldownAge.value = override.minimum_allowed_age ?? ''
  const snap: CooldownSnapshotPolicy | undefined = override.snapshots
  repoSnapshotEnabled.value = snap && typeof snap.enabled === 'boolean' ? snap.enabled : null
  repoSnapshotAge.value = snap?.minimum_allowed_age ?? ''
}

function buildRepoOverride(): CooldownRepoOverride | undefined {
  if (!overrideEnabled.value) return undefined
  const out: CooldownRepoOverride = { enabled: repoCooldownEnabled.value }
  const age = repoCooldownAge.value.trim()
  if (age.length > 0) out.minimum_allowed_age = age
  const snap: CooldownSnapshotPolicy = {}
  if (repoSnapshotEnabled.value !== null) snap.enabled = repoSnapshotEnabled.value
  const snapAge = repoSnapshotAge.value.trim()
  if (snapAge.length > 0) snap.minimum_allowed_age = snapAge
  if (Object.keys(snap).length > 0) out.snapshots = snap
  return out
}

function overrideJson(): string {
  return JSON.stringify(buildRepoOverride() ?? null)
}

// A window set in the repository's own config (`cooldown.duration`) is
// applied over any override at every repository save, so a repository that
// has one is edited through that key (enable + age only) and the override is
// left alone; every other proxy edits the override.
const windowFromConfig = ref(false)
const configWindowEnabled = ref(false)
const configWindow = ref('30d')
const windowEnabled = computed(() => (windowFromConfig.value ? configWindowEnabled.value : overrideEnabled.value))

const SNAPSHOT_OPTIONS = [
  { label: 'Inherit', value: null },
  { label: 'Cooldown on', value: true },
  { label: 'Cooldown off', value: false },
]

// ---------------------------------------------------------------------------
// Cooldown exemptions (proxy repositories; saved by the same Save button)
// ---------------------------------------------------------------------------
interface EditableExemption {
  pattern: string
  /** YYYY-MM-DD, empty for never. */
  expiresOn: string
  reason: string
  /** Stored instant, kept as-is while the date is unchanged. */
  storedExpiry: string | null
  active: boolean
}

const isProxy = computed(() => repoType.value.endsWith('-proxy'))

/** Expiry picker value: local midnight of the stored day. */
function expiryDate(day: string): Date | null {
  if (!day) return null
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d)
}

function expiryDay(value: Date | Date[] | (Date | null)[] | null | undefined): string {
  if (!(value instanceof Date)) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
}

// An expiry is 00:00 UTC of the chosen day and must be in the future.
const firstExpiryDay = (() => {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  d.setHours(0, 0, 0, 0)
  return d
})()
const exemptions = ref<EditableExemption[]>([])
const exemptionsLoadError = ref('')
const savedExemptions = ref('[]')
/** Last loaded/saved list, restored by Reset. */
const savedExemptionList = ref<CooldownExemption[]>([])

function toEditable(list: CooldownExemption[]): EditableExemption[] {
  return list.map((e) => ({
    pattern: e.pattern,
    expiresOn: e.expires_at ? e.expires_at.slice(0, 10) : '',
    reason: e.reason ?? '',
    storedExpiry: e.expires_at,
    active: e.active ?? true,
  }))
}

function exemptionInputs(): CooldownExemptionInput[] {
  return exemptions.value.map((e) => {
    let expires: string | null = null
    if (e.expiresOn) {
      expires = e.storedExpiry && e.storedExpiry.slice(0, 10) === e.expiresOn
        ? e.storedExpiry
        : `${e.expiresOn}T00:00:00Z`
    }
    return { pattern: e.pattern.trim(), expires_at: expires, reason: e.reason.trim() }
  })
}

function exemptionsJson(): string {
  return JSON.stringify(exemptionInputs())
}

/** Same rules the server enforces: non-empty, no spaces, '*' only last, not '*' alone. */
function patternError(pattern: string): string {
  const p = pattern.trim()
  if (p.length === 0) return 'Pattern is required'
  if (/\s/.test(p)) return 'No spaces allowed'
  if (p === '*') return "'*' alone would exempt everything"
  if (p.slice(0, -1).includes('*')) return "'*' only as the last character"
  return ''
}

const exemptionsValid = computed(() => {
  const seen = new Set<string>()
  return exemptions.value.every((e) => {
    const key = e.pattern.trim().toLowerCase()
    const ok = patternError(e.pattern) === '' && !seen.has(key)
    seen.add(key)
    return ok
  })
})

function addExemption() {
  exemptions.value.push({ pattern: '', expiresOn: '', reason: '', storedExpiry: null, active: true })
}

function removeExemption(index: number) {
  exemptions.value.splice(index, 1)
}

async function saveExemptions() {
  const res = await putCooldownExemptions(props.name, exemptionInputs())
  exemptions.value = toEditable(res.exemptions)
  savedExemptionList.value = res.exemptions
  savedExemptions.value = exemptionsJson()
  if (res.released > 0) {
    notify.info('Cooldown exemptions saved', `${res.released} blocked version(s) released`)
  }
}

// ---------------------------------------------------------------------------
// Dirty tracking, save, reset
// ---------------------------------------------------------------------------
const configDirty = computed(() => {
  const changed = stable(config.value) !== savedConfig.value
  return !awaitingBaseline.value && changed
})
const cooldownDirty = computed(() => overrideJson() !== savedOverride.value)
const exemptionsDirty = computed(() => exemptionsJson() !== savedExemptions.value)
const dirty = computed(() => configDirty.value || cooldownDirty.value || exemptionsDirty.value)

async function save() {
  if (!config.value) return
  saving.value = true
  saveError.value = ''
  try {
    await putRepo(props.name, config.value as Record<string, unknown>)
    savedConfig.value = stable(config.value)
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    saveError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
    notify.error(`Failed to update: ${saveError.value}`)
    saving.value = false
    return
  }
  if (cooldownDirty.value && cooldownConfig.value) {
    try {
      await saveCooldown()
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
      const msg = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
      saveError.value = `Repository saved; cooldown override failed: ${msg}`
      notify.warn('Repository saved', `Cooldown override failed: ${msg}`)
      saving.value = false
      return
    }
  }
  if (exemptionsDirty.value && isProxy.value) {
    try {
      await saveExemptions()
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
      const msg = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
      saveError.value = `Repository saved; cooldown exemptions failed: ${msg}`
      notify.warn('Repository saved', `Cooldown exemptions failed: ${msg}`)
      saving.value = false
      return
    }
  }
  saving.value = false
  notify.success('Repository updated', props.name)
}

async function saveCooldown() {
  const cfg = cooldownConfig.value
  if (!cfg) return
  // PUT replaces the WHOLE cooldown config on the server (same convention as
  // SettingsView), so clone it and swap only this repository's entry.
  const nextRepoNames: Record<string, CooldownRepoOverride> = { ...(cfg.repo_names ?? {}) }
  const override = buildRepoOverride()
  if (override === undefined) delete nextRepoNames[props.name]
  else nextRepoNames[props.name] = override
  const payload: CooldownConfig = { ...cfg, repo_names: nextRepoNames }
  await putCooldown(payload)
  cooldownConfig.value = payload
  savedOverride.value = overrideJson()
}

function reset() {
  if (savedConfig.value) {
    const restored = JSON.parse(savedConfig.value) as RepoConfigEnvelope
    // Re-seeding the form makes it re-emit; that emission is the new baseline.
    awaitingBaseline.value = true
    initialConfig.value = restored
    config.value = restored
  }
  applyOverride(cooldownConfig.value?.repo_names?.[props.name])
  exemptions.value = toEditable(savedExemptionList.value)
  saveError.value = ''
}

// ---------------------------------------------------------------------------
// Unsaved-changes guard
// ---------------------------------------------------------------------------
const leaveVisible = ref(false)
let leaveResolve: ((go: boolean) => void) | null = null

onBeforeRouteLeave((to, from, next) => {
  if (!dirty.value) {
    next()
    return
  }
  leaveVisible.value = true
  leaveResolve = (go) => { leaveVisible.value = false; next(go) }
})

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

// ---------------------------------------------------------------------------
// Danger zone: rename, delete
// ---------------------------------------------------------------------------
const moveVisible = ref(false)
const moveTarget = ref('')
async function handleMove() {
  try {
    await moveRepo(props.name, moveTarget.value)
    notify.success('Repository renamed', `${props.name} → ${moveTarget.value}`)
    moveVisible.value = false
    router.replace(`/admin/repositories/${encodeURIComponent(moveTarget.value)}/edit`)
  } catch {
    notify.error('Failed to rename repository')
  }
}

const { visible: deleteVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()
async function handleDelete() {
  const confirmed = await confirmDel(props.name)
  if (!confirmed) return
  try {
    const result = await deleteRepo(props.name)
    if (result === 'deleting') notify.info('Repository is being deleted', props.name)
    else notify.success('Repository deleted', props.name)
    // The repository is gone; nothing left to keep.
    savedConfig.value = stable(config.value)
    savedOverride.value = overrideJson()
    savedExemptions.value = exemptionsJson()
    router.push('/admin/repositories')
  } catch {
    notify.error('Failed to delete repository')
  }
}

// ---------------------------------------------------------------------------
// Load
// ---------------------------------------------------------------------------
onMounted(async () => {
  window.addEventListener('beforeunload', onBeforeUnload)
  try {
    const raw = await getRepo(props.name)
    const envelope = raw as RepoConfigEnvelope
    repoType.value = (envelope.repo?.type as string) ?? ''
    windowFromConfig.value = !!envelope.repo?.cooldown
    awaitingBaseline.value = true
    initialConfig.value = envelope
    config.value = envelope
    savedConfig.value = stable(envelope)
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    loadError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
    notify.error('Failed to load repository')
  } finally {
    loading.value = false
  }
  // The cooldown card loads independently; a failure disables its controls
  // and shows an inline error, it never blocks saving the repository.
  try {
    const cd = await getCooldown()
    cooldownConfig.value = cd
    applyOverride(cd.repo_names?.[props.name])
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    cooldownLoadError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Failed to load cooldown config'
  }
  savedOverride.value = overrideJson()
  if (isProxy.value) {
    try {
      savedExemptionList.value = (await getCooldownExemptions(props.name)).exemptions
      exemptions.value = toEditable(savedExemptionList.value)
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
      exemptionsLoadError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Failed to load cooldown exemptions'
    }
    savedExemptions.value = exemptionsJson()
  }
})
onBeforeUnmount(() => { window.removeEventListener('beforeunload', onBeforeUnload) })

// Back always lands on the management list: the list the operator came
// from (filters and page kept) when there is one, else a fresh one.
function goBack() {
  const prev = (window.history.state as { back?: unknown } | null)?.back
  if (typeof prev === 'string' && /^\/admin\/repositories\/?(\?|$)/.test(prev)) {
    router.back()
  } else {
    router.push('/admin/repositories')
  }
}

defineExpose({ dirty, save, reset, overrideEnabled, exemptionsValid })
</script>

<template>
  <AppLayout>
    <div class="max-w-3xl space-y-5 pb-24">
      <div class="space-y-1">
        <nav class="text-sm" aria-label="Breadcrumb">
          <a href="/admin/repositories" class="text-gray-500 hover:text-gray-900 hover:underline dark:hover:text-white" data-testid="back-btn" @click.prevent="goBack">
            <i class="pi pi-arrow-left mr-1 text-xs" />Manage Repositories
          </a>
        </nav>
        <div class="flex flex-wrap items-center gap-3">
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">{{ name }}</h1>
          <RepoTypeBadge v-if="repoType" :type="repoType" size="md" />
          <span v-if="repoType" class="text-sm text-gray-500">
            {{ repoModeLabel(undefined, repoType) }}<template v-if="storageLabel"> · {{ storageLabel }}</template>
          </span>
        </div>
      </div>

      <div v-if="loading" class="text-sm text-gray-500">Loading…</div>

      <div v-else-if="loadError" class="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">
        Failed to load repository: {{ loadError }}
      </div>

      <template v-else>
        <RepoConfigForm
          v-model:config="config"
          v-model:cooldown-enabled="configWindowEnabled"
          v-model:cooldown-window="configWindow"
          :initial-config="initialConfig"
          :read-only-type="true"
          :hide-cooldown="true"
          @valid-change="isValid = $event"
        />

        <!-- Cooldown (proxy repositories only) -->
        <Card v-if="isProxy" class="shadow-sm" data-testid="repo-cooldown-card">
          <template #title>Cooldown</template>
          <template #subtitle>
            Hold back newly published upstream versions until they reach a minimum age.
          </template>
          <template #content>
            <div v-if="cooldownLoadError" class="mb-3 text-sm text-red-700 dark:text-red-300" data-testid="repo-cooldown-load-error">
              {{ cooldownLoadError }}
            </div>
            <div class="space-y-6">
              <!-- Window -->
              <section class="space-y-3">
                <div class="flex items-center justify-between gap-4">
                  <div>
                    <div class="text-sm font-medium">Repository-specific window</div>
                    <div class="text-xs text-gray-500">
                      <template v-if="windowEnabled && windowFromConfig">Stored in this repository's config.</template>
                      <template v-else-if="windowEnabled">Overrides the global and per-format settings.</template>
                      <template v-else>Off: the global settings apply (minimum age {{ globalAgePlaceholder }}).</template>
                    </div>
                  </div>
                  <InputSwitch v-if="windowFromConfig" v-model="configWindowEnabled" :disabled="!canUpdate" data-testid="repo-cooldown-toggle" />
                  <InputSwitch v-else v-model="overrideEnabled" :disabled="!canEditCooldown || !cooldownConfig" data-testid="repo-cooldown-toggle" />
                </div>

                <div v-if="windowFromConfig && configWindowEnabled" class="grid gap-3 sm:grid-cols-2" data-testid="repo-cooldown-fields">
                  <label class="space-y-1">
                    <span class="block text-xs text-gray-500">Minimum allowed age</span>
                    <InputText v-model="configWindow" fluid placeholder="30d" :disabled="!canUpdate" data-testid="repo-cooldown-age" />
                    <span class="block text-xs text-gray-400">e.g. 7d, 24h, 30m</span>
                  </label>
                </div>

                <div v-else-if="!windowFromConfig && overrideEnabled" class="grid gap-x-4 gap-y-3 sm:grid-cols-2" data-testid="repo-cooldown-fields">
                  <div class="flex items-center justify-between gap-3 sm:col-span-2">
                    <span class="text-sm">Enforce cooldown in this repository</span>
                    <InputSwitch v-model="repoCooldownEnabled" :disabled="!canEditCooldown" data-testid="repo-cooldown-enabled" />
                  </div>
                  <label class="space-y-1">
                    <span class="block text-xs text-gray-500">Minimum allowed age</span>
                    <InputText v-model="repoCooldownAge" fluid :placeholder="globalAgePlaceholder" :disabled="!canEditCooldown" data-testid="repo-cooldown-age" />
                    <span class="block text-xs text-gray-400">e.g. 7d, 24h, 30m</span>
                  </label>
                  <div />
                  <label class="space-y-1">
                    <span class="block text-xs text-gray-500">SNAPSHOT versions</span>
                    <Select v-model="repoSnapshotEnabled" :options="SNAPSHOT_OPTIONS" option-label="label" option-value="value" fluid :disabled="!canEditCooldown" data-testid="repo-snapshot-enabled" />
                  </label>
                  <label class="space-y-1">
                    <span class="block text-xs text-gray-500">SNAPSHOT minimum age</span>
                    <InputText v-model="repoSnapshotAge" fluid :placeholder="globalSnapshotAgePlaceholder" :disabled="!canEditCooldown" data-testid="repo-snapshot-age" />
                    <span class="block text-xs text-gray-400">Maven / Gradle only</span>
                  </label>
                </div>
              </section>

              <!-- Exemptions -->
              <section class="space-y-3 border-t border-gray-200 pt-5 dark:border-gray-700" data-testid="repo-exemptions">
                <div>
                  <div class="text-sm font-medium">Exemptions</div>
                  <div class="text-xs text-gray-500">
                    Packages or images cooldown never blocks here: an exact name as the Cooldown page
                    lists it, or a prefix ending in <code>*</code> (<code>@myorg/*</code>,
                    <code>com.example.*</code>, <code>library/nginx*</code>). Leave the expiry empty for a
                    permanent exemption. Saving releases matching blocks.
                  </div>
                </div>
                <div v-if="exemptionsLoadError" class="text-sm text-red-700 dark:text-red-300" data-testid="repo-exemptions-load-error">
                  {{ exemptionsLoadError }}
                </div>
                <div v-if="exemptions.length" class="space-y-2">
                  <div class="hidden gap-2 text-xs font-medium text-gray-500 sm:grid sm:grid-cols-[minmax(0,1.3fr)_10.5rem_minmax(0,1fr)_2.25rem]" data-testid="repo-exemption-header">
                    <span>Package or prefix</span>
                    <span>Expires</span>
                    <span>Reason</span>
                    <span />
                  </div>
                  <div
                    v-for="(ex, idx) in exemptions"
                    :key="idx"
                    class="grid grid-cols-1 items-start gap-2 sm:grid-cols-[minmax(0,1.3fr)_10.5rem_minmax(0,1fr)_2.25rem]"
                    data-testid="repo-exemption-row"
                  >
                    <div>
                      <InputText v-model="ex.pattern" fluid placeholder="@myorg/*" aria-label="Package or prefix" :disabled="!canEditCooldown" :invalid="patternError(ex.pattern) !== ''" data-testid="repo-exemption-pattern" />
                      <span v-if="ex.pattern && patternError(ex.pattern)" class="text-xs text-red-600">{{ patternError(ex.pattern) }}</span>
                      <Tag v-if="!ex.active" value="expired" severity="warn" class="mt-1" data-testid="repo-exemption-expired" />
                    </div>
                    <DatePicker
                      :model-value="expiryDate(ex.expiresOn)"
                      fluid
                      show-icon
                      icon-display="input"
                      show-clear
                      date-format="yy-mm-dd"
                      placeholder="Never"
                      :min-date="firstExpiryDay"
                      :manual-input="false"
                      :disabled="!canEditCooldown"
                      aria-label="Expires"
                      data-testid="repo-exemption-expiry"
                      @update:model-value="ex.expiresOn = expiryDay($event)"
                    />
                    <InputText v-model="ex.reason" fluid placeholder="Optional" aria-label="Reason" :disabled="!canEditCooldown" data-testid="repo-exemption-reason" />
                    <Button v-if="canEditCooldown" icon="pi pi-trash" severity="secondary" text aria-label="Remove exemption" data-testid="repo-exemption-remove" @click="removeExemption(idx)" />
                  </div>
                </div>
                <Button v-if="canEditCooldown" label="Add exemption" icon="pi pi-plus" severity="secondary" outlined size="small" data-testid="repo-exemption-add" @click="addExemption" />
              </section>

              <div v-if="!canEditCooldown" class="text-xs text-gray-500" data-testid="repo-cooldown-readonly-note">
                Read-only — you do not have permission to edit cooldown settings.
              </div>
            </div>
          </template>
        </Card>

        <div v-if="saveError" data-testid="save-error" class="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">
          {{ saveError }}
        </div>

        <!-- Danger zone -->
        <Card v-if="canMove || canDelete" class="border border-red-200 shadow-sm dark:border-red-900/50" data-testid="danger-zone">
          <template #title>Danger zone</template>
          <template #content>
            <div class="flex flex-wrap gap-3">
              <Button v-if="canMove" label="Rename…" icon="pi pi-arrows-h" severity="secondary" outlined size="small" @click="moveTarget = ''; moveVisible = true" />
              <Button v-if="canDelete" label="Delete repository…" icon="pi pi-trash" severity="danger" outlined size="small" @click="handleDelete" />
            </div>
          </template>
        </Card>
        <div
          v-if="canUpdate"
          class="sticky bottom-0 z-10 -mx-4 flex items-center gap-3 border-t border-gray-200 bg-white/95 px-4 py-3 backdrop-blur dark:border-gray-700 dark:bg-gray-900/95"
          data-testid="save-bar"
        >
          <span v-if="dirty" class="text-sm text-amber-600 dark:text-amber-400" data-testid="dirty-note">Unsaved changes</span>
          <span v-else class="text-sm text-gray-500">All changes saved</span>
          <Button label="Discard" severity="secondary" text class="ml-auto" :disabled="!dirty || saving" data-testid="reset-btn" @click="reset" />
          <Button
            label="Save"
            icon="pi pi-check"
            :loading="saving"
            :disabled="!isValid || !exemptionsValid || saving || !dirty"
            data-testid="save-btn"
            @click="save"
          />
        </div>
      </template>

      <Dialog v-model:visible="moveVisible" header="Rename Repository" modal class="w-96">
        <p class="mb-3">Rename <strong>{{ name }}</strong> to:</p>
        <InputText v-model="moveTarget" placeholder="New name" class="w-full" />
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="moveVisible = false" />
          <Button label="Rename" :disabled="!moveTarget" @click="handleMove" />
        </template>
      </Dialog>

      <Dialog v-model:visible="deleteVisible" header="Confirm Delete" modal class="w-96">
        <p>Delete repository <strong>{{ targetName }}</strong>? This cannot be undone.</p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectDel" />
          <Button label="Delete" severity="danger" @click="acceptDel" />
        </template>
      </Dialog>

      <Dialog v-model:visible="leaveVisible" header="Unsaved changes" modal class="w-96" data-testid="leave-dialog" :closable="false">
        <p>You have unsaved changes. Leave this page and discard them?</p>
        <template #footer>
          <Button label="Stay" severity="secondary" text @click="leaveResolve?.(false)" />
          <Button label="Leave" severity="danger" @click="leaveResolve?.(true)" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
