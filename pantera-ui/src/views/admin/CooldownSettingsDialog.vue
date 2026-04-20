<script setup lang="ts">
import { ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Checkbox from 'primevue/checkbox'
import Button from 'primevue/button'
import { getCooldownConfig, updateCooldownConfig } from '@/api/settings'
import type { CooldownConfig } from '@/types'

// Compact settings dialog that edits the four global cooldown tunables
// (`enabled`, `minimum_allowed_age`, `history_retention_days`,
// `cleanup_batch_limit`). Per-repo-type overrides remain in
// SettingsView; this dialog is a quick-access editor embedded in
// CooldownView next to the artifact lists.
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

// Form buffer. We keep it as a partial CooldownConfig so the watcher
// below can overlay it from the server response without losing
// repo_types overrides the user has configured elsewhere.
const form = ref<CooldownConfig>({
  enabled: false,
  minimum_allowed_age: '',
})
const error = ref<string | null>(null)
const saving = ref(false)
const loading = ref(false)

// Reload form every time the dialog opens; this keeps the view in
// sync with hot-reloaded backend state without requiring a page
// refresh. `immediate: true` handles the case where the dialog is
// mounted already open (v-model starts true) — rare in normal flow
// but exercised by tests.
watch(visible, async v => {
  if (!v) return
  error.value = null
  loading.value = true
  try {
    const cfg = await getCooldownConfig()
    form.value = { ...cfg }
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'Failed to load settings'
  } finally {
    loading.value = false
  }
}, { immediate: true })

async function save() {
  saving.value = true
  error.value = null
  try {
    await updateCooldownConfig(form.value)
    emit('saved')
    visible.value = false
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'Save failed'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    header="Cooldown settings"
    :style="{ width: '32rem' }"
  >
    <div v-if="loading" class="text-sm text-gray-500 py-4">Loading...</div>
    <div v-else class="flex flex-col gap-4">
      <label class="flex items-center gap-2">
        <Checkbox v-model="form.enabled" binary />
        <span class="text-sm">Cooldown enabled globally</span>
      </label>

      <div>
        <label class="block text-sm font-medium mb-1">Minimum allowed age</label>
        <InputText
          v-model="form.minimum_allowed_age"
          placeholder="e.g. 7d, 24h, 30m"
          class="w-40"
        />
        <small class="block text-gray-500 mt-1">
          Reject artifacts published less than this duration ago.
        </small>
      </div>

      <div>
        <label class="block text-sm font-medium mb-1">History retention (days)</label>
        <InputNumber
          v-model="form.history_retention_days"
          :min="1"
          :max="3650"
          show-buttons
          class="w-40"
        />
        <small class="block text-gray-500 mt-1">
          How long to keep archived blocks before automatic purge. Default 90.
        </small>
      </div>

      <div>
        <label class="block text-sm font-medium mb-1">Cleanup batch limit</label>
        <InputNumber
          v-model="form.cleanup_batch_limit"
          :min="1"
          :max="100000"
          show-buttons
          class="w-40"
        />
        <small class="block text-gray-500 mt-1">
          Max rows moved from live to history per cleanup tick. Default 10000.
        </small>
      </div>

      <div v-if="error" class="text-red-600 text-sm" data-testid="dialog-error">
        {{ error }}
      </div>
    </div>
    <template #footer>
      <Button label="Cancel" severity="secondary" text @click="visible = false" />
      <Button label="Save" icon="pi pi-save" :loading="saving" @click="save" />
    </template>
  </Dialog>
</template>
