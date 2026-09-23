<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { getTechDef } from '@/utils/techSetup'
import AppLayout from '@/components/layout/AppLayout.vue'
import TechIcon from '@/components/common/TechIcon.vue'
import SetMeUpPanel from '@/components/setup/SetMeUpPanel.vue'

const props = defineProps<{ tech: string }>()
const route = useRoute()

const techDef = computed(() => getTechDef(props.tech))
const q = (key: string) => {
  const v = route.query[key]
  return typeof v === 'string' ? v : ''
}
</script>

<template>
  <AppLayout>
    <div class="flex items-center gap-2 text-sm text-gray-400 mb-6">
      <router-link to="/setup" class="hover:text-gray-600 dark:hover:text-gray-300 transition-colors">
        Set Me Up
      </router-link>
      <i class="pi pi-chevron-right text-xs" />
      <span class="text-gray-600 dark:text-gray-300 font-medium">{{ techDef?.label ?? tech }}</span>
    </div>

    <div v-if="techDef" class="flex items-center gap-4 mb-6">
      <TechIcon :tech-key="tech" size="lg" />
      <div>
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Set Me Up: {{ techDef.label }}</h1>
        <p class="text-sm text-gray-400 mt-0.5">Configure your client to use Pantera as the {{ techDef.label }} registry</p>
      </div>
    </div>

    <SetMeUpPanel
      :key="tech"
      :tech="tech"
      :repo="q('repo')"
      :client="q('client')"
      :tab="q('tab')"
      sync-route
    />
  </AppLayout>
</template>
