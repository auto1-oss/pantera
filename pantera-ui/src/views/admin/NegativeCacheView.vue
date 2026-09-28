<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  listNegCache,
  probeNegCacheUrl,
  invalidateNegCacheKey,
  invalidateNegCachePackage,
  invalidateNegCachePattern,
  getNegCacheStats,
  type NegCacheEntry,
  type NegCacheKey,
  type NegCacheInvalidationCounts,
  type NegCacheProbeResponse,
  type NegCacheSource,
  type NegCacheStats,
} from '@/api/negCache'
import { useNotificationStore } from '@/stores/notifications'
import { formatDurationMs } from '@/utils/duration'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Paginator from 'primevue/paginator'
import Dialog from 'primevue/dialog'
import Panel from 'primevue/panel'

const notify = useNotificationStore()

// --- Entries (cluster-wide list) ---
const entries = ref<NegCacheEntry[]>([])
const entriesTotal = ref(0)
const entriesPage = ref(0)
const entriesSize = ref(50)
const entriesLoading = ref(false)
const source = ref<NegCacheSource | null>(null)
const listNode = ref('')
const search = ref('')
const scopeFilter = ref<string | null>(null)
const typeFilter = ref<string | null>(null)
let searchTimeout: ReturnType<typeof setTimeout> | null = null
let listAbort: AbortController | null = null

// Dropdown values come from the data itself. They accumulate across loads
// so selecting a scope does not collapse the list of scopes to that one.
const seenScopes = ref<Set<string>>(new Set())
const seenTypes = ref<Set<string>>(new Set())

function optionsFrom(values: Set<string>, allLabel: string, selected: string | null) {
  const all = new Set(values)
  if (selected) all.add(selected)
  return [
    { label: allLabel, value: null as string | null },
    ...[...all].sort().map(v => ({ label: v, value: v as string | null })),
  ]
}
const scopeOptions = computed(() => optionsFrom(seenScopes.value, 'All repositories', scopeFilter.value))
const typeOptions = computed(() => optionsFrom(seenTypes.value, 'All types', typeFilter.value))

// --- URL check ---
const probeUrl = ref('')
const probeResult = ref<NegCacheProbeResponse | null>(null)
const probedUrl = ref('')
const probeLoading = ref(false)

// --- Stats ---
const stats = ref<NegCacheStats | null>(null)
const statsLoading = ref(false)

// --- Package clear confirmation ---
const pkgConfirmVisible = ref(false)
const pkgTarget = ref<{ artifactName: string; repoType: string } | null>(null)
const pkgLoading = ref(false)

// --- Advanced: pattern invalidation ---
const patScope = ref('')
const patRepoType = ref('')
const patArtifactName = ref('')
const patVersion = ref('')
const patLoading = ref(false)
const patConfirmVisible = ref(false)

// Row keys currently being cleared, to disable their buttons.
const clearing = ref<Set<string>>(new Set())

function keyId(k: NegCacheKey): string {
  return `${k.scope}|${k.repoType}|${k.artifactName}|${k.artifactVersion ?? ''}`
}

watch(search, () => {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    entriesPage.value = 0
    loadEntries()
  }, 400)
})

watch([scopeFilter, typeFilter], () => {
  entriesPage.value = 0
  loadEntries()
})

onBeforeUnmount(() => {
  if (searchTimeout) clearTimeout(searchTimeout)
  if (listAbort) listAbort.abort()
})

async function loadEntries() {
  if (listAbort) listAbort.abort()
  listAbort = new AbortController()
  const ctrl = listAbort
  entriesLoading.value = true
  try {
    const resp = await listNegCache({
      q: search.value.trim() || undefined,
      scope: scopeFilter.value ?? undefined,
      repoType: typeFilter.value ?? undefined,
      page: entriesPage.value,
      pageSize: entriesSize.value,
    }, ctrl.signal)
    if (ctrl.signal.aborted) return
    entries.value = resp.items
    entriesTotal.value = resp.total
    source.value = resp.source
    listNode.value = resp.node
    const scopes = new Set(seenScopes.value)
    const types = new Set(seenTypes.value)
    for (const e of resp.items) {
      if (e.key.scope) scopes.add(e.key.scope)
      if (e.key.repoType) types.add(e.key.repoType)
    }
    seenScopes.value = scopes
    seenTypes.value = types
  } catch {
    if (ctrl.signal.aborted) return
    entries.value = []
    entriesTotal.value = 0
    notify.error('Failed to load negative cache entries')
  } finally {
    if (!ctrl.signal.aborted) entriesLoading.value = false
  }
}

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await getNegCacheStats()
  } catch {
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}

