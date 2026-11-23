<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  listRepoJobs, listDeletedRepos, retryRepoJob, startRepoJob, restoreDeletedRepo,
  scanRepoOrphans, purgeRepoOrphan,
} from '@/api/repos'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { apiErrorMessage } from '@/utils/apiError'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Tag from 'primevue/tag'
import type { DeletedRepo, RepoJob, RepoJobPhase, RepoJobState, RepoJobType, RepoOrphan } from '@/types'

/** Refresh interval while a job is pending or running. */
const POLL_MS = 5000

const auth = useAuthStore()
const notify = useNotificationStore()

// Every endpoint behind this page needs the repository `delete` action.
const canManage = computed(() => auth.hasAction('api_repository_permissions', 'delete'))

const jobs = ref<RepoJob[]>([])
const deleted = ref<DeletedRepo[]>([])
const loading = ref(false)
const loaded = ref(false)
const jobsError = ref('')
const deletedError = ref('')

const TYPE_LABELS: Record<RepoJobType, string> = {
  purge: 'Purge',
  orphan_purge: 'Orphan purge',
  rename_followup: 'Rename follow-up',
}
const STATE_SEVERITY: Record<RepoJobState, string> = {
  pending: 'secondary',
  running: 'info',
  failed: 'danger',
  done: 'success',
  cancelled: 'secondary',
}
const PHASES: Array<{ phase: RepoJobPhase; label: string; meaning: string }> = [
  { phase: 'waiting', label: 'Waiting', meaning: 'restore window, nothing removed yet' },
  { phase: 'objects', label: 'Objects', meaning: 'removing files' },
  { phase: 'verify', label: 'Verify', meaning: 'checking nothing was written meanwhile' },
  { phase: 'rows', label: 'Rows', meaning: 'removing index entries' },
]

function typeLabel(type: string): string {
  return TYPE_LABELS[type as RepoJobType] ?? type
}

function stateSeverity(state: string): string {
  return STATE_SEVERITY[state as RepoJobState] ?? 'secondary'
}

function phaseLabel(phase: string | null): string {
  if (!phase) return '—'
  return PHASES.find(p => p.phase === phase)?.label ?? phase
}

function phaseMeaning(phase: string | null): string | undefined {
  return PHASES.find(p => p.phase === phase)?.meaning
}

function formatCount(n: number | null | undefined): string {
  return (n ?? 0).toLocaleString('en-US')
}

