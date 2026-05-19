<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getRepo, putRepo } from '@/api/repos'
import { getCooldown, putCooldown } from '@/api/settings'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import RepoConfigForm from '@/components/admin/RepoConfigForm.vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
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

const initialConfig = ref<RepoConfigEnvelope | null>(null)
const config = ref<RepoConfigEnvelope | null>(null)
const repoType = ref('')
const isValid = ref(false)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')

// --- Cooldown override state ---
// canEditCooldown gates the entire card from inputs (read-only otherwise).
// Mirrors SettingsView's cooldown section gating.
const canEditCooldown = computed(() =>
  auth.hasAction('api_cooldown_permissions', 'write'),
)

// The full cooldown config (loaded once). We PUT the WHOLE config back
// with repo_names[name] replaced — same convention SettingsView uses for
// repo_types. Null until the GET completes.
const cooldownConfig = ref<CooldownConfig | null>(null)
const cooldownLoadError = ref('')
const cooldownSaving = ref(false)

// "Use repository-specific cooldown" toggle. When OFF, the saved payload
// omits repo_names[name]; when ON, we expose the four fields below.
const overrideEnabled = ref(false)

// Bound to the four override fields. Defaults populated on mount from
// the loaded config (if any), otherwise from the global cooldown.
const repoCooldownEnabled = ref(true)
const repoCooldownAge = ref('')
const repoSnapshotEnabled = ref<boolean | null>(null)
const repoSnapshotAge = ref('')

// Placeholders shown when the override fields are empty — surface the
// effective global value so admins know what they're overriding.
const globalAgePlaceholder = computed(
  () => cooldownConfig.value?.minimum_allowed_age ?? '7d',
)
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
  repoCooldownEnabled.value
    = override.enabled ?? cooldownConfig.value?.enabled ?? true
  repoCooldownAge.value = override.minimum_allowed_age ?? ''
  const snap: CooldownSnapshotPolicy | undefined = override.snapshots
  repoSnapshotEnabled.value
    = snap && typeof snap.enabled === 'boolean' ? snap.enabled : null
  repoSnapshotAge.value = snap?.minimum_allowed_age ?? ''
}

onMounted(async () => {
  try {
    const raw = await getRepo(props.name)
    const envelope = raw as RepoConfigEnvelope
    repoType.value = (envelope.repo?.type as string) ?? ''
    initialConfig.value = envelope
    config.value = envelope
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    loadError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
    notify.error('Failed to load repository')
  } finally {
    loading.value = false
  }
  // Cooldown card loads independently — a failure here just disables the
  // card's controls and surfaces an inline error, it must NOT block the
  // main repo form.
  try {
    const cd = await getCooldown()
    cooldownConfig.value = cd
    applyOverride(cd.repo_names?.[props.name])
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    cooldownLoadError.value
      = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Failed to load cooldown config'
  }
})

async function save() {
  if (!config.value) return
  saving.value = true
  saveError.value = ''
  try {
    await putRepo(props.name, config.value as Record<string, unknown>)
    notify.success('Repository updated', props.name)
    router.push('/admin/repositories')
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    saveError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
    notify.error(`Failed to update: ${saveError.value}`)
  } finally {
    saving.value = false
  }
}

/**
 * Build the per-repo override payload from the current form state.
 * Returns undefined when the toggle is OFF so saveCooldown() can drop
 * repo_names[name] from the persisted config.
 */
function buildRepoOverride(): CooldownRepoOverride | undefined {
  if (!overrideEnabled.value) return undefined
  const out: CooldownRepoOverride = {
    enabled: repoCooldownEnabled.value,
  }
  const age = repoCooldownAge.value.trim()
  if (age.length > 0) out.minimum_allowed_age = age
  const snap: CooldownSnapshotPolicy = {}
  if (repoSnapshotEnabled.value !== null) snap.enabled = repoSnapshotEnabled.value
  const snapAge = repoSnapshotAge.value.trim()
  if (snapAge.length > 0) snap.minimum_allowed_age = snapAge
  if (Object.keys(snap).length > 0) out.snapshots = snap
  return out
}

async function saveCooldown() {
  if (!cooldownConfig.value) return
  cooldownSaving.value = true
  try {
    // Clone the existing cooldown config so we don't mutate the loaded
    // value until the PUT round-trips. PUT replaces the WHOLE config on
    // the server (same convention SettingsView uses for repo_types).
    const cfg = cooldownConfig.value
    const nextRepoNames: Record<string, CooldownRepoOverride> = {
      ...(cfg.repo_names ?? {}),
    }
    const override = buildRepoOverride()
    if (override === undefined) {
      delete nextRepoNames[props.name]
    } else {
      nextRepoNames[props.name] = override
    }
    const payload: CooldownConfig = {
      ...cfg,
      repo_names: nextRepoNames,
    }
    await putCooldown(payload)
    cooldownConfig.value = payload
    notify.success('Repository cooldown saved', props.name)
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    notify.error(
      'Failed to save cooldown',
      axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error',
    )
  } finally {
    cooldownSaving.value = false
  }
}
</script>

