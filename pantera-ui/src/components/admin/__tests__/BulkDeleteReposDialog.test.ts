import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import BulkDeleteReposDialog from '../BulkDeleteReposDialog.vue'

const deleteRepoMock = vi.fn()
vi.mock('@/api/repos', () => ({ deleteRepo: (...a: unknown[]) => deleteRepoMock(...a) }))

function mountDialog(names: string[]) {
  return mount(BulkDeleteReposDialog, {
    props: { visible: true, names },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
  })
}

describe('BulkDeleteReposDialog', () => {
  beforeEach(() => { deleteRepoMock.mockReset() })

  it('requires typing delete and reports per-repo outcomes in order', async () => {
    deleteRepoMock
      .mockResolvedValueOnce('deleted')
      .mockResolvedValueOnce('deleting')
      .mockRejectedValueOnce({ response: { data: { message: '409 in use' } } })
    const w = mountDialog(['a', 'b', 'c'])
    await flushPromises()
    const btn = document.querySelector('[data-testid="bulk-delete-confirm"]') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    const input = document.querySelector('[data-testid="bulk-delete-input"]') as HTMLInputElement
    input.value = 'delete'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(btn.disabled).toBe(false)
    btn.click()
    await flushPromises()
    expect(deleteRepoMock.mock.calls.map(c => c[0])).toEqual(['a', 'b', 'c'])
    const emitted = w.emitted('deleted')![0][0] as { deleted: string[]; deleting: string[]; failed: Array<{ name: string; reason: string }> }
    expect(emitted.deleted).toEqual(['a'])
    expect(emitted.deleting).toEqual(['b'])
    expect(emitted.failed).toEqual([{ name: 'c', reason: '409 in use' }])
    // failures stay visible in the dialog instead of closing it
    expect(document.body.textContent).toContain('409 in use')
    expect(w.emitted('update:visible') ?? []).not.toContainEqual([false])
    w.unmount()
  })

  it('closes itself when every delete succeeded', async () => {
    deleteRepoMock.mockResolvedValue('deleted')
    const w = mountDialog(['x'])
    await flushPromises()
    const input = document.querySelector('[data-testid="bulk-delete-input"]') as HTMLInputElement
    input.value = 'delete'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(document.querySelector('[data-testid="bulk-delete-confirm"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(w.emitted('update:visible')).toContainEqual([false])
    w.unmount()
  })
})