function formatInstant(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

const hasLiveJobs = computed(() => jobs.value.some(j => j.state === 'pending' || j.state === 'running'))

/** The purge job of a deleted repository that still sits in its restore window. */
function waitingPurgeJob(repo: DeletedRepo): RepoJob | undefined {
  return jobs.value.find(j => j.type === 'purge' && j.repo_key === repo.repo_key
    && j.state === 'pending' && (j.phase === 'waiting' || j.phase === null))
}

// ---------------------------------------------------------------------------
// Load + auto-refresh
// ---------------------------------------------------------------------------
let pollTimer: ReturnType<typeof setTimeout> | null = null
let abort: AbortController | null = null
let unmounted = false

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

/**
 * Loads both lists. `silent` is the background refresh: no spinner. A failed
 * list keeps what it showed before; the other one still updates.
 */
async function load(silent = false) {
  if (!canManage.value) return
  stopPolling()
  if (abort) abort.abort()
  abort = new AbortController()
  const ctrl = abort
  if (!silent) loading.value = true
  const [jobsRes, deletedRes] = await Promise.allSettled([
    listRepoJobs(ctrl.signal),
    listDeletedRepos(ctrl.signal),
  ])
  if (ctrl.signal.aborted || unmounted) return
  if (jobsRes.status === 'fulfilled') {
    jobs.value = jobsRes.value
    jobsError.value = ''
  } else {
    jobsError.value = apiErrorMessage(jobsRes.reason, 'Failed to load jobs')
  }
  if (deletedRes.status === 'fulfilled') {
    deleted.value = deletedRes.value
    deletedError.value = ''
  } else {
    deletedError.value = apiErrorMessage(deletedRes.reason, 'Failed to load deleted repositories')
  }
  loading.value = false
  loaded.value = true
  // Poll only while something can still change on its own.
  if (hasLiveJobs.value) pollTimer = setTimeout(() => { void load(true) }, POLL_MS)
}

onMounted(() => { void load() })
onBeforeUnmount(() => {
  unmounted = true
  stopPolling()
  if (abort) abort.abort()
  if (scanAbort) scanAbort.abort()
})

// ---------------------------------------------------------------------------
// Unowned storage directories
// ---------------------------------------------------------------------------
// The scan lists every storage and can take seconds on S3, so it runs only
// when asked for: never on mount and never on the polling timer.
const orphans = ref<RepoOrphan[]>([])
const scanned = ref(false)
const scanning = ref(false)
const scanError = ref('')
let scanAbort: AbortController | null = null

function orphanKey(o: RepoOrphan): string {
  return `${o.storage}/${o.directory}`
}

function orphanObjects(o: RepoOrphan): string {
  return `${formatCount(o.objects)}${o.more ? '+' : ''}`
}

async function scan() {
  if (!canManage.value || scanning.value) return
  scanAbort = new AbortController()
  const ctrl = scanAbort
  scanning.value = true
  scanError.value = ''
  try {
    const found = await scanRepoOrphans(ctrl.signal)
    if (ctrl.signal.aborted) return
    orphans.value = found
    scanned.value = true
  } catch (err: unknown) {
    if (ctrl.signal.aborted) return
    // The last result stays on screen.
    scanError.value = apiErrorMessage(err, 'Failed to scan storages')
  } finally {
    scanning.value = false
  }
}

// Purging an unowned directory has no restore window: the directory name is typed to confirm.
const orphanTarget = ref<RepoOrphan | null>(null)
const orphanTyped = ref('')
const orphanError = ref('')
const orphanPurging = ref(false)
const orphanVisible = computed({
  get: () => orphanTarget.value !== null,
  set: (open) => { if (!open) orphanTarget.value = null },
})

function askPurgeOrphan(o: RepoOrphan) {
  orphanTyped.value = ''
  orphanError.value = ''
  orphanTarget.value = o
}

async function doPurgeOrphan() {
  const target = orphanTarget.value
  if (!target || orphanTyped.value !== target.directory) return
  orphanPurging.value = true
  orphanError.value = ''
  try {
    await purgeRepoOrphan(target.storage, target.directory)
  } catch (err: unknown) {
    // 409: it is no longer an unowned directory of that storage — the message says why.
    orphanError.value = apiErrorMessage(err, 'Failed to purge the directory')
    orphanPurging.value = false
    return
  }
  orphanPurging.value = false
  orphans.value = orphans.value.filter(o => orphanKey(o) !== orphanKey(target))
  orphanTarget.value = null
  notify.info('Purge queued', `${target.directory}: follow it in the Jobs list`)
  // The new orphan_purge job is live, so this also starts the auto-refresh.
  await load(true)
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------
const busy = ref<Set<string>>(new Set())

function setBusy(key: string, on: boolean) {
  const next = new Set(busy.value)
  if (on) next.add(key)
  else next.delete(key)
  busy.value = next
}

async function retry(job: RepoJob) {
  const key = `retry:${job.id}`
  setBusy(key, true)
  try {
    await retryRepoJob(job.id)
    notify.success('Job queued again', `${typeLabel(job.type)} of ${job.repo_name ?? job.repo_key}`)
  } catch (err: unknown) {
    notify.error('Failed to retry job', apiErrorMessage(err, 'Unknown error'))
  } finally {
    setBusy(key, false)
  }
  await load(true)
}

// Restore, optionally under another name
const restoreTarget = ref<DeletedRepo | null>(null)
const restoreName = ref('')
const restoreError = ref('')
const restoring = ref(false)
const restoreVisible = computed({
  get: () => restoreTarget.value !== null,
  set: (open) => { if (!open) restoreTarget.value = null },
})

function askRestore(repo: DeletedRepo) {
  restoreName.value = repo.name
  restoreError.value = ''
  restoreTarget.value = repo
}

async function doRestore() {
  const repo = restoreTarget.value
  if (!repo) return
  const name = restoreName.value.trim()
  restoring.value = true
  restoreError.value = ''
  try {
    const res = await restoreDeletedRepo(repo.repo_key, name && name !== repo.name ? name : undefined)
    const groups = res.rejoined_groups?.length ? `Back in group(s) ${res.rejoined_groups.join(', ')}.` : undefined
    notify.success(`Repository ${res.name ?? repo.name} restored`, groups)
    restoreTarget.value = null
  } catch (err: unknown) {
    // 409: the purge has started or the name is taken — the message says which.
    restoreError.value = apiErrorMessage(err, 'Failed to restore repository')
    restoring.value = false
    return
  }
  restoring.value = false
  await load(true)
}

// Purge now: ends the restore window of a delete that is still waiting
const purgeTarget = ref<DeletedRepo | null>(null)
const purging = ref(false)
const purgeVisible = computed({
  get: () => purgeTarget.value !== null,
  set: (open) => { if (!open) purgeTarget.value = null },
})

async function doPurgeNow() {
  const repo = purgeTarget.value
  if (!repo) return
  const job = waitingPurgeJob(repo)
  if (!job) {
    purgeTarget.value = null
    return
  }
  purging.value = true
  try {
    await startRepoJob(job.id)
    notify.info('Purge started', repo.name)
  } catch (err: unknown) {
    notify.error('Failed to start the purge', apiErrorMessage(err, 'Unknown error'))
  } finally {
    purging.value = false
    purgeTarget.value = null
  }
  await load(true)
}
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="flex items-center justify-between gap-3">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Repository Jobs</h1>
          <p class="text-sm text-gray-500">
            Deleting a repository is instant; its data is removed here in the background and the delete
            can be undone until the purge starts.
          </p>
        </div>
        <div v-if="canManage" class="flex items-center gap-3">
          <span v-if="hasLiveJobs" class="text-xs text-gray-500" data-testid="jobs-auto-refresh">
            Refreshing every {{ POLL_MS / 1000 }} s
          </span>
          <Button
            label="Refresh"
            icon="pi pi-refresh"
            severity="secondary"
            outlined
            :loading="loading"
            data-testid="jobs-refresh"
            @click="load()"
          />
        </div>
      </div>

      <div
        v-if="!canManage"
        class="rounded-lg border border-gray-200 bg-gray-50 p-4 text-sm text-gray-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300"
        data-testid="jobs-no-permission"
      >
        You need the repository delete permission to see deleted repositories and their jobs.
      </div>

      <template v-else>
        <!-- Deleted repositories -->
        <Card class="shadow-sm" data-testid="deleted-card">
          <template #title>Deleted repositories</template>
          <template #subtitle>
            Deleted, data not purged yet. Restore brings a repository back with its data and group memberships.
          </template>
          <template #content>
            <div
              v-if="deletedError"
              class="mb-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
              data-testid="deleted-error"
            >
              {{ deletedError }}
            </div>
            <DataTable :value="deleted" :loading="loading && !loaded" striped-rows data-key="repo_key" data-testid="deleted-table">
              <Column header="Name">
                <template #body="{ data }"><span class="font-mono text-sm break-all">{{ data.name }}</span></template>
              </Column>
              <Column header="Type">
                <template #body="{ data }"><RepoTypeBadge :type="data.type" /></template>
              </Column>
              <Column header="Deleted">
                <template #body="{ data }">
                  <span :title="data.deleted_at">{{ formatInstant(data.deleted_at) }}</span>
                  <span v-if="data.deleted_by" class="block text-xs text-gray-500">by {{ data.deleted_by }}</span>
                </template>
              </Column>
              <Column header="Purge after">
                <template #body="{ data }">
                  <span :title="data.purge_after ?? undefined">{{ formatInstant(data.purge_after) }}</span>
                </template>
              </Column>
              <Column header="Actions">
                <template #body="{ data }">
                  <span class="flex gap-1 whitespace-nowrap">
                    <Button
                      label="Restore"
                      icon="pi pi-undo"
                      size="small"
                      severity="secondary"
                      text
                      data-testid="deleted-restore"
                      @click="askRestore(data)"
                    />
                    <Button
                      v-tooltip="waitingPurgeJob(data) ? 'End the restore window and remove the data now' : 'The purge has already started or its job is not listed'"
                      label="Purge now"
                      icon="pi pi-trash"
                      size="small"
                      severity="danger"
                      text
                      :disabled="!waitingPurgeJob(data)"
                      data-testid="deleted-purge-now"
                      @click="purgeTarget = data"
                    />
                  </span>
                </template>
              </Column>
              <template #empty>
                <div class="py-4 text-center text-gray-400" data-testid="deleted-empty">
                  {{ deletedError ? 'Could not load deleted repositories' : 'No deleted repositories waiting for purge' }}
                </div>
              </template>
            </DataTable>
          </template>
        </Card>

        <!-- Jobs -->
        <Card class="shadow-sm" data-testid="jobs-card">
          <template #title>Jobs</template>
          <template #subtitle>
            Unfinished first, then newest. A failed job stays failed until someone presses Retry.
          </template>
          <template #content>
            <div
              v-if="jobsError"
              class="mb-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
              data-testid="jobs-error"
            >
              {{ jobsError }}
            </div>
            <DataTable :value="jobs" :loading="loading && !loaded" striped-rows data-key="id" data-testid="jobs-table">
              <Column header="Type">
                <template #body="{ data }">{{ typeLabel(data.type) }}</template>
              </Column>
              <Column header="Repository">
                <template #body="{ data }">
                  <span class="font-mono text-sm break-all" :title="data.repo_key">{{ data.repo_name ?? data.repo_key }}</span>
                </template>
              </Column>
              <Column header="State">
                <template #body="{ data }">
                  <Tag :value="data.state" :severity="stateSeverity(data.state)" data-testid="job-state" />
                </template>
              </Column>
              <Column header="Phase">
                <template #body="{ data }">
                  <span :title="phaseMeaning(data.phase)">{{ phaseLabel(data.phase) }}</span>
                  <span
                    v-if="data.state === 'pending' && data.not_before"
                    class="block text-xs text-gray-500"
                    :title="data.not_before"
                  >until {{ formatInstant(data.not_before) }}</span>
                </template>
              </Column>
              <Column header="Objects">
                <template #body="{ data }"><span class="tabular-nums" data-testid="job-objects">{{ formatCount(data.objects_done) }}</span></template>
              </Column>
              <Column header="Rows">
                <template #body="{ data }"><span class="tabular-nums" data-testid="job-rows">{{ formatCount(data.rows_done) }}</span></template>
              </Column>
              <Column header="Attempts">
                <template #body="{ data }"><span class="tabular-nums">{{ data.attempts }}</span></template>
              </Column>
              <Column header="Node">
                <template #body="{ data }"><span class="text-sm break-all">{{ data.node ?? '—' }}</span></template>
              </Column>
              <Column header="Updated">
                <template #body="{ data }">
                  <span :title="data.updated_at ?? data.created_at">{{ formatInstant(data.updated_at ?? data.created_at) }}</span>
                </template>
              </Column>
              <Column header="Last error">
                <template #body="{ data }">
                  <span v-if="data.last_error" class="text-sm text-red-700 break-words dark:text-red-300" data-testid="job-error">{{ data.last_error }}</span>
                  <span v-else class="text-gray-400">—</span>
                </template>
              </Column>
              <Column header="Actions">
                <template #body="{ data }">
                  <Button
                    v-if="data.state === 'failed'"
                    label="Retry"
                    icon="pi pi-replay"
                    size="small"
                    severity="secondary"
                    text
                    :loading="busy.has(`retry:${data.id}`)"
                    data-testid="job-retry"
                    @click="retry(data)"
                  />
                </template>
              </Column>
              <template #empty>
                <div class="py-4 text-center text-gray-400" data-testid="jobs-empty">
                  {{ jobsError ? 'Could not load jobs' : 'No jobs' }}
                </div>
              </template>
            </DataTable>
            <dl class="mt-4 grid gap-x-6 gap-y-1 text-xs text-gray-500 sm:grid-cols-2" data-testid="jobs-phase-legend">
              <div v-for="p in PHASES" :key="p.phase" class="flex gap-1">
                <dt class="font-medium">{{ p.label }}:</dt>
                <dd>{{ p.meaning }}</dd>
              </div>
            </dl>
          </template>
        </Card>

        <!-- Unowned storage directories -->
        <Card class="shadow-sm" data-testid="orphans-card">
          <template #title>Unowned storage directories</template>
          <template #subtitle>
            Files left behind by repository deletes made before this release, or by an interrupted rename.
            Nothing is removed until you purge a specific directory.
          </template>
          <template #content>
            <div class="flex flex-wrap items-center gap-3">
              <Button
                label="Scan storages"
                icon="pi pi-search"
                severity="secondary"
                outlined
                :loading="scanning"
                data-testid="orphans-scan"
                @click="scan"
              />
              <span class="text-xs text-gray-500" data-testid="orphans-hint">
                <template v-if="scanning">Listing every storage; this can take a few seconds…</template>
                <template v-else-if="!scanned">Not scanned yet. The scan lists every storage repositories use, so it runs only when you start it.</template>
                <template v-else>Result of the last scan; scan again to update it.</template>
              </span>
            </div>
            <div
              v-if="scanError"
              class="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
              data-testid="orphans-error"
            >
              {{ scanError }}
            </div>
            <template v-if="scanned">
              <DataTable :value="orphans" striped-rows class="mt-4" data-testid="orphans-table">
                <Column header="Storage">
                  <template #body="{ data }">
                    <span class="text-sm break-all" :title="data.storage">{{ data.location }}</span>
                  </template>
                </Column>
                <Column header="Directory">
                  <template #body="{ data }"><span class="font-mono text-sm break-all">{{ data.directory }}</span></template>
                </Column>
                <Column header="Objects">
                  <template #body="{ data }">
                    <span
                      class="tabular-nums"
                      :title="data.more ? 'More objects than were counted' : undefined"
                      data-testid="orphan-objects"
                    >{{ orphanObjects(data) }}</span>
                  </template>
                </Column>
                <Column header="Actions">
                  <template #body="{ data }">
                    <Button
                      label="Purge…"
                      icon="pi pi-trash"
                      size="small"
                      severity="danger"
                      text
                      data-testid="orphan-purge"
                      @click="askPurgeOrphan(data)"
                    />
                  </template>
                </Column>
                <template #empty>
                  <div class="py-4 text-center text-gray-400" data-testid="orphans-empty">No unowned directories found</div>
                </template>
              </DataTable>
              <p class="mt-3 text-xs text-gray-500">
                A directory is listed when it holds files and belongs to no repository, no deleted repository
                and no running purge. Pantera cannot tell whether a storage also holds data it does not
                own, so check a directory before purging it. Objects are counted up to 1,000.
              </p>
            </template>
          </template>
        </Card>
      </template>

      <Dialog v-model:visible="orphanVisible" header="Purge Unowned Directory" modal class="w-[32rem]" data-testid="orphan-dialog">
        <template v-if="orphanTarget">
          <p class="mb-3 text-sm">
            This removes every file in the directory. It cannot be undone: there is no restore window.
          </p>
          <dl class="mb-4 grid grid-cols-[6rem_minmax(0,1fr)] gap-x-3 gap-y-1 text-sm" data-testid="orphan-details">
            <dt class="text-gray-500">Storage</dt>
            <dd class="break-all">{{ orphanTarget.location }}</dd>
            <dt class="text-gray-500">Directory</dt>
            <dd class="font-mono break-all">{{ orphanTarget.directory }}</dd>
            <dt class="text-gray-500">Objects</dt>
            <dd class="tabular-nums">{{ orphanObjects(orphanTarget) }}</dd>
          </dl>
          <label for="orphanConfirm" class="mb-1 block text-sm">
            Type <b class="font-mono break-all">{{ orphanTarget.directory }}</b> to confirm
          </label>
          <InputText
            id="orphanConfirm"
            v-model="orphanTyped"
            class="w-full"
            autocomplete="off"
            data-testid="orphan-confirm-input"
          />
          <div v-if="orphanError" class="mt-3 text-sm text-red-700 dark:text-red-300" data-testid="orphan-purge-error">
            {{ orphanError }}
          </div>
        </template>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="orphanTarget = null" />
          <Button
            label="Purge directory"
            severity="danger"
            :disabled="!orphanTarget || orphanTyped !== orphanTarget.directory || orphanPurging"
            :loading="orphanPurging"
            data-testid="orphan-purge-confirm"
            @click="doPurgeOrphan"
          />
        </template>
      </Dialog>

      <Dialog v-model:visible="restoreVisible" header="Restore Repository" modal class="w-[28rem]" data-testid="restore-dialog">
        <template v-if="restoreTarget">
          <p class="mb-3">
            Restore <strong class="font-mono break-all">{{ restoreTarget.name }}</strong> with its data and stop the purge?
          </p>
          <label for="restoreName" class="mb-1 block text-sm text-gray-500">Name</label>
          <InputText id="restoreName" v-model="restoreName" class="w-full" autocomplete="off" data-testid="restore-name" />
          <p class="mt-1 text-xs text-gray-500">Change it to restore under another name, for example when the old one is taken.</p>
          <div v-if="restoreError" class="mt-3 text-sm text-red-700 dark:text-red-300" data-testid="restore-error">
            {{ restoreError }}
          </div>
        </template>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="restoreTarget = null" />
          <Button
            label="Restore"
            :disabled="!restoreName.trim() || restoring"
            :loading="restoring"
            data-testid="restore-confirm"
            @click="doRestore"
          />
        </template>
      </Dialog>

      <Dialog v-model:visible="purgeVisible" header="Purge Now" modal class="w-[28rem]" data-testid="purge-dialog">
        <p v-if="purgeTarget">
          End the restore window of <strong class="font-mono break-all">{{ purgeTarget.name }}</strong> and remove its
          data now? It cannot be restored afterwards.
        </p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="purgeTarget = null" />
          <Button label="Purge now" severity="danger" :loading="purging" data-testid="purge-confirm" @click="doPurgeNow" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
