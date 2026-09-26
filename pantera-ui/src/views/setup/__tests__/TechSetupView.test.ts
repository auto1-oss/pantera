import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import TechSetupView from '../TechSetupView.vue'
import { listRepos, getRepo } from '@/api/repos'

vi.mock('@/api/repos', () => ({
  listRepos: vi.fn(),
  getRepo: vi.fn(),
}))
vi.mock('@/api/settings', () => ({
  getUiSettings: vi.fn().mockResolvedValue({ ui: { registry_url: 'https://reg.example.com', prefixes: ['artifactory'] } }),
}))

function page(items: { name: string; type: string }[]) {
  return { items, page: 0, size: 100, total: items.length, hasMore: false }
}

async function mountView(tech: string, query = '') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/setup/:tech', component: TechSetupView, props: true }],
  })
  await router.push(`/setup/${tech}${query}`)
  const wrapper = mount(TechSetupView, {
    props: { tech },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], router],
      stubs: {
        'router-link': { template: '<a><slot /></a>' },
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('TechSetupView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listRepos).mockReset()
    vi.mocked(getRepo).mockReset()
  })

  it('lists every repository once, grouped by mode, in a single pass', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([
      { name: 'npm-all', type: 'npm-group' },
      { name: 'npmjs', type: 'npm-proxy' },
      { name: 'npm-local', type: 'npm' },
      { name: 'pypi-local', type: 'pypi' },
    ]))
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm-group', members: ['npm-local', 'npmjs'] } })
    const { wrapper } = await mountView('npm')
    const groups = (wrapper.findComponent({ name: 'Select' }).props('options') as
      { label: string; items: { value: string }[] }[])
      .map(g => `${g.label}:${g.items.map(i => i.value).join(',')}`)
    expect({ groups, calls: vi.mocked(listRepos).mock.calls.length })
      .toEqual({ groups: ['Group:npm-all', 'Proxy:npmjs', 'Local:npm-local'], calls: 1 })
  })

  it('builds repository URLs from the registry URL and the global prefix', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npm-local', type: 'npm' }]))
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm' } })
    const { wrapper } = await mountView('npm')
    expect(wrapper.find('[data-testid="resolve-url"]').text())
      .toBe('https://reg.example.com/artifactory/npm-local/')
  })

  it('publishes to the group\'s local member when resolving from a group', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([
      { name: 'npm-all', type: 'npm-group' },
      { name: 'npm-other', type: 'npm' },
      { name: 'npm-local', type: 'npm' },
    ]))
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm-group', members: ['npm-local', 'npm-other'] } })
    const { wrapper } = await mountView('npm')
    expect({
      url: wrapper.find('[data-testid="publish-url"]').text(),
      note: wrapper.find('[data-testid="read-only-note"]').exists(),
    }).toEqual({ url: 'https://reg.example.com/artifactory/npm-local/', note: true })
  })

  it('shows a notice instead of a publish target when only read-only repositories exist', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npmjs', type: 'npm-proxy' }]))
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm-proxy' } })
    const { wrapper } = await mountView('npm')
    expect({
      url: wrapper.find('[data-testid="publish-url"]').exists(),
      notice: wrapper.find('[data-testid="no-publish-target"]').exists(),
    }).toEqual({ url: false, notice: true })
  })

  it('selects the repository named in the deep link and mirrors it into the query', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([
      { name: 'npm-all', type: 'npm-group' },
      { name: 'npm-local', type: 'npm' },
    ]))
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm' } })
    const { wrapper, router } = await mountView('npm', '?repo=npm-local&tab=verify')
    await flushPromises()
    const { repo, tab } = router.currentRoute.value.query
    expect({
      url: wrapper.find('[data-testid="resolve-url"]').text(),
      query: { repo, tab },
    }).toEqual({
      url: 'https://reg.example.com/artifactory/npm-local/',
      query: { repo: 'npm-local', tab: 'verify' },
    })
  })
})
