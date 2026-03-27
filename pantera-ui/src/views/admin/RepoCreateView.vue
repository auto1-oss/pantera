<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { putRepo } from '@/api/repos'
import { listStorages, putStorage } from '@/api/settings'
import { useNotificationStore } from '@/stores/notifications'
import { REPO_TYPE_CREATE_OPTIONS } from '@/utils/repoTypes'
import AppLayout from '@/components/layout/AppLayout.vue'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'
import type { StorageAlias } from '@/types'

const router = useRouter()
const notify = useNotificationStore()

const name = ref('')
const type = ref('file')
const saving = ref(false)
const advancedMode = ref(false)
const configJson = ref('{}')

// Storage
const storagePath = ref('/var/pantera/data')
const storageType = ref('fs')
const storageTypes = ['fs', 's3']

// S3 storage aliases
const allStorages = ref<StorageAlias[]>([])
const s3Storages = computed(() =>
  allStorages.value.filter(s => s.config?.type === 's3' || s.type === 's3')
)
const selectedS3Alias = ref('')
const creatingNewS3 = ref(false)
const s3Bucket = ref('')
const s3Region = ref('')
const s3Endpoint = ref('')
const s3AliasName = ref('')

// Proxy settings
const remoteUrl = ref('')
const remoteUsername = ref('')
const remotePassword = ref('')

// Group settings (comma-separated member repo names)
const groupMembers = ref('')

// Cooldown
const cooldownEnabled = ref(false)
const cooldownDuration = ref('P30D')

const repoTypes = REPO_TYPE_CREATE_OPTIONS

const isProxy = computed(() => type.value.endsWith('-proxy'))
const isGroup = computed(() => type.value.endsWith('-group'))
const isLocal = computed(() => !isProxy.value && !isGroup.value)

// Reset form fields when type changes
watch(type, () => {
  remoteUrl.value = ''
  remoteUsername.value = ''
  remotePassword.value = ''
  groupMembers.value = ''
})

// When switching to S3, reset S3 fields
watch(storageType, (val) => {
  if (val === 's3') {
    selectedS3Alias.value = ''
    creatingNewS3.value = false
  }
})

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

function buildConfig(): Record<string, unknown> {
  if (advancedMode.value) {
    const config = JSON.parse(configJson.value)
    config.type = type.value
    return config
  }
  let storage: Record<string, unknown> | string
  if (storageType.value === 's3' && selectedS3Alias.value) {
    storage = selectedS3Alias.value
  } else if (storageType.value === 'fs') {
    storage = { type: 'fs', path: storagePath.value }
  } else {
    storage = { type: storageType.value }
  }
  const config: Record<string, unknown> = {
    type: type.value,
    storage,
  }
  if (isProxy.value && remoteUrl.value) {
    const remote: Record<string, unknown> = { url: remoteUrl.value }
    if (remoteUsername.value) {
      remote.username = remoteUsername.value
      remote.password = remotePassword.value
    }
    config.remotes = [remote]
  }
  if (isGroup.value && groupMembers.value) {
    config.remotes = groupMembers.value.split(',').map(m => m.trim()).filter(Boolean)
  }
  if (cooldownEnabled.value) {
    config.cooldown = { duration: cooldownDuration.value }
  }
  return config
}

async function handleCreate() {
  saving.value = true
  try {
    const config = buildConfig()
    await putRepo(name.value, { repo: config })
    notify.success('Repository created', name.value)
    router.push('/admin/repositories')
  } catch (e: unknown) {
    notify.error('Failed to create repository', e instanceof Error ? e.message : 'Invalid config')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <AppLayout>
    <div class="max-w-2xl space-y-5">
      <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Create Repository</h1>

      <Card class="shadow-sm">
        <template #content>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium mb-1">Repository Name</label>
              <InputText v-model="name" placeholder="my-repo" class="w-full" />
            </div>

            <div>
              <label class="block text-sm font-medium mb-1">Type</label>
              <Select
                v-model="type"
                :options="repoTypes"
                optionLabel="label"
                optionValue="value"
                placeholder="Select type"
                class="w-full"
              />
            </div>

            <div class="flex items-center gap-2 pt-2">
              <Checkbox v-model="advancedMode" :binary="true" inputId="advMode" />
              <label for="advMode" class="text-sm text-gray-500 cursor-pointer">Advanced mode (raw JSON)</label>
            </div>
          </div>
        </template>
      </Card>

      <!-- Advanced JSON mode -->
      <Card v-if="advancedMode" class="shadow-sm">
        <template #title>Configuration (JSON)</template>
        <template #content>
          <Textarea v-model="configJson" rows="12" class="w-full font-mono text-sm" />
        </template>
      </Card>

      <!-- Form-based config -->
      <template v-else>
        <!-- Storage -->
        <Card class="shadow-sm">
          <template #title>Storage</template>
          <template #content>
            <div class="space-y-3">
              <div>
                <label class="block text-xs text-gray-500 mb-1">Storage Type</label>
                <Select v-model="storageType" :options="storageTypes" class="w-full" />
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
                    Or <button type="button" class="text-blue-500 hover:underline" @click="creatingNewS3 = true">create a new S3 storage</button>
                  </p>
                </div>
                <div v-else>
                  <p v-if="s3Storages.length === 0" class="text-sm text-gray-500 mb-2">No S3 storages configured yet. Create one below:</p>
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
              <label class="block text-xs text-gray-500 mb-1">Member repositories (comma-separated)</label>
              <InputText v-model="groupMembers" placeholder="maven-local, maven-proxy" class="w-full" />
              <p class="text-xs text-gray-400 mt-1">Enter the names of repositories to include in this group.</p>
            </div>
          </template>
        </Card>

        <!-- Cooldown -->
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

      <div class="flex gap-3 pt-2">
        <Button label="Create" icon="pi pi-check" :loading="saving" :disabled="!name" @click="handleCreate" />
        <Button label="Cancel" severity="secondary" text @click="router.back()" />
      </div>
    </div>
  </AppLayout>
</template>
