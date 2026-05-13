<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { getApiClient } from '@/api/client'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import Paginator from 'primevue/paginator'
import Dialog from 'primevue/dialog'
import TabView from 'primevue/tabview'
import TabPanel from 'primevue/tabpanel'

const notify = useNotificationStore()

// --- Inspector state ---
interface NegCacheEntry {
  key: { scope: string; repoType: string; artifactName: string; artifactVersion: string }
  tier: string
  ttlRemainingMs: number
}

const entries = ref<NegCacheEntry[]>([])
const entriesTotal = ref(0)
const entriesPage = ref(0)
const entriesSize = ref(50)
const entriesLoading = ref(false)
const filterScope = ref('')
const filterType = ref('')
const filterName = ref('')
const filterVersion = ref('')
let filterTimeout: ReturnType<typeof setTimeout> | null = null

// --- Probe state ---
const probeKey = ref('')
const probeResult = ref<{ present: boolean; tiers?: string[] } | null>(null)
const probeLoading = ref(false)

// --- Single invalidation state ---
const invScope = ref('')
const invRepoType = ref('')
const invArtifactName = ref('')
const invVersion = ref('')
const invLoading = ref(false)

// --- Pattern invalidation state ---
const patScope = ref('')
const patRepoType = ref('')
const patArtifactName = ref('')
const patVersion = ref('')
const patLoading = ref(false)
const patConfirmVisible = ref(false)

// --- Stats state ---
interface NegCacheStats {
  enabled: boolean
  l1Size: number
  hitCount: number
  missCount: number
  hitRate: number
  evictionCount: number
  requestCount: number
}
const stats = ref<NegCacheStats | null>(null)
const statsLoading = ref(false)

// --- Watchers for filter debounce ---
function onFilterChange() {
  if (filterTimeout) clearTimeout(filterTimeout)
  filterTimeout = setTimeout(() => {
    entriesPage.value = 0
    loadEntries()
  }, 400)
}

watch(filterScope, onFilterChange)
watch(filterType, onFilterChange)
watch(filterName, onFilterChange)
watch(filterVersion, onFilterChange)

onBeforeUnmount(() => {
  if (filterTimeout) clearTimeout(filterTimeout)
})

// --- API calls ---
async function loadEntries() {
  entriesLoading.value = true
  try {
    const params: Record<string, unknown> = {
      page: entriesPage.value,
      pageSize: entriesSize.value,
    }
    if (filterScope.value.trim()) params.scope = filterScope.value.trim()
    if (filterType.value.trim()) params.repoType = filterType.value.trim()
    if (filterName.value.trim()) params.artifactName = filterName.value.trim()
    if (filterVersion.value.trim()) params.version = filterVersion.value.trim()
    const { data } = await getApiClient().get('/admin/neg-cache', { params })
    entries.value = data.items ?? []
    entriesTotal.value = data.total ?? 0
  } catch {
    entries.value = []
    entriesTotal.value = 0
  } finally {
    entriesLoading.value = false
  }
}

async function doProbe() {
  if (!probeKey.value.trim()) return
  probeLoading.value = true
  probeResult.value = null
  try {
    const { data } = await getApiClient().get('/admin/neg-cache/probe', {
      params: { key: probeKey.value.trim() },
    })
    probeResult.value = data
  } catch {
    notify.error('Probe failed')
  } finally {
    probeLoading.value = false
  }
}

