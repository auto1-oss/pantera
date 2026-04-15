<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { listStorages, putStorage } from '@/api/settings'
import { useNotificationStore } from '@/stores/notifications'
import { REPO_TYPE_CREATE_OPTIONS } from '@/utils/repoTypes'
import type { RepoConfigEnvelope } from '@/types/repo'
import type { StorageAlias } from '@/types'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'

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

// Proxy fields
const remoteUrl = ref('')
const remoteUsername = ref('')
const remotePassword = ref('')

// Group fields (newline-separated for textarea; comma-separated also accepted)
const groupMembers = ref('')

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
  if (isProxy.value && !remoteUrl.value) return false
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

onMounted(() => { loadStorages() })

// Reset derivative proxy/group fields when type changes (only in create mode)
watch(repoType, () => {
  if (!props.readOnlyType) {
    remoteUrl.value = ''
    remoteUsername.value = ''
    remotePassword.value = ''
    groupMembers.value = ''
  }
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

  // Remotes (proxy repos store [{url, username?, password?}])
  const firstRemote = Array.isArray(repo.remotes) ? repo.remotes[0] : undefined
  if (firstRemote && typeof firstRemote === 'object' && 'url' in firstRemote) {
    remoteUrl.value = firstRemote.url ?? ''
    remoteUsername.value = firstRemote.username ?? ''
    remotePassword.value = firstRemote.password ?? ''
  }

  // Members (group repos — stored as array of name strings in the remotes
  // field OR in a dedicated members field depending on server version)
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
  groupMembers.value = members.join('\n')

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

  if (isProxy.value && remoteUrl.value) {
    const remote: { url: string; username?: string; password?: string } = {
      url: remoteUrl.value,
    }
    if (remoteUsername.value) {
      remote.username = remoteUsername.value
      remote.password = remotePassword.value
    }
    repo.remotes = [remote]
  }

  if (isGroup.value && groupMembers.value) {
    // Accept both comma and newline as separators
    const members = groupMembers.value
      .split(/[\n,]+/)
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

// Watch all individual form fields and re-emit the built config
watch(
  [
    repoType, storageType, storagePath, selectedS3Alias,
    s3Bucket, s3Region, s3Endpoint,
    remoteUrl, remoteUsername, remotePassword,
    groupMembers, cooldownEnabled, cooldownDuration,
  ],
  () => { emitConfig() },
)
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

  <!-- Proxy: Remote upstream -->
  <Card v-if="isProxy" class="shadow-sm">
    <template #title>Remote Upstream</template>
    <template #content>
      <div class="space-y-3">
        <div>
          <label class="block text-xs text-gray-500 mb-1">Remote URL</label>
          <InputText v-model="remoteUrl" placeholder="https://repo1.maven.org/maven2" class="w-full" />
        </div>
        <div>
          <label class="block text-xs text-gray-500 mb-1">Username (optional)</label>
          <InputText v-model="remoteUsername" placeholder="Leave empty for anonymous" class="w-full" />
        </div>
        <div v-if="remoteUsername">
          <label class="block text-xs text-gray-500 mb-1">Password</label>
          <InputText v-model="remotePassword" type="password" class="w-full" />
        </div>
      </div>
    </template>
  </Card>

  <!-- Group: Members -->
  <Card v-if="isGroup" class="shadow-sm">
    <template #title>Group Members</template>
    <template #content>
      <div>
        <label class="block text-xs text-gray-500 mb-1">
          Member repositories (one per line or comma-separated)
        </label>
        <Textarea v-model="groupMembers" rows="4" placeholder="maven-local&#10;maven-proxy" class="w-full font-mono text-sm" />
        <p class="text-xs text-gray-400 mt-1">
          Enter the names of repositories to include in this group.
        </p>
      </div>
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
