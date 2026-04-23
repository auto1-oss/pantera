import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import RepoDetailView from '../RepoDetailView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import * as reposApi from '@/api/repos'

vi.mock('@/api/repos', () => ({
  getRepo: vi.fn().mockResolvedValue({ repo: { type: 'maven-proxy', storage: {} } }),
  getTree: vi.fn().mockResolvedValue({
    items: [
      { name: 'a.jar', path: '/a.jar', type: 'file', size: 10, modified: '2026-04-23T14:00:00Z' },
    ],
    marker: null,
    hasMore: false,
  }),
  getArtifactDetail: vi.fn().mockResolvedValue({ path: '/foo', name: 'foo', size: 1024 }),
  getPullInstructions: vi.fn().mockResolvedValue({ type: 'maven-proxy', instructions: [] }),
  deleteArtifacts: vi.fn().mockResolvedValue(undefined),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div/>' } },
    { path: '/repositories/:name', component: RepoDetailView, props: true },
  ],
})

function mountView() {
  return mount(RepoDetailView, {
    props: { name: 'my-repo' },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], router],
      stubs: {
        'router-link': true,
        'router-view': true,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
}

describe('RepoDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders repo name', async () => {
    await router.push('/repositories/my-repo')
    await router.isReady()
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('my-repo')
  })

  it('clicking the Size header triggers getTree with sort=size&sort_dir=asc, then desc on re-click', async () => {
    await router.push('/repositories/my-repo')
    await router.isReady()
    const wrapper = mountView()
    await flushPromises()
    const headers = wrapper.findAll('[data-testid="tree-header"]')
    expect(headers[2].text()).toContain('Size')
    await headers[2].trigger('click')
    await flushPromises()
    const getTreeMock = vi.mocked(reposApi.getTree)
    const lastCall = getTreeMock.mock.calls[getTreeMock.mock.calls.length - 1]
    expect(lastCall[1]).toMatchObject({ sort: 'size', sort_dir: 'asc' })
    // Re-click flips to desc
    await headers[2].trigger('click')
    await flushPromises()
    const lastCall2 = getTreeMock.mock.calls[getTreeMock.mock.calls.length - 1]
    expect(lastCall2[1]).toMatchObject({ sort: 'size', sort_dir: 'desc' })
  })
})