async function doInvalidateSingle() {
  if (!invScope.value || !invRepoType.value || !invArtifactName.value) {
    notify.error('Scope, Type, and Artifact Name are required')
    return
  }
  invLoading.value = true
  try {
    const { data } = await getApiClient().post('/admin/neg-cache/invalidate', {
      scope: invScope.value,
      repoType: invRepoType.value,
      artifactName: invArtifactName.value,
      version: invVersion.value,
    })
    const inv = data.invalidated
    notify.success(
      'Invalidated',
      `L1: ${inv?.l1 ?? 0}, L2: ${inv?.l2 ?? 0}`,
    )
    loadEntries()
  } catch {
    notify.error('Invalidation failed')
  } finally {
    invLoading.value = false
  }
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
    const { data } = await getApiClient().post('/admin/neg-cache/invalidate-pattern', body)
    const inv = data.invalidated
    notify.success(
      'Pattern invalidated',
      `L1: ${inv?.l1 ?? 0}, L2: ${inv?.l2 ?? 0}`,
    )
    loadEntries()
    loadStats()
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

async function loadStats() {
  statsLoading.value = true
  try {
    const { data } = await getApiClient().get('/admin/neg-cache/stats')
    stats.value = data
  } catch {
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}

onMounted(() => {
  loadEntries()
  loadStats()
})
</script>

<template>
  <AppLayout>
    <div class="space-y-6">
      <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Negative Cache</h1>

      <TabView>
        <!-- Inspector Tab -->
        <TabPanel header="Inspector">
          <Card class="shadow-sm">
            <template #title>
              <div class="flex items-center justify-between">
                <span>L1 Cache Entries</span>
                <span class="text-sm font-normal text-gray-400">{{ entriesTotal }} total</span>
              </div>
            </template>
            <template #content>
              <p class="text-xs text-gray-400 mb-2">
                Filters use exact match. Use <code>*</code> as wildcard
                (e.g. <code>github.com/*</code>).
              </p>
              <div class="grid grid-cols-4 gap-3 mb-4">
                <InputText v-model="filterScope" placeholder="Scope (repo name)..." class="w-full" />
                <InputText v-model="filterType" placeholder="Repo type (maven, go...)" class="w-full" />
                <InputText v-model="filterName" placeholder="Artifact name..." class="w-full" />
                <InputText v-model="filterVersion" placeholder="Version (empty for metadata)" class="w-full" />
              </div>
              <DataTable :value="entries" :loading="entriesLoading" stripedRows>
                <Column header="Scope">
                  <template #body="{ data }">{{ data.key.scope }}</template>
                </Column>
                <Column header="Type">
                  <template #body="{ data }">
                    <Tag :value="data.key.repoType" severity="info" />
                  </template>
                </Column>
                <Column header="Artifact">
                  <template #body="{ data }">
                    <span class="font-mono text-sm">{{ data.key.artifactName }}</span>
                  </template>
                </Column>
                <Column header="Version">
                  <template #body="{ data }">
                    <span v-if="data.key.artifactVersion">{{ data.key.artifactVersion }}</span>
                    <span v-else class="text-gray-400 italic" title="Metadata endpoint (no version)">&mdash;</span>
                  </template>
                </Column>
                <Column header="Tier">
                  <template #body="{ data }">
                    <Tag :value="data.tier" :severity="data.tier === 'L1' ? 'success' : 'warn'" />
                  </template>
                </Column>
                <template #empty>
                  <div class="text-center text-gray-400 py-4">No entries</div>
                </template>
              </DataTable>
              <Paginator
                v-if="entriesTotal > entriesSize"
                :rows="entriesSize"
                :totalRecords="entriesTotal"
                :first="entriesPage * entriesSize"
                @page="(e: any) => { entriesPage = e.page; entriesSize = e.rows; loadEntries() }"
              />
            </template>
          </Card>

          <!-- Probe -->
          <Card class="shadow-sm mt-4">
            <template #title>Probe Key</template>
            <template #content>
              <div class="flex gap-3 items-end">
                <div class="flex-1">
                  <label class="block text-sm text-gray-500 mb-1">Key (scope:type:name:version)</label>
                  <InputText
                    v-model="probeKey"
                    placeholder="my-group:maven:com.example:foo:1.0.0"
                    class="w-full font-mono"
                    @keyup.enter="doProbe"
                  />
                </div>
                <Button label="Probe" icon="pi pi-search" :loading="probeLoading" @click="doProbe" />
              </div>
              <div v-if="probeResult" class="mt-3 p-3 rounded-lg bg-gray-50 dark:bg-gray-800">
                <div class="flex items-center gap-2">
                  <Tag
                    :value="probeResult.present ? 'PRESENT' : 'NOT FOUND'"
                    :severity="probeResult.present ? 'danger' : 'success'"
                  />
                  <span v-if="probeResult.tiers" class="text-sm text-gray-500">
                    Tiers: {{ probeResult.tiers.join(', ') }}
                  </span>
                </div>
              </div>
            </template>
          </Card>
        </TabPanel>

        <!-- Invalidation Tab -->
        <TabPanel header="Invalidation">
          <!-- Single key invalidation -->
          <Card class="shadow-sm">
            <template #title>Invalidate Single Key</template>
            <template #content>
              <div class="grid grid-cols-2 gap-3 mb-4">
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Scope *</label>
                  <InputText v-model="invScope" placeholder="my-group" class="w-full" />
                </div>
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Repo Type *</label>
                  <InputText v-model="invRepoType" placeholder="maven" class="w-full" />
                </div>
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Artifact Name *</label>
                  <InputText v-model="invArtifactName" placeholder="com.example:foo" class="w-full" />
                </div>
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Version</label>
                  <InputText v-model="invVersion" placeholder="1.0.0" class="w-full" />
                </div>
              </div>
              <Button
                label="Invalidate"
                icon="pi pi-trash"
                severity="danger"
                :loading="invLoading"
                @click="doInvalidateSingle"
              />
            </template>
          </Card>

          <!-- Pattern invalidation -->
          <Card class="shadow-sm mt-4">
            <template #title>
              <div class="flex items-center gap-2">
                <span>Invalidate by Pattern</span>
                <Tag value="Rate Limited: 10/min" severity="warn" />
              </div>
            </template>
            <template #content>
              <p class="text-sm text-gray-400 mb-4">
                Leave fields empty to match all. Each field uses exact match;
                use <code>*</code> as wildcard (e.g. <code>github.com/*</code>).
              </p>
              <div class="grid grid-cols-2 gap-3 mb-4">
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Scope (repo name)</label>
                  <InputText v-model="patScope" placeholder="(all)" class="w-full" />
                </div>
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Repo Type</label>
                  <InputText v-model="patRepoType" placeholder="maven, go, npm... (all)" class="w-full" />
                </div>
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Artifact Name</label>
                  <InputText v-model="patArtifactName" placeholder="github.com/* (all)" class="w-full" />
                </div>
                <div>
                  <label class="block text-sm text-gray-500 mb-1">Version</label>
                  <InputText v-model="patVersion" placeholder="(empty=metadata, all=*)" class="w-full" />
                </div>
              </div>
              <Button
                label="Invalidate Pattern"
                icon="pi pi-exclamation-triangle"
                severity="danger"
                :loading="patLoading"
                @click="showPatternConfirm"
              />
            </template>
          </Card>

          <!-- Confirmation dialog -->
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
        </TabPanel>

        <!-- Stats Tab -->
        <TabPanel header="Stats">
          <Card class="shadow-sm">
            <template #title>
              <div class="flex items-center justify-between">
                <span>Cache Statistics</span>
                <Button icon="pi pi-refresh" text size="small" :loading="statsLoading" @click="loadStats" />
              </div>
            </template>
            <template #content>
              <div v-if="stats" class="grid grid-cols-2 md:grid-cols-4 gap-4">
                <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
                  <div class="text-sm text-gray-500">Status</div>
                  <div class="text-lg font-semibold mt-1">
                    <Tag :value="stats.enabled ? 'Enabled' : 'Disabled'" :severity="stats.enabled ? 'success' : 'danger'" />
                  </div>
                </div>
                <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
                  <div class="text-sm text-gray-500">L1 Size</div>
                  <div class="text-2xl font-bold mt-1">{{ stats.l1Size.toLocaleString() }}</div>
                </div>
                <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
                  <div class="text-sm text-gray-500">Hit Rate</div>
                  <div class="text-2xl font-bold mt-1">{{ (stats.hitRate * 100).toFixed(1) }}%</div>
                </div>
                <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
                  <div class="text-sm text-gray-500">Requests</div>
                  <div class="text-2xl font-bold mt-1">{{ stats.requestCount.toLocaleString() }}</div>
                </div>
                <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
                  <div class="text-sm text-gray-500">Hits</div>
                  <div class="text-2xl font-bold mt-1 text-green-600">{{ stats.hitCount.toLocaleString() }}</div>
                </div>
                <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
                  <div class="text-sm text-gray-500">Misses</div>
                  <div class="text-2xl font-bold mt-1 text-amber-600">{{ stats.missCount.toLocaleString() }}</div>
                </div>
                <div class="p-4 rounded-lg bg-gray-50 dark:bg-gray-800">
                  <div class="text-sm text-gray-500">Evictions</div>
                  <div class="text-2xl font-bold mt-1 text-red-600">{{ stats.evictionCount.toLocaleString() }}</div>
                </div>
              </div>
              <div v-else class="text-gray-400 text-center py-8">
                {{ statsLoading ? 'Loading...' : 'No statistics available' }}
              </div>
            </template>
          </Card>
        </TabPanel>
      </TabView>
    </div>
  </AppLayout>
</template>
