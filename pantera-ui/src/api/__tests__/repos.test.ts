import { describe, it, expect, vi, beforeEach } from 'vitest'

const del = vi.fn()
const put = vi.fn()
const get = vi.fn()
const post = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ delete: del, put, get, post }),
}))

import {
  deleteRepo, moveRepo, listRepoJobs, getRepoJob, retryRepoJob, startRepoJob,
  listDeletedRepos, restoreDeletedRepo,
} from '../repos'

const JOB = {
  id: 7, type: 'purge', repo_key: 'k1', repo_name: 'old', state: 'pending', phase: 'waiting',
  objects_done: 0, rows_done: 0, attempts: 0, last_error: null, not_before: '2026-11-24T10:00:00Z',
  node: null, created_by: 'ayd', created_at: '2026-11-23T10:00:00Z', updated_at: null, finished_at: null,
}

beforeEach(() => {
  del.mockReset(); put.mockReset(); get.mockReset(); post.mockReset()
})

describe('deleteRepo', () => {
  it('reports a finished delete on 200', async () => {
    del.mockResolvedValue({ status: 200, data: '' })
    await expect(deleteRepo('old')).resolves.toEqual({ kind: 'deleted' })
    expect(del).toHaveBeenCalledWith('/repositories/old')
  })

  it('reports a delete still running on a 202 without a job', async () => {
    del.mockResolvedValue({ status: 202, data: { status: 'deleting', message: 'in background' } })
    await expect(deleteRepo('big')).resolves.toEqual({ kind: 'deleting', message: 'in background' })
  })

  it('reports the purge job of a 202 that carries one', async () => {
    del.mockResolvedValue({
      status: 202,
      data: {
        status: 'deleting', job_id: 123, repo_key: 'k1', purge_after: '2026-11-24T10:00:00Z',
        removed_from_groups: ['g1'], message: 'scheduled',
      },
    })
    await expect(deleteRepo('old')).resolves.toEqual({
      kind: 'job', jobId: 123, repoKey: 'k1', purgeAfter: '2026-11-24T10:00:00Z',
      removedFromGroups: ['g1'], message: 'scheduled',
    })
  })

  it('tolerates a job answer without the optional fields', async () => {
    del.mockResolvedValue({ status: 202, data: { status: 'deleting', job_id: 5 } })
    await expect(deleteRepo('old')).resolves.toEqual({
      kind: 'job', jobId: 5, repoKey: '', purgeAfter: null, removedFromGroups: [], message: '',
    })
  })

  it('sends no query when no option is set', async () => {
    del.mockResolvedValue({ status: 202, data: { status: 'deleting', job_id: 1 } })
    await deleteRepo('old', {})
    await deleteRepo('old', { force: false })
    expect(del.mock.calls).toEqual([['/repositories/old'], ['/repositories/old']])
  })

  it('sends force and retention_hours only when set, zero included', async () => {
    del.mockResolvedValue({ status: 202, data: { status: 'deleting', job_id: 1 } })
    await deleteRepo('old', { force: true })
    await deleteRepo('old', { retentionHours: 0 })
    await deleteRepo('old', { force: true, retentionHours: 48 })
    expect(del.mock.calls).toEqual([
      ['/repositories/old', { params: { force: 'true' } }],
      ['/repositories/old', { params: { retention_hours: 0 } }],
      ['/repositories/old', { params: { force: 'true', retention_hours: 48 } }],
    ])
  })

  it('passes a refusal on to the caller', async () => {
    const refusal = { response: { status: 409, data: { error: 'CONFLICT', message: "Repository 'x' is a member of group(s) a, b" } } }
    del.mockRejectedValue(refusal)
    await expect(deleteRepo('x')).rejects.toBe(refusal)
  })
})

