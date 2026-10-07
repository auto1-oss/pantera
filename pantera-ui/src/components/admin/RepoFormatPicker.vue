<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  REPO_TYPE_CREATE_OPTIONS, REPO_TYPE_DESCRIPTIONS,
  repoTypeBase, repoTypeIcon, repoTypeColorClass, techLabel,
} from '@/utils/repoTypes'
import type { RepoMode } from '@/types'

const props = defineProps<{ modelValue: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [string] }>()

const TABS: Array<{ key: RepoMode; label: string; hint: string }> = [
  { key: 'hosted', label: 'Hosted', hint: 'Pantera stores what you publish' },
  { key: 'proxy', label: 'Proxy', hint: 'Caches an upstream registry' },
  { key: 'group', label: 'Group', hint: 'One URL over several repositories' },
]

function modeOf(value: string): RepoMode {
  if (value.endsWith('-proxy')) return 'proxy'
  if (value.endsWith('-group')) return 'group'
  return 'hosted'
}

// Open on the tab of the current value so editing a seed keeps context.
const tab = ref<RepoMode>(props.modelValue ? modeOf(props.modelValue) : 'hosted')
watch(() => props.modelValue, (v) => { if (v) tab.value = modeOf(v) })

const cards = computed(() =>
  REPO_TYPE_CREATE_OPTIONS
    .filter(o => modeOf(o.value) === tab.value)
    .map(o => ({
      value: o.value,
      label: techLabel(o.value),
      icon: repoTypeIcon(o.value),
      colorClass: repoTypeColorClass(o.value),
      description: REPO_TYPE_DESCRIPTIONS[repoTypeBase(o.value)] ?? '',
    })),
)
</script>

<template>
  <div class="space-y-3">
    <div class="flex gap-1 rounded-lg bg-gray-100 p-1 dark:bg-gray-800" role="tablist">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        role="tab"
        :aria-selected="tab === t.key"
        :data-testid="`format-tab-${t.key}`"
        class="flex-1 rounded-md px-3 py-1.5 text-sm transition-colors"
        :class="tab === t.key
          ? 'bg-white font-medium text-gray-900 shadow-sm dark:bg-gray-700 dark:text-white'
          : 'text-gray-600 hover:text-gray-900 dark:text-gray-300 dark:hover:text-white'"
        :title="t.hint"
        @click="tab = t.key"
      >
        {{ t.label }}
      </button>
    </div>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      <button
        v-for="c in cards"
        :key="c.value"
        type="button"
        :data-testid="`format-card-${c.value}`"
        :aria-pressed="modelValue === c.value"
        class="flex items-start gap-3 rounded-lg border p-3 text-left transition-colors hover:border-blue-400 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        :class="modelValue === c.value
          ? 'border-blue-500 bg-blue-50 ring-1 ring-blue-500 dark:bg-blue-900/20'
          : 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800'"
        @click="emit('update:modelValue', c.value)"
      >
        <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md" :class="c.colorClass">
          <i :class="c.icon" />
        </span>
        <span class="min-w-0">
          <span class="block text-sm font-medium text-gray-900 dark:text-white">{{ c.label }}</span>
          <span class="block text-xs leading-snug text-gray-500">{{ c.description }}</span>
        </span>
      </button>
    </div>
  </div>
</template>
