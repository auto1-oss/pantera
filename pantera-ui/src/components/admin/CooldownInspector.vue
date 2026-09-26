<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  inspectCooldownPackage,
  refreshCooldownPackage,
  suggestCooldownPackages,
  INSPECT_REPO_TYPES,
  type CooldownInspectResponse,
  type CooldownState,
  type InspectNegCacheEntry,
  type InspectSuggestion,
} from '@/api/cooldown'
import { listRepos } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import { formatDurationMs } from '@/utils/duration'
import { repoTypeBase } from '@/utils/repoTypes'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import AutoComplete from 'primevue/autocomplete'
import Select from 'primevue/select'

export interface InspectQuery {
  repoType: string
  package: string
  repo?: string
}

const props = defineProps<{
  initialRepoType?: string
  initialPackage?: string
  initialRepo?: string
}>()

const emit = defineEmits<{ (e: 'query', q: InspectQuery): void }>()

const notify = useNotificationStore()

const repoType = ref<string>(props.initialRepoType || 'npm')
// Bound to the AutoComplete: a string while typing, the option object for an
// instant after a suggestion is picked (onSuggestionSelect turns it back).
const pkg = ref<string | InspectSuggestion>(props.initialPackage ?? '')
const suggestions = ref<InspectSuggestion[]>([])
let suggestSeq = 0
const repo = ref<string | null>(props.initialRepo || null)
const repoNames = ref<string[]>([])
const loading = ref(false)
const refreshing = ref(false)
const result = ref<CooldownInspectResponse | null>(null)
const error = ref('')

interface RepoDiff {
  repo: string
  appeared: string[]
  disappeared: string[]
}
const refreshDiff = ref<{
  repos: RepoDiff[]
  mismatchesBefore: number
  mismatchesAfter: number
} | null>(null)

const typeOptions = [{ label: 'Any type', value: '' }, ...INSPECT_REPO_TYPES]

const SOURCE_LABELS: Record<string, string> = { index: 'indexed', cooldown: 'cooldown' }

function packageText(): string {
  return typeof pkg.value === 'string' ? pkg.value : pkg.value.package
}

const repoOptions = computed(() => {
  const names = new Set(repoNames.value)
  if (repo.value) names.add(repo.value)
  return [
    { label: 'All repositories of this type', value: null as string | null },
    ...[...names].sort().map(n => ({ label: n, value: n as string | null })),
  ]
})

const mismatchCount = computed(() => result.value?.versions.filter(v => v.mismatch).length ?? 0)

async function loadRepoNames() {
  if (!repoType.value) {
    repoNames.value = []
    return
  }
  try {
    const resp = await listRepos({ type: repoType.value, size: 500 })
    repoNames.value = resp.items
      .filter(r => repoTypeBase(r.type) === repoType.value)
      .map(r => r.name)
  } catch {
    repoNames.value = []
  }
}

// A repository belongs to one type: changing the type drops the selection.
watch(repoType, () => {
  repo.value = null
  loadRepoNames()
})

function currentQuery(): InspectQuery | null {
  const name = packageText().trim()
  if (!name || !repoType.value) return null
  return { repoType: repoType.value, package: name, repo: repo.value ?? undefined }
}

/** AutoComplete completeMethod; the component debounces (delay) and gates on minLength. */
async function searchPackages(event: { query: string }) {
  const seq = ++suggestSeq
  try {
    const found = await suggestCooldownPackages({
      q: event.query,
      repoType: repoType.value || undefined,
      limit: 20,
    })
    if (seq === suggestSeq) suggestions.value = found
  } catch {
    if (seq === suggestSeq) suggestions.value = []
  }
}

/** Inspect a suggested package, switching to its repo type when it differs. */
function pickSuggestion(s: InspectSuggestion) {
  if (s.repoType && s.repoType !== repoType.value) {
    repoType.value = s.repoType
    repo.value = null
  }
  pkg.value = s.package
  inspect()
}

function onSuggestionSelect(event: { value: InspectSuggestion }) {
  pickSuggestion(event.value)
}

/** Enter inspects the text as typed, unless it just picked a highlighted suggestion. */
function onPackageEnter(event: KeyboardEvent) {
  if (event.defaultPrevented) return
  inspect()
}

async function inspect() {
  const q = currentQuery()
  if (!q) {
    if (packageText().trim() && !repoType.value) {
      error.value = 'Choose a repo type, or pick one of the suggestions'
    }
    return
  }
  emit('query', q)
  loading.value = true
  error.value = ''
  refreshDiff.value = null
  try {
    result.value = await inspectCooldownPackage(q)
  } catch (err: unknown) {
    result.value = null
    const status = (err as { response?: { status?: number } })?.response?.status
    error.value = status ? `Inspect failed (HTTP ${status})` : 'Inspect failed'
  } finally {
    loading.value = false
  }
}

