<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { listRepos, getRepo } from '@/api/repos'
import { getUiSettings } from '@/api/settings'
import {
  getTechDef, getSetupSteps, techRepos, repoMode, defaultPublishRepo,
  type RepoMode, type SetupStep,
} from '@/utils/techSetup'
import AppLayout from '@/components/layout/AppLayout.vue'
import TechIcon from '@/components/common/TechIcon.vue'
import Select from 'primevue/select'
import type { RepoListItem } from '@/types'
import { useConfigStore } from '@/stores/config'

const props = defineProps<{ tech: string }>()

const techDef = computed(() => getTechDef(props.tech))
const configStore = useConfigStore()

// ─── Repo loading ──────────────────────────────────────────────────────────

const PAGE_SIZE = 100
const MAX_PAGES = 50

const repos = ref<RepoListItem[]>([])
const reposLoading = ref(false)
const resolveRepo = ref('')
const publishRepo = ref('')

/** Every repository the caller can read, across all pages. */
async function listAllRepos(): Promise<RepoListItem[]> {
  const all: RepoListItem[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const resp = await listRepos({ page, size: PAGE_SIZE })
    all.push(...resp.items)
    if (!resp.hasMore) break
  }
  return all
}

async function loadRepos() {
  const tech = techDef.value
  if (!tech) return
  reposLoading.value = true
  repos.value = []
  resolveRepo.value = ''
  publishRepo.value = ''
  try {
    repos.value = techRepos(tech, await listAllRepos().catch(() => []))
    resolveRepo.value = repos.value[0]?.name ?? ''
  } finally {
    reposLoading.value = false
  }
}

const localRepos = computed(() => repos.value.filter(r => repoMode(r.type) === 'local'))
const resolveItem = computed(() => repos.value.find(r => r.name === resolveRepo.value))
const resolveMode = computed<RepoMode | null>(() =>
  resolveItem.value ? repoMode(resolveItem.value.type) : null
)

/** Direct members of a group, in declared order; empty when unreadable. */
async function groupMembers(name: string): Promise<string[]> {
  try {
    const cfg = await getRepo(name) as { repo?: { members?: unknown } }
    const members = cfg.repo?.members
    return Array.isArray(members) ? members.filter((m): m is string => typeof m === 'string') : []
  } catch {
    return []
  }
}

// A new resolve repo re-derives the publish default: itself when local,
// else the group's first local member, else the first local repo.
watch(resolveRepo, async (name) => {
  const item = resolveItem.value
  const members = item && repoMode(item.type) === 'group' ? await groupMembers(name) : []
  if (resolveRepo.value !== name) return
  publishRepo.value = defaultPublishRepo(item, localRepos.value, members)
})

onMounted(async () => {
  loadRepos()
  try {
    const uiSettings = await getUiSettings()
    if (uiSettings.ui?.registry_url) configStore.registryUrl = uiSettings.ui.registry_url
  } catch { /* non-critical */ }
})
watch(() => props.tech, loadRepos)

// ─── Instructions ──────────────────────────────────────────────────────────

const repoUrl = (name: string) => `${configStore.registryUrl.replace(/\/+$/, '')}/${name || 'YOUR_REPO'}`

const steps = computed(() => getSetupSteps(props.tech, {
  registryUrl: configStore.registryUrl,
  resolveRepo: resolveRepo.value,
  publishRepo: techDef.value?.publishable ? publishRepo.value : '',
}))

interface Section { key: 'resolve' | 'publish'; title: string; steps: SetupStep[] }

const sections = computed<Section[]>(() => [
  { key: 'resolve' as const, title: 'Resolve', steps: steps.value.resolve },
  { key: 'publish' as const, title: 'Publish', steps: steps.value.publish },
].filter(s => s.steps.length > 0))

const MODE_LABEL: Record<RepoMode, string> = { group: 'Group', proxy: 'Proxy', local: 'Local' }

const resolveOptions = computed(() =>
  repos.value.map(r => ({ label: r.name, value: r.name, mode: repoMode(r.type) }))
)
const publishOptions = computed(() =>
  localRepos.value.map(r => ({ label: r.name, value: r.name, mode: 'local' as RepoMode }))
)

// ─── Copy ──────────────────────────────────────────────────────────────────

const copiedStep = ref<string | null>(null)

