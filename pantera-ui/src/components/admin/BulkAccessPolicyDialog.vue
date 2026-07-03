<template>
  <Dialog
    v-model:visible="visibleModel"
    header="Set anonymous-access policy"
    :modal="true"
    :style="{ width: '32rem' }"
  >
    <div class="space-y-4">
      <p class="text-sm">
        Apply to <b>{{ scopeDescription }}</b>.
      </p>

      <div class="flex items-center gap-2">
        <ToggleSwitch v-model="setRead" input-id="setRead" />
        <label for="setRead" class="text-sm">
          Set <code>anonymous_read</code> to
        </label>
        <Checkbox
          v-model="anonymousRead"
          :binary="true"
          :disabled="!setRead"
          input-id="anonReadVal"
        />
        <span class="text-xs text-gray-500">
          {{ anonymousRead ? 'allowed' : 'rejected (401)' }}
        </span>
      </div>

      <div class="flex items-center gap-2">
        <ToggleSwitch v-model="setWrite" input-id="setWrite" />
        <label for="setWrite" class="text-sm">
          Set <code>anonymous_write</code> to
        </label>
        <Checkbox
          v-model="anonymousWrite"
          :binary="true"
          :disabled="!setWrite"
          input-id="anonWriteVal"
        />
        <span class="text-xs text-gray-500">
          {{ anonymousWrite ? 'allowed' : 'rejected (401)' }}
        </span>
      </div>

      <Message v-if="warnHostedRead" severity="warn" :closable="false">
        You are about to <b>allow anonymous reads</b> on hosted repositories.
        Hosted repos usually contain internal artifacts that should require
        credentials. Confirm this is intentional.
      </Message>
    </div>
    <template #footer>
      <Button
        label="Cancel"
        severity="secondary"
        text
        @click="emit('update:visible', false)"
      />
      <Button
        label="Apply"
        severity="primary"
        :disabled="!setRead && !setWrite"
        @click="submit"
      />
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Message from 'primevue/message'
import ToggleSwitch from 'primevue/toggleswitch'
import {
  bulkUpdateAccessPolicy,
  type BulkAccessPolicyRequest,
  type BulkAccessPolicyResult,
} from '@/api/repos'

const props = defineProps<{
  visible: boolean
  /** Selector type — controls scope of the operation. */
  selectorType: 'hosted' | 'proxy' | 'group' | 'all'
  /** Optional explicit list — intersected with selectorType server-side. */
  selectedNames?: string[]
  /** Total count for the scope description ("17 hosted repositories"). */
  scopeCount: number
}>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'applied', r: BulkAccessPolicyResult): void
}>()

// Two-way bridge for v-model:visible — PrimeVue's Dialog mutates the prop
// on its own (close button, ESC, backdrop click) and we have to forward
// those back up to the parent.
const visibleModel = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v),
})

const setRead = ref(false)
const setWrite = ref(false)
const anonymousRead = ref(false)
const anonymousWrite = ref(false)

const scopeDescription = computed(() => {
  if (props.selectedNames && props.selectedNames.length > 0) {
    const n = props.selectedNames.length
    return `${n} selected repository${n === 1 ? '' : 'ies'}`
  }
  const noun = props.scopeCount === 1 ? 'repository' : 'repositories'
  const typeLabel = props.selectorType === 'all' ? '' : `${props.selectorType} `
  return `all ${props.scopeCount} ${typeLabel}${noun}`.replace(/\s+/g, ' ').trim()
})

const warnHostedRead = computed(() =>
  setRead.value
  && anonymousRead.value === true
  && (props.selectorType === 'hosted' || props.selectorType === 'all'),
)

// Reset state every time the dialog opens — operators should not accidentally
// re-apply the previous run's settings on a different selection.
watch(() => props.visible, (open) => {
  if (open) {
    setRead.value = false
    setWrite.value = false
    anonymousRead.value = false
    anonymousWrite.value = false
  }
})

async function submit() {
  const body: BulkAccessPolicyRequest = {
    selector: {
      type: props.selectorType,
      ...(props.selectedNames && props.selectedNames.length > 0
        ? { names: props.selectedNames }
        : {}),
    },
    ...(setRead.value  ? { anonymous_read:  anonymousRead.value }  : {}),
    ...(setWrite.value ? { anonymous_write: anonymousWrite.value } : {}),
  }
  const result = await bulkUpdateAccessPolicy(body)
  emit('applied', result)
  emit('update:visible', false)
}
</script>
