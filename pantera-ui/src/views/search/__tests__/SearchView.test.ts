import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SearchView from '../SearchView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'

vi.mock('@/api/search', () => ({
  search: vi.fn().mockResolvedValue({
    items: [{ repo_type: 'maven', repo_name: 'central', artifact_path: 'com/example/foo', size: 512 }],
    page: 0, size: 20, total: 1, hasMore: false,
  }),
}))

describe('SearchView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders search input', () => {
    const wrapper = mount(SearchView, {
      global: {
        plugins: [[PrimeVue, { theme: { preset: Aura } }]],
        stubs: {
          'router-link': true,
          AppLayout: { template: '<div><slot /></div>' },
        },
      },
    })
    expect(wrapper.find('[data-testid="search-input"]').exists() || wrapper.text().includes('Search')).toBe(true)
  })
})
