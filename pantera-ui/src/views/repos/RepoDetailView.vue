<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getRepo, getTree, getArtifactDetail, deleteArtifacts } from '@/api/repos'
import { getArtifactVulnerabilities, triggerVulnerabilityScan, triggerRepoScan, getVulnerabilitySummary } from '@/api/vulns'
import { getApiClient } from '@/api/client'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import type { TreeEntry, ArtifactDetail, VulnerabilityReport, VulnerabilitySummary } from '@/types'

const props = defineProps<{ name: string }>()
const route = useRoute()
const notify = useNotificationStore()
const auth = useAuthStore()
const canDelete = computed(() => auth.user?.can_delete_artifacts === true || auth.hasAction('api_repository_permissions', 'delete'))

const repoConfig = ref<Record<string, unknown> | null>(null)
const repoType = computed(() => {
  if (!repoConfig.value) return ''
  const repo = repoConfig.value.repo as Record<string, unknown> | undefined
  return (repo?.type as string) ?? ''
})
const isProxy = computed(() => repoType.value.endsWith('-proxy'))
const isGroup = computed(() => repoType.value.endsWith('-group'))
const groupMembers = computed(() => {
  if (!repoConfig.value) return []
  const repo = repoConfig.value.repo as Record<string, unknown> | undefined
  const members = repo?.members as string[] | undefined
  return members ?? (repo?.remotes as string[] | undefined) ?? []
})
const treeItems = ref<TreeEntry[]>([])
const currentPath = ref('/')
const treeLoading = ref(false)
const marker = ref<string | null>(null)
const hasMore = ref(false)
const sortAsc = ref(true)

const sortedItems = computed(() => {
  return [...treeItems.value].sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1
    const cmp = a.name.localeCompare(b.name, undefined, { numeric: true })
    return sortAsc.value ? cmp : -cmp
  })
})

// Artifact detail dialog
const detailVisible = ref(false)
const selectedArtifact = ref<ArtifactDetail | null>(null)
const deleting = ref(false)
const downloading = ref(false)

// Vulnerability state for the detail dialog
const vulnReport = ref<VulnerabilityReport | null>(null)
const vulnLoading = ref(false)
const vulnScanning = ref(false)
const vulnNeverScanned = ref(false)
const canScan = auth.hasAction('api_repository_permissions', 'create')

// Repo-level scan state
const repoScanning = ref(false)
const repoScanProgress = ref<{ enqueued: number } | null>(null)

// Repo-level vulnerability summary
const repoVulnSummary = ref<VulnerabilitySummary | null>(null)

let treeAbortCtrl: AbortController | null = null
let repoAbortCtrl: AbortController | null = null

async function loadRepo() {
  if (repoAbortCtrl) repoAbortCtrl.abort()
  if (treeAbortCtrl) treeAbortCtrl.abort()
  repoAbortCtrl = new AbortController()
  const ctrl = repoAbortCtrl
  repoConfig.value = null
  treeItems.value = []
  currentPath.value = '/'
  marker.value = null
  hasMore.value = false
  try {
    repoConfig.value = await getRepo(props.name)
    if (ctrl.signal.aborted) return
    // Load vulnerability summary for this repo
    getVulnerabilitySummary().then(all => {
      repoVulnSummary.value = all.find(s => s.repo_name === props.name) ?? null
    }).catch(() => {})
    // Group repos show members, not artifact tree
    if (!isGroup.value) {
      const qpath = route.query.path as string | undefined
      if (qpath) {
        const cleanPath = qpath.startsWith('/') ? qpath : '/' + qpath
        await loadTree(cleanPath)
      } else {
        await loadTree('/')
      }
    }
  } catch (err: unknown) {
    if (ctrl.signal.aborted) return
  }
}

onMounted(loadRepo)
onBeforeUnmount(() => {
  if (treeAbortCtrl) treeAbortCtrl.abort()
  if (repoAbortCtrl) repoAbortCtrl.abort()
})
watch(() => props.name, loadRepo)

