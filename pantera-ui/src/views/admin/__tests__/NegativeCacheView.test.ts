import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import NegativeCacheView from '../NegativeCacheView.vue'
import { useNotificationStore } from '@/stores/notifications'
import type { NegCacheEntry } from '@/api/negCache'

const listMock = vi.fn()
const probeMock = vi.fn()
const invalidateKeyMock = vi.fn()
const invalidatePackageMock = vi.fn()
const invalidatePatternMock = vi.fn()
const statsMock = vi.fn()

vi.mock('@/api/negCache', () => ({
  listNegCache: (...a: unknown[]) => listMock(...a),
  probeNegCacheUrl: (...a: unknown[]) => probeMock(...a),
  invalidateNegCacheKey: (...a: unknown[]) => invalidateKeyMock(...a),
  invalidateNegCachePackage: (...a: unknown[]) => invalidatePackageMock(...a),
  invalidateNegCachePattern: (...a: unknown[]) => invalidatePatternMock(...a),
  getNegCacheStats: (...a: unknown[]) => statsMock(...a),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const LODASH_GROUP: NegCacheEntry = {
  key: { scope: 'npm-group', repoType: 'npm', artifactName: 'lodash', artifactVersion: '' },
  tiers: ['L1', 'L2'],
  ttlRemainingMs: 3_600_000 + 5 * 60_000,
}
const FOO_MAVEN: NegCacheEntry = {
  key: { scope: 'maven-proxy', repoType: 'maven', artifactName: 'com.example:foo', artifactVersion: '1.0' },
  tiers: ['L2'],
  ttlRemainingMs: 90_000,
}

let wrapper: VueWrapper | null = null

function mountView() {
  wrapper = mount(NegativeCacheView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' },
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
  return wrapper
}

function vmOf(w: VueWrapper) {
  return w.vm as unknown as {
    search: string
    scopeFilter: string | null
    typeFilter: string | null
    probeUrl: string
    scopeOptions: Array<{ label: string; value: string | null }>
    typeOptions: Array<{ label: string; value: string | null }>
  }
}

async function settleDebounce() {
  // The search box debounces for 400 ms before querying.
  await new Promise(r => setTimeout(r, 450))
  await flushPromises()
}

describe('NegativeCacheView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const m of [listMock, probeMock, invalidateKeyMock, invalidatePackageMock, invalidatePatternMock, statsMock]) {
      m.mockReset()
    }
    listMock.mockResolvedValue({
      items: [LODASH_GROUP, FOO_MAVEN], total: 2, source: 'L2+L1', node: 'pantera-a',
    })
    statsMock.mockResolvedValue({
      enabled: true, l1Size: 12, l2Size: 340, hitCount: 5, missCount: 15, hitRate: 0.25,
      evictionCount: 0, requestCount: 20, node: 'pantera-a',
    })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('renders cluster-wide rows with tiers, human TTL, stats and the answering node', async () => {
    const w = mountView()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('lodash')
    expect(text).toContain('com.example:foo')
    expect(text).toContain('1h 5m')
    expect(text).toContain('1m 30s')
    expect(w.get('[data-testid="negcache-l2size"]').text()).toBe('340')
    expect(w.get('[data-testid="negcache-origin"]').text()).toContain('pantera-a')
    expect(w.get('[data-testid="negcache-origin"]').text()).toContain('L2+L1')
    expect(text).toContain('25.0%')
  })

  it('populates repository and type dropdowns from the loaded data', async () => {
    const w = mountView()
    await flushPromises()
    const vm = vmOf(w)
    expect(vm.scopeOptions.map(o => o.value)).toEqual([null, 'maven-proxy', 'npm-group'])
    expect(vm.typeOptions.map(o => o.value)).toEqual([null, 'maven', 'npm'])
  })

  it('sends the free-text search as q after the debounce, and dropdowns as scope/repoType', async () => {
    const w = mountView()
    await flushPromises()
    const vm = vmOf(w)
    vm.search = 'LoDash'
    await settleDebounce()
    expect(listMock.mock.calls.at(-1)![0]).toMatchObject({ q: 'LoDash', page: 0 })

    vm.scopeFilter = 'npm-group'
    vm.typeFilter = 'npm'
    await flushPromises()
    expect(listMock.mock.calls.at(-1)![0]).toMatchObject({ q: 'LoDash', scope: 'npm-group', repoType: 'npm' })
  })

  it('clears a single row, toasts the honest counts and reloads list and stats', async () => {
    invalidateKeyMock.mockResolvedValue({ l1: 1, l2: 1 })
    const w = mountView()
    await flushPromises()
    const listCalls = listMock.mock.calls.length
    const statsCalls = statsMock.mock.calls.length

    await w.findAll('[data-testid="negcache-row-clear"]')[0].trigger('click')
    await flushPromises()

    expect(invalidateKeyMock).toHaveBeenCalledWith(LODASH_GROUP.key)
    expect(listMock.mock.calls.length).toBe(listCalls + 1)
    expect(statsMock.mock.calls.length).toBe(statsCalls + 1)
    const toast = useNotificationStore().toasts.at(-1)!
    expect(toast.severity).toBe('success')
    expect(toast.detail).toContain('Removed 1 from L1, 1 from L2')
  })

  it('reports "nothing to remove" when the server removed zero entries', async () => {
    invalidateKeyMock.mockResolvedValue({ l1: 0, l2: 0 })
    const w = mountView()
    await flushPromises()
    await w.findAll('[data-testid="negcache-row-clear"]')[1].trigger('click')
    await flushPromises()
    const toast = useNotificationStore().toasts.at(-1)!
    expect(toast.severity).toBe('info')
    expect(toast.summary).toContain('nothing to remove')
  })

  it('clears every entry for a package after confirmation', async () => {
    invalidatePackageMock.mockResolvedValue({ l1: 2, l2: 3 })
    const w = mountView()
    await flushPromises()

    await w.findAll('[data-testid="negcache-row-clear-package"]')[0].trigger('click')
    await flushPromises()
    expect(invalidatePackageMock).not.toHaveBeenCalled()

    const confirm = document.body.querySelector('[data-testid="negcache-confirm-clear-package"]') as HTMLElement
    expect(confirm).not.toBeNull()
    expect(confirm.textContent).toContain('Clear all for lodash')
    confirm.click()
    await flushPromises()

    expect(invalidatePackageMock).toHaveBeenCalledWith({ artifactName: 'lodash', repoType: 'npm' })
    expect(useNotificationStore().toasts.at(-1)!.detail).toContain('Removed 2 from L1, 3 from L2')
  })

  it('shows a shadowed verdict for a URL with present keys and clears a key from the probe', async () => {
    probeMock.mockResolvedValue({
      shadowed: true,
      keys: [
        { key: LODASH_GROUP.key, l1: true, l2: true, ttlRemainingMs: 60_000 },
        { key: { ...LODASH_GROUP.key, scope: 'npm-proxy' }, l1: false, l2: false, ttlRemainingMs: null },
      ],
    })
    invalidateKeyMock.mockResolvedValue({ l1: 1, l2: 1 })
    const w = mountView()
    await flushPromises()
    vmOf(w).probeUrl = '/npm-group/lodash'
    await flushPromises()
    await w.get('#negcache-probe-url').trigger('keyup.enter')
    await flushPromises()

    expect(probeMock).toHaveBeenCalledWith('/npm-group/lodash')
    const verdict = w.get('[data-testid="negcache-probe-verdict"]').text()
    expect(verdict).toContain('This URL is shadowed by the negative cache')
    const link = w.get('[data-testid="negcache-troubleshoot-link"]')
    expect(link.attributes('data-to')).toContain('/admin/troubleshoot')
    expect(link.attributes('data-to')).toContain('/npm-group/lodash')

    // Only the present key offers a Clear button.
    const clears = w.findAll('[data-testid="negcache-probe-clear"]')
    expect(clears).toHaveLength(1)

    probeMock.mockResolvedValue({
      shadowed: false,
      keys: [{ key: LODASH_GROUP.key, l1: false, l2: false, ttlRemainingMs: null }],
    })
    await clears[0].trigger('click')
    await flushPromises()
    expect(invalidateKeyMock).toHaveBeenCalledWith(LODASH_GROUP.key)
    // The probe is re-run after the clear, so the verdict flips.
    expect(probeMock).toHaveBeenCalledTimes(2)
    expect(w.get('[data-testid="negcache-probe-verdict"]').text()).toContain('not shadowed')
  })

  it('reports the pattern rate limit on 429', async () => {
    invalidatePatternMock.mockRejectedValue({ response: { status: 429 } })
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as { doInvalidatePattern: () => Promise<void> }
    await vm.doInvalidatePattern()
    await flushPromises()
    const toast = useNotificationStore().toasts.at(-1)!
    expect(toast.severity).toBe('error')
    expect(toast.summary).toBe('Rate limit exceeded')
  })
})
