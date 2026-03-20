<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRepo, putRepo } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import Textarea from 'primevue/textarea'
import Button from 'primevue/button'

const props = defineProps<{ name: string }>()
const router = useRouter()
const notify = useNotificationStore()

const configJson = ref('')
const repoType = ref('')
const saving = ref(false)
const loading = ref(true)

onMounted(async () => {
  try {
    const raw = await getRepo(props.name)
    const repo = (raw as Record<string, unknown>).repo as Record<string, unknown> | undefined
    repoType.value = (repo?.type as string) ?? ''
    configJson.value = JSON.stringify(raw, null, 2)
  } catch {
    notify.error('Failed to load repository')
  } finally {
    loading.value = false
  }
})

async function handleSave() {
  saving.value = true
  try {
    const config = JSON.parse(configJson.value)
    await putRepo(props.name, config)
    notify.success('Repository updated', props.name)
    router.push('/admin/repositories')
  } catch (e: unknown) {
    notify.error('Failed to update', e instanceof Error ? e.message : 'Invalid JSON')
  } finally {
    saving.value = false
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

      <div v-if="!loading" class="bg-white dark:bg-gray-800 p-6 rounded-lg shadow-sm space-y-4">
        <div>
          <label class="block text-sm font-medium mb-1">Configuration (JSON)</label>
          <Textarea v-model="configJson" rows="16" class="w-full font-mono text-sm" />
        </div>
        <div class="flex gap-3">
          <Button label="Save" icon="pi pi-check" :loading="saving" @click="handleSave" />
          <Button label="Cancel" severity="secondary" text @click="router.back()" />
        </div>
      </div>
    </div>
  </AppLayout>
</template>