async function refreshAll() {
  const tasks: Promise<unknown>[] = [loadEntries(), loadStats()]
  if (probedUrl.value) tasks.push(runProbe(probedUrl.value))
  await Promise.all(tasks)
}

function countsDetail(c: NegCacheInvalidationCounts): string {
  const node = c.node ? ` (node ${c.node})` : ''
  return `Removed ${c.l1} from L1, ${c.l2} from L2${node}`
}

function reportCounts(title: string, c: NegCacheInvalidationCounts) {
  if (c.l1 === 0 && c.l2 === 0) {
    notify.info(`${title}: nothing to remove`, 'No matching entry was present in L1 or L2')
  } else {
    notify.success(title, countsDetail(c))
  }
}

async function clearKey(key: NegCacheKey) {
  const id = keyId(key)
  clearing.value = new Set(clearing.value).add(id)
  try {
    const counts = await invalidateNegCacheKey(key)
    reportCounts('Entry cleared', counts)
  } catch {
    notify.error('Failed to clear entry')
  } finally {
    const next = new Set(clearing.value)
    next.delete(id)
    clearing.value = next
    await refreshAll()
  }
}

function askClearPackage(key: NegCacheKey) {
  pkgTarget.value = { artifactName: key.artifactName, repoType: key.repoType }
  pkgConfirmVisible.value = true
}

async function doClearPackage() {
  const target = pkgTarget.value
  pkgConfirmVisible.value = false
  if (!target) return
  pkgLoading.value = true
  try {
    const counts = await invalidateNegCachePackage({
      artifactName: target.artifactName,
      repoType: target.repoType || undefined,
    })
    reportCounts(`Cleared ${target.artifactName}`, counts)
  } catch {
    notify.error('Failed to clear package')
  } finally {
    pkgLoading.value = false
    await refreshAll()
  }
}

async function runProbe(url: string) {
  probeLoading.value = true
  try {
    probeResult.value = await probeNegCacheUrl(url)
    probedUrl.value = url
  } catch (err: unknown) {
    probeResult.value = null
    const status = (err as { response?: { status?: number } })?.response?.status
    notify.error('URL check failed', status === 404 ? 'Unknown repository in URL' : undefined)
  } finally {
    probeLoading.value = false
  }
}

function doProbe() {
  const url = probeUrl.value.trim()
  if (!url) return
  runProbe(url)
}

function showPatternConfirm() {
  patConfirmVisible.value = true
}

async function doInvalidatePattern() {
  patConfirmVisible.value = false
  patLoading.value = true
  try {
    const body: Record<string, string> = {}
    if (patScope.value.trim()) body.scope = patScope.value.trim()
    if (patRepoType.value.trim()) body.repoType = patRepoType.value.trim()
    if (patArtifactName.value.trim()) body.artifactName = patArtifactName.value.trim()
    if (patVersion.value.trim()) body.version = patVersion.value.trim()
    const counts = await invalidateNegCachePattern(body)
    reportCounts('Pattern invalidated', counts)
    await refreshAll()
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 429) {
      notify.error('Rate limit exceeded', 'Max 10 pattern invalidations per minute')
    } else {
      notify.error('Pattern invalidation failed')
    }
  } finally {
    patLoading.value = false
  }
}

const answeredBy = computed(() => stats.value?.node || listNode.value)
const sourceTitle = computed(() => source.value === 'L2+L1'
  ? "Cluster-wide: Valkey (L2) merged with this node's L1"
  : "Valkey is not configured: only this node's L1 is visible")

onMounted(() => {
  loadEntries()
  loadStats()
})
</script>

