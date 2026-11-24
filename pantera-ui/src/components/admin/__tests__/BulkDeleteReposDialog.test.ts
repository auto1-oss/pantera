import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import BulkDeleteReposDialog from '../BulkDeleteReposDialog.vue'
import type { BulkDeleteResult } from '../BulkDeleteReposDialog.vue'

const deleteRepoMock = vi.fn()
vi.mock('@/api/repos', () => ({ deleteRepo: (...a: unknown[]) => deleteRepoMock(...a) }))

const JOB = { kind: 'job', jobId: 1, repoKey: 'k', purgeAfter: '2026-11-24T10:00:00Z', removedFromGroups: [], message: '' }
const GROUP_REFUSAL = {
  response: { status: 409, data: { message: "Repository 'c' is a member of group(s) g1, g2; remove it from them or delete with force=true" } },
}

function mountDialog(names: string[]) {
  return mount(BulkDeleteReposDialog, {
    props: { visible: true, names },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
  })
}

async function typeDelete() {
  const input = document.querySelector('[data-testid="bulk-delete-input"]') as HTMLInputElement
  input.value = 'delete'
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

function confirmButton(): HTMLButtonElement {
  return document.querySelector('[data-testid="bulk-delete-confirm"]') as HTMLButtonElement
}

describe('BulkDeleteReposDialog', () => {
  beforeEach(() => {
    deleteRepoMock.mockReset()
    document.body.innerHTML = ''
  })

  it('requires typing delete and reports per-repo outcomes in order', async () => {
    deleteRepoMock
      .mockResolvedValueOnce({ kind: 'deleted' })
      .mockResolvedValueOnce({ kind: 'deleting', message: '' })
      .mockRejectedValueOnce({ response: { data: { message: '409 in use' } } })
      .mockResolvedValueOnce(JOB)
    const w = mountDialog(['a', 'b', 'c', 'd'])
    await flushPromises()
    const btn = confirmButton()
    expect(btn.disabled).toBe(true)
    await typeDelete()
    expect(btn.disabled).toBe(false)
    btn.click()
    await flushPromises()
    expect(deleteRepoMock.mock.calls.map(c => c[0])).toEqual(['a', 'b', 'c', 'd'])
    // force is not sent unless the operator ticked it
    expect(deleteRepoMock.mock.calls.map(c => c[1])).toEqual([{}, {}, {}, {}])
    const emitted = w.emitted('deleted')![0][0] as BulkDeleteResult
    expect(emitted.deleted).toEqual(['a'])
    expect(emitted.deleting).toEqual(['b', 'd'])
    expect(emitted.failed).toEqual([{ name: 'c', reason: '409 in use' }])
    // failures stay visible in the dialog instead of closing it
    expect(document.body.textContent).toContain('409 in use')
    expect(w.emitted('update:visible') ?? []).not.toContainEqual([false])
    w.unmount()
  })

  it('closes itself when every delete succeeded', async () => {
    deleteRepoMock.mockResolvedValue({ kind: 'deleted' })
    const w = mountDialog(['x'])
    await flushPromises()
    await typeDelete()
    confirmButton().click()
    await flushPromises()
    expect(w.emitted('update:visible')).toContainEqual([false])
    w.unmount()
  })

  it('a group-membership refusal of one repository does not abort the others', async () => {
    deleteRepoMock
      .mockResolvedValueOnce(JOB)
      .mockRejectedValueOnce(GROUP_REFUSAL)
      .mockResolvedValueOnce(JOB)
    const w = mountDialog(['a', 'c', 'e'])
    await flushPromises()
    await typeDelete()
    confirmButton().click()
    await flushPromises()
    expect(deleteRepoMock.mock.calls.map(c => c[0])).toEqual(['a', 'c', 'e'])
    const emitted = w.emitted('deleted')![0][0] as BulkDeleteResult
    expect(emitted.deleting).toEqual(['a', 'e'])
    expect(emitted.failed).toEqual([{ name: 'c', reason: GROUP_REFUSAL.response.data.message, inGroups: true }])
    // the refusal is reported for that repository, with the groups the server named
    const failures = document.querySelector('[data-testid="bulk-delete-failures"]')!
    expect(failures.textContent).toContain('c: Repository \'c\' is a member of group(s) g1, g2')
    expect(document.querySelector('[data-testid="bulk-delete-force-hint"]')).not.toBeNull()
    // only the refused repository is left to delete
    expect(document.querySelector('[data-testid="bulk-delete-targets"]')!.textContent).toBe('c')
    w.unmount()
  })

  it('deletes with force only after the operator ticked the box, and only what is left', async () => {
    deleteRepoMock
      .mockResolvedValueOnce(JOB)
      .mockRejectedValueOnce(GROUP_REFUSAL)
      .mockResolvedValueOnce({ ...JOB, removedFromGroups: ['g1', 'g2'] })
    const w = mountDialog(['a', 'c'])
    await flushPromises()
    await typeDelete()
    confirmButton().click()
    await flushPromises()
    // the parent clears its selection after a run; the dialog keeps the rest
    await w.setProps({ names: [] })
    const box = document.querySelector('[data-testid="bulk-delete-force"] input') as HTMLInputElement
    expect(box.checked).toBe(false)
    box.click()
    await flushPromises()
    expect(document.querySelector('[data-testid="bulk-delete-force-hint"]')).toBeNull()
    confirmButton().click()
    await flushPromises()
    expect(deleteRepoMock.mock.calls).toEqual([['a', {}], ['c', {}], ['c', { force: true }]])
    const second = w.emitted('deleted')![1][0] as BulkDeleteResult
    expect(second.deleting).toEqual(['c'])
    expect(second.failed).toEqual([])
    expect(w.emitted('update:visible')).toContainEqual([false])
    w.unmount()
  })
})
