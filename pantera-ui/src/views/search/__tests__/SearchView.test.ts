import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, RouterLinkStub, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SearchView from '../SearchView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { search as searchApi } from '@/api/search'
import type { SearchResult } from '@/types'

vi.mock('@/api/search', () => ({
  search: vi.fn(),
}))

function makeResult(overrides: Partial<SearchResult>): SearchResult {
  return {
    repo_type: 'file',
    repo_name: 'repo',
    artifact_path: 'artifact',
    size: 100,
    ...overrides,
  }
}

async function mountWithResult(item: SearchResult) {
  vi.mocked(searchApi).mockResolvedValue({
    items: [item],
    page: 0,
    size: 20,
    total: 1,
  })
  const wrapper = mount(SearchView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: {
        'router-link': RouterLinkStub,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
  await wrapper.find('[data-testid="search-input"]').setValue('anything')
  const searchButton = wrapper.findAll('button').find((b) => b.text() === 'Search')
  await searchButton!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('SearchView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders search input', () => {
    vi.mocked(searchApi).mockResolvedValue({
      items: [{ repo_type: 'maven', repo_name: 'central', artifact_path: 'com/example/foo', size: 512 }],
      page: 0, size: 20, total: 1,
    })
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

  describe('browse target and displayed artifact name', () => {
    it('flat dotted file key (reported bug) browses to repo root and shows the full name', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'services',
        artifact_path: 'wkda.services.b2x-vehicle-store-service.1.0.0-SNAPSHOT.b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom',
        version: '1.0.0-SNAPSHOT',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/services?path=%2F&from=search')
      expect(wrapper.text()).toContain(
        'wkda.services.b2x-vehicle-store-service.1.0.0-SNAPSHOT.b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom',
      )
    })

    it('slashed file key browses to its parent dir and shows the trailing segment as name', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'files',
        artifact_path: '/a/b/c.txt',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/files?path=%2Fa%2Fb&from=search')
      expect(wrapper.text()).toContain('c.txt')
    })

    it('slashed maven key with version dots is not dot-mangled', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'maven',
        repo_name: 'central',
        artifact_path: '/com/google/guava/guava/31.0-jre/guava-31.0-jre.jar',
        version: '31.0-jre',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      const to = link.props('to') as string
      expect(to).not.toContain('31/0-jre')
    })

    it('npm scoped package path is unchanged', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'npm',
        repo_name: 'npm-local',
        artifact_path: '@scope/pkg',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/npm-local?path=%2F%40scope%2Fpkg&from=search')
    })

    it('does not split on a dot that is part of a directory name', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'files',
        artifact_path: '/com.example/foo/bar.jar',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/files?path=%2Fcom.example%2Ffoo&from=search')
    })
  })
})