async function loadTree(path: string) {
  if (treeAbortCtrl) treeAbortCtrl.abort()
  treeAbortCtrl = new AbortController()
  const ctrl = treeAbortCtrl
  treeLoading.value = true
  currentPath.value = path
  try {
    const resp = await getTree(props.name, { path }, ctrl.signal)
    if (ctrl.signal.aborted) return
    treeItems.value = resp.items
    marker.value = resp.marker
    hasMore.value = resp.hasMore
  } catch (err: unknown) {
    if (ctrl.signal.aborted) return
    treeItems.value = []
  } finally {
    if (!ctrl.signal.aborted) treeLoading.value = false
  }
}

async function loadMore() {
  if (!marker.value) return
  treeLoading.value = true
  try {
    const resp = await getTree(props.name, { path: currentPath.value, marker: marker.value })
    treeItems.value.push(...resp.items)
    marker.value = resp.marker
    hasMore.value = resp.hasMore
  } finally {
    treeLoading.value = false
  }
}

function navigateTree(entry: TreeEntry) {
  if (entry.type === 'directory') {
    loadTree(entry.path)
  } else {
    showArtifactDetail(entry.path)
  }
}

async function showArtifactDetail(path: string) {
  try {
    selectedArtifact.value = await getArtifactDetail(props.name, path)
    detailVisible.value = true
    // Load vulnerability report in background (don't block dialog opening)
    loadVulnReport(path)
  } catch {
    // ignore
  }
}

async function loadVulnReport(path: string) {
  vulnReport.value = null
  vulnNeverScanned.value = false
  vulnLoading.value = true
  try {
    const report = await getArtifactVulnerabilities(props.name, path)
    if (report === null) {
      vulnNeverScanned.value = true
    } else {
      vulnReport.value = report
    }
  } catch {
    // Scanning might not be enabled — silently skip
  } finally {
    vulnLoading.value = false
  }
}

async function handleScanNow() {
  if (!selectedArtifact.value) return
  vulnScanning.value = true
  try {
    vulnReport.value = await triggerVulnerabilityScan(props.name, selectedArtifact.value.path)
    vulnNeverScanned.value = false
    notify.success('Scan complete', `${vulnReport.value.vuln_count} finding(s) found`)
  } catch {
    notify.error('Scan failed', 'Could not scan this artifact')
  } finally {
    vulnScanning.value = false
  }
}

async function handleScanRepo() {
  if (repoScanning.value) return
  repoScanning.value = true
  repoScanProgress.value = null
  try {
    const resp = await triggerRepoScan(props.name)
    repoScanProgress.value = { enqueued: resp.enqueued }
    notify.info(`Scanning ${props.name}`, `${resp.enqueued} artifact(s) queued — results will appear in the Vulnerabilities page`)
  } catch {
    notify.error('Scan failed', `Could not start repository scan`)
  } finally {
    // Keep the spinner a few seconds so user sees feedback, then reset
    setTimeout(() => {
      repoScanning.value = false
      repoScanProgress.value = null
    }, 4000)
  }
}

function severityClass(sev: string): string {
  switch (sev) {
    case 'CRITICAL': return 'text-red-600 dark:text-red-400 font-bold'
    case 'HIGH': return 'text-orange-500 dark:text-orange-400 font-semibold'
    case 'MEDIUM': return 'text-yellow-500 dark:text-yellow-400'
    default: return 'text-gray-400'
  }
}

function vulnSeverityTagType(sev: string): 'danger' | 'warn' | 'info' | 'secondary' {
  switch (sev) {
    case 'CRITICAL': return 'danger'
    case 'HIGH': return 'warn'
    case 'MEDIUM': return 'info'
    default: return 'secondary'
  }
}

