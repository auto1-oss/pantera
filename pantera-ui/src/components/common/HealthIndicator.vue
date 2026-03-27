<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { getHealth } from '@/api/settings'

const status = ref<'ok' | 'degraded' | 'unknown'>('unknown')
let interval: ReturnType<typeof setInterval> | null = null

async function check() {
  try {
    const resp = await getHealth()
    status.value = resp.status === 'ok' ? 'ok' : 'degraded'
  } catch {
    status.value = 'unknown'
  }
}

onMounted(() => {
  check()
  interval = setInterval(check, 30_000)
})

onUnmounted(() => {
  if (interval) clearInterval(interval)
})

const colorClass = {
  ok: 'bg-green-500',
  degraded: 'bg-amber-500',
  unknown: 'bg-gray-400',
}
</script>

<template>
  <span
    class="inline-block w-2.5 h-2.5 rounded-full"
    :class="colorClass[status]"
    :title="`Health: ${status}`"
  />
</template>
