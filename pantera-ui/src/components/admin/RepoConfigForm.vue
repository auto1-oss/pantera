<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { listStorages, putStorage } from '@/api/settings'
import { useNotificationStore } from '@/stores/notifications'
import { REPO_TYPE_CREATE_OPTIONS } from '@/utils/repoTypes'
import type { RepoConfigEnvelope } from '@/types/repo'
import type { StorageAlias } from '@/types'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'
import AutoComplete from 'primevue/autocomplete'
import Tag from 'primevue/tag'
import Dialog from 'primevue/dialog'
import { listRepos } from '@/api/repos'
import type { RepoListItem } from '@/types'

const props = defineProps<{
  /** Current config value (v-model:config) */
  config: RepoConfigEnvelope | null
  /** Config loaded from the server — used to populate the form fields */
  initialConfig: RepoConfigEnvelope | null
  /** When true the Type dropdown is disabled (edit mode: type is immutable) */
  readOnlyType?: boolean
}>()

const emit = defineEmits<{
  'update:config': [value: RepoConfigEnvelope]
  'valid-change': [value: boolean]
}>()

const notify = useNotificationStore()

// ---------------------------------------------------------------------------
// Form fields
// ---------------------------------------------------------------------------
const repoType = ref('file')
const storageType = ref('fs')
const storagePath = ref('/var/pantera/data')

// S3 alias selection
const allStorages = ref<StorageAlias[]>([])
const s3Storages = computed(() =>
  allStorages.value.filter(s => s.config?.type === 's3' || s.type === 's3'),
)
const selectedS3Alias = ref('')
const creatingNewS3 = ref(false)
const s3Bucket = ref('')
const s3Region = ref('')
const s3Endpoint = ref('')
const s3AliasName = ref('')

// Proxy fields — list of remotes (proxies may have multiple fallback upstreams)
interface RemoteEntry { url: string; username: string; password: string }
const remotes = ref<RemoteEntry[]>([{ url: '', username: '', password: '' }])

function addRemote() {
  remotes.value.push({ url: '', username: '', password: '' })
}

function removeRemote(idx: number) {
  if (remotes.value.length > 1) remotes.value.splice(idx, 1)
  else remotes.value[0] = { url: '', username: '', password: '' }
}

// Group fields — array of member repo names (edited as a reorderable list)
const groupMembers = ref<string[]>([])

function addMember() {
  groupMembers.value.push('')
}

function removeMember(idx: number) {
  groupMembers.value.splice(idx, 1)
}

function moveMemberUp(idx: number) {
  if (idx <= 0) return
  const arr = groupMembers.value
  ;[arr[idx - 1], arr[idx]] = [arr[idx], arr[idx - 1]]
}

function moveMemberDown(idx: number) {
  const arr = groupMembers.value
  if (idx >= arr.length - 1) return
  ;[arr[idx], arr[idx + 1]] = [arr[idx + 1], arr[idx]]
}

// State for compatible repos dropdown (group member selection)
const compatibleRepos = ref<RepoListItem[]>([])
const filteredRepos = ref<RepoListItem[]>([])

/**
 * Given a group type like "maven-group", return the compatible member types.
 * Rule: strip "-group" -> base; compatible = [base, base + "-proxy"]
 */
function compatibleTypes(groupType: string): string[] {
  const base = groupType.replace(/-group$/, '')
  return [base, `${base}-proxy`]
}

/**
 * Fetch repos compatible with the current group type from the API.
 */
async function fetchCompatibleRepos() {
  if (!repoType.value?.endsWith('-group')) return
  const types = compatibleTypes(repoType.value)
  try {
    const resp = await listRepos({ size: 500 })
    const all: RepoListItem[] = resp.items ?? []
    compatibleRepos.value = all.filter(r => types.includes(r.type))
  } catch (e) {
    console.error('Failed to fetch compatible repos', e)
    compatibleRepos.value = []
  }
}

/**
 * PrimeVue AutoComplete completeMethod — filters the pre-fetched list client-side.
 */
function searchRepos(event: { query: string }) {
  const q = event.query.toLowerCase()
  filteredRepos.value = compatibleRepos.value.filter(
    r => !groupMembers.value.includes(r.name) && r.name.toLowerCase().includes(q)
  )
}

