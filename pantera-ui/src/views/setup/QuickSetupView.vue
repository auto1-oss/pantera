<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import InputText from 'primevue/inputtext'
import { SETUP_TECHS, techRepos } from '@/utils/techSetup'
import { listAllRepos } from '@/composables/useSetupRepos'
import AppLayout from '@/components/layout/AppLayout.vue'
import TechIcon from '@/components/common/TechIcon.vue'
import type { RepoListItem } from '@/types'

const query = ref('')
const repos = ref<RepoListItem[] | null>(null)

onMounted(async () => {
  repos.value = await listAllRepos().catch(() => null)
})

const tiles = computed(() => {
  const q = query.value.trim().toLowerCase()
  return SETUP_TECHS
    .filter(t => !q || t.label.toLowerCase().includes(q) || t.key.includes(q))
    .map(t => ({ ...t, count: repos.value ? techRepos(t, repos.value).length : null }))
})
</script>

<template>
  <AppLayout>
    <div>
      <div class="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Set Me Up</h1>
          <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Pick a format to get ready-to-paste client configuration, with your credentials filled in.
          </p>
        </div>
        <span class="relative w-full sm:w-64">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <InputText
            v-model="query"
            placeholder="Search formats..."
            aria-label="Search formats"
            class="!pl-10 w-full"
            data-testid="format-search"
          />
        </span>
      </div>

      <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3" data-testid="format-grid">
        <router-link
          v-for="tech in tiles"
          :key="tech.key"
          :to="`/setup/${tech.key}`"
          class="flex flex-col items-center gap-2 p-4 rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 hover:shadow-md hover:border-gray-300 dark:hover:border-gray-600 transition-all no-underline group focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          :class="tech.count === 0 ? 'opacity-50' : ''"
          :data-testid="`tile-${tech.key}`"
        >
          <TechIcon :tech-key="tech.key" size="lg" />
          <span class="text-sm font-medium text-gray-700 dark:text-gray-300 group-hover:text-gray-900 dark:group-hover:text-white text-center leading-tight">
            {{ tech.label }}
          </span>
          <span v-if="tech.count !== null" class="text-[11px] text-gray-400">
            {{ tech.count === 1 ? '1 repository' : `${tech.count} repositories` }}
          </span>
        </router-link>
      </div>
      <p v-if="tiles.length === 0" class="text-sm text-gray-400 text-center py-10">No format matches "{{ query }}".</p>
    </div>
  </AppLayout>
</template>
