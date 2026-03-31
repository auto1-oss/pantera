<script setup lang="ts">
import { SETUP_TECHS } from '@/utils/techSetup'
import AppLayout from '@/components/layout/AppLayout.vue'
import TechIcon from '@/components/common/TechIcon.vue'
</script>

<template>
  <AppLayout>
    <div>
      <div class="mb-8">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Quick Setup</h1>
        <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">Select your package manager to get started</p>
      </div>

      <div class="grid grid-cols-4 gap-3">
        <router-link
          v-for="tech in SETUP_TECHS"
          :key="tech.key"
          :to="`/setup/${tech.key}`"
          class="flex flex-col items-center gap-3 p-4 rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 hover:shadow-md hover:border-gray-300 dark:hover:border-gray-600 transition-all no-underline group"
        >
          <TechIcon :tech-key="tech.key" size="lg" />
          <span class="text-sm font-medium text-gray-700 dark:text-gray-300 group-hover:text-gray-900 dark:group-hover:text-white text-center leading-tight">
            {{ tech.label }}
          </span>
        </router-link>
      </div>
    </div>
  </AppLayout>
</template>
