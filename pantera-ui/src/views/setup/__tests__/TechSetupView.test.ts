import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import TechSetupView from '../TechSetupView.vue'
import { listRepos, getRepo } from '@/api/repos'

vi.mock('@/api/repos', () => ({
  listRepos: vi.fn(),
  getRepo: vi.fn(),
}))
vi.mock('@/api/settings', () => ({
  getUiSettings: vi.fn().mockResolvedValue({}),
}))

function page(items: { name: string; type: string }[]) {
  return { items, page: 0, size: 100, total: items.length, hasMore: false }
}

function mountView(tech: string) {
  return mount(TechSetupView, {
    props: { tech },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: {
        'router-link': { template: '<a><slot /></a>' },
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
}

describe('TechSetupView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listRepos).mockReset()
    vi.mocked(getRepo).mockReset()
  })

  it('lists every repository once and loads the list in a single pass', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([
      { name: 'npm-all', type: 'npm-group' },
      { name: 'npmjs', type: 'npm-proxy' },
      { name: 'npm-local', type: 'npm' },
      { name: 'pypi-local', type: 'pypi' },
    ]))
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm-group', members: ['npm-local', 'npmjs'] } })
    const wrapper = mountView('npm')
    await flushPromises()
    const opts = (wrapper.findComponent({ name: 'Select' }).props('options') as { value: string }[])
      .map(o => o.value)
    expect({ opts, calls: vi.mocked(listRepos).mock.calls.length })
      .toEqual({ opts: ['npm-all', 'npmjs', 'npm-local'], calls: 1 })
  })

  it('publishes to the group\'s local member when resolving from a group', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([
      { name: 'npm-all', type: 'npm-group' },
      { name: 'npm-other', type: 'npm' },
      { name: 'npm-local', type: 'npm' },
    ]))
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm-group', members: ['npm-local', 'npm-other'] } })
    const wrapper = mountView('npm')
    await flushPromises()
    const publish = wrapper.find('[data-testid="publish-steps"]').text()
    expect({
      local: publish.includes('/npm-local/'),
      group: publish.includes('/npm-all'),
      note: wrapper.find('[data-testid="read-only-note"]').exists(),
    }).toEqual({ local: true, group: false, note: true })
  })

  it('shows no publish steps when only read-only repositories exist', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npmjs', type: 'npm-proxy' }]))
    const wrapper = mountView('npm')
    await flushPromises()
    expect({
      steps: wrapper.find('[data-testid="publish-steps"]').exists(),
      notice: wrapper.find('[data-testid="no-publish-target"]').exists(),
    }).toEqual({ steps: false, notice: true })
  })
})
