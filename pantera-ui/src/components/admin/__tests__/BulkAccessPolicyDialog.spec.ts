import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import BulkAccessPolicyDialog from '../BulkAccessPolicyDialog.vue'

// Module mock: bulkUpdateAccessPolicy is the only thing the dialog calls.
// We capture invocations so submit() assertions can verify the request body.
const bulkUpdateAccessPolicy = vi.fn()

vi.mock('@/api/repos', () => ({
  bulkUpdateAccessPolicy: (...args: unknown[]) => bulkUpdateAccessPolicy(...args),
}))

function mountDialog(props: Partial<{
  visible: boolean
  selectorType: 'hosted' | 'proxy' | 'group' | 'all'
  selectedNames: string[]
  scopeCount: number
}> = {}) {
  return mount(BulkAccessPolicyDialog, {
    attachTo: document.body,
    props: {
      visible: true,
      selectorType: 'all',
      scopeCount: 0,
      ...props,
    },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
    },
  })
}

describe('BulkAccessPolicyDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    bulkUpdateAccessPolicy.mockReset()
  })

  it('describes a hosted scope with the count + type label', async () => {
    const wrapper = mountDialog({ selectorType: 'hosted', scopeCount: 5 })
    await flushPromises()
    // Dialog renders to document.body (PrimeVue teleports).
    expect(document.body.textContent).toContain('all 5 hosted repositories')
    wrapper.unmount()
  })

  it('shows the hosted-read warning only when both conditions hold', async () => {
    const wrapper = mountDialog({ selectorType: 'hosted', scopeCount: 5 })
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      setRead: boolean; anonymousRead: boolean; warnHostedRead: boolean
    }
    // No toggles flipped yet -> no warning.
    expect(vm.warnHostedRead).toBe(false)

    vm.setRead = true
    vm.anonymousRead = true
    await flushPromises()
    expect(vm.warnHostedRead).toBe(true)
    expect(document.body.textContent).toContain('allow anonymous reads')
    wrapper.unmount()
  })

  it('submit emits applied with the API result and forwards the patch body', async () => {
    const apiResult = {
      updated: [{
        name: 'maven-central',
        previous: { anonymous_read: false, anonymous_write: false },
        current:  { anonymous_read: true,  anonymous_write: false },
      }],
      skipped: [],
    }
    bulkUpdateAccessPolicy.mockResolvedValueOnce(apiResult)

    const wrapper = mountDialog({
      selectorType: 'proxy',
      scopeCount: 12,
      selectedNames: ['maven-central', 'npm-registry'],
    })
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      setRead: boolean; anonymousRead: boolean; submit: () => Promise<void>
    }
    vm.setRead = true
    vm.anonymousRead = true
    await flushPromises()

    await vm.submit()
    await flushPromises()

    expect(bulkUpdateAccessPolicy).toHaveBeenCalledTimes(1)
    expect(bulkUpdateAccessPolicy).toHaveBeenCalledWith({
      selector: { type: 'proxy', names: ['maven-central', 'npm-registry'] },
      anonymous_read: true,
    })

    const applied = wrapper.emitted('applied') as Array<[typeof apiResult]>
    expect(applied).toHaveLength(1)
    expect(applied[0][0]).toEqual(apiResult)

    const visible = wrapper.emitted('update:visible') as Array<[boolean]>
    expect(visible.some(e => e[0] === false)).toBe(true)
    wrapper.unmount()
  })
})
