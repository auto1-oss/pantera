import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import RepoEditView from '../RepoEditView.vue'

// A proxy whose own config stores `cooldown.duration`: the Cooldown card
// edits that key through the REAL RepoConfigForm, never the override.
const putRepoMock = vi.fn().mockResolvedValue(undefined)
const putCooldownMock = vi.fn().mockResolvedValue(undefined)
vi.mock('@/api/cooldown', () => ({
  getCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
  putCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
}))
vi.mock('@/api/repos', () => ({
  getRepo: vi.fn().mockResolvedValue({
    repo: {
      type: 'npm-proxy',
      storage: { type: 'fs', path: '/var/pantera/data' },
      remotes: [{ url: 'https://registry.npmjs.org' }],
      cooldown: { duration: 'P30D' },
    },
  }),
  putRepo: (...a: unknown[]) => putRepoMock(...a),
  moveRepo: vi.fn(),
  deleteRepo: vi.fn(),
  listRepos: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))
vi.mock('@/api/settings', () => ({
  getCooldown: vi.fn().mockResolvedValue({
    enabled: true, minimum_allowed_age: '7d',
    repo_names: { npmjs: { enabled: true, minimum_allowed_age: '30d' } },
  }),
  putCooldown: (...a: unknown[]) => putCooldownMock(...a),
  listStorages: vi.fn().mockResolvedValue([]),
  putStorage: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))

describe('RepoEditView — cooldown window stored in the repository config', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = {
      name: 't', context: 'c',
      permissions: { api_cooldown_permissions: ['read', 'write'], api_repository_permissions: ['update'] },
    } as unknown as typeof auth.user
    putRepoMock.mockClear()
    putCooldownMock.mockClear()
  })

  it('edits cooldown.duration in the short form and leaves the override alone', async () => {
    const w = mount(RepoEditView, {
      props: { name: 'npmjs' },
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
    })
    await flushPromises()
    const age = w.find('[data-testid="repo-cooldown-age"]')
    expect((age.element as HTMLInputElement).value, 'loaded window').toBe('30d')
    expect(w.find('[data-testid="repo-snapshot-age"]').exists(), 'no SNAPSHOT fields').toBe(false)
    expect(w.findAll('[data-testid="repo-cooldown-card"]').length, 'one Cooldown card').toBe(1)
    await age.setValue('14d')
    await flushPromises()
    await (w.vm as unknown as { save: () => Promise<void> }).save()
    await flushPromises()
    const body = putRepoMock.mock.calls[0][1] as { repo: { cooldown?: { duration: string } } }
    expect(body.repo.cooldown, 'config window').toEqual({ duration: 'P14D' })
    expect(putCooldownMock, 'override untouched').not.toHaveBeenCalled()
  })
})
