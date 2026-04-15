<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { putRepo } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoConfigForm from '@/components/admin/RepoConfigForm.vue'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'
import { REPO_TYPE_CREATE_OPTIONS } from '@/utils/repoTypes'
import type { RepoConfigEnvelope } from '@/types/repo'

const router = useRouter()
const notify = useNotificationStore()

const name = ref('')
const saving = ref(false)
const advancedMode = ref(false)
const configJson = ref('{}')

// type shown in the top card (also drives the initial-config seed for RepoConfigForm)
const type = ref('file')
const repoTypes = REPO_TYPE_CREATE_OPTIONS

// Config built/received from the shared form
const config = ref<RepoConfigEnvelope | null>(null)
const isFormValid = ref(false)

// The form needs an "initial config" to know the starting type; we seed it
// whenever the user picks a type from the top card.
const seedConfig = computed<RepoConfigEnvelope>(() => ({
  repo: { type: type.value, storage: { type: 'fs', path: '/var/pantera/data' } },
}))

const isValid = computed(() =>
  !!name.value && (advancedMode.value ? true : isFormValid.value),
)

async function handleCreate() {
  saving.value = true
  try {
    let body: Record<string, unknown>
    if (advancedMode.value) {
      const parsed = JSON.parse(configJson.value)
      parsed.type = type.value
      body = { repo: parsed }
    } else {
      body = config.value as Record<string, unknown> ?? { repo: { type: type.value } }
    }
    await putRepo(name.value, body)
    notify.success('Repository created', name.value)
    router.push('/admin/repositories')
  } catch (e: unknown) {
    const axiosErr = e as { response?: { data?: { message?: string } }; message?: string }
    const msg = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Invalid config'
    notify.error('Failed to create repository', msg)
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

      <!-- Structured form -->
      <template v-else>
        <RepoConfigForm
          v-model:config="config"
          :initial-config="seedConfig"
          :read-only-type="true"
          @valid-change="isFormValid = $event"
        />
      </template>

      <div class="flex gap-3 pt-2">
        <Button label="Create" icon="pi pi-check" :loading="saving" :disabled="!isValid" @click="handleCreate" />
        <Button label="Cancel" severity="secondary" text @click="router.back()" />
      </div>
    </div>
  </AppLayout>
</template>