function visibleSet(r: CooldownInspectResponse, name: string): Set<string> {
  const found = r.repos.find(x => x.name === name)
  return new Set(found?.metadata.visibleVersions ?? [])
}

function computeDiff(before: CooldownInspectResponse, after: CooldownInspectResponse) {
  const repos: RepoDiff[] = after.repos.map(r => {
    const b = visibleSet(before, r.name)
    const a = visibleSet(after, r.name)
    return {
      repo: r.name,
      appeared: [...a].filter(v => !b.has(v)),
      disappeared: [...b].filter(v => !a.has(v)),
    }
  })
  return {
    repos,
    mismatchesBefore: before.versions.filter(v => v.mismatch).length,
    mismatchesAfter: after.versions.filter(v => v.mismatch).length,
  }
}

async function refreshPackage() {
  const q = currentQuery()
  if (!q) return
  refreshing.value = true
  try {
    const { before, after } = await refreshCooldownPackage(q)
    result.value = after
    refreshDiff.value = computeDiff(before, after)
    const appeared = refreshDiff.value.repos.reduce((n, r) => n + r.appeared.length, 0)
    notify.success(
      'Package refreshed',
      appeared > 0
        ? `${appeared} version${appeared === 1 ? '' : 's'} became visible`
        : 'No change in visible versions',
    )
  } catch {
    notify.error('Refresh failed')
  } finally {
    refreshing.value = false
  }
}

function stateSeverity(state: CooldownState): string {
  switch (state) {
    case 'blocked': return 'danger'
    case 'released': return 'success'
    case 'expired': return 'info'
    default: return 'secondary'
  }
}

function formatUntil(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  return d.toLocaleString()
}

function negKeyLabel(entry: InspectNegCacheEntry): string {
  if (typeof entry.key === 'string') return entry.key
  const k = entry.key
  return [k.scope, k.repoType, k.artifactName, k.artifactVersion].filter(Boolean).join(' : ')
}

function metadataSeverity(status: number | undefined): string {
  if (status === undefined) return 'secondary'
  if (status >= 200 && status < 300) return 'success'
  if (status >= 400 && status < 500) return 'warn'
  return 'danger'
}

/** Client path of the package's metadata in a repo, for the troubleshooter. */
function metadataPath(repoName: string): string | null {
  const name = result.value?.package ?? ''
  if (!name) return null
  switch (result.value?.repoType) {
    case 'npm': return `/${repoName}/${name}`
    case 'pypi': return `/${repoName}/simple/${name}/`
    case 'go': return `/${repoName}/${name}/@v/list`
    case 'maven':
    case 'gradle': {
      const [group, artifact] = name.split(':')
      if (!group || !artifact) return null
      return `/${repoName}/${group.replace(/\./g, '/')}/${artifact}/maven-metadata.xml`
    }
    case 'php': return `/${repoName}/p2/${name}.json`
    default: return null
  }
}

onMounted(() => {
  loadRepoNames()
  if (packageText().trim()) inspect()
})

defineExpose({ inspect, refreshPackage, result, refreshDiff })
</script>

