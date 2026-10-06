<script setup lang="ts">
import { computed } from 'vue'
import Dialog from 'primevue/dialog'

export interface BulkDeleteResult {
  deleted: string[]
  deleting: string[]
  failed: Array<{ name: string; reason: string }>
}

const props = defineProps<{ visible: boolean; names: string[] }>()
const emit = defineEmits<{ 'update:visible': [boolean]; deleted: [BulkDeleteResult] }>()
const visibleModel = computed({ get: () => props.visible, set: v => emit('update:visible', v) })
</script>

<template>
  <Dialog v-model:visible="visibleModel" header="Delete repositories" modal />
</template>
