import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import RepoEditView from '../RepoEditView.vue'

const pushMock = vi.fn()
const backMock = vi.fn()
vi.mock('@/api/cooldown', () => ({
  getCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
  putCooldownExemptions: vi.fn(),
}))
vi.mock('@/api/repos', () => ({
  getRepo: vi.fn().mockResolvedValue({ repo: { storage: { path: '/var/pantera/data', type: 'fs' }, type: 'file' } }),
  putRepo: vi.fn(),
  moveRepo: vi.fn(),
  deleteRepo: vi.fn(),
  listRepos: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))
vi.mock('@/api/settings', () => ({
  getCooldown: vi.fn().mockResolvedValue({ enabled: true, minimum_allowed_age: '7d', repo_names: {} }),
  putCooldown: vi.fn(),
  listStorages: vi.fn().mockResolvedValue([]),
  putStorage: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock, back: backMock, replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))

async function clickBack(back: string | null) {
  window.history.replaceState(back === null ? null : { back }, '')
  const w = mount(RepoEditView, {
    props: { name: 'files' },
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
  })
  await flushPromises()
  await w.find('[data-testid="back-btn"]').trigger('click')
}

describe('RepoEditView back button', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = {
      name: 't', context: 'c', permissions: { api_repository_permissions: ['update'] },
    } as unknown as typeof auth.user
    pushMock.mockReset()
    backMock.mockReset()
  })

  afterEach(() => window.history.replaceState(null, ''))

  it('returns to the management list it came from, keeping its filters', async () => {
    await clickBack('/admin/repositories?q=npm&page=2')
    expect(backMock, 'history back').toHaveBeenCalledOnce()
    expect(pushMock, 'no push').not.toHaveBeenCalled()
  })

  it('opens the management list when the previous page was elsewhere', async () => {
    await clickBack('/repositories/files')
    expect(pushMock, 'push to list').toHaveBeenCalledWith('/admin/repositories')
    expect(backMock, 'no history back').not.toHaveBeenCalled()
  })

  it('opens the management list on a direct visit', async () => {
    await clickBack(null)
    expect(pushMock).toHaveBeenCalledWith('/admin/repositories')
  })
})
