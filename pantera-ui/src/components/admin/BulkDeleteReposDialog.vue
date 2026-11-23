<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import { deleteRepo } from '@/api/repos'

export interface BulkDeleteFailure {
  name: string
  reason: string
  /** The server refused because the repository is a member of groups (409). */
  inGroups?: boolean
}

export interface BulkDeleteResult {
  /** Removed within the request (a server without a database). */
  deleted: string[]
  /** Gone at once; the data is purged in the background (server answered 202). */
  deleting: string[]
  failed: BulkDeleteFailure[]
}

const props = defineProps<{ visible: boolean; names: string[] }>()
const emit = defineEmits<{ 'update:visible': [boolean]; deleted: [BulkDeleteResult] }>()

// Two-way bridge for v-model:visible — PrimeVue's Dialog mutates the prop on
// its own (close button, ESC, backdrop) and that has to reach the parent.
const visibleModel = computed({ get: () => props.visible, set: v => emit('update:visible', v) })

const typed = ref('')
const force = ref(false)
const running = ref(false)
const failures = ref<BulkDeleteFailure[]>([])
// What the next run deletes: the selection when the dialog opens, then only
// the repositories that failed. The parent clears its selection after a run,
// so the list is kept here.
const targets = ref<string[]>([])

watch(() => props.visible, (open) => {
  if (open) {
    typed.value = ''
    force.value = false
    failures.value = []
    targets.value = [...props.names]
  }
}, { immediate: true })

const title = computed(() => `Delete ${targets.value.length} repositor${targets.value.length === 1 ? 'y' : 'ies'}`)
const blockedByGroups = computed(() => failures.value.some(f => f.inGroups))

async function run() {
  running.value = true
  const result: BulkDeleteResult = { deleted: [], deleting: [], failed: [] }
  // Sequential on purpose: the operator reads the outcome in list order, and
  // one refused repository must not stop the ones after it.
  for (const name of targets.value) {
    try {
      const r = await deleteRepo(name, force.value ? { force: true } : {})
      if (r.kind === 'deleted') result.deleted.push(name)
      else result.deleting.push(name)
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } }; message?: string }
      const failure: BulkDeleteFailure = { name, reason: err.response?.data?.message ?? err.message ?? 'failed' }
      if (err.response?.status === 409) failure.inGroups = true
      result.failed.push(failure)
    }
  }
  running.value = false
  failures.value = result.failed
  targets.value = result.failed.map(f => f.name)
  emit('deleted', result)
  if (result.failed.length === 0) emit('update:visible', false)
}
</script>

<template>
  <Dialog v-model:visible="visibleModel" :header="title" modal :style="{ width: '32rem' }">
    <p class="mb-2 text-sm">
      The repositories disappear at once. Their data is removed in the background after the
      restore window; until then each can be restored under Repository Jobs.
    </p>
    <ul class="mb-3 max-h-40 overflow-auto rounded border border-gray-200 p-2 font-mono text-sm dark:border-gray-700" data-testid="bulk-delete-targets">
      <li v-for="n in targets" :key="n">{{ n }}</li>
    </ul>
    <div class="mb-3 flex items-start gap-2">
      <Checkbox v-model="force" binary input-id="bulkDeleteForce" :disabled="running" data-testid="bulk-delete-force" />
      <label for="bulkDeleteForce" class="text-sm">
        Also remove them from the groups they are members of.
        <span class="block text-xs text-gray-500">Without this, a repository that is a group member is not deleted.</span>
      </label>
    </div>
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
    <p v-if="blockedByGroups && !force" class="mt-2 text-xs text-gray-500" data-testid="bulk-delete-force-hint">
      Tick the box above and press Delete again to delete the group members too.
    </p>
    <template #footer>
      <Button label="Cancel" text severity="secondary" @click="emit('update:visible', false)" />
      <Button
        label="Delete"
        severity="danger"
        :disabled="typed !== 'delete' || running || targets.length === 0"
        :loading="running"
        data-testid="bulk-delete-confirm"
        @click="run"
      />
    </template>
  </Dialog>
</template>
