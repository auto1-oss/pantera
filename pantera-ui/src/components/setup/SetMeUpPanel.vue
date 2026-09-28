<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Select from 'primevue/select'
import { getRepo } from '@/api/repos'
import { getUiSettings } from '@/api/settings'
import { listAllRepos } from '@/composables/useSetupRepos'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import {
  getTechDef, techRepos, repoMode, defaultPublishRepo, type RepoMode,
} from '@/utils/techSetup'
import {
  FORMAT_SNIPPETS, buildSnippetCtx, resolveBase, repoUrlFor, configuredRepoUrl,
  type Client, type Step,
} from '@/utils/setup'
import type { RepoListItem } from '@/types'
import TechIcon from '@/components/common/TechIcon.vue'
import CodeStep from './CodeStep.vue'
import TokenCard from './TokenCard.vue'
import { copyText } from './clipboard'

type TabKey = 'configure' | 'resolve' | 'publish' | 'verify'

const props = withDefaults(defineProps<{
  tech: string
  repo?: string
  client?: string
  tab?: string
  /** Mirror repo / client / tab into the route query (the /setup/:tech page) */
  syncRoute?: boolean
}>(), { repo: '', client: '', tab: '', syncRoute: false })

const TABS: { key: TabKey; label: string }[] = [
  { key: 'configure', label: 'Configure' },
  { key: 'resolve', label: 'Resolve' },
  { key: 'publish', label: 'Publish' },
  { key: 'verify', label: 'Verify' },
]
const MODE_LABEL: Record<RepoMode, string> = { group: 'Group', proxy: 'Proxy', local: 'Local' }

const auth = useAuthStore()
const configStore = useConfigStore()
const route = props.syncRoute ? useRoute() : null
const router = props.syncRoute ? useRouter() : null

const techDef = computed(() => getTechDef(props.tech))

// ─── Registry base ─────────────────────────────────────────────────────────

const serverRegistryUrl = ref<string | undefined>(undefined)
const prefixes = ref<string[]>([])

const base = computed(() => resolveBase(
  serverRegistryUrl.value,
  configStore.configuredRegistryUrl || undefined,
  prefixes.value,
  window.location.origin,
))

async function loadUiSettings() {
  try {
    const s = await getUiSettings()
    serverRegistryUrl.value = s.ui?.registry_url || undefined
    prefixes.value = Array.isArray(s.ui?.prefixes) ? s.ui.prefixes : []
  } catch { /* fall back to the UI config / origin; the banner says so */ }
}

// ─── Repositories ──────────────────────────────────────────────────────────

const repos = ref<RepoListItem[]>([])
const reposLoading = ref(false)
const resolveRepo = ref('')
const publishRepo = ref('')

interface RepoCfg { url?: string; members: string[] }
const repoCfgs = ref<Record<string, RepoCfg>>({})
const cfgRequests = new Map<string, Promise<RepoCfg>>()

/** A repository's configured public URL and group members; empty when unreadable. */
function repoCfg(name: string): Promise<RepoCfg> {
  let req = cfgRequests.get(name)
  if (!req) {
    req = getRepo(name)
      .then(raw => {
        const members = (raw as { repo?: { members?: unknown } }).repo?.members
        return {
          url: configuredRepoUrl(raw),
          members: Array.isArray(members) ? members.filter((m): m is string => typeof m === 'string') : [],
        }
      })
      .catch(() => ({ members: [] }))
      .then(cfg => {
        repoCfgs.value = { ...repoCfgs.value, [name]: cfg }
        return cfg
      })
    cfgRequests.set(name, req)
  }
  return req
}

async function loadRepos() {
  const tech = techDef.value
  repos.value = []
  resolveRepo.value = ''
  publishRepo.value = ''
  if (!tech) return
  reposLoading.value = true
  try {
    repos.value = techRepos(tech, await listAllRepos().catch(() => []))
    const wanted = props.repo && repos.value.some(r => r.name === props.repo) ? props.repo : ''
    resolveRepo.value = wanted || repos.value[0]?.name || ''
  } finally {
    reposLoading.value = false
  }
}

const localRepos = computed(() => repos.value.filter(r => repoMode(r.type) === 'local'))
const resolveItem = computed(() => repos.value.find(r => r.name === resolveRepo.value))
const resolveMode = computed<RepoMode | null>(() =>
  resolveItem.value ? repoMode(resolveItem.value.type) : null,
)

