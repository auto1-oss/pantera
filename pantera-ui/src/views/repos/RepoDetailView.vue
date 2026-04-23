<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getRepo, getTree, getArtifactDetail, deleteArtifacts } from '@/api/repos'
import { getApiClient } from '@/api/client'
import { yankVersion, unyankVersion } from '@/api/pypi'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import Dialog from 'primevue/dialog'
import Textarea from 'primevue/textarea'
import Message from 'primevue/message'
import Select from 'primevue/select'
import type { TreeEntry, ArtifactDetail } from '@/types'

const props = defineProps<{ name: string }>()
const route = useRoute()
const notify = useNotificationStore()
const auth = useAuthStore()
const canDelete = computed(() => auth.user?.can_delete_artifacts === true || auth.hasAction('api_repository_permissions', 'delete'))

// PyPI yank/unyank
const isPypi = computed(() => repoType.value === 'pypi' || repoType.value === 'pypi-proxy' || repoType.value === 'pypi-group')
const yankDialogVisible = ref(false)
const yankTarget = ref<{ pkg: string; version: string } | null>(null)
const yankReason = ref('')
const yankLoading = ref(false)

/** Parse a PyPI tree-entry path /<pkg>/<version>/<file> → { pkg, version } */
function parsePypiCoords(entryPath: string): { pkg: string; version: string } | null {
  const parts = entryPath.replace(/^\//, '').split('/')
  if (parts.length < 2) return null
  return { pkg: parts[0], version: parts[1] }
}

function openYankDialog(entry: TreeEntry, event: Event) {
  event.stopPropagation()
  const coords = parsePypiCoords(entry.path)
  if (!coords) return
  yankTarget.value = coords
  yankReason.value = ''
  yankDialogVisible.value = true
}

async function confirmYank() {
  if (!yankTarget.value) return
  yankLoading.value = true
  try {
    await yankVersion(props.name, yankTarget.value.pkg, yankTarget.value.version, yankReason.value)
    notify.success('Version yanked', `${yankTarget.value.pkg} ${yankTarget.value.version}`)
    yankDialogVisible.value = false
    await loadTree(currentPath.value)
  } catch {
    notify.error('Failed to yank version')
  } finally {
    yankLoading.value = false
  }
}

async function handleUnyank(entry: TreeEntry, event: Event) {
  event.stopPropagation()
  const coords = parsePypiCoords(entry.path)
  if (!coords) return
  try {
    await unyankVersion(props.name, coords.pkg, coords.version)
    notify.success('Version unyanked', `${coords.pkg} ${coords.version}`)
    await loadTree(currentPath.value)
  } catch {
    notify.error('Failed to unyank version')
  }
}

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
const sortBy = ref<'name' | 'date'>('name')

// Server is authoritative for ordering after 2.2.0 — treeItems comes back
// already sorted by the sort params sent on load. Kept as a computed ref
// to keep the template unchanged; directories-first is still enforced here
// as a belt-and-braces guard if a server path ever returns mixed order.
const sortedItems = computed(() => {
  return [...treeItems.value].sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1
    return 0
  })
})

function onSortChange() {
  loadTree(currentPath.value)
}

// Artifact detail dialog
const detailVisible = ref(false)
const selectedArtifact = ref<ArtifactDetail | null>(null)
const deleting = ref(false)
const downloading = ref(false)

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
    const resp = await getTree(props.name, {
      path,
      sort: sortBy.value,
      sort_dir: sortAsc.value ? 'asc' : 'desc',
    }, ctrl.signal)
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
    const resp = await getTree(props.name, {
      path: currentPath.value,
      marker: marker.value,
      sort: sortBy.value,
      sort_dir: sortAsc.value ? 'asc' : 'desc',
    })
    treeItems.value.push(...resp.items)
    marker.value = resp.marker
    hasMore.value = resp.hasMore
  } finally {
    treeLoading.value = false
  }
}