// Create-member modal state
const showCreateMemberDialog = ref(false)
const newMemberType = ref('')
const newMemberName = ref('')
const newMemberCreating = ref(false)

async function createMemberRepo() {
  if (!newMemberName.value || !newMemberType.value) return
  newMemberCreating.value = true
  try {
    const { putRepo } = await import('@/api/repos')
    await putRepo(newMemberName.value, {
      repo: {
        type: newMemberType.value,
        storage: { type: 'fs' },
      },
    })
    groupMembers.value.push(newMemberName.value)
    await fetchCompatibleRepos()
    showCreateMemberDialog.value = false
    newMemberName.value = ''
    newMemberType.value = ''
  } catch (e: unknown) {
    console.error('Failed to create member repo', e)
  } finally {
    newMemberCreating.value = false
  }
}

// Cooldown
const cooldownEnabled = ref(false)
const cooldownDuration = ref('P30D')

// Computed type flags
const isProxy = computed(() => repoType.value.endsWith('-proxy'))
const isGroup = computed(() => repoType.value.endsWith('-group'))

const repoTypes = REPO_TYPE_CREATE_OPTIONS

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------
const isValid = computed<boolean>(() => {
  if (!repoType.value) return false
  if (isProxy.value && !remotes.value.some(r => r.url.trim())) return false
  if (isGroup.value && groupMembers.value.length === 0) return false
  if (cooldownEnabled.value && !cooldownDuration.value) return false
  return true
})

watch(isValid, v => emit('valid-change', v), { immediate: true })

// ---------------------------------------------------------------------------
// Storage aliases
// ---------------------------------------------------------------------------
async function loadStorages() {
  try {
    allStorages.value = await listStorages()
  } catch { /* ignore */ }
}

async function handleCreateS3Alias() {
  if (!s3AliasName.value || !s3Bucket.value || !s3Region.value) return
  try {
    await putStorage(s3AliasName.value, {
      type: 's3',
      bucket: s3Bucket.value,
      region: s3Region.value,
      endpoint: s3Endpoint.value || undefined,
    })
    notify.success('S3 storage created', s3AliasName.value)
    selectedS3Alias.value = s3AliasName.value
    creatingNewS3.value = false
    await loadStorages()
  } catch {
    notify.error('Failed to create S3 storage')
  }
}

onMounted(() => {
  loadStorages()
  if (repoType.value?.endsWith('-group')) fetchCompatibleRepos()
})

// Reset derivative proxy/group fields when type changes (only in create mode)
watch(repoType, () => {
  if (!props.readOnlyType) {
    remotes.value = [{ url: '', username: '', password: '' }]
    groupMembers.value = []
  }
  fetchCompatibleRepos()
})

// Reset S3 sub-fields when storage type switches
watch(storageType, (val) => {
  if (val === 's3') {
    selectedS3Alias.value = ''
    creatingNewS3.value = false
  }
})

// ---------------------------------------------------------------------------
// Decompose: initial-config → individual refs
// ---------------------------------------------------------------------------
function decomposeConfig(raw: RepoConfigEnvelope) {
  const repo = raw.repo

  repoType.value = repo.type ?? 'file'

  // Storage
  if (typeof repo.storage === 'string') {
    storageType.value = 's3'
    selectedS3Alias.value = repo.storage
  } else if (repo.storage?.type === 'fs') {
    storageType.value = 'fs'
    storagePath.value = repo.storage.path ?? ''
  } else if (repo.storage?.type === 's3') {
    storageType.value = 's3'
    // Inline S3 — treat as "new" so the user can review the fields.
    // We store the values but don't try to create an alias automatically.
    s3Bucket.value = repo.storage.bucket ?? ''
    s3Region.value = repo.storage.region ?? ''
    s3Endpoint.value = repo.storage.endpoint ?? ''
  }

  // Remotes (proxy repos store [{url, username?, password?}, ...] — can be multiple)
  if (Array.isArray(repo.remotes)
      && repo.remotes.length > 0
      && typeof repo.remotes[0] === 'object' && 'url' in (repo.remotes[0] as object)) {
    remotes.value = (repo.remotes as Array<{ url?: string; username?: string; password?: string }>)
      .map(r => ({
        url: r.url ?? '',
        username: r.username ?? '',
        password: r.password ?? '',
      }))
    if (remotes.value.length === 0) {
      remotes.value = [{ url: '', username: '', password: '' }]
    }
  } else {
    remotes.value = [{ url: '', username: '', password: '' }]
  }

  // Members (group repos — stored as array of name strings in the members field,
  // or — on older server versions — as an array of strings in the remotes field)
  const members: string[] = []
  if (Array.isArray(repo.members)) {
    for (const m of repo.members) {
      if (typeof m === 'string') members.push(m)
    }
  } else if (Array.isArray(repo.remotes)) {
    for (const m of repo.remotes) {
      if (typeof m === 'string') members.push(m)
    }
  }
  groupMembers.value = members

  // Cooldown
  cooldownEnabled.value = !!repo.cooldown
  cooldownDuration.value = repo.cooldown?.duration ?? 'P30D'
}

