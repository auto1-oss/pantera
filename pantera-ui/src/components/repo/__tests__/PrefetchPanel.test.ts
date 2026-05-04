import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import InputSwitch from 'primevue/inputswitch'
import PrefetchPanel from '../PrefetchPanel.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'

// Mock the prefetch API at module level so we can observe + control
// every round-trip the panel makes.
const getStatsMock = vi.fn()
const patchMock = vi.fn()

vi.mock('@/api/prefetch', () => ({
  getPrefetchStats: (...args: unknown[]) => getStatsMock(...args),
  patchPrefetchEnabled: (...args: unknown[]) => patchMock(...args),
}))

function mountPanel(initialEnabled?: boolean) {
  return mount(PrefetchPanel, {
    props: { name: 'maven-proxy', initialEnabled },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
    },
  })
}

describe('PrefetchPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getStatsMock.mockReset()
    patchMock.mockReset()
    getStatsMock.mockResolvedValue({
      repo: 'maven-proxy',
      window: '24h',
      prefetched: 42,
      cooldown_blocked: 7,
      dropped_queue_full: 3,
      dropped_semaphore_saturated: 0,
      dropped_dedup_in_flight: 0,
      dropped_circuit_open: 0,
      last_fetch_at: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    })
    patchMock.mockResolvedValue(undefined)
  })

  it('renders the toggle in the on state when initialEnabled=true', async () => {
    const wrapper = mountPanel(true)
    await flushPromises()

    // PrimeVue InputSwitch reflects the bound value via the
    // {@code data-p-checked} attribute on the root element.
    const sw = wrapper.find('[data-testid="prefetch-toggle"]')
    expect(sw.exists()).toBe(true)
    expect(sw.attributes('data-p-checked')).toBe('true')
  })

  it('renders the toggle in the off state when initialEnabled=false', async () => {
    const wrapper = mountPanel(false)
    await flushPromises()

    const sw = wrapper.find('[data-testid="prefetch-toggle"]')
    expect(sw.exists()).toBe(true)
    expect(sw.attributes('data-p-checked')).toBe('false')
  })

  it('loads stats on mount and renders the four key counters', async () => {
    const wrapper = mountPanel(true)
    await flushPromises()

    expect(getStatsMock).toHaveBeenCalledWith('maven-proxy')
    const text = wrapper.text()
    expect(text).toContain('Prefetched')
    expect(text).toContain('42')
    expect(text).toContain('Cooldown blocked')
    expect(text).toContain('7')
    expect(text).toContain('Dropped: queue full')
    expect(text).toContain('3')
    expect(text).toContain('Last fetch')
    // last_fetch_at was 5min ago in the mock
    expect(text).toMatch(/5m ago|6m ago|4m ago/)
  })

  it('renders an em-dash for last fetch when the field is missing', async () => {
    getStatsMock.mockResolvedValueOnce({
      repo: 'maven-proxy',
      window: '24h',
      prefetched: 0,
      cooldown_blocked: 0,
      dropped_queue_full: 0,
      dropped_semaphore_saturated: 0,
      dropped_dedup_in_flight: 0,
      dropped_circuit_open: 0,
      // last_fetch_at intentionally omitted
    })
    const wrapper = mountPanel(true)
    await flushPromises()

    const tile = wrapper.find('[data-testid="prefetch-stat-last-fetch"]')
    expect(tile.text()).toContain('—')
  })

  it('PATCHes prefetch_enabled with the right body when toggled', async () => {
    const wrapper = mountPanel(false)
    await flushPromises()

    // PrimeVue's InputSwitch swallows raw clicks via its own internal
    // handler that derefs `this.modelValue`; in the test environment
    // (happy-dom) that handler doesn't always fire from a synthetic
    // click on the inner input. We drive the change at the component
    // boundary instead — emitting the same event the panel's
    // {@code @update:modelValue} listener is bound to. This is the
    // narrowest possible contract test: it asserts the panel calls
    // PATCH when the InputSwitch reports a new value, regardless of
    // PrimeVue internals.
    const sw = wrapper.findComponent(InputSwitch)
    expect(sw.exists()).toBe(true)
    sw.vm.$emit('update:modelValue', true)
    await flushPromises()

    expect(patchMock).toHaveBeenCalledTimes(1)
    expect(patchMock).toHaveBeenCalledWith('maven-proxy', true)
    expect(wrapper.emitted('update:enabled')).toBeTruthy()
    expect(wrapper.emitted('update:enabled')![0]).toEqual([true])
  })

  it('rolls back the toggle when PATCH fails', async () => {
    patchMock.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountPanel(false)
    await flushPromises()

    const sw = wrapper.findComponent(InputSwitch)
    sw.vm.$emit('update:modelValue', true)
    await flushPromises()

    expect(patchMock).toHaveBeenCalledTimes(1)
    // Toggle must revert to false — the error rolled back.
    // Inspect the local ref via the DOM data-p-checked attribute the
    // PrimeVue switch sets when the bound value is true.
    const root = wrapper.find('[data-testid="prefetch-toggle"]')
    expect(root.attributes('data-p-checked')).toBe('false')
    // No success emit fired.
    expect(wrapper.emitted('update:enabled')).toBeFalsy()
  })
})
