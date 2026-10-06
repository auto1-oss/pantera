<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { getRepo, putRepo, moveRepo, deleteRepo } from '@/api/repos'
import { getCooldown, putCooldown } from '@/api/settings'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import { repoModeLabel } from '@/utils/repoTypes'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import RepoConfigForm from '@/components/admin/RepoConfigForm.vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Dialog from 'primevue/dialog'
import InputSwitch from 'primevue/inputswitch'
import InputText from 'primevue/inputtext'
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
// Baseline of the last loaded/saved state, as JSON, for dirty tracking.
const savedConfig = ref('')

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
const savedOverride = ref('')

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

// ---------------------------------------------------------------------------
// Dirty tracking, save, reset
// ---------------------------------------------------------------------------
const configDirty = computed(() => JSON.stringify(config.value) !== savedConfig.value)
const cooldownDirty = computed(() => overrideJson() !== savedOverride.value)
const dirty = computed(() => configDirty.value || cooldownDirty.value)

async function save() {
  if (!config.value) return
  saving.value = true
  saveError.value = ''
  try {
    await putRepo(props.name, config.value as Record<string, unknown>)
    savedConfig.value = JSON.stringify(config.value)
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
    initialConfig.value = restored
    config.value = restored
  }
  applyOverride(cooldownConfig.value?.repo_names?.[props.name])
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
    savedConfig.value = JSON.stringify(config.value)
    savedOverride.value = overrideJson()
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
    initialConfig.value = envelope
    config.value = envelope
    savedConfig.value = JSON.stringify(envelope)
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
})
onBeforeUnmount(() => { window.removeEventListener('beforeunload', onBeforeUnload) })

defineExpose({ dirty, save, reset, overrideEnabled })
</script>

<template>
  <AppLayout>
    <div class="max-w-2xl space-y-5">
      <div class="flex flex-wrap items-center gap-3">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Edit: {{ name }}</h1>
        <RepoTypeBadge v-if="repoType" :type="repoType" size="md" />
        <span v-if="repoType" class="text-sm text-gray-500">
          {{ repoModeLabel(undefined, repoType) }}<template v-if="storageLabel"> · {{ storageLabel }}</template>
        </span>
        <RouterLink :to="`/repositories/${encodeURIComponent(name)}`" class="ml-auto text-sm text-blue-600 hover:underline dark:text-blue-400">
          <i class="pi pi-folder-open mr-1" />Browse
        </RouterLink>
      </div>

      <div v-if="loading" class="text-sm text-gray-500">Loading…</div>

      <div v-else-if="loadError" class="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">
        Failed to load repository: {{ loadError }}
      </div>

      <template v-else>
        <RepoConfigForm
          v-model:config="config"
          :initial-config="initialConfig"
          :read-only-type="true"
          @valid-change="isValid = $event"
        />

        <!-- Per-repository cooldown override -->
        <Card class="shadow-sm" data-testid="repo-cooldown-card">
          <template #title>Cooldown</template>
          <template #subtitle>
            Override the global cooldown for this repository. Repository-specific
            cooldown overrides type-level settings. SNAPSHOT policy further
            overrides for SNAPSHOT artifacts (Maven/Gradle). Saved together with the repository.
          </template>
          <template #content>
            <div v-if="cooldownLoadError" class="mb-3 text-sm text-red-700 dark:text-red-300" data-testid="repo-cooldown-load-error">
              {{ cooldownLoadError }}
            </div>
            <div class="space-y-5">
              <div class="flex items-center justify-between rounded-lg bg-gray-50 p-3 dark:bg-gray-800">
                <div>
                  <div class="text-sm font-medium">Use repository-specific cooldown</div>
                  <div class="text-xs text-gray-500">Off: inherit from per-type / global settings.</div>
                </div>
                <InputSwitch v-model="overrideEnabled" :disabled="!canEditCooldown || !cooldownConfig" data-testid="repo-cooldown-toggle" />
              </div>
              <div v-if="overrideEnabled" class="space-y-3 border-l-4 border-blue-200 pl-3 dark:border-blue-800" data-testid="repo-cooldown-fields">
                <div class="flex items-center gap-3">
                  <label class="w-44 text-sm text-gray-500">Enabled</label>
                  <InputSwitch v-model="repoCooldownEnabled" :disabled="!canEditCooldown" data-testid="repo-cooldown-enabled" />
                  <span class="text-xs text-gray-400">{{ repoCooldownEnabled ? 'Cooldown enforced for this repo' : 'Cooldown disabled for this repo' }}</span>
                </div>
                <div class="flex items-center gap-3">
                  <label class="w-44 text-sm text-gray-500">Minimum allowed age</label>
                  <InputText v-model="repoCooldownAge" class="w-32" :placeholder="globalAgePlaceholder" :disabled="!canEditCooldown" data-testid="repo-cooldown-age" />
                  <span class="text-xs text-gray-400">e.g. 7d, 24h, 30m</span>
                </div>
                <div class="flex items-center gap-3">
                  <label class="w-44 text-sm text-gray-500">SNAPSHOT enabled (override)</label>
                  <select v-model="repoSnapshotEnabled" class="rounded border px-2 py-1 text-sm dark:bg-gray-800" :disabled="!canEditCooldown" data-testid="repo-snapshot-enabled">
                    <option :value="null">inherit</option>
                    <option :value="true">true</option>
                    <option :value="false">false</option>
                  </select>
                </div>
                <div class="flex items-center gap-3">
                  <label class="w-44 text-sm text-gray-500">SNAPSHOT minimum age</label>
                  <InputText v-model="repoSnapshotAge" class="w-32" :placeholder="globalSnapshotAgePlaceholder" :disabled="!canEditCooldown" data-testid="repo-snapshot-age" />
                  <span class="text-xs text-gray-400">e.g. 14d, 30d</span>
                </div>
              </div>
              <div v-if="!canEditCooldown" class="text-xs text-gray-500" data-testid="repo-cooldown-readonly-note">
                Read-only — you do not have permission to edit cooldown settings.
              </div>
            </div>
          </template>
        </Card>

        <div v-if="saveError" data-testid="save-error" class="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">
          {{ saveError }}
        </div>

        <div class="flex items-center gap-3 pt-2">
          <Button
            v-if="canUpdate"
            label="Save"
            icon="pi pi-check"
            :loading="saving"
            :disabled="!isValid || saving || !dirty"
            data-testid="save-btn"
            @click="save"
          />
          <Button label="Reset" severity="secondary" text :disabled="!dirty || saving" data-testid="reset-btn" @click="reset" />
          <span v-if="dirty" class="text-xs text-amber-600 dark:text-amber-400" data-testid="dirty-note">Unsaved changes</span>
          <Button label="Back" severity="secondary" text class="ml-auto" @click="router.back()" />
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