// A new resolve repo re-derives the publish default: itself when local,
// else the group's first local member, else the first local repo.
watch(resolveRepo, async (name) => {
  if (!name) return
  const cfg = await repoCfg(name)
  if (resolveRepo.value !== name) return
  const item = resolveItem.value
  publishRepo.value = defaultPublishRepo(
    item, localRepos.value, item && repoMode(item.type) === 'group' ? cfg.members : [],
  )
})
watch(publishRepo, (name) => { if (name) repoCfg(name) })

const repoGroups = computed(() => (['group', 'proxy', 'local'] as RepoMode[])
  .map(mode => ({
    label: MODE_LABEL[mode],
    items: repos.value
      .filter(r => repoMode(r.type) === mode)
      .map(r => ({ label: r.name, value: r.name })),
  }))
  .filter(g => g.items.length > 0))

const publishOptions = computed(() => localRepos.value.map(r => ({ label: r.name, value: r.name })))

function urlFor(name: string): string {
  return name ? repoUrlFor(base.value.base, name, repoCfgs.value[name]?.url) : ''
}

const resolveUrl = computed(() => urlFor(resolveRepo.value))
const effectivePublish = computed(() => (techDef.value?.publishable ? publishRepo.value : ''))
const publishUrl = computed(() => urlFor(effectivePublish.value))

// ─── Snippets ──────────────────────────────────────────────────────────────

const token = ref('')

const clients = computed<Client[]>(() => {
  const build = FORMAT_SNIPPETS[props.tech]
  if (!build || !resolveRepo.value) return []
  try {
    return build(buildSnippetCtx({
      repo: resolveRepo.value,
      pubRepo: effectivePublish.value,
      repoUrl: resolveUrl.value,
      pubUrl: publishUrl.value,
      mode: resolveMode.value ?? undefined,
      user: auth.username,
      token: token.value,
    }))
  } catch {
    return []
  }
})

const clientId = ref(props.client)
const activeClient = computed(() =>
  clients.value.find(c => c.id === clientId.value) ?? clients.value[0],
)

const tab = ref<TabKey>(TABS.some(t => t.key === props.tab) ? props.tab as TabKey : 'configure')

const tabSteps = computed<Step[]>(() => activeClient.value?.[tab.value] ?? [])

/** Why the Publish tab has no steps, or '' when it has some. */
const publishReason = computed(() => {
  const label = techDef.value?.label ?? props.tech
  const client = activeClient.value
  if (tab.value !== 'publish' || tabSteps.value.length > 0) return ''
  if (client?.publishNote) return client.publishNote
  if (!techDef.value?.publishable) return `Set Me Up covers resolution only for ${label}.`
  if (localRepos.value.length === 0) {
    return `There is no local ${label} repository to publish to. Groups and proxies are read-only; ask an administrator for a local repository.`
  }
  return `${client?.label ?? 'This client'} has no publish flow.`
})

const copiedAll = ref(false)
async function copyAll() {
  if (!(await copyText(tabSteps.value.map(s => s.code).join('\n\n')))) return
  copiedAll.value = true
  setTimeout(() => { copiedAll.value = false }, 2000)
}

const copiedUrl = ref(false)
async function copyUrl() {
  if (!(await copyText(`${resolveUrl.value}/`))) return
  copiedUrl.value = true
  setTimeout(() => { copiedUrl.value = false }, 2000)
}

// ─── Route sync ────────────────────────────────────────────────────────────

watch([resolveRepo, () => activeClient.value?.id, tab], ([repo, client, t]) => {
  if (!router || !route || !repo) return
  const query = { ...route.query, repo, tab: t, ...(client ? { client } : {}) }
  if (!client) delete query.client
  const same = Object.keys(query).length === Object.keys(route.query).length
    && Object.entries(query).every(([k, v]) => route.query[k] === v)
  if (!same) router.replace({ query })
})

watch(() => props.tech, () => { loadRepos() })

onMounted(() => {
  loadUiSettings()
  loadRepos()
})
</script>