function formatModified(iso?: string | null): string {
  if (!iso) return ''
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return ''
  const diffMs = Date.now() - ts
  const sec = Math.round(diffMs / 1000)
  if (sec < 60) return `${sec}s ago`
  const min = Math.round(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.round(min / 60)
  if (hr < 48) return `${hr}h ago`
  const day = Math.round(hr / 24)
  if (day < 30) return `${day}d ago`
  const mon = Math.round(day / 30)
  if (mon < 18) return `${mon}mo ago`
  return `${Math.round(mon / 12)}y ago`
}

function formatModifiedAbsolute(iso?: string | null): string {
  if (!iso) return ''
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return ''
  return new Date(ts).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
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
  } catch {
    // ignore
  }
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

      <div v-if="repoConfig" class="flex items-center gap-3">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">{{ name }}</h1>
        <RepoTypeBadge v-if="repoType" :type="repoType" size="md" />
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
            <div class="flex items-center gap-2">
              <Select
                v-model="sortBy"
                :options="[
                  { label: 'Name', value: 'name' },
                  { label: 'Date', value: 'date' },
                ]"
                option-label="label"
                option-value="value"
                size="small"
                class="!text-xs"
                @change="onSortChange"
              />
              <Button
                :icon="sortAsc
                  ? (sortBy === 'date' ? 'pi pi-sort-amount-up-alt' : 'pi pi-sort-alpha-down')
                  : (sortBy === 'date' ? 'pi pi-sort-amount-down' : 'pi pi-sort-alpha-up')"
                :title="sortAsc ? 'Ascending' : 'Descending'"
                text size="small"
                @click="sortAsc = !sortAsc; onSortChange()"
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
              <span v-if="entry.size" class="text-xs text-gray-400 w-16 text-right">{{ formatSize(entry.size) }}</span>
              <span
                v-if="entry.modified"
                class="text-xs text-gray-400 w-20 text-right tabular-nums"
                :title="formatModifiedAbsolute(entry.modified)"
              >{{ formatModified(entry.modified) }}</span>
              <!-- PyPI yanked status badge (action buttons are in the artifact detail dialog) -->
              <Tag
                v-if="isPypi && entry.type === 'file' && (entry as Record<string, unknown>).yanked"
                value="Yanked"
                severity="danger"
                class="text-xs"
              />
              <i class="pi pi-chevron-right text-gray-300" />
            </div>
          </div>

          <div v-if="hasMore" class="text-center mt-4">
            <Button label="Load more" severity="secondary" text :loading="treeLoading" @click="loadMore" />
          </div>
        </template>
      </Card>

      <!-- Artifact Detail Dialog -->
      <Dialog v-model:visible="detailVisible" header="Artifact Detail" modal class="w-full max-w-lg">
        <div v-if="selectedArtifact" class="space-y-3">
          <div><strong>Path:</strong> <span class="font-mono text-sm">{{ selectedArtifact.path }}</span></div>
          <div><strong>Size:</strong> {{ selectedArtifact.size > 0 ? formatSize(selectedArtifact.size) : '—' }}</div>
          <div v-if="selectedArtifact.modified"><strong>Modified:</strong> {{ selectedArtifact.modified }}</div>
          <Tag
            v-if="isPypi && (selectedArtifact as Record<string, unknown>).yanked"
            value="Yanked"
            severity="danger"
            class="text-xs"
          />
          <div class="flex gap-2 pt-2">
            <Button
              icon="pi pi-download"
              label="Download"
              :loading="downloading"
              @click="downloadArtifact(selectedArtifact!.path)"
            />
            <template v-if="isPypi && parsePypiCoords(selectedArtifact.path)">
              <Button
                v-if="(selectedArtifact as Record<string, unknown>).yanked"
                icon="pi pi-undo"
                label="Unyank"
                severity="secondary"
                @click="handleUnyank(selectedArtifact!, $event); detailVisible = false"
              />
              <Button
                v-else
                icon="pi pi-ban"
                label="Yank"
                severity="secondary"
                outlined
                @click="detailVisible = false; openYankDialog(selectedArtifact!, $event)"
              />
            </template>
            <Button
              v-if="canDelete"
              icon="pi pi-trash"
              label="Delete"
              severity="danger"
              :loading="deleting"
              @click="handleDeleteArtifact(selectedArtifact!.path)"
            />
          </div>
        </div>
      </Dialog>

      <!-- PyPI Yank Dialog -->
      <Dialog
        v-model:visible="yankDialogVisible"
        header="Yank Version"
        modal
        class="w-full max-w-md"
      >
        <div v-if="yankTarget" class="space-y-4">
          <p class="text-sm text-gray-600 dark:text-gray-400">
            Yanking
            <span class="font-mono font-semibold">{{ yankTarget.pkg }} {{ yankTarget.version }}</span>
            will prevent new installs while keeping existing pinned installs working.
          </p>
          <div class="space-y-1">
            <label class="text-sm font-medium text-gray-700 dark:text-gray-300">
              Reason <span class="text-gray-400 font-normal">(optional)</span>
            </label>
            <Textarea
              v-model="yankReason"
              rows="3"
              class="w-full"
              placeholder="e.g. Critical bug in this release"
              auto-resize
            />
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <Button
              label="Cancel"
              severity="secondary"
              text
              @click="yankDialogVisible = false"
            />
            <Button
              icon="pi pi-ban"
              label="Yank"
              severity="danger"
              :loading="yankLoading"
              @click="confirmYank"
            />
          </div>
        </div>
      </Dialog>
    </div>
  </AppLayout>
</template>
