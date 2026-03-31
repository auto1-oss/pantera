<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { listRepos } from '@/api/repos'
import { getTechDef, getSetupSteps } from '@/utils/techSetup'
import AppLayout from '@/components/layout/AppLayout.vue'
import TechIcon from '@/components/common/TechIcon.vue'
import Select from 'primevue/select'
import type { RepoListItem } from '@/types'
import { useConfigStore } from '@/stores/config'

const props = defineProps<{ tech: string }>()

const techDef = computed(() => getTechDef(props.tech))

// ─── Repo loading ──────────────────────────────────────────────────────────

const repos = ref<RepoListItem[]>([])
const reposLoading = ref(false)
const selectedRepo = ref('')

async function loadRepos() {
  if (!techDef.value) return
  reposLoading.value = true
  repos.value = []
  selectedRepo.value = ''
  try {
    const results = await Promise.all(
      techDef.value.repoTypes.map(t =>
        listRepos({ type: t, size: 100 }).then(r => r.items).catch(() => [] as RepoListItem[])
      )
    )
    repos.value = results.flat()
    if (repos.value.length > 0) selectedRepo.value = repos.value[0].name
  } finally {
    reposLoading.value = false
  }
}

onMounted(loadRepos)
watch(() => props.tech, loadRepos)

// ─── Instructions ──────────────────────────────────────────────────────────

const configStore = useConfigStore()

const repoUrl = computed(() => {
  const base = configStore.registryUrl
  return selectedRepo.value ? `${base}/${selectedRepo.value}` : `${base}/YOUR_REPO`
})

const steps = computed(() => getSetupSteps(props.tech, repoUrl.value))

const repoOptions = computed(() =>
  repos.value.map(r => ({ label: r.name, value: r.name }))
)

// ─── Copy ──────────────────────────────────────────────────────────────────

const copiedStep = ref<number | null>(null)

async function copyStep(code: string, index: number) {
  await navigator.clipboard.writeText(code)
  copiedStep.value = index
  setTimeout(() => { copiedStep.value = null }, 2000)
}
</script>

<template>
  <AppLayout>
    <!-- Not found -->
    <div v-if="!techDef" class="text-gray-400 text-center py-20">
      <i class="pi pi-exclamation-circle text-4xl mb-3 block" />
      Unknown technology "{{ tech }}"
    </div>

    <template v-else>
      <!-- Breadcrumb -->
      <div class="flex items-center gap-2 text-sm text-gray-400 mb-6">
        <router-link to="/setup" class="hover:text-gray-600 dark:hover:text-gray-300 transition-colors">
          Quick Setup
        </router-link>
        <i class="pi pi-chevron-right text-xs" />
        <span class="text-gray-600 dark:text-gray-300 font-medium">{{ techDef.label }}</span>
      </div>

      <div>
        <!-- Header -->
        <div class="flex items-center gap-4 mb-7">
          <TechIcon :tech-key="tech" size="lg" />
          <div>
            <h1 class="text-2xl font-bold text-gray-900 dark:text-white">{{ techDef.label }} Setup</h1>
            <p class="text-sm text-gray-400 mt-0.5">Configure your client to use Pantera as the {{ techDef.label }} registry</p>
          </div>
        </div>

        <!-- Repo selector -->
        <div class="mb-6 p-4 rounded-xl bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700">
          <label class="block text-xs font-semibold uppercase tracking-wide text-gray-400 mb-2">Repository</label>

          <div v-if="reposLoading" class="text-sm text-gray-400">
            <i class="pi pi-spin pi-spinner mr-2" />Loading repositories…
          </div>

          <div v-else-if="repos.length === 0" class="flex items-center justify-between">
            <span class="text-sm text-amber-500">No {{ techDef.label }} repositories found.</span>
            <router-link to="/admin/repositories/create" class="text-xs text-blue-500 hover:underline">
              Create one →
            </router-link>
          </div>

          <div v-else class="flex items-center gap-3">
            <Select
              v-model="selectedRepo"
              :options="repoOptions"
              option-label="label"
              option-value="value"
              class="flex-1"
            />
            <router-link
              v-if="selectedRepo"
              :to="{ name: 'repo-detail', params: { name: selectedRepo } }"
              class="text-xs text-blue-500 hover:underline whitespace-nowrap"
            >
              View repo →
            </router-link>
          </div>

          <p class="mt-3 text-xs text-gray-400">
            Registry URL:&nbsp;
            <code class="font-mono bg-gray-100 dark:bg-gray-900 px-1.5 py-0.5 rounded text-gray-700 dark:text-gray-300 select-all break-all">{{ repoUrl }}/</code>
          </p>
        </div>

        <!-- Auth note -->
        <div class="mb-5 px-4 py-3 rounded-lg bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 text-xs text-blue-700 dark:text-blue-300">
          <i class="pi pi-info-circle mr-1.5" />
          <template v-if="techDef.tokenOnly">
            Replace <code class="font-mono bg-blue-100 dark:bg-blue-800/50 px-1 rounded">YOUR_TOKEN</code> with an API token generated on your
            <router-link to="/profile" class="underline font-medium">Profile</router-link> page.
          </template>
          <template v-else>
            Replace <code class="font-mono bg-blue-100 dark:bg-blue-800/50 px-1 rounded">YOUR_USERNAME</code> and
            <code class="font-mono bg-blue-100 dark:bg-blue-800/50 px-1 rounded">YOUR_TOKEN</code> with your Pantera username and an API token from your
            <router-link to="/profile" class="underline font-medium">Profile</router-link> page.
          </template>
        </div>

        <!-- Steps -->
        <div class="space-y-3">
          <div
            v-for="(step, i) in steps"
            :key="i"
            class="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 overflow-hidden"
          >
            <!-- Step header -->
            <div class="flex items-center gap-3 px-4 py-3 border-b border-gray-100 dark:border-gray-700">
              <span
                class="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold text-white flex-shrink-0"
                :style="{ background: techDef.color }"
              >{{ i + 1 }}</span>
              <span class="text-sm font-medium text-gray-800 dark:text-gray-200">{{ step.title }}</span>
            </div>

            <!-- Optional description -->
            <div
              v-if="step.description"
              class="px-4 pt-3 text-xs text-gray-500 dark:text-gray-400 [&_code]:bg-gray-100 [&_code]:dark:bg-gray-700 [&_code]:px-1 [&_code]:rounded [&_code]:font-mono"
              v-html="step.description"
            />

            <!-- Code block -->
            <div class="relative group">
              <pre class="px-4 py-3 text-xs font-mono text-gray-800 dark:text-gray-200 bg-gray-50 dark:bg-gray-900 overflow-x-auto leading-relaxed">{{ step.code }}</pre>
              <button
                class="absolute top-2 right-2 px-2 py-1 rounded text-xs font-medium transition-all opacity-0 group-hover:opacity-100 flex items-center gap-1"
                :class="copiedStep === i
                  ? 'bg-green-500 text-white opacity-100'
                  : 'bg-white dark:bg-gray-700 border border-gray-200 dark:border-gray-600 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-600 shadow-sm'"
                @click="copyStep(step.code, i)"
              >
                <i :class="copiedStep === i ? 'pi pi-check' : 'pi pi-copy'" />
                {{ copiedStep === i ? 'Copied!' : 'Copy' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>
  </AppLayout>
</template>
