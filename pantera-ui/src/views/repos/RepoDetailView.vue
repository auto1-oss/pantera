<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getRepo, getTree, getArtifactDetail, deleteArtifacts } from '@/api/repos'
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
import type { TreeEntry, ArtifactDetail } from '@/types'

const props = defineProps<{ name: string }>()
const route = useRoute()
const notify = useNotificationStore()
const auth = useAuthStore()
const canDelete = computed(() => auth.user?.can_delete_artifacts === true)

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
    const cmp = a.name.localeCompare(b.name)
    return sortAsc.value ? cmp : -cmp
  })
})

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
          <div class="flex items-center justify-between min-h-[32px]">
            <span>Artifacts</span>
            <div class="flex items-center gap-1">
              <Button
                :icon="sortAsc ? 'pi pi-sort-alpha-down' : 'pi pi-sort-alpha-up'"
                :title="sortAsc ? 'A → Z' : 'Z → A'"
                text size="small"
                @click="sortAsc = !sortAsc"
              />
              <Button :class="currentPath === '/' ? 'invisible' : ''" icon="pi pi-arrow-up" label="Up" text size="small" @click="goUp" />
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
      <Dialog v-model:visible="detailVisible" header="Artifact Detail" modal class="w-full max-w-lg">
        <div v-if="selectedArtifact" class="space-y-3">
          <div><strong>Path:</strong> <span class="font-mono text-sm">{{ selectedArtifact.path }}</span></div>
          <div><strong>Size:</strong> {{ selectedArtifact.size > 0 ? formatSize(selectedArtifact.size) : '—' }}</div>
          <div v-if="selectedArtifact.modified"><strong>Modified:</strong> {{ selectedArtifact.modified }}</div>
          <div class="flex gap-2 pt-2">
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
        </div>
      </Dialog>
    </div>
  </AppLayout>
</template>
