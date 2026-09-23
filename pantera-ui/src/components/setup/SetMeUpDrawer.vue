<script setup lang="ts">
import { computed } from 'vue'
import Drawer from 'primevue/drawer'
import { getTechDef } from '@/utils/techSetup'
import TechIcon from '@/components/common/TechIcon.vue'
import SetMeUpPanel from './SetMeUpPanel.vue'

const props = defineProps<{
  visible: boolean
  tech: string
  repo: string
}>()

const emit = defineEmits<{ 'update:visible': [value: boolean] }>()

const techDef = computed(() => getTechDef(props.tech))
</script>

<template>
  <Drawer
    :visible="visible"
    position="right"
    class="!w-full md:!w-[52rem] lg:!w-[60rem]"
    :block-scroll="true"
    @update:visible="emit('update:visible', $event)"
  >
    <template #header>
      <div class="flex items-center gap-3 min-w-0">
        <TechIcon :tech-key="tech" size="sm" />
        <div class="min-w-0">
          <h2 class="text-lg font-semibold text-gray-900 dark:text-white leading-tight">Set Me Up</h2>
          <p class="text-xs text-gray-400 truncate">{{ techDef?.label }} · {{ repo }}</p>
        </div>
      </div>
    </template>
    <!-- Mounted per opening: a generated token never outlives the drawer. -->
    <SetMeUpPanel v-if="visible" :tech="tech" :repo="repo" />
  </Drawer>
</template>
