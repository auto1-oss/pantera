<script setup lang="ts">
import { techLabel, subtypeLabel, repoTypeColor } from '@/utils/repoTypes'

defineProps<{
  type: string
  size?: 'sm' | 'md'
}>()
</script>

<template>
  <span class="inline-flex items-center gap-1.5 flex-shrink-0">
    <!-- Technology: blue -->
    <span
      class="inline-flex items-center gap-1 font-medium rounded bg-blue-500/10 text-blue-400"
      :class="size === 'md' ? 'text-xs px-2 py-0.5' : 'text-[10px] px-1.5 py-0.5'"
    >
      <span
        class="rounded-full flex-shrink-0"
        :class="size === 'md' ? 'w-2 h-2' : 'w-1.5 h-1.5'"
        :style="{ background: repoTypeColor(type) }"
      />
      {{ techLabel(type) }}
    </span>
    <!-- Subtype: amber/orange -->
    <span
      class="font-medium rounded bg-amber-500/10 text-amber-500"
      :class="size === 'md' ? 'text-xs px-2 py-0.5' : 'text-[10px] px-1.5 py-0.5'"
    >
      {{ subtypeLabel(type) }}
    </span>
  </span>
</template>
