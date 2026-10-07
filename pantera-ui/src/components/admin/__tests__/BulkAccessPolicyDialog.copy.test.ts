import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import BulkAccessPolicyDialog from '../BulkAccessPolicyDialog.vue'

vi.mock('@/api/repos', () => ({ bulkUpdateAccessPolicy: vi.fn() }))

describe('BulkAccessPolicyDialog copy', () => {
  it('speaks in plain language', async () => {
    const w = mount(BulkAccessPolicyDialog, {
      props: { visible: true, selectorType: 'all', selectedNames: ['a', 'b'], scopeCount: 2 },
      attachTo: document.body,
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
    })
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('Anonymous access for 2 repositories')
    expect(text).toContain('Allow anonymous download')
    expect(text).toContain('Allow anonymous upload')
    expect(text).toContain('rejected with 401')
    expect(text).not.toContain('Set access policy')
    expect(text).not.toContain('Set anonymous-access policy')
    w.unmount()
  })
})
