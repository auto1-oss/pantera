<script setup lang="ts">
import { computed } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import type { BulkUnblockItem } from '@/api/cooldown'

// Confirmation only: the parent owns the request so the same code path
// serves the dialog, tests and any future keyboard shortcut.
const props = defineProps<{ visible: boolean; items: BulkUnblockItem[]; running?: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean]; confirm: [] }>()

const visibleModel = computed({ get: () => props.visible, set: v => emit('update:visible', v) })
const PREVIEW = 10
const preview = computed(() => props.items.slice(0, PREVIEW))
const more = computed(() => Math.max(0, props.items.length - PREVIEW))
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    :header="`Unblock ${items.length} artifact${items.length === 1 ? '' : 's'}`"
    modal
    :style="{ width: '32rem' }"
  >
    <p class="mb-2 text-sm">
      Each version is released until its cooldown window would have ended and is
      recorded in the history as a manual unblock.
    </p>
    <ul class="max-h-48 overflow-auto rounded border border-gray-200 p-2 font-mono text-xs dark:border-gray-700">
      <li v-for="it in preview" :key="`${it.repo}|${it.artifact}|${it.version}`">
        {{ it.repo }} · {{ it.artifact }}@{{ it.version }}
      </li>
      <li v-if="more > 0" class="text-gray-500">and {{ more }} more</li>
    </ul>
    <template #footer>
      <Button label="Cancel" text severity="secondary" :disabled="running" @click="emit('update:visible', false)" />
      <Button
        label="Unblock"
        icon="pi pi-unlock"
        severity="danger"
        :loading="running"
        :disabled="running || items.length === 0"
        data-testid="bulk-unblock-confirm"
        @click="emit('confirm')"
      />
    </template>
  </Dialog>
</template>
