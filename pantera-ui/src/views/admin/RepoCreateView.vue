<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { putRepo } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoConfigForm from '@/components/admin/RepoConfigForm.vue'
import RepoFormatPicker from '@/components/admin/RepoFormatPicker.vue'
import RepoNameField from '@/components/admin/RepoNameField.vue'
import Textarea from 'primevue/textarea'
import Button from 'primevue/button'
import Card from 'primevue/card'
import ToggleSwitch from 'primevue/toggleswitch'
import type { RepoConfigEnvelope } from '@/types/repo'

const router = useRouter()
const notify = useNotificationStore()

// Step 1: format. Nothing else renders until a card is picked.
const type = ref<string | null>(null)

// Step 2: name + configuration.
const name = ref('')
const nameValid = ref(false)
const config = ref<RepoConfigEnvelope | null>(null)
const formValid = ref(false)
const advancedMode = ref(false)
const configJson = ref('{}')
const saving = ref(false)
const saveError = ref('')

// The shared form needs an initial config to know the starting type; it is
// re-seeded whenever a different format card is picked.
const seedConfig = computed<RepoConfigEnvelope | null>(() =>
  type.value
    ? { repo: { type: type.value, storage: { type: 'fs', path: '/var/pantera/data' } } }
    : null,
)
watch(type, () => { saveError.value = '' })

const isValid = computed(() =>
  !!type.value && nameValid.value && (advancedMode.value || formValid.value),
)

async function handleCreate() {
  if (!type.value) return
  saving.value = true
  saveError.value = ''
  try {
    let body: Record<string, unknown>
    if (advancedMode.value) {
      const parsed = JSON.parse(configJson.value) as Record<string, unknown>
      parsed.type = type.value
      body = { repo: parsed }
    } else {
      body = (config.value as Record<string, unknown> | null) ?? { repo: { type: type.value } }
    }
    await putRepo(name.value, body)
    notify.success('Repository created', name.value)
    router.push({ path: '/admin/repositories', query: { highlight: name.value } })
  } catch (e: unknown) {
    const axiosErr = e as { response?: { data?: { message?: string } }; message?: string }
    saveError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Invalid configuration'
    notify.error('Failed to create repository', saveError.value)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <AppLayout>
    <div class="max-w-3xl space-y-5">
      <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Create Repository</h1>

      <Card class="shadow-sm">
        <template #title>1. Format</template>
        <template #subtitle>Pick what the repository will serve.</template>
        <template #content>
          <RepoFormatPicker v-model="type" />
        </template>
      </Card>

      <template v-if="type && seedConfig">
        <Card class="shadow-sm">
          <template #title>
            <div class="flex items-center justify-between">
              <span>2. Name and configuration</span>
              <label class="flex items-center gap-2 text-sm font-normal text-gray-500">
                <ToggleSwitch v-model="advancedMode" data-testid="json-toggle" />
                Edit as JSON
              </label>
            </div>
          </template>
          <template #content>
            <RepoNameField v-model="name" @valid-change="nameValid = $event" />
          </template>
        </Card>

        <Card v-if="advancedMode" class="shadow-sm">
          <template #title>Configuration (JSON)</template>
          <template #content>
            <Textarea v-model="configJson" rows="12" class="w-full font-mono text-sm" />
          </template>
        </Card>
        <RepoConfigForm
          v-else
          v-model:config="config"
          :initial-config="seedConfig"
          :read-only-type="true"
          @valid-change="formValid = $event"
        />

        <div
          v-if="saveError"
          data-testid="create-error"
          class="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
        >
          {{ saveError }}
        </div>

        <div class="flex gap-3 pt-2">
          <Button
            label="Create"
            icon="pi pi-check"
            :loading="saving"
            :disabled="!isValid || saving"
            data-testid="create-btn"
            @click="handleCreate"
          />
          <Button label="Cancel" severity="secondary" text @click="router.back()" />
        </div>
      </template>
    </div>
  </AppLayout>
</template>
