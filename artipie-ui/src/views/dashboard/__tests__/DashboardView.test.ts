import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DashboardView from '../DashboardView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primevue/themes/aura'

vi.mock('@/api/settings', () => ({
  getDashboardStats: vi.fn().mockResolvedValue({
    repo_count: 10, artifact_count: 500, total_storage: 1024, blocked_count: 2,
  }),
  getReposByType: vi.fn().mockResolvedValue({ types: { maven: 5, docker: 3 } }),
  getDashboardRequests: vi.fn().mockResolvedValue({ period: '24h', data: [] }),
}))

describe('DashboardView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders stat cards', async () => {
    const wrapper = mount(DashboardView, {
      global: {
        plugins: [[PrimeVue, { theme: { preset: Aura } }]],
        stubs: {
          'router-link': true,
          Chart: true,
          AppLayout: { template: '<div><slot /></div>' },
        },
      },
    })
    // Wait for async onMounted
    await new Promise((r) => setTimeout(r, 50))
    expect(wrapper.text()).toContain('Dashboard')
  })
})