async function copyStep(code: string, id: string) {
  await navigator.clipboard.writeText(code)
  copiedStep.value = id
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

        <!-- Repo selectors -->
        <div class="mb-6 p-4 rounded-xl bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700">
          <div v-if="reposLoading" class="text-sm text-gray-400">
            <i class="pi pi-spin pi-spinner mr-2" />Loading repositories…
          </div>

          <div v-else-if="repos.length === 0" class="flex items-center justify-between">
            <span class="text-sm text-amber-500">No {{ techDef.label }} repositories found.</span>
            <router-link to="/admin/repositories/create" class="text-xs text-blue-500 hover:underline">
              Create one →
            </router-link>
          </div>

          <div v-else class="grid gap-4" :class="techDef.publishable ? 'md:grid-cols-2' : ''">
            <div data-testid="resolve-picker">
              <label class="block text-xs font-semibold uppercase tracking-wide text-gray-400 mb-2">Resolve from</label>
              <Select
                v-model="resolveRepo"
                :options="resolveOptions"
                option-label="label"
                option-value="value"
                class="w-full"
              >
                <template #option="{ option }">
                  <div class="flex items-center justify-between w-full gap-3">
                    <span>{{ option.label }}</span>
                    <span class="text-[10px] uppercase tracking-wide text-gray-400">{{ MODE_LABEL[option.mode as RepoMode] }}</span>
                  </div>
                </template>
              </Select>
              <p class="mt-2 text-xs text-gray-400 break-all">
                <code class="font-mono bg-gray-100 dark:bg-gray-900 px-1.5 py-0.5 rounded text-gray-700 dark:text-gray-300 select-all">{{ repoUrl(resolveRepo) }}/</code>
                <router-link
                  v-if="resolveRepo"
                  :to="{ name: 'repo-detail', params: { name: resolveRepo } }"
                  class="ml-2 text-blue-500 hover:underline whitespace-nowrap"
                >View repo →</router-link>
              </p>
              <p v-if="resolveMode === 'local' && repos.length > localRepos.length" class="mt-2 text-xs text-amber-600 dark:text-amber-400">
                A local repository only serves what was published to it. Resolve from a group or proxy to reach upstream packages.
              </p>
            </div>

            <div v-if="techDef.publishable" data-testid="publish-picker">
              <label class="block text-xs font-semibold uppercase tracking-wide text-gray-400 mb-2">Publish to</label>
              <template v-if="publishOptions.length > 0">
                <Select
                  v-model="publishRepo"
                  :options="publishOptions"
                  option-label="label"
                  option-value="value"
                  class="w-full"
                />
                <p class="mt-2 text-xs text-gray-400 break-all">
                  <code class="font-mono bg-gray-100 dark:bg-gray-900 px-1.5 py-0.5 rounded text-gray-700 dark:text-gray-300 select-all">{{ repoUrl(publishRepo) }}/</code>
                </p>
              </template>
              <p v-else class="text-sm text-amber-500" data-testid="no-publish-target">
                No local {{ techDef.label }} repository to publish to. Groups and proxies are read-only; ask an administrator for a local repository.
              </p>
            </div>
          </div>

          <p
            v-if="techDef.publishable && (resolveMode === 'group' || resolveMode === 'proxy') && publishOptions.length > 0"
            class="mt-4 text-xs text-gray-500 dark:text-gray-400"
            data-testid="read-only-note"
          >
            <i class="pi pi-info-circle mr-1" />
            <strong>{{ resolveRepo }}</strong> is a {{ resolveMode }} and does not accept uploads, so the publish steps below target
            <strong>{{ publishRepo }}</strong>.
          </p>
          <p v-else-if="!techDef.publishable && repos.length > 0" class="mt-4 text-xs text-gray-500 dark:text-gray-400">
            <i class="pi pi-info-circle mr-1" />
            Quick Setup covers resolution only for {{ techDef.label }}.
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
        <section v-for="section in sections" :key="section.key" class="mb-8" :data-testid="`${section.key}-steps`">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400 mb-3">
            {{ section.title }}
            <span class="normal-case font-normal tracking-normal">
              — {{ section.key === 'resolve' ? resolveRepo || 'YOUR_REPO' : publishRepo }}
            </span>
          </h2>
          <div class="space-y-3">
            <div
              v-for="(step, i) in section.steps"
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

              <!--
                `step.description` is author-controlled static markup from
                `utils/techSetup.ts` (`getSetupSteps`). It is never sourced
                from user input — it is a compile-time-constant English
                sentence with inline `<code>` tags for syntax styling. No
                XSS surface; `vue/no-v-html` is suppressed locally.
              -->
              <!-- eslint-disable vue/no-v-html -->
              <div
                v-if="step.description"
                class="px-4 pt-3 text-xs text-gray-500 dark:text-gray-400 [&_code]:bg-gray-100 [&_code]:dark:bg-gray-700 [&_code]:px-1 [&_code]:rounded [&_code]:font-mono"
                v-html="step.description"
              />
              <!-- eslint-enable vue/no-v-html -->

              <!-- Code block -->
              <div class="relative group">
                <pre class="px-4 py-3 text-xs font-mono text-gray-800 dark:text-gray-200 bg-gray-50 dark:bg-gray-900 overflow-x-auto leading-relaxed">{{ step.code }}</pre>
                <button
                  class="absolute top-2 right-2 px-2 py-1 rounded text-xs font-medium transition-all opacity-0 group-hover:opacity-100 flex items-center gap-1"
                  :class="copiedStep === `${section.key}-${i}`
                    ? 'bg-green-500 text-white opacity-100'
                    : 'bg-white dark:bg-gray-700 border border-gray-200 dark:border-gray-600 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-600 shadow-sm'"
                  @click="copyStep(step.code, `${section.key}-${i}`)"
                >
                  <i :class="copiedStep === `${section.key}-${i}` ? 'pi pi-check' : 'pi pi-copy'" />
                  {{ copiedStep === `${section.key}-${i}` ? 'Copied!' : 'Copy' }}
                </button>
              </div>
            </div>
          </div>
        </section>
      </div>
    </template>
  </AppLayout>
</template>
