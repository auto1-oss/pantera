import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RepoListView from '../RepoListView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primevue/themes/aura'

vi.mock('@/api/repos', () => ({
  listRepos: vi.fn().mockResolvedValue({
    items: ['maven-central', 'docker-local'],
    page: 0, size: 20, total: 2, hasMore: false,
  }),
}))

describe('RepoListView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders repository list heading', async () => {
    const wrapper = mount(RepoListView, {
      global: {
        plugins: [[PrimeVue, { theme: { preset: Aura } }]],
        stubs: {
          'router-link': true,
          'router-view': true,
          AppLayout: { template: '<div><slot /></div>' },
        },
      },
    })
    await new Promise((r) => setTimeout(r, 50))
    expect(wrapper.text()).toContain('Repositories')
  })
})