<template>
  <div v-if="!techDef" class="text-gray-400 text-center py-20">
    <i class="pi pi-exclamation-circle text-4xl mb-3 block" />
    Unknown technology "{{ tech }}"
  </div>

  <div v-else class="space-y-5" data-testid="set-me-up-panel">
    <!-- Unconfigured registry URL -->
    <div
      v-if="!base.configured"
      class="px-4 py-3 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 text-sm text-amber-800 dark:text-amber-300"
      role="alert"
      data-testid="unconfigured-banner"
    >
      <i class="pi pi-exclamation-triangle mr-1.5" />
      The registry URL is not configured, so these instructions use this page's address
      (<code class="font-mono">{{ base.base }}</code>), which may not reach the registry.
      <template v-if="auth.isAdmin">
        Set <strong>Registry URL</strong> in
        <router-link to="/admin/settings" class="underline font-medium">System Settings</router-link>.
      </template>
      <template v-else>Ask an administrator to set the registry URL.</template>
    </div>

    <!-- Repository pickers -->
    <div class="p-4 rounded-xl bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700">
      <div v-if="reposLoading" class="text-sm text-gray-400">
        <i class="pi pi-spin pi-spinner mr-2" />Loading repositories…
      </div>

      <div v-else-if="repos.length === 0" class="flex flex-wrap items-center justify-between gap-2" data-testid="no-repos">
        <span class="text-sm text-amber-500">No {{ techDef.label }} repositories you can read.</span>
        <router-link v-if="auth.isAdmin" to="/admin/repositories/create" class="text-xs text-blue-500 hover:underline">
          Create one →
        </router-link>
      </div>

      <div v-else class="grid gap-4" :class="techDef.publishable && resolveMode !== 'local' ? 'md:grid-cols-2' : ''">
        <div data-testid="resolve-picker">
          <label for="smu-repo" class="block text-xs font-semibold uppercase tracking-wide text-gray-400 mb-2">Repository</label>
          <Select
            v-model="resolveRepo"
            input-id="smu-repo"
            :options="repoGroups"
            option-label="label"
            option-value="value"
            option-group-label="label"
            option-group-children="items"
            class="w-full"
          />
          <div class="mt-2 flex items-center gap-2 min-w-0">
            <code
              class="font-mono text-xs bg-gray-100 dark:bg-gray-900 px-1.5 py-0.5 rounded text-gray-700 dark:text-gray-300 select-all break-all"
              data-testid="resolve-url"
            >{{ resolveUrl }}/</code>
            <button
              type="button"
              class="text-xs flex-shrink-0 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 rounded px-1"
              :class="copiedUrl ? 'text-green-500' : 'text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'"
              aria-label="Copy repository URL"
              @click="copyUrl"
            >
              <i :class="copiedUrl ? 'pi pi-check' : 'pi pi-copy'" />
            </button>
          </div>
          <p v-if="resolveMode === 'local' && repos.length > localRepos.length" class="mt-2 text-xs text-amber-600 dark:text-amber-400">
            A local repository only serves what was published to it. Resolve from a group or proxy to reach upstream packages.
          </p>
        </div>

        <div v-if="techDef.publishable && resolveMode !== 'local'" data-testid="publish-picker">
          <label for="smu-publish" class="block text-xs font-semibold uppercase tracking-wide text-gray-400 mb-2">Publish to</label>
          <template v-if="publishOptions.length > 0">
            <Select
              v-model="publishRepo"
              input-id="smu-publish"
              :options="publishOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
            <p class="mt-2 text-xs text-gray-400 break-all">
              <code class="font-mono bg-gray-100 dark:bg-gray-900 px-1.5 py-0.5 rounded text-gray-700 dark:text-gray-300 select-all" data-testid="publish-url">{{ publishUrl }}/</code>
            </p>
            <p class="mt-2 text-xs text-gray-500 dark:text-gray-400" data-testid="read-only-note">
              <i class="pi pi-info-circle mr-1" />
              <strong>{{ resolveRepo }}</strong> is a {{ resolveMode }} and does not accept uploads, so publishing goes to
              <strong>{{ publishRepo }}</strong>.
            </p>
          </template>
          <p v-else class="text-sm text-amber-500" data-testid="no-publish-target">
            No local {{ techDef.label }} repository to publish to. Groups and proxies are read-only; ask an administrator for a local repository.
          </p>
        </div>
      </div>
    </div>

    <template v-if="resolveRepo">
      <!-- Credentials -->
      <TokenCard :format="tech" :repo="resolveRepo" :username="auth.username" @token="token = $event" />

      <!-- Clients -->
      <div v-if="clients.length === 0" class="px-4 py-6 rounded-xl border border-dashed border-gray-300 dark:border-gray-700 text-center text-sm text-gray-400" data-testid="no-clients">
        <TechIcon :tech-key="tech" size="sm" class="mx-auto mb-2" />
        Client instructions for {{ techDef.label }} are not available yet. Use the repository URL above.
      </div>

      <template v-else>
        <div class="flex flex-wrap items-center gap-2" role="radiogroup" aria-label="Client" data-testid="client-chips">
          <span class="text-xs font-semibold uppercase tracking-wide text-gray-400 mr-1">Client</span>
          <button
            v-for="c in clients"
            :key="c.id"
            type="button"
            role="radio"
            :aria-checked="activeClient?.id === c.id"
            class="px-3 py-1 rounded-full text-xs font-medium border transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            :class="activeClient?.id === c.id
              ? 'text-white border-transparent'
              : 'border-gray-200 dark:border-gray-600 text-gray-600 dark:text-gray-300 bg-white dark:bg-gray-800 hover:border-gray-400 dark:hover:border-gray-500'"
            :style="activeClient?.id === c.id ? { background: techDef.color } : undefined"
            :data-testid="`client-${c.id}`"
            @click="clientId = c.id"
          >
            {{ c.label }}
          </button>
        </div>

        <!-- Tabs -->
        <div>
          <div class="flex items-end justify-between gap-2 border-b border-gray-200 dark:border-gray-700">
            <div class="flex flex-wrap gap-1" role="tablist" aria-label="Setup stage">
              <button
                v-for="t in TABS"
                :id="`smu-tab-${t.key}`"
                :key="t.key"
                type="button"
                role="tab"
                :aria-selected="tab === t.key"
                aria-controls="smu-tabpanel"
                class="px-3 py-2 text-sm font-medium border-b-2 transition-colors whitespace-nowrap focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 rounded-t"
                :class="tab === t.key
                  ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-200'"
                :data-testid="`tab-${t.key}`"
                @click="tab = t.key"
              >
                {{ t.label }}
                <span
                  v-if="(activeClient?.[t.key].length ?? 0) > 0"
                  class="ml-1 text-[10px] text-gray-400"
                >{{ activeClient?.[t.key].length }}</span>
              </button>
            </div>
            <button
              v-if="tabSteps.length > 1"
              type="button"
              class="mb-1.5 px-2 py-1 rounded text-xs font-medium flex items-center gap-1 flex-shrink-0 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
              :class="copiedAll ? 'bg-green-500 text-white' : 'text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800'"
              aria-label="Copy all snippets in this tab"
              data-testid="copy-all"
              @click="copyAll"
            >
              <i :class="copiedAll ? 'pi pi-check' : 'pi pi-clone'" />{{ copiedAll ? 'Copied' : 'Copy all' }}
            </button>
          </div>

          <div id="smu-tabpanel" role="tabpanel" :aria-labelledby="`smu-tab-${tab}`" class="pt-4 space-y-3" :data-testid="`panel-${tab}`">
            <p
              v-if="tab === 'publish' && tabSteps.length > 0 && resolveMode !== 'local' && effectivePublish"
              class="text-xs text-gray-500 dark:text-gray-400"
            >
              <i class="pi pi-info-circle mr-1" />Publishing goes to <strong>{{ effectivePublish }}</strong>.
            </p>
            <CodeStep
              v-for="(step, i) in tabSteps"
              :key="`${activeClient?.id}-${tab}-${i}`"
              :step="step"
              :index="i + 1"
              :color="techDef.color"
            />
            <!--
              `publishNote` is static markup authored in `utils/setup/<format>.ts`
              (same contract as step descriptions); the fallbacks are plain text
              built from the technology label. No user or server input.
            -->
            <!-- eslint-disable vue/no-v-html -->
            <div
              v-if="tab === 'publish' && publishReason"
              class="flex gap-2 px-4 py-3 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-sm text-gray-500 dark:text-gray-400 [&_code]:bg-gray-100 [&_code]:dark:bg-gray-700 [&_code]:px-1 [&_code]:rounded [&_code]:font-mono"
            >
              <i class="pi pi-info-circle mt-0.5" />
              <p data-testid="publish-reason" v-html="publishReason" />
            </div>
            <!-- eslint-enable vue/no-v-html -->
            <p
              v-else-if="tabSteps.length === 0"
              class="text-sm text-gray-400"
              data-testid="tab-empty"
            >
              Nothing to do in this step for {{ activeClient?.label }}.
            </p>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>