<template>
  <AppLayout>
    <div class="max-w-2xl space-y-5">
      <div class="flex items-center gap-3">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Edit: {{ name }}</h1>
        <RepoTypeBadge v-if="repoType" :type="repoType" size="md" />
      </div>

      <div v-if="loading" class="text-sm text-gray-500">Loading…</div>

      <div v-else-if="loadError" class="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-4 text-sm text-red-700 dark:text-red-300">
        Failed to load repository: {{ loadError }}
      </div>

      <template v-else>
        <RepoConfigForm
          v-model:config="config"
          :initial-config="initialConfig"
          :read-only-type="true"
          @valid-change="isValid = $event"
        />

        <div
          v-if="saveError"
          class="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-4 text-sm text-red-700 dark:text-red-300"
        >
          Failed to update: {{ saveError }}
        </div>

        <div class="flex gap-3 pt-2">
          <Button
            label="Save"
            icon="pi pi-check"
            :loading="saving"
            :disabled="!isValid || saving"
            @click="save"
          />
          <Button label="Cancel" severity="secondary" text @click="router.back()" />
        </div>

        <!-- Per-repository cooldown override -->
        <Card class="shadow-sm" data-testid="repo-cooldown-card">
          <template #title>Cooldown</template>
          <template #subtitle>
            Override the global cooldown for this repository. Repository-specific
            cooldown overrides type-level settings. SNAPSHOT policy further
            overrides for SNAPSHOT artifacts (Maven/Gradle).
          </template>
          <template #content>
            <div
              v-if="cooldownLoadError"
              class="text-sm text-red-700 dark:text-red-300 mb-3"
              data-testid="repo-cooldown-load-error"
            >
              {{ cooldownLoadError }}
            </div>

            <div class="space-y-5">
              <!-- Master toggle -->
              <div class="flex items-center justify-between p-3 bg-gray-50 dark:bg-gray-800 rounded-lg">
                <div>
                  <div class="font-medium text-sm">Use repository-specific cooldown</div>
                  <div class="text-xs text-gray-500">
                    Off: inherit from per-type / global settings.
                  </div>
                </div>
                <InputSwitch
                  v-model="overrideEnabled"
                  :disabled="!canEditCooldown || !cooldownConfig"
                  data-testid="repo-cooldown-toggle"
                />
              </div>

              <!-- Override fields — only when toggle is ON -->
              <div
                v-if="overrideEnabled"
                class="border-l-4 border-blue-200 dark:border-blue-800 pl-3 space-y-3"
                data-testid="repo-cooldown-fields"
              >
                <div class="flex items-center gap-3">
                  <label class="text-sm text-gray-500 w-44">Enabled</label>
                  <InputSwitch
                    v-model="repoCooldownEnabled"
                    :disabled="!canEditCooldown"
                    data-testid="repo-cooldown-enabled"
                  />
                  <span class="text-xs text-gray-400">
                    {{ repoCooldownEnabled ? 'Cooldown enforced for this repo' : 'Cooldown disabled for this repo' }}
                  </span>
                </div>
                <div class="flex items-center gap-3">
                  <label class="text-sm text-gray-500 w-44">Minimum allowed age</label>
                  <InputText
                    v-model="repoCooldownAge"
                    class="w-32"
                    :placeholder="globalAgePlaceholder"
                    :disabled="!canEditCooldown"
                    data-testid="repo-cooldown-age"
                  />
                  <span class="text-xs text-gray-400">e.g. 7d, 24h, 30m</span>
                </div>
                <div class="flex items-center gap-3">
                  <label class="text-sm text-gray-500 w-44">SNAPSHOT enabled (override)</label>
                  <select
                    v-model="repoSnapshotEnabled"
                    class="px-2 py-1 border rounded text-sm dark:bg-gray-800"
                    :disabled="!canEditCooldown"
                    data-testid="repo-snapshot-enabled"
                  >
                    <option :value="null">inherit</option>
                    <option :value="true">true</option>
                    <option :value="false">false</option>
                  </select>
                </div>
                <div class="flex items-center gap-3">
                  <label class="text-sm text-gray-500 w-44">SNAPSHOT minimum age</label>
                  <InputText
                    v-model="repoSnapshotAge"
                    class="w-32"
                    :placeholder="globalSnapshotAgePlaceholder"
                    :disabled="!canEditCooldown"
                    data-testid="repo-snapshot-age"
                  />
                  <span class="text-xs text-gray-400">e.g. 14d, 30d</span>
                </div>
              </div>

              <Button
                v-if="canEditCooldown"
                label="Save cooldown"
                icon="pi pi-save"
                :loading="cooldownSaving"
                :disabled="!cooldownConfig || cooldownSaving"
                data-testid="repo-cooldown-save"
                @click="saveCooldown"
              />
              <div v-else class="text-xs text-gray-500" data-testid="repo-cooldown-readonly-note">
                Read-only — you do not have permission to edit cooldown settings.
              </div>
            </div>
          </template>
        </Card>
      </template>
    </div>
  </AppLayout>
</template>
