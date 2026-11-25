import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import RepoJobsView from '../RepoJobsView.vue'
import type { DeletedRepo, RepoJob, RepoOrphan } from '@/types'

const listJobsMock = vi.fn()
const listDeletedMock = vi.fn()
const retryMock = vi.fn()
const startMock = vi.fn()
const restoreMock = vi.fn()
const scanMock = vi.fn()
const purgeOrphanMock = vi.fn()

vi.mock('@/api/repos', () => ({
  listRepoJobs: (...a: unknown[]) => listJobsMock(...a),
  listDeletedRepos: (...a: unknown[]) => listDeletedMock(...a),
  retryRepoJob: (...a: unknown[]) => retryMock(...a),
  startRepoJob: (...a: unknown[]) => startMock(...a),
  restoreDeletedRepo: (...a: unknown[]) => restoreMock(...a),
  scanRepoOrphans: (...a: unknown[]) => scanMock(...a),
  purgeRepoOrphan: (...a: unknown[]) => purgeOrphanMock(...a),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

function job(over: Partial<RepoJob>): RepoJob {
  return {
    id: 1, type: 'purge', repo_key: 'k1', repo_name: 'old-maven', state: 'done', phase: 'rows',
    objects_done: 0, rows_done: 0, attempts: 1, last_error: null, not_before: null, node: 'node-a',
    created_by: 'ayd', created_at: '2026-11-23T10:00:00Z', updated_at: '2026-11-23T10:05:00Z', finished_at: null,
    ...over,
  }
}

const WAITING = job({ id: 11, state: 'pending', phase: 'waiting', not_before: '2026-11-24T10:00:00Z', node: null })
const RUNNING = job({ id: 12, repo_key: 'k2', repo_name: 'npm-old', state: 'running', phase: 'objects', objects_done: 1234567, rows_done: 0 })
const FAILED = job({ id: 13, repo_key: 'k3', repo_name: 'pypi-old', state: 'failed', phase: 'verify', attempts: 5, last_error: 'S3 timeout', objects_done: 2500, rows_done: 12000 })
const DONE = job({ id: 14, type: 'rename_followup', repo_key: 'k4', repo_name: 'renamed', state: 'done' })

const DELETED_WAITING: DeletedRepo = {
  repo_key: 'k1', name: 'old-maven', type: 'maven', deleted_at: '2026-11-23T10:00:00Z', deleted_by: 'ayd', purge_after: '2026-11-24T10:00:00Z',
}
const DELETED_PURGING: DeletedRepo = {
  repo_key: 'k2', name: 'npm-old', type: 'npm', deleted_at: '2026-11-22T10:00:00Z', deleted_by: null, purge_after: '2026-11-23T10:00:00Z',
}

let wrapper: VueWrapper | null = null

function seedAuth(actions: string[] = ['read', 'delete']) {
  const auth = useAuthStore()
  auth.user = {
    name: 't', context: 'c',
    permissions: { api_repository_permissions: actions },
  } as unknown as typeof auth.user
}

async function mountView() {
  wrapper = mount(RepoJobsView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
    },
  })
  await flushPromises()
  return wrapper
}

function all<T extends HTMLElement = HTMLElement>(testid: string): T[] {
  return Array.from(document.querySelectorAll(`[data-testid="${testid}"]`)) as T[]
}

function el<T extends HTMLElement = HTMLElement>(testid: string): T | null {
  return document.querySelector(`[data-testid="${testid}"]`) as T | null
}

function toasts() {
  return useNotificationStore().toasts.map(t => ({ severity: t.severity, summary: t.summary, detail: t.detail }))
}

describe('RepoJobsView', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    document.body.innerHTML = ''
    setActivePinia(createPinia())
    seedAuth()
    listJobsMock.mockReset(); listDeletedMock.mockReset()
    retryMock.mockReset(); startMock.mockReset(); restoreMock.mockReset()
    scanMock.mockReset(); purgeOrphanMock.mockReset()
    listJobsMock.mockResolvedValue([])
    listDeletedMock.mockResolvedValue([])
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
  })

  it('renders deleted repositories and jobs', async () => {
    listJobsMock.mockResolvedValue([WAITING, RUNNING, FAILED, DONE])
    listDeletedMock.mockResolvedValue([DELETED_WAITING, DELETED_PURGING])
    const w = await mountView()

    const deletedRows = w.findAll('[data-testid="deleted-table"] tbody tr')
    expect(deletedRows).toHaveLength(2)
    expect(deletedRows[0].text()).toContain('old-maven')
    expect(deletedRows[0].text()).toContain('by ayd')
    expect(deletedRows[1].text()).toContain('npm-old')

    const jobRows = w.findAll('[data-testid="jobs-table"] tbody tr')
    expect(jobRows).toHaveLength(4)
    expect(all('job-state').map(e => e.textContent)).toEqual(['pending', 'running', 'failed', 'done'])
    expect(jobRows[0].text()).toContain('Waiting')
    expect(jobRows[1].text()).toContain('Objects')
    expect(jobRows[1].text()).toContain('node-a')
    expect(jobRows[2].text()).toContain('Verify')
    expect(jobRows[2].text()).toContain('S3 timeout')
    expect(jobRows[3].text()).toContain('Rename follow-up')
    // thousands separators
    expect(all('job-objects').map(e => e.textContent)).toEqual(['0', '1,234,567', '2,500', '0'])
    expect(all('job-rows')[2].textContent).toBe('12,000')
    // what the phases mean
    const legend = el('jobs-phase-legend')!.textContent!
    expect(legend).toContain('restore window')
    expect(legend).toContain('removing files')
    expect(legend).toContain('checking nothing was written meanwhile')
    expect(legend).toContain('removing index entries')
  })

  it('shows empty states', async () => {
    await mountView()
    expect(el('deleted-empty')!.textContent).toContain('No deleted repositories')
    expect(el('jobs-empty')!.textContent).toContain('No jobs')
    expect(el('jobs-error')).toBeNull()
    expect(el('deleted-error')).toBeNull()
  })

  it('does not poll when no job is pending or running', async () => {
    listJobsMock.mockResolvedValue([FAILED, DONE])
    await mountView()
    expect(listJobsMock).toHaveBeenCalledTimes(1)
    expect(el('jobs-auto-refresh')).toBeNull()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(listJobsMock).toHaveBeenCalledTimes(1)
    expect(listDeletedMock).toHaveBeenCalledTimes(1)
  })

  it('polls while a job is live and stops once none is', async () => {
    listJobsMock
      .mockResolvedValueOnce([RUNNING])
      .mockResolvedValueOnce([WAITING])
      .mockResolvedValue([DONE])
    await mountView()
    expect(el('jobs-auto-refresh')).not.toBeNull()
    await vi.advanceTimersByTimeAsync(4_999)
    expect(listJobsMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(listJobsMock).toHaveBeenCalledTimes(2)
    expect(listDeletedMock).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(listJobsMock).toHaveBeenCalledTimes(3)
    // the last answer has nothing live: no further requests
    await vi.advanceTimersByTimeAsync(60_000)
    expect(listJobsMock).toHaveBeenCalledTimes(3)
    expect(el('jobs-auto-refresh')).toBeNull()
  })

  it('stops polling and aborts the request when the view unmounts', async () => {
    listJobsMock.mockResolvedValue([RUNNING])
    const w = await mountView()
    expect(listJobsMock).toHaveBeenCalledTimes(1)
    const signal = listJobsMock.mock.calls[0][0] as AbortSignal
    w.unmount()
    wrapper = null
    expect(signal.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(listJobsMock).toHaveBeenCalledTimes(1)
  })

  it('keeps polling after a failed refresh of a live list and shows the error', async () => {
    listJobsMock
      .mockResolvedValueOnce([RUNNING])
      .mockRejectedValueOnce({ response: { status: 503, data: { message: 'database unavailable' } } })
      .mockResolvedValue([DONE])
    const w = await mountView()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(el('jobs-error')!.textContent).toContain('database unavailable')
    // the rows shown before stay
    expect(w.findAll('[data-testid="jobs-table"] tbody tr')).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(listJobsMock).toHaveBeenCalledTimes(3)
    expect(el('jobs-error')).toBeNull()
  })

  it('shows an error state per list and reloads on Refresh', async () => {
    listJobsMock.mockRejectedValueOnce({ response: { status: 500, data: { message: 'jobs boom' } } })
    listDeletedMock.mockRejectedValueOnce(new Error('Network Error'))
    await mountView()
    expect(el('jobs-error')!.textContent).toContain('jobs boom')
    expect(el('deleted-error')!.textContent).toContain('Network Error')
    expect(el('jobs-empty')!.textContent).toContain('Could not load jobs')
    listJobsMock.mockResolvedValue([DONE])
    listDeletedMock.mockResolvedValue([DELETED_WAITING])
    el('jobs-refresh')!.click()
    await flushPromises()
    expect(el('jobs-error')).toBeNull()
    expect(el('deleted-error')).toBeNull()
    expect(listJobsMock).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('old-maven')
  })

  it('retries a failed job and refreshes', async () => {
    listJobsMock.mockResolvedValueOnce([FAILED, DONE]).mockResolvedValue([{ ...FAILED, state: 'pending' }, DONE])
    retryMock.mockResolvedValue({ ...FAILED, state: 'pending' })
    await mountView()
    // Retry is offered for the failed job only
    expect(all('job-retry')).toHaveLength(1)
    all('job-retry')[0].click()
    await flushPromises()
    expect(retryMock).toHaveBeenCalledWith(13)
    expect(listJobsMock).toHaveBeenCalledTimes(2)
    expect(all('job-state').map(e => e.textContent)).toEqual(['pending', 'done'])
    expect(all('job-retry')).toHaveLength(0)
    expect(toasts()[0].severity).toBe('success')
  })

  it('reports a retry the server refuses and still refreshes', async () => {
    listJobsMock.mockResolvedValue([FAILED])
    retryMock.mockRejectedValue({ response: { status: 409, data: { message: 'Job 13 is not failed' } } })
    await mountView()
    all('job-retry')[0].click()
    await flushPromises()
    expect(toasts()).toEqual([{ severity: 'error', summary: 'Failed to retry job', detail: 'Job 13 is not failed' }])
    expect(listJobsMock).toHaveBeenCalledTimes(2)
  })

  it('purges now through the waiting purge job of that repository, after a confirmation', async () => {
    listJobsMock.mockResolvedValue([WAITING, RUNNING])
    listDeletedMock.mockResolvedValue([DELETED_WAITING, DELETED_PURGING])
    startMock.mockResolvedValue({ ...WAITING, state: 'running', phase: 'objects' })
    await mountView()
    const buttons = all<HTMLButtonElement>('deleted-purge-now')
    // the second repository is already being purged: nothing to start
    expect(buttons.map(b => b.disabled)).toEqual([false, true])
    buttons[0].click()
    await flushPromises()
    expect(startMock).not.toHaveBeenCalled()
    expect(el('purge-dialog')!.textContent).toContain('cannot be restored afterwards')
    el('purge-confirm')!.click()
    await flushPromises()
    expect(startMock).toHaveBeenCalledWith(11)
    expect(listJobsMock).toHaveBeenCalledTimes(2)
    expect(listDeletedMock).toHaveBeenCalledTimes(2)
  })

  it('restores under the same name without sending one', async () => {
    listDeletedMock.mockResolvedValueOnce([DELETED_WAITING]).mockResolvedValue([])
    listJobsMock.mockResolvedValueOnce([WAITING]).mockResolvedValue([{ ...WAITING, state: 'cancelled' }])
    restoreMock.mockResolvedValue({ name: 'old-maven', repo_key: 'k1', rejoined_groups: ['g1'] })
    await mountView()
    all('deleted-restore')[0].click()
    await flushPromises()
    expect(el<HTMLInputElement>('restore-name')!.value).toBe('old-maven')
    el('restore-confirm')!.click()
    await flushPromises()
    expect(restoreMock).toHaveBeenCalledWith('k1', undefined)
    expect(listDeletedMock).toHaveBeenCalledTimes(2)
    expect(el('deleted-empty')).not.toBeNull()
    expect(toasts()).toEqual([{ severity: 'success', summary: 'Repository old-maven restored', detail: 'Back in group(s) g1.' }])
  })

  it('restores under another name and keeps the dialog open on a 409', async () => {
    listDeletedMock.mockResolvedValue([DELETED_WAITING])
    listJobsMock.mockResolvedValue([WAITING])
    restoreMock
      .mockRejectedValueOnce({ response: { status: 409, data: { message: "Name 'old-maven' is taken" } } })
      .mockResolvedValueOnce({ name: 'maven-restored', repo_key: 'k1', rejoined_groups: [] })
    await mountView()
    all('deleted-restore')[0].click()
    await flushPromises()
    el('restore-confirm')!.click()
    await flushPromises()
    expect(el('restore-error')!.textContent).toContain("Name 'old-maven' is taken")
    expect(listDeletedMock).toHaveBeenCalledTimes(1)
    const input = el<HTMLInputElement>('restore-name')!
    input.value = 'maven-restored'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    el('restore-confirm')!.click()
    await flushPromises()
    expect(restoreMock).toHaveBeenLastCalledWith('k1', 'maven-restored')
    expect(listDeletedMock).toHaveBeenCalledTimes(2)
    expect(el('restore-error')).toBeNull()
  })

  it('without the delete permission shows a note and calls nothing', async () => {
    seedAuth(['read', 'update', 'move'])
    await mountView()
    expect(el('jobs-no-permission')).not.toBeNull()
    expect(el('jobs-refresh')).toBeNull()
    expect(el('jobs-table')).toBeNull()
    expect(el('deleted-table')).toBeNull()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(listJobsMock).not.toHaveBeenCalled()
    expect(listDeletedMock).not.toHaveBeenCalled()
    // the unowned-directory scan is gated the same way
    expect(el('orphans-card')).toBeNull()
    expect(el('orphans-scan')).toBeNull()
    expect(scanMock).not.toHaveBeenCalled()
  })

  describe('unowned storage directories', () => {
    const BIG: RepoOrphan = {
      storage: '5f2b9c1e0a7d4e38', location: 's3 bucket=artifacts region=eu-west-1', directory: 'old-maven', objects: 1000, more: true,
    }
    const SMALL: RepoOrphan = {
      storage: 'a1', location: 'fs /var/pantera/data', directory: 'tmp-rename', objects: 42, more: false,
    }
    const ORPHAN_JOB = job({ id: 57, type: 'orphan_purge', repo_key: 'o1', repo_name: null, state: 'pending', phase: 'objects' })

    async function typeName(value: string) {
      const input = el<HTMLInputElement>('orphan-confirm-input')!
      input.value = value
      input.dispatchEvent(new Event('input'))
      await flushPromises()
    }

    it('does not scan on mount or on the polling timer', async () => {
      listJobsMock.mockResolvedValue([RUNNING])
      await mountView()
      await vi.advanceTimersByTimeAsync(20_000)
      // the page did poll its jobs meanwhile
      expect(listJobsMock.mock.calls.length).toBeGreaterThan(2)
      expect(scanMock).not.toHaveBeenCalled()
      expect(el('orphans-hint')!.textContent).toContain('Not scanned yet')
      expect(el('orphans-table')).toBeNull()
      expect(el('orphans-card')!.textContent).toContain('Nothing is removed until you purge a specific directory')
    })

    it('scans when asked and renders the directories', async () => {
      scanMock.mockResolvedValue([BIG, SMALL])
      const w = await mountView()
      el('orphans-scan')!.click()
      await flushPromises()
      expect(scanMock).toHaveBeenCalledTimes(1)
      const rows = w.findAll('[data-testid="orphans-table"] tbody tr')
      expect(rows).toHaveLength(2)
      expect(rows[0].text()).toContain('s3 bucket=artifacts region=eu-west-1')
      expect(rows[0].text()).toContain('old-maven')
      expect(rows[1].text()).toContain('tmp-rename')
      expect(all('orphan-objects').map(e => e.textContent)).toEqual(['1,000+', '42'])
      // the refreshes of the other lists never scan again
      el('jobs-refresh')!.click()
      await flushPromises()
      expect(scanMock).toHaveBeenCalledTimes(1)
    })

    it('says so when a scan finds nothing', async () => {
      scanMock.mockResolvedValue([])
      await mountView()
      el('orphans-scan')!.click()
      await flushPromises()
      expect(el('orphans-empty')!.textContent).toContain('No unowned directories found')
    })

    it('keeps the last result when a later scan fails', async () => {
      scanMock
        .mockResolvedValueOnce([SMALL])
        .mockRejectedValueOnce({ response: { status: 500, data: { message: 'storage unreachable' } } })
      const w = await mountView()
      el('orphans-scan')!.click()
      await flushPromises()
      el('orphans-scan')!.click()
      await flushPromises()
      expect(el('orphans-error')!.textContent).toContain('storage unreachable')
      expect(w.findAll('[data-testid="orphans-table"] tbody tr')).toHaveLength(1)
    })

    it('purges only after the directory name is typed, then refreshes the jobs and drops the row', async () => {
      scanMock.mockResolvedValue([BIG, SMALL])
      purgeOrphanMock.mockResolvedValue({ status: 'purging', job_id: 57 })
      listJobsMock.mockResolvedValueOnce([]).mockResolvedValue([ORPHAN_JOB])
      const w = await mountView()
      el('orphans-scan')!.click()
      await flushPromises()
      all('orphan-purge')[0].click()
      await flushPromises()
      // the dialog names what is about to go
      const details = el('orphan-details')!.textContent!
      expect(details).toContain('s3 bucket=artifacts region=eu-west-1')
      expect(details).toContain('old-maven')
      expect(details).toContain('1,000+')
      expect(el('orphan-dialog')!.textContent).toContain('no restore window')
      const confirm = el<HTMLButtonElement>('orphan-purge-confirm')!
      expect(confirm.disabled).toBe(true)
      await typeName('old-mave')
      expect(confirm.disabled).toBe(true)
      await typeName('old-maven')
      expect(confirm.disabled).toBe(false)
      expect(purgeOrphanMock).not.toHaveBeenCalled()
      confirm.click()
      await flushPromises()
      expect(purgeOrphanMock).toHaveBeenCalledWith('5f2b9c1e0a7d4e38', 'old-maven')
      // jobs reloaded: the new job is listed and, being live, starts the polling
      expect(listJobsMock).toHaveBeenCalledTimes(2)
      expect(document.body.textContent).toContain('Orphan purge')
      expect(el('jobs-auto-refresh')).not.toBeNull()
      await vi.advanceTimersByTimeAsync(5_000)
      expect(listJobsMock).toHaveBeenCalledTimes(3)
      // the purged row is gone without another scan
      const rows = w.findAll('[data-testid="orphans-table"] tbody tr')
      expect(rows).toHaveLength(1)
      expect(rows[0].text()).toContain('tmp-rename')
      expect(scanMock).toHaveBeenCalledTimes(1)
      expect(el('orphan-confirm-input')).toBeNull()
    })

    it('shows the 409 message of the server and keeps the row', async () => {
      scanMock.mockResolvedValue([SMALL])
      const message = "Directory 'tmp-rename' is no longer an unowned directory of storage a1"
      purgeOrphanMock.mockRejectedValue({ response: { status: 409, data: { error: 'CONFLICT', message } } })
      const w = await mountView()
      el('orphans-scan')!.click()
      await flushPromises()
      all('orphan-purge')[0].click()
      await flushPromises()
      await typeName('tmp-rename')
      el('orphan-purge-confirm')!.click()
      await flushPromises()
      expect(el('orphan-purge-error')!.textContent).toContain(message)
      expect(w.findAll('[data-testid="orphans-table"] tbody tr')).toHaveLength(1)
      expect(listJobsMock).toHaveBeenCalledTimes(1)
    })
  })
})