<template>
  <div class="space-y-4">
    <Card class="shadow-sm">
      <template #content>
        <div class="flex flex-wrap items-end gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm text-gray-500" for="inspect-repo-type">Repo type</label>
            <Select
              id="inspect-repo-type"
              v-model="repoType"
              :options="typeOptions"
              option-label="label"
              option-value="value"
              class="w-44"
            />
          </div>
          <div class="flex flex-col gap-1 flex-1 min-w-[14rem]">
            <label class="text-sm text-gray-500" for="inspect-package">Package</label>
            <AutoComplete
              v-model="pkg"
              input-id="inspect-package"
              :suggestions="suggestions"
              option-label="package"
              :delay="250"
              :min-length="2"
              empty-search-message="No matching packages"
              placeholder="Any part of the name: http5, jackson databind, @types node..."
              class="w-full"
              input-class="w-full"
              data-testid="inspect-package"
              @complete="searchPackages"
              @item-select="onSuggestionSelect"
              @keydown.enter="onPackageEnter"
            >
              <template #option="{ option }">
                <div class="flex flex-wrap items-center gap-2" data-testid="inspect-suggestion">
                  <span class="font-mono">{{ option.display }}</span>
                  <Tag :value="option.repoType" severity="secondary" />
                  <Tag
                    v-for="src in option.sources"
                    :key="src"
                    :value="SOURCE_LABELS[src] ?? src"
                    :severity="src === 'cooldown' ? 'warn' : 'info'"
                  />
                  <span class="text-xs text-gray-400">{{ option.repos.join(', ') }}</span>
                </div>
              </template>
            </AutoComplete>
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm text-gray-500" for="inspect-repo">Repository (optional)</label>
            <Select
              id="inspect-repo"
              v-model="repo"
              :options="repoOptions"
              option-label="label"
              option-value="value"
              placeholder="All repositories of this type"
              :disabled="!repoType"
              filter
              class="w-60"
            />
          </div>
          <Button label="Inspect" icon="pi pi-search" :loading="loading" data-testid="inspect-run" @click="inspect" />
          <Button
            v-if="result"
            v-tooltip.bottom="'Clear every cache layer for this package on all nodes, then re-inspect'"
            label="Refresh package"
            icon="pi pi-sync"
            severity="warn"
            outlined
            :loading="refreshing"
            data-testid="inspect-refresh"
            @click="refreshPackage"
          />
        </div>
      </template>
    </Card>

    <div
      v-if="error"
      class="p-3 rounded-lg border border-red-300 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
    >
      {{ error }}
    </div>

    <div
      v-if="result?.didYouMean?.length"
      class="p-3 rounded-lg border border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-200"
      data-testid="inspect-did-you-mean"
    >
      No exact match for <span class="font-mono">{{ result.package }}</span> — did you mean:
      <span class="inline-flex flex-wrap gap-2 ml-1">
        <Button
          v-for="s in result.didYouMean"
          :key="`${s.repoType}:${s.package}`"
          :label="s.display"
          size="small"
          link
          class="font-mono !p-0"
          data-testid="inspect-did-you-mean-option"
          @click="pickSuggestion(s)"
        />
      </span>
    </div>

    <Card v-if="refreshDiff" class="shadow-sm" data-testid="inspect-refresh-diff">
      <template #title>Refresh result</template>
      <template #content>
        <p class="text-sm text-gray-500 mb-2">
          Mismatches: {{ refreshDiff.mismatchesBefore }} before &rarr; {{ refreshDiff.mismatchesAfter }} after
        </p>
        <ul class="space-y-1 text-sm">
          <li v-for="d in refreshDiff.repos" :key="d.repo" class="flex flex-wrap items-center gap-2">
            <span class="font-semibold text-gray-900 dark:text-white">{{ d.repo }}:</span>
            <template v-if="d.appeared.length || d.disappeared.length">
              <span v-if="d.appeared.length" class="text-green-600 dark:text-green-400">
                became visible: <span class="font-mono">{{ d.appeared.join(', ') }}</span>
              </span>
              <span v-if="d.disappeared.length" class="text-amber-600 dark:text-amber-400">
                now hidden: <span class="font-mono">{{ d.disappeared.join(', ') }}</span>
              </span>
            </template>
            <span v-else class="text-gray-400">no change</span>
          </li>
        </ul>
      </template>
    </Card>

    <template v-if="result">
      <Card class="shadow-sm">
        <template #title>
          <div class="flex flex-wrap items-center justify-between gap-2">
            <span>
              Versions of <span class="font-mono">{{ result.package }}</span>
            </span>
            <span class="flex items-center gap-2 text-sm font-normal">
              <Tag
                v-if="mismatchCount > 0"
                :value="`${mismatchCount} mismatch${mismatchCount === 1 ? '' : 'es'}`"
                severity="danger"
              />
              <span class="text-gray-400">
                {{ result.versions.length }} versions &middot; node <span class="font-mono">{{ result.node }}</span>
              </span>
            </span>
          </div>
        </template>
        <template #content>
          <DataTable :value="result.versions" striped-rows size="small" data-testid="inspect-versions">
            <Column field="version" header="Version" sortable>
              <template #body="{ data }">
                <span class="font-mono">{{ data.version }}</span>
                <Tag
                  v-if="data.mismatch"
                  v-tooltip.top="data.cooldown.state === 'blocked'
                    ? 'Blocked by cooldown but still visible — a cache layer is stale'
                    : 'Released but still hidden — a cache layer is stale'"
                  value="mismatch"
                  severity="danger"
                  class="ml-2"
                  data-testid="inspect-mismatch"
                />
              </template>
            </Column>
            <Column header="Cooldown">
              <template #body="{ data }">
                <Tag :value="data.cooldown.state" :severity="stateSeverity(data.cooldown.state)" />
                <span v-if="data.cooldown.reason" class="ml-2 text-xs text-gray-400">{{ data.cooldown.reason }}</span>
              </template>
            </Column>
            <Column header="Blocked until">
              <template #body="{ data }">
                <span class="text-sm">{{ formatUntil(data.cooldown.blockedUntil) }}</span>
              </template>
            </Column>
            <Column header="Visible in">
              <template #body="{ data }">
                <span class="flex flex-wrap gap-1">
                  <Tag v-for="r in data.visibleIn" :key="r" :value="r" severity="success" />
                  <span v-if="!data.visibleIn.length" class="text-gray-400">&mdash;</span>
                </span>
              </template>
            </Column>
            <Column header="Hidden in">
              <template #body="{ data }">
                <span class="flex flex-wrap gap-1">
                  <Tag v-for="r in data.hiddenIn" :key="r" :value="r" severity="secondary" />
                  <span v-if="!data.hiddenIn.length" class="text-gray-400">&mdash;</span>
                </span>
              </template>
            </Column>
            <template #empty>
              <div class="text-center text-gray-400 py-4">No versions known for this package</div>
            </template>
          </DataTable>
        </template>
      </Card>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4" data-testid="inspect-repos">
        <Card v-for="r in result.repos" :key="r.name" class="shadow-sm">
          <template #title>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="flex items-center gap-2">
                <span>{{ r.name }}</span>
                <Tag :value="r.mode" severity="secondary" />
              </span>
              <router-link
                v-if="metadataPath(r.name)"
                :to="{ path: '/admin/troubleshoot', query: { url: metadataPath(r.name) } }"
                class="text-sm font-normal text-amber-600 dark:text-amber-400 underline"
              >
                Troubleshoot this
              </router-link>
            </div>
          </template>
          <template #content>
            <div class="space-y-3 text-sm">
              <div v-if="r.members?.length" class="text-gray-500">
                Members: <span class="font-mono text-xs">{{ r.members.join(' → ') }}</span>
              </div>
              <div>
                <div class="text-xs uppercase tracking-wide text-gray-400 mb-1">Served metadata</div>
                <div v-if="r.metadata.unsupported" class="text-gray-400">
                  Metadata inspection is not supported for this format yet
                </div>
                <div v-else class="flex flex-wrap items-center gap-2">
                  <Tag
                    :value="r.metadata.status !== undefined ? `HTTP ${r.metadata.status}` : 'no response'"
                    :severity="metadataSeverity(r.metadata.status)"
                  />
                  <span>{{ r.metadata.visibleVersions?.length ?? 0 }} visible versions</span>
                  <span v-if="r.metadata.fetchedVia" class="text-xs text-gray-400">via {{ r.metadata.fetchedVia }}</span>
                  <span v-if="r.metadata.error" class="text-red-500 break-all">{{ r.metadata.error }}</span>
                </div>
              </div>
              <div v-if="r.envelope">
                <div class="text-xs uppercase tracking-wide text-gray-400 mb-1">Filtered metadata envelope</div>
                <div class="flex flex-wrap gap-4">
                  <span>
                    L1:
                    <Tag
                      :value="r.envelope.l1.present ? 'present' : 'absent'"
                      :severity="r.envelope.l1.present ? 'info' : 'secondary'"
                    />
                    <span v-if="r.envelope.l1.present" class="ml-1 text-gray-500">
                      age {{ formatDurationMs(r.envelope.l1.ageMs) }}
                    </span>
                  </span>
                  <span>
                    L2:
                    <Tag
                      :value="r.envelope.l2.present ? 'present' : 'absent'"
                      :severity="r.envelope.l2.present ? 'info' : 'secondary'"
                    />
                    <span v-if="r.envelope.l2.present" class="ml-1 text-gray-500">
                      TTL {{ formatDurationMs(r.envelope.l2.ttlRemainingMs) }}
                    </span>
                  </span>
                </div>
              </div>
              <div>
                <div class="text-xs uppercase tracking-wide text-gray-400 mb-1">Negative cache</div>
                <ul v-if="r.negativeCache?.length" class="space-y-1">
                  <li v-for="(n, i) in r.negativeCache" :key="i" class="flex flex-wrap items-center gap-2">
                    <span class="font-mono text-xs break-all">{{ negKeyLabel(n) }}</span>
                    <Tag v-if="n.l1" value="L1" severity="danger" />
                    <Tag v-if="n.l2" value="L2" severity="danger" />
                    <Tag v-if="!n.l1 && !n.l2" value="absent" severity="secondary" />
                  </li>
                </ul>
                <span v-else class="text-gray-400">No entries</span>
              </div>
            </div>
          </template>
        </Card>
      </div>
    </template>
  </div>
</template>
