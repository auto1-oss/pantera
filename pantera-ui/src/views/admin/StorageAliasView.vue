<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { listStorages, putStorage, deleteStorage } from '@/api/settings'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Dialog from 'primevue/dialog'
import Tag from 'primevue/tag'
import type { StorageAlias } from '@/types'

const notify = useNotificationStore()
const auth = useAuthStore()
const { visible: delVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()

const aliases = ref<StorageAlias[]>([])
const loading = ref(false)

// Form state
const formVisible = ref(false)
const formSaving = ref(false)
const formEdit = ref(false)
const formName = ref('')
const formType = ref('fs')

// FS fields
const fsPath = ref('')

// S3 fields
const s3Bucket = ref('')
const s3Region = ref('')
const s3Endpoint = ref('')
const s3CredType = ref('basic')
const s3AccessKey = ref('')
const s3SecretKey = ref('')

const storageTypes = [
  { label: 'File System (fs)', value: 'fs' },
  { label: 'S3 Compatible', value: 's3' },
]

const credentialTypes = [
  { label: 'Basic (Access Key)', value: 'basic' },
  { label: 'Default (AWS Chain)', value: 'default' },
]

const formValid = computed(() => {
  if (!formName.value.trim()) return false
  if (formType.value === 'fs') return !!fsPath.value.trim()
  if (formType.value === 's3') return !!s3Bucket.value.trim()
  return false
})

function storageLabel(type: string | undefined): string {
  if (type === 'fs') return 'File System'
  if (type === 's3') return 'S3'
  return type ?? 'Unknown'
}

function storageSeverity(type: string | undefined): string {
  if (type === 'fs') return 'info'
  if (type === 's3') return 'warn'
  return 'secondary'
}

function storageDetail(config: Record<string, unknown>): string {
  if (config?.type === 'fs') return (config.path as string) ?? ''
  if (config?.type === 's3') {
    const parts = [config.bucket as string]
    if (config.region) parts.push(config.region as string)
    if (config.endpoint) parts.push(config.endpoint as string)
    return parts.join(' / ')
  }
  return ''
}

async function load() {
  loading.value = true
  try { aliases.value = await listStorages() }
  finally { loading.value = false }
}

function resetForm() {
  formName.value = ''
  formType.value = 'fs'
  fsPath.value = ''
  s3Bucket.value = ''
  s3Region.value = ''
  s3Endpoint.value = ''
  s3CredType.value = 'basic'
  s3AccessKey.value = ''
  s3SecretKey.value = ''
}

function openCreate() {
  formEdit.value = false
  resetForm()
  formVisible.value = true
}

function openEdit(alias: StorageAlias) {
  formEdit.value = true
  formName.value = alias.name
  const cfg = alias.config ?? {}
  formType.value = (cfg.type as string) ?? 'fs'
  if (formType.value === 'fs') {
    fsPath.value = (cfg.path as string) ?? ''
  } else if (formType.value === 's3') {
    s3Bucket.value = (cfg.bucket as string) ?? ''
    s3Region.value = (cfg.region as string) ?? ''
    s3Endpoint.value = (cfg.endpoint as string) ?? ''
    const creds = cfg.credentials as Record<string, unknown> | undefined
    if (creds) {
      s3CredType.value = (creds.type as string) ?? 'basic'
      s3AccessKey.value = (creds.accessKeyId as string) ?? ''
      s3SecretKey.value = (creds.secretAccessKey as string) ?? ''
    } else {
      s3CredType.value = 'default'
      s3AccessKey.value = ''
      s3SecretKey.value = ''
    }
  }
  formVisible.value = true
}

function buildConfig(): Record<string, unknown> {
  if (formType.value === 'fs') {
    return { type: 'fs', path: fsPath.value.trim() }
  }
  const cfg: Record<string, unknown> = {
    type: 's3',
    bucket: s3Bucket.value.trim(),
  }
  if (s3Region.value.trim()) cfg.region = s3Region.value.trim()
  if (s3Endpoint.value.trim()) cfg.endpoint = s3Endpoint.value.trim()
  if (s3CredType.value === 'basic' && s3AccessKey.value.trim()) {
    cfg.credentials = {
      type: 'basic',
      accessKeyId: s3AccessKey.value.trim(),
      secretAccessKey: s3SecretKey.value.trim(),
    }
  }
  return cfg
}

async function handleSave() {
  formSaving.value = true
  try {
    await putStorage(formName.value.trim(), buildConfig())
    notify.success(formEdit.value ? 'Storage updated' : 'Storage created', formName.value)
    formVisible.value = false
    load()
  } catch { notify.error('Failed to save storage') }
  finally { formSaving.value = false }
}

async function handleDelete(name: string) {
  if (await confirmDel(name)) {
    try { await deleteStorage(name); notify.success('Storage deleted', name); load() }
    catch { notify.error('Failed to delete storage (may be in use by repositories)') }
  }
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Storage Configuration</h1>
          <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
            Define named storage configurations that repositories can reference by alias.
          </p>
        </div>
        <Button v-if="auth.hasAction('api_alias_permissions', 'create')" label="Add Storage" icon="pi pi-plus" @click="openCreate" />
      </div>

      <DataTable :value="aliases" :loading="loading" stripedRows class="shadow-sm">
        <template #empty>
          <div class="text-center py-8 text-gray-500">
            <i class="pi pi-database text-4xl mb-3 block" />
            <p class="text-lg font-medium">No storage configurations yet</p>
            <p class="text-sm mt-1">Add a storage configuration to get started.</p>
          </div>
        </template>
        <Column field="name" header="Name" sortable />
        <Column header="Type">
          <template #body="{ data }">
            <Tag :value="storageLabel(data.config?.type)" :severity="storageSeverity(data.config?.type)" />
          </template>
        </Column>
        <Column header="Details">
          <template #body="{ data }">
            <span class="text-sm text-gray-600 dark:text-gray-400 font-mono">{{ storageDetail(data.config) }}</span>
          </template>
        </Column>
        <Column header="Actions" class="w-32">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Button v-if="auth.hasAction('api_alias_permissions', 'create')" icon="pi pi-pencil" text size="small" @click="openEdit(data)" />
              <Button v-if="auth.hasAction('api_alias_permissions', 'delete')" icon="pi pi-trash" text size="small" severity="danger" @click="handleDelete(data.name)" />
            </div>
          </template>
        </Column>
      </DataTable>

      <!-- Delete Dialog -->
      <Dialog v-model:visible="delVisible" header="Confirm Delete" modal class="w-96">
        <p>Delete storage <strong>{{ targetName }}</strong>? Repositories using it will break.</p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectDel" />
          <Button label="Delete" severity="danger" @click="acceptDel" />
        </template>
      </Dialog>

      <!-- Create / Edit Dialog -->
      <Dialog v-model:visible="formVisible" :header="formEdit ? 'Edit Storage' : 'Add Storage'" modal class="w-[520px]">
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium mb-1">Name</label>
            <InputText v-model="formName" placeholder="e.g. default, remote_s3" class="w-full" :disabled="formEdit" />
          </div>

          <div>
            <label class="block text-sm font-medium mb-1">Type</label>
            <Select v-model="formType" :options="storageTypes" optionLabel="label" optionValue="value" class="w-full" />
          </div>

          <!-- File System fields -->
          <template v-if="formType === 'fs'">
            <div>
              <label class="block text-sm font-medium mb-1">Path</label>
              <InputText v-model="fsPath" placeholder="/var/pantera/data" class="w-full font-mono" />
              <small class="text-gray-500">Absolute path on the server filesystem.</small>
            </div>
          </template>

          <!-- S3 fields -->
          <template v-if="formType === 's3'">
            <div>
              <label class="block text-sm font-medium mb-1">Bucket <span class="text-red-500">*</span></label>
              <InputText v-model="s3Bucket" placeholder="my-pantera-bucket" class="w-full" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium mb-1">Region</label>
                <InputText v-model="s3Region" placeholder="us-east-1" class="w-full" />
              </div>
              <div>
                <label class="block text-sm font-medium mb-1">Endpoint</label>
                <InputText v-model="s3Endpoint" placeholder="https://s3.amazonaws.com" class="w-full" />
                <small class="text-gray-500">For MinIO or S3-compatible.</small>
              </div>
            </div>

            <div>
              <label class="block text-sm font-medium mb-1">Credentials</label>
              <Select v-model="s3CredType" :options="credentialTypes" optionLabel="label" optionValue="value" class="w-full" />
            </div>

            <template v-if="s3CredType === 'basic'">
              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="block text-sm font-medium mb-1">Access Key ID</label>
                  <InputText v-model="s3AccessKey" class="w-full font-mono" />
                </div>
                <div>
                  <label class="block text-sm font-medium mb-1">Secret Access Key</label>
                  <InputText v-model="s3SecretKey" type="password" class="w-full font-mono" />
                </div>
              </div>
            </template>
            <p v-else class="text-sm text-gray-500 italic">
              AWS SDK default credential chain will be used (env vars, instance profile, etc.)
            </p>
          </template>
        </div>

        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="formVisible = false" />
          <Button :label="formEdit ? 'Update' : 'Create'" :loading="formSaving" :disabled="!formValid" @click="handleSave" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