describe('moveRepo', () => {
  const ANSWER = {
    name: 'new', previous_name: 'old', repo_key: 'k1', keep_old_name_days: 0,
    groups_updated: ['g1'], roles_updated: [],
  }

  it('sends only the new name by default and returns the answer', async () => {
    put.mockResolvedValue({ status: 200, data: ANSWER })
    await expect(moveRepo('old', 'new')).resolves.toEqual(ANSWER)
    expect(put).toHaveBeenCalledWith('/repositories/old/move', { new_name: 'new' })
  })

  it('leaves keep_old_name_days out when it is zero', async () => {
    put.mockResolvedValue({ status: 200, data: ANSWER })
    await moveRepo('old', 'new', 0)
    expect(put).toHaveBeenCalledWith('/repositories/old/move', { new_name: 'new' })
  })

  it('sends keep_old_name_days when the old name stays', async () => {
    put.mockResolvedValue({ status: 200, data: { ...ANSWER, keep_old_name_days: 30 } })
    const res = await moveRepo('old', 'new', 30)
    expect(put).toHaveBeenCalledWith('/repositories/old/move', { new_name: 'new', keep_old_name_days: 30 })
    expect(res?.keep_old_name_days).toBe(30)
  })

  it('resolves to null when the server answers without a body', async () => {
    put.mockResolvedValue({ status: 200, data: '' })
    await expect(moveRepo('old', 'new')).resolves.toBeNull()
  })

  it('passes a conflict on to the caller', async () => {
    const conflict = { response: { status: 409, data: { message: 'taken' } } }
    put.mockRejectedValue(conflict)
    await expect(moveRepo('old', 'new')).rejects.toBe(conflict)
  })
})

describe('repository jobs', () => {
  it('lists jobs, passing the abort signal', async () => {
    get.mockResolvedValue({ data: { items: [JOB] } })
    const signal = new AbortController().signal
    await expect(listRepoJobs(signal)).resolves.toEqual([JOB])
    expect(get).toHaveBeenCalledWith('/repository-jobs', { signal })
  })

  it('answers an empty list when the server sends no items', async () => {
    get.mockResolvedValue({ data: {} })
    await expect(listRepoJobs()).resolves.toEqual([])
  })

  it('gets one job', async () => {
    get.mockResolvedValue({ data: JOB })
    await expect(getRepoJob(7)).resolves.toEqual(JOB)
    expect(get).toHaveBeenCalledWith('/repository-jobs/7')
  })

  it('retries and starts a job by id', async () => {
    post.mockResolvedValue({ data: JOB })
    await expect(retryRepoJob(7)).resolves.toEqual(JOB)
    await expect(startRepoJob(7)).resolves.toEqual(JOB)
    expect(post.mock.calls).toEqual([['/repository-jobs/7/retry'], ['/repository-jobs/7/start']])
  })

  it('passes a 409 of retry on to the caller', async () => {
    const conflict = { response: { status: 409, data: { message: 'not failed' } } }
    post.mockRejectedValue(conflict)
    await expect(retryRepoJob(7)).rejects.toBe(conflict)
  })
})

describe('deleted repositories', () => {
  const DELETED = {
    repo_key: 'k1', name: 'old', type: 'maven', deleted_at: '2026-11-23T10:00:00Z',
    deleted_by: 'ayd', purge_after: '2026-11-24T10:00:00Z',
  }

  it('lists them', async () => {
    get.mockResolvedValue({ data: { items: [DELETED] } })
    await expect(listDeletedRepos()).resolves.toEqual([DELETED])
    expect(get).toHaveBeenCalledWith('/deleted-repositories', { signal: undefined })
  })

  it('restores under the old name without a body', async () => {
    post.mockResolvedValue({ data: { name: 'old', repo_key: 'k1', rejoined_groups: ['g1'] } })
    await expect(restoreDeletedRepo('k1')).resolves.toEqual({ name: 'old', repo_key: 'k1', rejoined_groups: ['g1'] })
    expect(post).toHaveBeenCalledWith('/deleted-repositories/k1/restore')
  })

  it('restores under another name and encodes the key', async () => {
    post.mockResolvedValue({ data: { name: 'other', repo_key: 'a/b', rejoined_groups: [] } })
    await restoreDeletedRepo('a/b', 'other')
    expect(post).toHaveBeenCalledWith('/deleted-repositories/a%2Fb/restore', { name: 'other' })
  })
})
