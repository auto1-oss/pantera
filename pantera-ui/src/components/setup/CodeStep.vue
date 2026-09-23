<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import type { Step } from '@/utils/setup'
import { copyText, downloadText } from './clipboard'

const props = defineProps<{
  step: Step
  /** 1-based position, shown as the step badge */
  index: number
  /** Badge colour (the technology colour) */
  color?: string
}>()

const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

async function copy() {
  if (!(await copyText(props.step.code))) return
  copied.value = true
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { copied.value = false }, 2000)
}

function download() {
  if (props.step.download) downloadText(props.step.download, props.step.code)
}

onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <div class="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 overflow-hidden" data-testid="code-step">
    <div class="flex items-center gap-3 px-4 py-3 border-b border-gray-100 dark:border-gray-700">
      <span
        class="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold text-white flex-shrink-0"
        :style="{ background: color ?? '#6B7280' }"
      >{{ index }}</span>
      <span class="text-sm font-medium text-gray-800 dark:text-gray-200">{{ step.title }}</span>
    </div>

    <!--
      `step.description` is static markup authored in `utils/setup/<format>.ts`
      (compile-time English with inline <code>/<a> tags). It never carries user
      or server input, so there is no XSS surface.
    -->
    <!-- eslint-disable vue/no-v-html -->
    <div
      v-if="step.description"
      class="px-4 pt-3 text-xs text-gray-500 dark:text-gray-400 leading-relaxed [&_code]:bg-gray-100 [&_code]:dark:bg-gray-700 [&_code]:px-1 [&_code]:rounded [&_code]:font-mono [&_a]:text-blue-500 [&_a]:underline"
      data-testid="step-description"
      v-html="step.description"
    />
    <!-- eslint-enable vue/no-v-html -->

    <div class="px-4 pt-3 pb-4">
      <div class="flex items-center justify-between gap-2 mb-1.5 min-h-[1.75rem]">
        <span
          v-if="step.file"
          class="text-[11px] font-mono text-gray-500 dark:text-gray-400 truncate"
          data-testid="step-file"
        ><i class="pi pi-file text-[10px] mr-1" />{{ step.file }}</span>
        <span v-else />
        <div class="flex items-center gap-1.5 flex-shrink-0">
          <button
            v-if="step.download"
            type="button"
            class="px-2 py-1 rounded text-xs font-medium flex items-center gap-1 border border-gray-200 dark:border-gray-600 text-gray-600 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            :aria-label="`Download ${step.download}`"
            data-testid="download-btn"
            @click="download"
          >
            <i class="pi pi-download" />Download
          </button>
          <button
            type="button"
            class="px-2 py-1 rounded text-xs font-medium flex items-center gap-1 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            :class="copied
              ? 'bg-green-500 text-white border border-green-500'
              : 'border border-gray-200 dark:border-gray-600 text-gray-600 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600'"
            :aria-label="copied ? 'Copied' : `Copy ${step.title}`"
            data-testid="copy-btn"
            @click="copy"
          >
            <i :class="copied ? 'pi pi-check' : 'pi pi-copy'" />{{ copied ? 'Copied' : 'Copy' }}
          </button>
        </div>
      </div>
      <pre
        class="px-3 py-2.5 rounded-lg text-xs font-mono text-gray-800 dark:text-gray-200 bg-gray-50 dark:bg-gray-900 border border-gray-100 dark:border-gray-700 overflow-x-auto leading-relaxed"
        tabindex="0"
        :aria-label="`${step.title} code`"
      >{{ step.code }}</pre>
      <span class="sr-only" aria-live="polite">{{ copied ? 'Copied to clipboard' : '' }}</span>
    </div>
  </div>
</template>