// Watch initialConfig and decompose whenever it arrives (handles async load)
watch(() => props.initialConfig, (val) => {
  if (val) decomposeConfig(val)
}, { immediate: true })

// ---------------------------------------------------------------------------
// Build: individual refs → RepoConfigEnvelope
// ---------------------------------------------------------------------------
function buildConfig(): RepoConfigEnvelope {
  let storage: RepoConfigEnvelope['repo']['storage']

  if (storageType.value === 's3' && selectedS3Alias.value) {
    storage = selectedS3Alias.value
  } else if (storageType.value === 's3' && (s3Bucket.value || creatingNewS3.value)) {
    storage = {
      type: 's3',
      bucket: s3Bucket.value || undefined,
      region: s3Region.value || undefined,
      endpoint: s3Endpoint.value || undefined,
    }
  } else {
    // fs or fallback
    storage = { type: 'fs', path: storagePath.value }
  }

  const repo: RepoConfigEnvelope['repo'] = {
    type: repoType.value,
    storage,
  }

  if (isProxy.value) {
    // Emit every non-empty remote entry, preserving order. Multi-remote proxies
    // (fallback upstreams) are fully round-tripped.
    const built = remotes.value
      .filter(r => r.url.trim())
      .map(r => {
        const out: { url: string; username?: string; password?: string } = { url: r.url.trim() }
        if (r.username.trim()) {
          out.username = r.username.trim()
          out.password = r.password
        }
        return out
      })
    if (built.length > 0) repo.remotes = built
  }

  if (isGroup.value && groupMembers.value.length > 0) {
    const members = groupMembers.value
      .map(m => m.trim())
      .filter(Boolean)
    repo.members = members
  }

  if (cooldownEnabled.value && cooldownDuration.value) {
    repo.cooldown = { duration: cooldownDuration.value }
  }

  return { repo }
}

// Emit updated config on any field change
function emitConfig() {
  emit('update:config', buildConfig())
}

// Watch all individual form fields and re-emit the built config.
// Arrays/objects are watched deep to catch edits within remote entries.
watch(
  [
    repoType, storageType, storagePath, selectedS3Alias,
    s3Bucket, s3Region, s3Endpoint,
    cooldownEnabled, cooldownDuration,
  ],
  () => { emitConfig() },
)
watch(remotes, () => { emitConfig() }, { deep: true })
watch(groupMembers, () => { emitConfig() }, { deep: true })
</script>

