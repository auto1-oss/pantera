<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { deleteRepo } from '@/api/repos'

export interface BulkDeleteResult {
  deleted: string[]
  /** Large repositories are removed in the background (server answered 202). */
  deleting: string[]
  failed: Array<{ name: string; reason: string }>
}

const props = defineProps<{ visible: boolean; names: string[] }>()
const emit = defineEmits<{ 'update:visible': [boolean]; deleted: [BulkDeleteResult] }>()

// Two-way bridge for v-model:visible — PrimeVue's Dialog mutates the prop on
// its own (close button, ESC, backdrop) and that has to reach the parent.
const visibleModel = computed({ get: () => props.visible, set: v => emit('update:visible', v) })

const typed = ref('')
const running = ref(false)
const failures = ref<Array<{ name: string; reason: string }>>([])

watch(() => props.visible, (open) => {
  if (open) {
    typed.value = ''
    failures.value = []
  }
})

const title = computed(() => `Delete ${props.names.length} repositor${props.names.length === 1 ? 'y' : 'ies'}`)

async function run() {
  running.value = true
  const result: BulkDeleteResult = { deleted: [], deleting: [], failed: [] }
  // Sequential on purpose: each delete can be a heavy storage operation and
  // the operator reads the outcome in list order.
  for (const name of props.names) {
    try {
      const r = await deleteRepo(name)
      if (r === 'deleting') result.deleting.push(name)
      else result.deleted.push(name)
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } }; message?: string }
      result.failed.push({ name, reason: err.response?.data?.message ?? err.message ?? 'failed' })
    }
  }
  running.value = false
  failures.value = result.failed
  emit('deleted', result)
  if (result.failed.length === 0) emit('update:visible', false)
}
</script>

<template>
  <Dialog v-model:visible="visibleModel" :header="title" modal :style="{ width: '32rem' }">
    <p class="mb-2 text-sm">
      This removes the repositories and their stored artifacts. It cannot be undone.
    </p>
    <ul class="mb-3 max-h-40 overflow-auto rounded border border-gray-200 p-2 font-mono text-sm dark:border-gray-700">
      <li v-for="n in names" :key="n">{{ n }}</li>
    </ul>
    <label for="bulkDeleteConfirm" class="mb-1 block text-sm">Type <b>delete</b> to confirm</label>
    <InputText
      id="bulkDeleteConfirm"
      v-model="typed"
      class="w-full"
      autocomplete="off"
      data-testid="bulk-delete-input"
    />
    <ul v-if="failures.length" class="mt-3 text-sm text-red-700 dark:text-red-300" data-testid="bulk-delete-failures">
      <li v-for="f in failures" :key="f.name">{{ f.name }}: {{ f.reason }}</li>
    </ul>
    <template #footer>
      <Button label="Cancel" text severity="secondary" @click="emit('update:visible', false)" />
      <Button
        label="Delete"
        severity="danger"
        :disabled="typed !== 'delete' || running"
        :loading="running"
        data-testid="bulk-delete-confirm"
        @click="run"
      />
    </template>
  </Dialog>
</template>
