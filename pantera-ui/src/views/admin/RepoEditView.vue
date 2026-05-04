<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRepo, putRepo } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import RepoConfigForm from '@/components/admin/RepoConfigForm.vue'
import PrefetchPanel from '@/components/repo/PrefetchPanel.vue'
import Button from 'primevue/button'
import type { RepoConfigEnvelope } from '@/types/repo'

const props = defineProps<{ name: string }>()
const router = useRouter()
const notify = useNotificationStore()

const initialConfig = ref<RepoConfigEnvelope | null>(null)
const config = ref<RepoConfigEnvelope | null>(null)
const repoType = ref('')
const isValid = ref(false)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')

// Prefetch toggle state — extracted from the loaded JSONB config and
// surfaced to the PrefetchPanel. The panel owns the round-trip to
// PATCH /repositories/:name {settings.prefetch} and tells us via
// update:enabled when it changes so we keep the local mirror in sync
// (so that closing/reopening the page reflects the new value without
// a refetch).
const prefetchEnabled = ref<boolean | undefined>(undefined)

// Pre-fetch is only meaningful for repos that proxy upstreams. Hosted
// repos (file/raw/maven hosted, etc.) have no upstream to warm from,
// so we hide the panel there to avoid surfacing a no-op toggle.
const showPrefetchPanel = computed(
  () => repoType.value.endsWith('-proxy') || repoType.value.endsWith('-group'),
)

/**
 * Decode {@code repo.settings.prefetch} from the loaded envelope.
 * Tolerates the field being absent (returns undefined → panel renders
 * with the proxy default of "on" for proxy repos, "off" for others).
 */
function readPrefetchEnabled(envelope: RepoConfigEnvelope): boolean | undefined {
  const raw = (envelope.repo as unknown as { settings?: { prefetch?: unknown } })
    .settings?.prefetch
  if (typeof raw === 'boolean') return raw
  if (typeof raw === 'string') return raw === 'true'
  return undefined
}

onMounted(async () => {
  try {
    const raw = await getRepo(props.name)
    const envelope = raw as RepoConfigEnvelope
    repoType.value = (envelope.repo?.type as string) ?? ''
    initialConfig.value = envelope
    config.value = envelope
    prefetchEnabled.value = readPrefetchEnabled(envelope)
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } }; message?: string }
    loadError.value = axiosErr.response?.data?.message ?? axiosErr.message ?? 'Unknown error'
    notify.error('Failed to load repository')
  } finally {
    loading.value = false
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

        <PrefetchPanel
          v-if="showPrefetchPanel"
          :name="name"
          :initial-enabled="prefetchEnabled"
          @update:enabled="prefetchEnabled = $event"
        />

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
      </template>
    </div>
  </AppLayout>
</template>
