import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import RepoEditView from '../RepoEditView.vue'

// This spec mounts the REAL RepoConfigForm: on mount it re-emits a
// normalised config (key order, storage shape, defaulted flags), which must
// not count as an edit.
const getRepoMock = vi.fn()
const getCooldownMock = vi.fn()
vi.mock('@/api/repos', () => ({
  getRepo: (...a: unknown[]) => getRepoMock(...a),
  putRepo: vi.fn().mockResolvedValue(undefined),
  moveRepo: vi.fn(),
  deleteRepo: vi.fn(),
  listRepos: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))
vi.mock('@/api/settings', () => ({
  getCooldown: (...a: unknown[]) => getCooldownMock(...a),
  putCooldown: vi.fn().mockResolvedValue(undefined),
  listStorages: vi.fn().mockResolvedValue([]),
  putStorage: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))

type Vm = { dirty: boolean; reset: () => void; overrideEnabled: boolean }

describe('RepoEditView dirty tracking with the real config form', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = {
      name: 't', context: 'c',
      permissions: { api_cooldown_permissions: ['read', 'write'], api_repository_permissions: ['update'] },
    } as unknown as typeof auth.user
    getRepoMock.mockReset()
    getCooldownMock.mockReset()
    // Postgres JSONB key order and no anonymous_* / immutable keys, as a
    // migrated repository is stored.
    getRepoMock.mockResolvedValue({ repo: { storage: { path: '/var/pantera/data', type: 'fs' }, type: 'file' } })
    getCooldownMock.mockResolvedValue({ enabled: true, minimum_allowed_age: '7d', repo_names: {} })
  })

  it('is clean right after load and clean again after reset', async () => {
    const w = mount(RepoEditView, {
      props: { name: 'files' },
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
    })
    await flushPromises()
    const vm = w.vm as unknown as Vm
    expect(vm.dirty).toBe(false)
    expect(w.find('[data-testid="dirty-note"]').exists()).toBe(false)
    vm.overrideEnabled = true
    await flushPromises()
    expect(vm.dirty).toBe(true)
    vm.reset()
    await flushPromises()
    expect(vm.dirty).toBe(false)
  })

  it('stays clean when the cooldown GET fails', async () => {
    getCooldownMock.mockRejectedValue(new Error('503'))
    const w = mount(RepoEditView, {
      props: { name: 'files' },
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
    })
    await flushPromises()
    expect((w.vm as unknown as Vm).dirty).toBe(false)
  })
})