<template>
  <AppLayout>
    <div class="space-y-6">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Negative Cache</h1>
          <p class="text-xs text-gray-400 mt-1" data-testid="negcache-origin">
            <span v-if="answeredBy">Answered by node <span class="font-mono">{{ answeredBy }}</span></span>
            <span v-if="answeredBy && source"> &middot; </span>
            <span v-if="source" :title="sourceTitle">source {{ source }}</span>
          </p>
        </div>
        <Button
          icon="pi pi-refresh"
          label="Refresh"
          outlined
          :loading="entriesLoading || statsLoading"
          @click="refreshAll"
        />
      </div>

      <!-- Stats -->
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4" data-testid="negcache-stats">
        <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
          <div class="text-sm text-gray-500">L1 size (this node)</div>
          <div class="text-2xl font-bold mt-1 text-gray-900 dark:text-white">
            {{ stats ? stats.l1Size.toLocaleString() : '—' }}
          </div>
        </div>
        <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
          <div class="text-sm text-gray-500">L2 size (cluster)</div>
          <div class="text-2xl font-bold mt-1 text-gray-900 dark:text-white" data-testid="negcache-l2size">
            {{ stats && stats.l2Size !== null ? stats.l2Size.toLocaleString() : '—' }}
          </div>
        </div>
        <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
          <div class="text-sm text-gray-500">Hit rate</div>
          <div class="text-2xl font-bold mt-1 text-gray-900 dark:text-white">
            {{ stats ? `${(stats.hitRate * 100).toFixed(1)}%` : '—' }}
          </div>
          <div v-if="stats" class="text-xs text-gray-400 mt-1">
            {{ stats.hitCount.toLocaleString() }} hits / {{ stats.requestCount.toLocaleString() }} lookups
          </div>
        </div>
        <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
          <div class="text-sm text-gray-500">Status</div>
          <div class="mt-2">
            <Tag
              v-if="stats"
              :value="stats.enabled ? 'Enabled' : 'Disabled'"
              :severity="stats.enabled ? 'success' : 'danger'"
            />
            <span v-else class="text-gray-400">{{ statsLoading ? 'Loading...' : '—' }}</span>
          </div>
          <div v-if="stats" class="text-xs text-gray-400 mt-1">
            {{ stats.evictionCount.toLocaleString() }} evictions
          </div>
        </div>
      </div>

      <!-- Check a URL -->
      <Card class="shadow-sm">
        <template #title>Check a URL</template>
        <template #content>
          <p class="text-sm text-gray-500 mb-3">
            Paste the URL a client requested (or <code>/&lt;repo&gt;/&lt;path&gt;</code>). Every negative cache key
            the repository and its group members would use for that path is listed with its L1/L2 state.
          </p>
          <div class="flex gap-3 items-end">
            <div class="flex-1">
              <label class="block text-sm text-gray-500 mb-1" for="negcache-probe-url">URL</label>
              <InputText
                id="negcache-probe-url"
                v-model="probeUrl"
                placeholder="https://registry.example.com/npm-group/lodash  or  /maven-group/com/example/foo/1.0/foo-1.0.jar"
                class="w-full font-mono"
                @keyup.enter="doProbe"
              />
            </div>
            <Button label="Check" icon="pi pi-search" :loading="probeLoading" @click="doProbe" />
          </div>

          <div v-if="probeResult" class="mt-4 space-y-3" data-testid="negcache-probe-result">
            <div
              class="flex flex-wrap items-center justify-between gap-2 p-3 rounded-lg border"
              :class="probeResult.shadowed
                ? 'border-red-300 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300'
                : 'border-green-300 bg-green-50 text-green-700 dark:border-green-800 dark:bg-green-950/40 dark:text-green-300'"
              data-testid="negcache-probe-verdict"
            >
              <span class="flex items-center gap-2 font-medium">
                <i :class="probeResult.shadowed ? 'pi pi-ban' : 'pi pi-check-circle'" />
                {{ probeResult.shadowed
                  ? 'This URL is shadowed by the negative cache'
                  : 'This URL is not shadowed by the negative cache' }}
              </span>
              <router-link
                :to="{ path: '/admin/troubleshoot', query: { url: probedUrl } }"
                class="text-sm underline"
                data-testid="negcache-troubleshoot-link"
              >
                Troubleshoot this
              </router-link>
            </div>
            <DataTable :value="probeResult.keys" size="small" striped-rows>
              <Column header="Scope">
                <template #body="{ data }">{{ data.key.scope }}</template>
              </Column>
              <Column header="Type">
                <template #body="{ data }"><Tag :value="data.key.repoType" severity="info" /></template>
              </Column>
              <Column header="Package">
                <template #body="{ data }">
                  <span class="font-mono text-sm break-all">{{ data.key.artifactName }}</span>
                </template>
              </Column>
              <Column header="Version">
                <template #body="{ data }">
                  <span v-if="data.key.artifactVersion">{{ data.key.artifactVersion }}</span>
                  <span v-else class="text-gray-400 italic" title="Metadata (no version)">&mdash;</span>
                </template>
              </Column>
              <Column header="L1">
                <template #body="{ data }">
                  <Tag :value="data.l1 ? 'present' : 'absent'" :severity="data.l1 ? 'danger' : 'secondary'" />
                </template>
              </Column>
              <Column header="L2">
                <template #body="{ data }">
                  <Tag :value="data.l2 ? 'present' : 'absent'" :severity="data.l2 ? 'danger' : 'secondary'" />
                </template>
              </Column>
              <Column header="TTL">
                <template #body="{ data }">{{ formatDurationMs(data.ttlRemainingMs) }}</template>
              </Column>
              <Column header="">
                <template #body="{ data }">
                  <Button
                    v-if="data.l1 || data.l2"
                    label="Clear"
                    icon="pi pi-trash"
                    size="small"
                    severity="danger"
                    text
                    :loading="clearing.has(keyId(data.key))"
                    data-testid="negcache-probe-clear"
                    @click="clearKey(data.key)"
                  />
                </template>
              </Column>
              <template #empty>
                <div class="text-center text-gray-400 py-2">No keys derived for this URL</div>
              </template>
            </DataTable>
          </div>
        </template>
      </Card>

      <!-- Entries -->
      <Card class="shadow-sm">
        <template #title>
          <div class="flex items-center justify-between">
            <span>Entries</span>
            <span class="text-sm font-normal text-gray-400">{{ entriesTotal.toLocaleString() }} total</span>
          </div>
        </template>
        <template #content>
          <div class="flex flex-wrap items-end gap-3 mb-4">
            <div class="flex flex-col gap-1 flex-1 min-w-[16rem]">
              <label class="text-sm text-gray-500" for="negcache-search">Search</label>
              <span class="relative">
                <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 pointer-events-none" />
                <InputText
                  id="negcache-search"
                  v-model="search"
                  placeholder="Package, version or repository..."
                  class="w-full !pl-10"
                />
              </span>
            </div>
            <div class="flex flex-col gap-1">
              <label class="text-sm text-gray-500" for="negcache-scope">Repository</label>
              <Select
                id="negcache-scope"
                v-model="scopeFilter"
                :options="scopeOptions"
                option-label="label"
                option-value="value"
                placeholder="All repositories"
                filter
                class="w-52"
              />
            </div>
            <div class="flex flex-col gap-1">
              <label class="text-sm text-gray-500" for="negcache-type">Type</label>
              <Select
                id="negcache-type"
                v-model="typeFilter"
                :options="typeOptions"
                option-label="label"
                option-value="value"
                placeholder="All types"
                class="w-40"
              />
            </div>
          </div>

          <DataTable :value="entries" :loading="entriesLoading" striped-rows data-testid="negcache-table">
            <Column header="Scope">
              <template #body="{ data }">
                <span class="break-all">{{ data.key.scope }}</span>
              </template>
            </Column>
            <Column header="Type">
              <template #body="{ data }"><Tag :value="data.key.repoType" severity="info" /></template>
            </Column>
            <Column header="Package">
              <template #body="{ data }">
                <span class="font-mono text-sm break-all">{{ data.key.artifactName }}</span>
              </template>
            </Column>
            <Column header="Version">
              <template #body="{ data }">
                <span v-if="data.key.artifactVersion">{{ data.key.artifactVersion }}</span>
                <span v-else class="text-gray-400 italic" title="Metadata endpoint (no version)">&mdash;</span>
              </template>
            </Column>
            <Column header="Tiers">
              <template #body="{ data }">
                <span class="flex gap-1">
                  <Tag
                    v-for="t in data.tiers"
                    :key="t"
                    :value="t"
                    :severity="t === 'L1' ? 'success' : 'warn'"
                  />
                </span>
              </template>
            </Column>
            <Column header="TTL">
              <template #body="{ data }">
                <span :title="data.ttlRemainingMs === null ? 'L1-only entry: TTL not tracked' : undefined">
                  {{ formatDurationMs(data.ttlRemainingMs) }}
                </span>
              </template>
            </Column>
            <Column header="Actions">
              <template #body="{ data }">
                <span class="flex gap-1 whitespace-nowrap">
                  <Button
                    label="Clear"
                    icon="pi pi-trash"
                    size="small"
                    severity="danger"
                    text
                    :loading="clearing.has(keyId(data.key))"
                    data-testid="negcache-row-clear"
                    @click="clearKey(data.key)"
                  />
                  <Button
                    v-tooltip="`Clear all entries for ${data.key.artifactName} in every repository`"
                    label="Clear package"
                    icon="pi pi-eraser"
                    size="small"
                    severity="secondary"
                    text
                    data-testid="negcache-row-clear-package"
                    @click="askClearPackage(data.key)"
                  />
                </span>
              </template>
            </Column>
            <template #empty>
              <div class="text-center text-gray-400 py-4">No entries</div>
            </template>
          </DataTable>
          <Paginator
            v-if="entriesTotal > entriesSize"
            :rows="entriesSize"
            :total-records="entriesTotal"
            :first="entriesPage * entriesSize"
            @page="(e: any) => { entriesPage = e.page; entriesSize = e.rows; loadEntries() }"
          />
        </template>
      </Card>

      <Dialog
        v-model:visible="pkgConfirmVisible"
        header="Clear package"
        :modal="true"
        class="w-[28rem]"
      >
        <p v-if="pkgTarget">
          Clear all negative cache entries for
          <strong class="font-mono break-all">{{ pkgTarget.artifactName }}</strong>
          <span v-if="pkgTarget.repoType"> ({{ pkgTarget.repoType }})</span>
          in every repository, from L1 on all nodes and from L2?
        </p>
        <template #footer>
          <Button label="Cancel" text @click="pkgConfirmVisible = false" />
          <Button
            :label="pkgTarget ? `Clear all for ${pkgTarget.artifactName}` : 'Clear'"
            severity="danger"
            :loading="pkgLoading"
            data-testid="negcache-confirm-clear-package"
            @click="doClearPackage"
          />
        </template>
      </Dialog>

      <!-- Advanced -->
      <Panel header="Advanced" toggleable collapsed data-testid="negcache-advanced">
        <div class="flex items-center gap-2 mb-2">
          <span class="font-medium text-gray-900 dark:text-white">Invalidate by pattern</span>
          <Tag value="Rate limited: 10/min" severity="warn" />
        </div>
        <p class="text-sm text-gray-400 mb-4">
          Leave fields empty to match all. Each field uses exact match;
          use <code>*</code> as wildcard (e.g. <code>github.com/*</code>). Covers L1 on all nodes and L2.
        </p>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
          <div>
            <label class="block text-sm text-gray-500 mb-1">Scope (repo name)</label>
            <InputText v-model="patScope" placeholder="(all)" class="w-full" />
          </div>
          <div>
            <label class="block text-sm text-gray-500 mb-1">Repo type</label>
            <InputText v-model="patRepoType" placeholder="maven, go, npm... (all)" class="w-full" />
          </div>
          <div>
            <label class="block text-sm text-gray-500 mb-1">Artifact name</label>
            <InputText v-model="patArtifactName" placeholder="github.com/* (all)" class="w-full" />
          </div>
          <div>
            <label class="block text-sm text-gray-500 mb-1">Version</label>
            <InputText v-model="patVersion" placeholder="(empty=metadata, all=*)" class="w-full" />
          </div>
        </div>
        <Button
          label="Invalidate pattern"
          icon="pi pi-exclamation-triangle"
          severity="danger"
          :loading="patLoading"
          @click="showPatternConfirm"
        />
      </Panel>

      <Dialog
        v-model:visible="patConfirmVisible"
        header="Confirm Pattern Invalidation"
        :modal="true"
        :closable="true"
      >
        <p class="mb-4">
          This will remove all negative cache entries matching the specified pattern.
          Are you sure you want to proceed?
        </p>
        <div class="text-sm text-gray-500 space-y-1 mb-4">
          <div v-if="patScope">Scope: <strong>{{ patScope }}</strong></div>
          <div v-if="patRepoType">Type: <strong>{{ patRepoType }}</strong></div>
          <div v-if="patArtifactName">Artifact: <strong>{{ patArtifactName }}</strong></div>
          <div v-if="patVersion">Version: <strong>{{ patVersion }}</strong></div>
          <div v-if="!patScope && !patRepoType && !patArtifactName && !patVersion" class="text-red-500 font-medium">
            Warning: No filters specified. This will clear ALL entries.
          </div>
        </div>
        <template #footer>
          <Button label="Cancel" text @click="patConfirmVisible = false" />
          <Button label="Confirm Invalidation" severity="danger" @click="doInvalidatePattern" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
