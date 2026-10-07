<script setup lang="ts">
import Button from 'primevue/button'

defineProps<{ count: number }>()
const emit = defineEmits<{ clear: [] }>()
</script>

<template>
  <div
    v-if="count > 0"
    data-testid="selection-bar"
    class="flex flex-wrap items-center gap-3 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2 text-sm dark:border-blue-800 dark:bg-blue-900/20"
  >
    <span class="font-medium">{{ count }} selected</span>
    <slot />
    <Button label="Clear" text size="small" severity="secondary" class="ml-auto" @click="emit('clear')" />
  </div>
</template>
