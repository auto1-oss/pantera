<script setup lang="ts">
import { computed } from 'vue'
import { getTechDef } from '@/utils/techSetup'

const props = defineProps<{
  techKey: string
  size?: 'sm' | 'md' | 'lg'
}>()

const tech = computed(() => getTechDef(props.techKey))

const sizeClass = computed(() => ({
  sm: 'w-8 h-8 text-sm',
  md: 'w-10 h-10 text-base',
  lg: 'w-14 h-14 text-xl',
}[props.size ?? 'md']))

const svgSize = computed(() => ({ sm: 16, md: 20, lg: 28 }[props.size ?? 'md']))
</script>

<template>
  <div
    v-if="tech"
    class="rounded-xl flex items-center justify-center flex-shrink-0"
    :class="sizeClass"
    :style="{ background: `${tech.color}18` }"
  >
    <!-- simple-icons SVG -->
    <svg
      v-if="tech.icon"
      :width="svgSize"
      :height="svgSize"
      viewBox="0 0 24 24"
      :fill="tech.color"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path :d="tech.icon.path" />
    </svg>
    <!-- Letter fallback -->
    <span
      v-else
      class="font-bold leading-none select-none"
      :style="{ color: tech.color }"
    >{{ tech.label.slice(0, 2).toUpperCase() }}</span>
  </div>
</template>
