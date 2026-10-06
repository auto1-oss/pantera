import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RepoManagementView from '../RepoManagementView.vue'
import type { RepoListItem } from '@/types'

const listReposMock = vi.fn()
const deleteRepoMock = vi.fn()
vi.mock('@/api/repos', () => ({
  listRepos: (...a: unknown[]) => listReposMock(...a),
  deleteRepo: (...a: unknown[]) => deleteRepoMock(...a),
  moveRepo: vi.fn().mockResolvedValue(undefined),
  bulkUpdateAccessPolicy: vi.fn(),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasAction: () => true }) }))

const ITEMS: RepoListItem[] = [
  { name: 'maven-central', type: 'maven-proxy', mode: 'proxy', storage: 'fs', anonymous_read: false, anonymous_write: false, immutable: null, updated_at: '2026-10-01T00:00:00Z', updated_by: 'ayd' },
  { name: 'npm-local', type: 'npm', mode: 'hosted', storage: 's3-main', anonymous_read: true, anonymous_write: false, immutable: true, updated_at: null, updated_by: null },
  { name: 'team/files', type: 'file', mode: 'hosted', storage: 'fs', anonymous_read: false, anonymous_write: false, immutable: true, updated_at: null, updated_by: null },
]

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/repositories', component: RepoManagementView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
}

async function mountView(initial = '/admin/repositories') {
  const r = router()
  await r.push(initial)
  const wrapper = mount(RepoManagementView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], r],
      directives: { tooltip: {} },
      stubs: {
        AppLayout: { template: '<div><slot /></div>' },
        BulkAccessPolicyDialog: true,
        BulkDeleteReposDialog: true,
        SetMeUpDrawer: true,
      },
    },
  })
  await flushPromises()
  return { wrapper, r }
}

type Vm = {
  selected: RepoListItem[]
  typeFilter: string | null
  modeFilter: string | null
  error: string
}

describe('RepoManagementView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listReposMock.mockReset()
    deleteRepoMock.mockReset()
    listReposMock.mockResolvedValue({ items: ITEMS, page: 0, size: 20, total: 3, hasMore: false })
  })

  it('selecting one row leaves the others unselected', async () => {
    const { wrapper } = await mountView()
    const boxes = wrapper.findAll('tbody input[type="checkbox"]')
    expect(boxes.length).toBe(3)
    await boxes[0].trigger('change')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).selected.map(r => r.name)).toEqual(['maven-central'])
  })

  it('shows the selection bar only when rows are selected and clears it on filter change', async () => {
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as Vm
    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)
    vm.selected = [ITEMS[0]]
    await flushPromises()
    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Anonymous access')
    expect(wrapper.text()).not.toContain('Set access policy')
    vm.modeFilter = 'proxy'
    await flushPromises()
    expect(vm.selected.length).toBe(0)
  })

  it('passes filters and sort to the API and resets the page', async () => {
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as Vm
    vm.typeFilter = 'npm'
    await flushPromises()
    vm.modeFilter = 'hosted'
    await flushPromises()
    const last = listReposMock.mock.calls.at(-1)![0]
    expect(last).toMatchObject({ type: 'npm', mode: 'hosted', page: 0, sort: 'name', order: 'asc' })
  })

  it('restores filters from the URL query', async () => {
    await mountView('/admin/repositories?q=mav&type=maven&mode=proxy&sort=updated_at&order=desc')
    const first = listReposMock.mock.calls[0][0]
    expect(first).toMatchObject({ q: 'mav', type: 'maven', mode: 'proxy', sort: 'updated_at', order: 'desc' })
  })

  it('renders mode, storage, anonymous chips and updated-by', async () => {
    const { wrapper } = await mountView()
    const text = wrapper.text()
    expect(text).toContain('Proxy')
    expect(text).toContain('s3-main')
    expect(text).toContain('read')
    expect(text).toContain('ayd')
  })

  it('browseLinkEncodesSlashNames', async () => {
    const { wrapper } = await mountView()
    const hrefs = wrapper.findAll('a').map(a => a.attributes('href'))
    expect(hrefs).toContain('/repositories/team%2Ffiles')
  })

  it('shows an inline error with retry when the list fails', async () => {
    listReposMock.mockRejectedValueOnce(new Error('boom'))
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-testid="list-error"]').exists()).toBe(true)
    await wrapper.find('[data-testid="list-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="list-error"]').exists()).toBe(false)
    expect(wrapper.findAll('tbody input[type="checkbox"]').length).toBe(3)
  })

  it('shows empty state with a clear-filters action when filters hide everything', async () => {
    const { wrapper } = await mountView()
    listReposMock.mockResolvedValueOnce({ items: [], page: 0, size: 20, total: 0, hasMore: false })
    ;(wrapper.vm as unknown as Vm).typeFilter = 'conan'
    await flushPromises()
    expect(wrapper.text()).toContain('No repositories match')
    expect(wrapper.find('[data-testid="clear-filters"]').exists()).toBe(true)
  })

  it('reports bulk delete outcomes and reloads', async () => {
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as { onBulkDeleted: (r: { deleted: string[]; deleting: string[]; failed: Array<{ name: string; reason: string }> }) => void; selected: RepoListItem[] }
    vm.selected = [ITEMS[0], ITEMS[1]]
    await flushPromises()
    const calls = listReposMock.mock.calls.length
    vm.onBulkDeleted({ deleted: ['maven-central'], deleting: ['npm-local'], failed: [] })
    await flushPromises()
    expect(vm.selected.length).toBe(0)
    expect(listReposMock.mock.calls.length).toBe(calls + 1)
  })
})
