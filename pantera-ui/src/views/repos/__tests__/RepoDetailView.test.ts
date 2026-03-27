import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RepoDetailView from '../RepoDetailView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primevue/themes/aura'

vi.mock('@/api/repos', () => ({
  getRepo: vi.fn().mockResolvedValue({ repo: { type: 'maven-proxy', storage: {} } }),
  getTree: vi.fn().mockResolvedValue({ items: [], marker: null, hasMore: false }),
  getArtifactDetail: vi.fn().mockResolvedValue({ path: '/foo', name: 'foo', size: 1024 }),
  getPullInstructions: vi.fn().mockResolvedValue({ type: 'maven-proxy', instructions: ['mvn dependency:get -Dartifact=...'] }),
}))

describe('RepoDetailView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders repo name', async () => {
    const wrapper = mount(RepoDetailView, {
      props: { name: 'my-repo' },
      global: {
        plugins: [[PrimeVue, { theme: { preset: Aura } }]],
        stubs: {
          'router-link': true,
          'router-view': true,
          AppLayout: { template: '<div><slot /></div>' },
        },
      },
    })
    await new Promise((r) => setTimeout(r, 100))
    expect(wrapper.text()).toContain('my-repo')
  })
})
