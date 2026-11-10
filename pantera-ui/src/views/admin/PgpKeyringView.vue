<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { listPgpKeys, uploadPgpKey, deletePgpKey } from '@/api/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Dialog from 'primevue/dialog'
import type { PgpKey } from '@/types'

const notify = useNotificationStore()
const { visible: delVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()

const keys = ref<PgpKey[]>([])
const loading = ref(false)
const loadError = ref('')

// Add-key dialog state
const formVisible = ref(false)
const formSaving = ref(false)
const formError = ref('')
const armoredKey = ref('')
const description = ref('')

const formValid = computed(() => armoredKey.value.trim().length > 0)

function extractErrorMessage(err: unknown): string {
  const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
  return axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
}

function formatUploadedAt(iso: string): string {
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString()
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    keys.value = await listPgpKeys()
  } catch (err: unknown) {
    loadError.value = extractErrorMessage(err)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  armoredKey.value = ''
  description.value = ''
  formError.value = ''
}

function openAdd() {
  resetForm()
  formVisible.value = true
}

async function handleUpload() {
  formSaving.value = true
  formError.value = ''
  try {
    const inserted = await uploadPgpKey(armoredKey.value, description.value)
    notify.success(
      inserted.length > 1 ? `${inserted.length} keys added` : 'Key added',
      inserted.map(k => k.key_id_hex).join(', '),
    )
    formVisible.value = false
    await load()
  } catch (err: unknown) {
    formError.value = extractErrorMessage(err)
  } finally {
    formSaving.value = false
  }
}

async function handleDelete(key: PgpKey) {
  if (await confirmDel(key.key_id_hex)) {
    try {
      await deletePgpKey(key.key_id_hex)
      notify.success('PGP key deleted', key.key_id_hex)
      await load()
    } catch (err: unknown) {
      notify.error('Failed to delete PGP key', extractErrorMessage(err))
    }
  }
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Maven PGP Keyring</h1>
          <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
            Trusted public keys used to verify Maven artifact signatures (<code>.asc</code>
            files) for repositories with signature verification enabled. This is separate
            from any other credentials or secrets configured elsewhere.
          </p>
        </div>
        <Button label="Add Key" icon="pi pi-plus" @click="openAdd" />
      </div>

      <div
        v-if="loadError"
        class="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-4 text-sm text-red-700 dark:text-red-300"
      >
        Failed to load PGP keys: {{ loadError }}
      </div>

      <DataTable v-else :value="keys" :loading="loading" striped-rows class="shadow-sm">
        <template #empty>
          <div class="text-center py-8 text-gray-500">
            <i class="pi pi-key text-4xl mb-3 block" />
            <p class="text-lg font-medium">No PGP keys registered</p>
            <p class="text-sm mt-1">Add a trusted public key to enable signature verification.</p>
          </div>
        </template>
        <Column header="Key ID">
          <template #body="{ data }">
            <span class="text-sm font-mono text-gray-700 dark:text-gray-300">{{ data.key_id_hex }}</span>
          </template>
        </Column>
        <Column header="Fingerprint">
          <template #body="{ data }">
            <span class="text-xs font-mono text-gray-500 dark:text-gray-400 break-all">{{ data.fingerprint }}</span>
          </template>
        </Column>
        <Column field="description" header="Description">
          <template #body="{ data }">
            <span class="text-sm text-gray-600 dark:text-gray-400">{{ data.description || '—' }}</span>
          </template>
        </Column>
        <Column header="Uploaded By">
          <template #body="{ data }">
            <span class="text-sm text-gray-600 dark:text-gray-400">{{ data.uploaded_by }}</span>
          </template>
        </Column>
        <Column header="Uploaded At">
          <template #body="{ data }">
            <span class="text-sm text-gray-500 dark:text-gray-400">{{ formatUploadedAt(data.uploaded_at) }}</span>
          </template>
        </Column>
        <Column header="Actions" class="w-24">
          <template #body="{ data }">
            <Button icon="pi pi-trash" text size="small" severity="danger" @click="handleDelete(data)" />
          </template>
        </Column>
      </DataTable>

      <!-- Delete Dialog -->
      <Dialog v-model:visible="delVisible" header="Confirm Delete" modal class="w-96">
        <p>
          Delete PGP key <strong class="font-mono">{{ targetName }}</strong>?
          Artifacts previously signed by this key will fail signature
          verification immediately.
        </p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectDel" />
          <Button label="Delete" severity="danger" @click="acceptDel" />
        </template>
      </Dialog>

      <!-- Add Key Dialog -->
      <Dialog v-model:visible="formVisible" header="Add PGP Key" modal class="w-[560px]">
        <div class="space-y-4">
          <div
            v-if="formError"
            class="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-3 text-sm text-red-700 dark:text-red-300"
          >
            {{ formError }}
          </div>

          <div>
            <label class="block text-sm font-medium mb-1">Public Key (ASCII-armored)</label>
            <Textarea
              v-model="armoredKey"
              rows="10"
              class="w-full font-mono text-xs"
              placeholder="-----BEGIN PGP PUBLIC KEY BLOCK-----&#10;...&#10;-----END PGP PUBLIC KEY BLOCK-----"
            />
            <small class="text-gray-500">
              Paste the full armored public key block. A block containing a
              master key plus sub-keys registers one row per key found.
            </small>
          </div>

          <div>
            <label class="block text-sm font-medium mb-1">Description (optional)</label>
            <InputText v-model="description" placeholder="e.g. release signing key for team X" class="w-full" />
          </div>
        </div>

        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="formVisible = false" />
          <Button label="Add Key" :loading="formSaving" :disabled="!formValid" @click="handleUpload" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