function formatScannedAt(iso: string): string {
  const secs = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)
  if (secs < 60) return 'just now'
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`
  return `${Math.floor(secs / 86400)}d ago`
}

async function handleDeleteArtifact(path: string) {
  deleting.value = true
  try {
    await deleteArtifacts(props.name, path)
    notify.success('Artifact deleted', path)
    detailVisible.value = false
    await loadTree(currentPath.value)
  } catch {
    notify.error('Failed to delete artifact')
  } finally {
    deleting.value = false
  }
}

function goUp() {
  const parts = currentPath.value.split('/').filter(Boolean)
  parts.pop()
  loadTree('/' + parts.join('/'))
}

const breadcrumbItems = computed(() => {
  const parts = currentPath.value.split('/').filter(Boolean)
  return parts.map((p, i) => ({
    label: p,
    command: () => loadTree('/' + parts.slice(0, i + 1).join('/')),
  }))
})

const breadcrumbHome = { icon: 'pi pi-home', command: () => loadTree('/') }

async function downloadArtifact(path: string) {
  downloading.value = true
  try {
    const client = getApiClient()
    // Step 1: Get a single-use download token (JWT-auth'd)
    const resp = await client.post(
      `/repositories/${props.name}/artifact/download-token`,
      null,
      { params: { path } },
    )
    const token = resp.data.token as string
    // Step 2: Navigate browser directly to the token-based URL.
    // The browser's native download manager handles progress, disk streaming,
    // and memory — no JS blob buffering needed.
    const base = client.defaults.baseURL ?? ''
    const url = `${base}/repositories/${props.name}/artifact/download-direct`
      + `?path=${encodeURIComponent(path)}&token=${encodeURIComponent(token)}`
    window.open(url, '_blank')
  } catch {
    notify.error('Failed to download artifact')
  } finally {
    downloading.value = false
  }
}

function formatSize(bytes?: number): string {
  if (!bytes) return '-'
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <!-- Header -->
      <div class="flex items-center gap-3">
        <router-link v-if="route.query.from === 'search'" to="/search" class="text-blue-500 hover:underline text-sm">
          <i class="pi pi-arrow-left mr-1" /> Back to Search
        </router-link>
        <router-link v-else to="/repositories" class="text-blue-500 hover:underline text-sm">
          <i class="pi pi-arrow-left mr-1" /> Repositories
        </router-link>
      </div>

      <div v-if="repoConfig" class="flex items-center justify-between gap-3">
        <div class="flex items-center gap-3">
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">{{ name }}</h1>
          <RepoTypeBadge v-if="repoType" :type="repoType" size="md" />
        </div>
        <Button
          v-if="canScan"
          icon="pi pi-shield"
          :label="repoScanProgress ? `${repoScanProgress.enqueued} artifact(s) queued…` : 'Scan Repository'"
          size="small"
          severity="secondary"
          :loading="repoScanning"
          :disabled="repoScanning"
          @click="handleScanRepo"
        />
      </div>

      <!-- Vulnerability summary -->
      <div v-if="repoVulnSummary" class="flex items-center gap-3 text-sm flex-wrap">
        <span class="text-gray-500 dark:text-gray-400 flex items-center gap-1">
          <i class="pi pi-shield text-gray-400" />
          {{ repoVulnSummary.scanned_artifacts }} artifact{{ repoVulnSummary.scanned_artifacts !== 1 ? 's' : '' }} scanned
          <span v-if="repoVulnSummary.last_scanned" class="text-gray-400">· last {{ formatScannedAt(repoVulnSummary.last_scanned) }}</span>
        </span>
        <template v-if="repoVulnSummary.vuln_count > 0">
          <Tag v-if="repoVulnSummary.critical > 0" :value="`${repoVulnSummary.critical} CRITICAL`" severity="danger" class="text-xs" />
          <Tag v-if="repoVulnSummary.high > 0"     :value="`${repoVulnSummary.high} HIGH`"         severity="warn"   class="text-xs" />
          <Tag v-if="repoVulnSummary.medium > 0"   :value="`${repoVulnSummary.medium} MEDIUM`"     severity="info"   class="text-xs" />
          <Tag v-if="repoVulnSummary.low > 0"      :value="`${repoVulnSummary.low} LOW`"           severity="secondary" class="text-xs" />
        </template>
        <span v-else class="text-xs text-green-600 dark:text-green-400 font-medium">✓ No vulnerabilities found</span>
      </div>

      <!-- Proxy cache banner -->
      <Message v-if="isProxy" severity="warn" :closable="false" class="mb-0">
        <p class="font-semibold mb-1">Proxy Cache View</p>
        <p class="text-sm">
          This listing only shows artifacts that have been cached locally. It is a snapshot from the
          time each artifact was first requested through this proxy and does not reflect the current
          state of the upstream repository. If an artifact is not listed here, it has likely never
          been requested through this proxy.
        </p>
      </Message>

      <!-- Group: Members list -->
      <template v-if="isGroup">
        <Message severity="info" :closable="false" class="mb-0">
          <p class="font-semibold mb-1">Virtual Group Repository</p>
          <p class="text-sm">
            This is a virtual repository that aggregates artifacts from its member repositories.
            Artifacts are not stored here directly — they reside in the individual member
            repositories listed below.
          </p>
        </Message>

        <Card class="shadow-sm">
          <template #title>Members</template>
          <template #content>
            <div v-if="groupMembers.length === 0" class="text-gray-400 text-center py-8">
              No members configured
            </div>
            <div v-else class="divide-y divide-gray-100 dark:divide-gray-700">
              <router-link
                v-for="member in groupMembers"
                :key="member"
                :to="{ name: 'repo-detail', params: { name: member } }"
                class="flex items-center gap-3 py-2 px-2 hover:bg-gray-50 dark:hover:bg-gray-700 rounded no-underline"
              >
                <i class="pi pi-box text-blue-500" />
                <span class="flex-1 text-sm font-mono text-gray-800 dark:text-gray-200">{{ member }}</span>
                <i class="pi pi-chevron-right text-gray-300" />
              </router-link>
            </div>
          </template>
        </Card>
      </template>

      <!-- Artifact Tree (non-group repos) -->
      <Card v-else class="shadow-sm">
        <template #title>
          <div class="flex items-center justify-between">
            <span>Artifacts</span>
            <div class="flex items-center gap-1">
              <Button
                :icon="sortAsc ? 'pi pi-sort-alpha-down' : 'pi pi-sort-alpha-up'"
                :title="sortAsc ? 'A → Z' : 'Z → A'"
                text size="small"
                @click="sortAsc = !sortAsc"
              />
              <Button v-if="currentPath !== '/'" icon="pi pi-arrow-up" label="Up" text size="small" @click="goUp" />
            </div>
          </div>
        </template>
        <template #content>
          <Breadcrumb :model="breadcrumbItems" :home="breadcrumbHome" class="mb-4" />

          <div v-if="treeLoading && treeItems.length === 0" class="text-gray-400 text-center py-8">
            <i class="pi pi-spin pi-spinner text-2xl" />
          </div>

          <div v-else-if="treeItems.length === 0" class="text-gray-400 text-center py-8">
            No artifacts in this directory
          </div>

          <div v-else class="divide-y divide-gray-100 dark:divide-gray-700">
            <div
              v-for="entry in sortedItems"
              :key="entry.path"
              class="flex items-center gap-3 py-2 px-2 hover:bg-gray-50 dark:hover:bg-gray-700 cursor-pointer rounded"
              @click="navigateTree(entry)"
            >
              <i :class="entry.type === 'directory' ? 'pi pi-folder text-yellow-500' : 'pi pi-file text-gray-400'" />
              <span class="flex-1 text-sm font-mono text-gray-800 dark:text-gray-200">{{ entry.name }}</span>
              <span v-if="entry.size" class="text-xs text-gray-400">{{ formatSize(entry.size) }}</span>
              <i class="pi pi-chevron-right text-gray-300" />
            </div>
          </div>

          <div v-if="hasMore" class="text-center mt-4">
            <Button label="Load more" severity="secondary" text :loading="treeLoading" @click="loadMore" />
          </div>
        </template>
      </Card>

      <!-- Artifact Detail Dialog -->
      <Dialog v-model:visible="detailVisible" header="Artifact Detail" modal class="w-full max-w-2xl">
        <div v-if="selectedArtifact" class="space-y-4">
          <!-- Basic info -->
          <div class="space-y-2">
            <div><strong>Path:</strong> <span class="font-mono text-sm">{{ selectedArtifact.path }}</span></div>
            <div><strong>Size:</strong> {{ selectedArtifact.size > 0 ? formatSize(selectedArtifact.size) : '—' }}</div>
            <div v-if="selectedArtifact.modified"><strong>Modified:</strong> {{ selectedArtifact.modified }}</div>
          </div>

          <!-- Actions -->
          <div class="flex gap-2">
            <Button
              icon="pi pi-download"
              label="Download"
              :loading="downloading"
              @click="downloadArtifact(selectedArtifact!.path)"
            />
            <Button
              v-if="canDelete"
              icon="pi pi-trash"
              label="Delete"
              severity="danger"
              :loading="deleting"
              @click="handleDeleteArtifact(selectedArtifact!.path)"
            />
          </div>

          <!-- Security Vulnerabilities section -->
          <div class="border-t border-gray-200 dark:border-gray-700 pt-4">
            <div class="flex items-center justify-between mb-3">
              <div class="flex items-center gap-2">
                <i class="pi pi-shield text-gray-500" />
                <span class="font-semibold text-sm text-gray-700 dark:text-gray-300">
                  Security Vulnerabilities
                </span>
                <!-- Severity summary badges -->
                <template v-if="vulnReport">
                  <Tag
                    v-if="vulnReport.critical > 0"
                    :value="`${vulnReport.critical} C`"
                    severity="danger"
                    class="text-xs"
                  />
                  <Tag
                    v-if="vulnReport.high > 0"
                    :value="`${vulnReport.high} H`"
                    severity="warn"
                    class="text-xs"
                  />
                  <Tag
                    v-if="vulnReport.medium > 0"
                    :value="`${vulnReport.medium} M`"
                    severity="info"
                    class="text-xs"
                  />
                  <Tag
                    v-if="vulnReport.low > 0"
                    :value="`${vulnReport.low} L`"
                    severity="secondary"
                    class="text-xs"
                  />
                  <span
                    v-if="vulnReport.vuln_count === 0"
                    class="text-xs text-green-600 dark:text-green-400 font-medium"
                  >
                    ✓ No vulnerabilities found
                  </span>
                </template>
              </div>
              <div class="flex items-center gap-2">
                <!-- Stale indicator -->
                <span
                  v-if="vulnReport?.is_stale"
                  class="text-xs text-yellow-500"
                  title="Scan results may be outdated"
                >
                  ⚠ Stale
                </span>
                <!-- Last scanned -->
                <span v-if="vulnReport" class="text-xs text-gray-400">
                  Scanned {{ formatScannedAt(vulnReport.scanned_at) }}
                </span>
                <!-- Scan Now button -->
                <Button
                  v-if="canScan"
                  label="Scan Now"
                  icon="pi pi-refresh"
                  size="small"
                  text
                  :loading="vulnScanning"
                  @click="handleScanNow"
                />
              </div>
            </div>

            <!-- Loading state -->
            <div v-if="vulnLoading" class="text-center text-gray-400 text-sm py-4">
              <i class="pi pi-spin pi-spinner mr-1" /> Loading scan results...
            </div>

            <!-- Never scanned -->
            <div
              v-else-if="vulnNeverScanned"
              class="text-sm text-gray-400 py-2 flex items-center gap-2"
            >
              <i class="pi pi-info-circle" />
              Not yet scanned.
              <Button
                v-if="canScan"
                label="Scan Now"
                size="small"
                text
                :loading="vulnScanning"
                @click="handleScanNow"
              />
            </div>

            <!-- CVE findings table -->
            <DataTable
              v-else-if="vulnReport && vulnReport.vuln_count > 0"
              :value="vulnReport.findings"
              size="small"
              stripedRows
              class="text-sm"
            >
              <Column field="cve_id" header="CVE ID">
                <template #body="{ data }">
                  <a
                    :href="data.cve_id.startsWith('GHSA-') ? `https://github.com/advisories/${data.cve_id}` : `https://avd.aquasec.com/nvd/${data.cve_id.toLowerCase()}`"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="font-mono text-xs text-blue-500 hover:underline"
                  >
                    {{ data.cve_id }}
                  </a>
                </template>
              </Column>
              <Column field="severity" header="Severity">
                <template #body="{ data }">
                  <Tag
                    :value="data.severity"
                    :severity="vulnSeverityTagType(data.severity)"
                    class="text-xs"
                  />
                </template>
              </Column>
              <Column field="package_name" header="Package">
                <template #body="{ data }">
                  <span class="font-mono text-xs">{{ data.package_name }}</span>
                </template>
              </Column>
              <Column header="Version">
                <template #body="{ data }">
                  <span class="font-mono text-xs text-red-500">{{ data.installed_version }}</span>
                  <span v-if="data.fixed_version" class="text-gray-400 text-xs mx-1">→</span>
                  <span v-if="data.fixed_version" class="font-mono text-xs text-green-500">
                    {{ data.fixed_version }}
                  </span>
                </template>
              </Column>
            </DataTable>
          </div>
        </div>
      </Dialog>
    </div>
  </AppLayout>
</template>