<template>
  <!-- Storage -->
  <Card class="shadow-sm">
    <template #title>Storage</template>
    <template #content>
      <div class="space-y-3">
        <div v-if="!readOnlyType">
          <label class="block text-sm font-medium mb-1">Type</label>
          <Select
            v-model="repoType"
            :options="repoTypes"
            optionLabel="label"
            optionValue="value"
            placeholder="Select type"
            class="w-full"
          />
        </div>

        <div>
          <label class="block text-xs text-gray-500 mb-1">Storage Type</label>
          <Select v-model="storageType" :options="['fs', 's3']" class="w-full" />
        </div>

        <div v-if="storageType === 'fs'">
          <label class="block text-xs text-gray-500 mb-1">Path</label>
          <InputText v-model="storagePath" placeholder="/var/pantera/data" class="w-full" />
        </div>

        <!-- S3: select existing alias or create new -->
        <template v-if="storageType === 's3'">
          <div v-if="s3Storages.length > 0 && !creatingNewS3">
            <label class="block text-xs text-gray-500 mb-1">S3 Storage</label>
            <Select
              v-model="selectedS3Alias"
              :options="s3Storages.map(s => s.name)"
              placeholder="Select S3 storage"
              class="w-full"
            />
            <p class="text-xs text-gray-400 mt-1">
              Or
              <button type="button" class="text-blue-500 hover:underline" @click="creatingNewS3 = true">
                create a new S3 storage
              </button>
            </p>
          </div>
          <div v-else>
            <p v-if="s3Storages.length === 0 && !creatingNewS3" class="text-sm text-gray-500 mb-2">
              No S3 storages configured yet. Create one below:
            </p>
            <div class="space-y-2">
              <div>
                <label class="block text-xs text-gray-500 mb-1">Alias Name</label>
                <InputText v-model="s3AliasName" placeholder="my-s3-storage" class="w-full" />
              </div>
              <div>
                <label class="block text-xs text-gray-500 mb-1">Bucket</label>
                <InputText v-model="s3Bucket" placeholder="my-bucket" class="w-full" />
              </div>
              <div>
                <label class="block text-xs text-gray-500 mb-1">Region</label>
                <InputText v-model="s3Region" placeholder="eu-west-1" class="w-full" />
              </div>
              <div>
                <label class="block text-xs text-gray-500 mb-1">Endpoint (optional)</label>
                <InputText v-model="s3Endpoint" placeholder="https://s3.amazonaws.com" class="w-full" />
              </div>
              <div class="flex gap-2">
                <Button
                  label="Create S3 Storage"
                  size="small"
                  :disabled="!s3AliasName || !s3Bucket || !s3Region"
                  @click="handleCreateS3Alias"
                />
                <Button
                  v-if="s3Storages.length > 0"
                  label="Cancel"
                  severity="secondary"
                  text
                  size="small"
                  @click="creatingNewS3 = false"
                />
              </div>
            </div>
          </div>
        </template>
      </div>
    </template>
  </Card>

  <!-- Proxy: Remote upstreams (list of fallback URLs) -->
  <Card v-if="isProxy" class="shadow-sm">
    <template #title>
      <div class="flex items-center justify-between">
        <span>Remote Upstreams</span>
        <span class="text-xs font-normal text-gray-500">
          {{ remotes.length }} {{ remotes.length === 1 ? 'remote' : 'remotes' }}
        </span>
      </div>
    </template>
    <template #content>
      <p class="text-xs text-gray-500 mb-3">
        Multiple remotes act as fallbacks: if the first returns 4xx/5xx, the next is tried in order.
      </p>
      <div class="space-y-3">
        <div
          v-for="(remote, idx) in remotes"
          :key="idx"
          class="border border-gray-200 dark:border-gray-700 rounded-md p-3 space-y-2 bg-gray-50 dark:bg-gray-800/50"
        >
          <div class="flex items-center justify-between">
            <span class="text-xs font-semibold text-gray-600 dark:text-gray-400">
              Remote #{{ idx + 1 }}
            </span>
            <Button
              icon="pi pi-times"
              severity="danger"
              text
              rounded
              size="small"
              :disabled="remotes.length === 1 && !remote.url && !remote.username"
              :aria-label="`Remove remote ${idx + 1}`"
              @click="removeRemote(idx)"
            />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">URL</label>
            <InputText
              v-model="remote.url"
              placeholder="https://repo1.maven.org/maven2"
              class="w-full"
            />
          </div>
          <div class="grid grid-cols-2 gap-2">
            <div>
              <label class="block text-xs text-gray-500 mb-1">Username (optional)</label>
              <InputText
                v-model="remote.username"
                placeholder="Anonymous"
                class="w-full"
                autocomplete="off"
              />
            </div>
            <div v-if="remote.username">
              <label class="block text-xs text-gray-500 mb-1">Password</label>
              <InputText
                v-model="remote.password"
                type="password"
                class="w-full"
                autocomplete="new-password"
              />
            </div>
          </div>
        </div>
        <Button
          icon="pi pi-plus"
          label="Add remote"
          severity="secondary"
          outlined
          size="small"
          @click="addRemote"
        />
      </div>
    </template>
  </Card>

  <!-- Group: Members (chips / list editor) -->
  <Card v-if="isGroup" class="shadow-sm">
    <template #title>
      <div class="flex items-center justify-between">
        <span>Group Members</span>
        <span class="text-xs font-normal text-gray-500">
          {{ groupMembers.length }} {{ groupMembers.length === 1 ? 'member' : 'members' }}
        </span>
      </div>
    </template>
    <template #content>
      <p class="text-xs text-gray-500 mb-3">
        Resolution order matters: the first matching member wins. Drag or remove to reorder.
      </p>
      <ul
        v-if="groupMembers.length > 0"
        class="divide-y divide-gray-200 dark:divide-gray-700 border border-gray-200 dark:border-gray-700 rounded-md mb-3"
      >
        <li
          v-for="(member, idx) in groupMembers"
          :key="idx"
          class="flex items-center gap-2 px-3 py-2 bg-white dark:bg-gray-900"
        >
          <span class="text-xs text-gray-400 w-6 tabular-nums">{{ idx + 1 }}.</span>
          <AutoComplete
            v-model="groupMembers[idx]"
            :suggestions="filteredRepos"
            optionLabel="name"
            field="name"
            @complete="searchRepos"
            @item-select="(e: any) => { groupMembers[idx] = e.value.name }"
            placeholder="Search repos..."
            class="flex-1"
            :dropdown="true"
            forceSelection
          >
            <template #option="{ option }">
              <div class="flex items-center gap-2">
                <span>{{ option.name }}</span>
                <Tag :value="option.type" severity="info" class="text-xs" />
              </div>
            </template>
          </AutoComplete>
          <Button
            icon="pi pi-arrow-up"
            text
            rounded
            size="small"
            :disabled="idx === 0"
            :aria-label="`Move ${member || 'member'} up`"
            @click="moveMemberUp(idx)"
          />
          <Button
            icon="pi pi-arrow-down"
            text
            rounded
            size="small"
            :disabled="idx === groupMembers.length - 1"
            :aria-label="`Move ${member || 'member'} down`"
            @click="moveMemberDown(idx)"
          />
          <Button
            icon="pi pi-times"
            severity="danger"
            text
            rounded
            size="small"
            :aria-label="`Remove ${member || 'member'}`"
            @click="removeMember(idx)"
          />
        </li>
      </ul>
      <div v-else class="text-sm text-gray-400 italic mb-3">
        No members yet — add at least one to enable saving.
      </div>
      <div class="flex items-center">
        <Button
          icon="pi pi-plus"
          label="Add member"
          severity="secondary"
          outlined
          size="small"
          @click="addMember"
        />
        <Button
          icon="pi pi-plus-circle"
          label="Create new"
          severity="info"
          outlined
          size="small"
          class="ml-2"
          @click="showCreateMemberDialog = true"
        />
      </div>

      <Dialog
        v-model:visible="showCreateMemberDialog"
        header="Create New Member Repository"
        :modal="true"
        :style="{ width: '500px' }"
      >
        <div class="flex flex-col gap-4">
          <div>
            <label class="block text-sm font-medium mb-1">Type</label>
            <Select
              v-model="newMemberType"
              :options="compatibleTypes(repoType).map(t => ({ label: t, value: t }))"
              optionLabel="label"
              optionValue="value"
              placeholder="Select type"
              class="w-full"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Name</label>
            <InputText v-model="newMemberName" placeholder="e.g. maven-central" class="w-full" />
          </div>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" @click="showCreateMemberDialog = false" />
          <Button
            label="Create & Add"
            icon="pi pi-check"
            :loading="newMemberCreating"
            :disabled="!newMemberName || !newMemberType"
            @click="createMemberRepo"
          />
        </template>
      </Dialog>
    </template>
  </Card>

  <!-- Cooldown (proxy only) -->
  <Card v-if="isProxy" class="shadow-sm">
    <template #title>Cooldown</template>
    <template #content>
      <div class="space-y-3">
        <div class="flex items-center gap-2">
          <Checkbox v-model="cooldownEnabled" :binary="true" inputId="cdEnabled" />
          <label for="cdEnabled" class="text-sm cursor-pointer">Enable cooldown period</label>
        </div>
        <div v-if="cooldownEnabled">
          <label class="block text-xs text-gray-500 mb-1">Duration (ISO 8601)</label>
          <InputText v-model="cooldownDuration" placeholder="P30D" class="w-48" />
          <p class="text-xs text-gray-400 mt-1">e.g. P30D = 30 days, P7D = 7 days, PT12H = 12 hours</p>
        </div>
      </div>
    </template>
  </Card>
</template>
