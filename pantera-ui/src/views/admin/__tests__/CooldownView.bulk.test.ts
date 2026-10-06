import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import CooldownView from '../CooldownView.vue'
import type { BlockedArtifact } from '@/types'

const getCooldownBlockedMock = vi.fn()
const getCooldownOverviewMock = vi.fn()
const unblockBulkMock = vi.fn()

vi.mock('@/api/settings', () => ({
  getCooldownOverview: (...a: unknown[]) => getCooldownOverviewMock(...a),
  getCooldownBlocked: (...a: unknown[]) => getCooldownBlockedMock(...a),
  getCooldownHistory: vi.fn().mockResolvedValue({ items: [], page: 0, size: 50, total: 0, hasMore: false }),
}))
vi.mock('@/api/repos', () => ({ unblockArtifact: vi.fn(), unblockAll: vi.fn() }))
vi.mock('@/api/cooldown', async (orig) => ({
  ...(await orig<object>()),
  unblockBulk: (...a: unknown[]) => unblockBulkMock(...a),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))

const ROWS: BlockedArtifact[] = [
  { package_name: 'lodash', version: '4.17.21', repo: 'npm-proxy', repo_type: 'npm-proxy', reason: 'FRESH_RELEASE', blocked_date: '2026-10-01', blocked_until: '2026-10-08T00:00:00Z', remaining_hours: 10 },
  { package_name: 'left-pad', version: '1.3.0', repo: 'npm-proxy', repo_type: 'npm-proxy', reason: 'FRESH_RELEASE', blocked_date: '2026-10-01', blocked_until: '2026-10-08T00:00:00Z', remaining_hours: 10 },
]

function mountView() {
  const r = createRouter({ history: createMemoryHistory(), routes: [{ path: '/cooldown', component: { template: '<div />' } }] })
  return mount(CooldownView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], r],
      directives: { tooltip: {} },
      stubs: { AppLayout: { template: '<div><slot /></div>' }, BulkUnblockDialog: true },
    },
  })
}

type Vm = {
  selectedBlocked: BlockedArtifact[]
  repoFilter: string | null
  mode: 'active' | 'history'
  runBulkUnblock: () => Promise<void>
}

describe('CooldownView bulk unblock', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = {
      name: 't', context: 'c',
      permissions: { api_cooldown_permissions: ['read', 'write'], api_cooldown_history_permissions: ['read'] },
    } as unknown as typeof auth.user
    getCooldownOverviewMock.mockReset()
    getCooldownOverviewMock.mockResolvedValue([])
    getCooldownBlockedMock.mockReset()
    getCooldownBlockedMock.mockResolvedValue({ items: ROWS, page: 0, size: 50, total: 2, hasMore: false })
    unblockBulkMock.mockReset()
  })

  it('shows a checkbox column in active mode and a bar when rows are selected', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.findAll('tbody input[type="checkbox"]').length).toBe(2)
    expect(w.find('[data-testid="selection-bar"]').exists()).toBe(false)
    const vm = w.vm as unknown as Vm
    vm.selectedBlocked = [ROWS[0]]
    await flushPromises()
    expect(w.find('[data-testid="selection-bar"]').exists()).toBe(true)
    expect(w.text()).toContain('Unblock selected')
  })

  it('hides the checkbox column in history mode', async () => {
    const w = mountView()
    await flushPromises()
    ;(w.vm as unknown as Vm).mode = 'history'
    await flushPromises()
    expect(w.findAll('tbody input[type="checkbox"]').length).toBe(0)
  })

  it('filterChangeClearsBulkSelection', async () => {
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as Vm
    vm.selectedBlocked = [ROWS[0]]
    await flushPromises()
    vm.repoFilter = 'npm-proxy'
    await flushPromises()
    expect(vm.selectedBlocked.length).toBe(0)
  })

  it('sends the selected items in one request, clears the selection and reloads', async () => {
    unblockBulkMock.mockResolvedValue({
      unblocked: [{ repo: 'npm-proxy', artifact: 'lodash', version: '4.17.21' }],
      failed: [{ repo: 'npm-proxy', artifact: 'left-pad', version: '1.3.0', reason: 'forbidden' }],
    })
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as Vm
    vm.selectedBlocked = [...ROWS]
    await flushPromises()
    const before = getCooldownBlockedMock.mock.calls.length
    await vm.runBulkUnblock()
    await flushPromises()
    expect(unblockBulkMock).toHaveBeenCalledTimes(1)
    expect(unblockBulkMock.mock.calls[0][0]).toEqual([
      { repo: 'npm-proxy', artifact: 'lodash', version: '4.17.21' },
      { repo: 'npm-proxy', artifact: 'left-pad', version: '1.3.0' },
    ])
    expect(vm.selectedBlocked.length).toBe(0)
    expect(getCooldownBlockedMock.mock.calls.length).toBeGreaterThan(before)
  })
})
