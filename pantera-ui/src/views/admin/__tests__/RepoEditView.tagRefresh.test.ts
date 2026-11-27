import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import RepoEditView from '../RepoEditView.vue'
import RepoConfigForm from '@/components/admin/RepoConfigForm.vue'

// This spec mounts the REAL RepoConfigForm: what is sent on save is what the
// form builds from the loaded config. PUT replaces the whole config, so a
// tag_refresh_seconds the form did not send back would be deleted.
const getRepoMock = vi.fn()
const putRepoMock = vi.fn()

vi.mock('@/api/cooldown', () => ({
  getCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
  putCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
}))
vi.mock('@/api/repos', () => ({
  getRepo: (...a: unknown[]) => getRepoMock(...a),
  putRepo: (...a: unknown[]) => putRepoMock(...a),
  moveRepo: vi.fn(),
  deleteRepo: vi.fn(),
  listRepos: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))
vi.mock('@/api/settings', () => ({
  getCooldown: vi.fn().mockResolvedValue({ enabled: true, minimum_allowed_age: '7d', repo_names: {} }),
  putCooldown: vi.fn().mockResolvedValue(undefined),
  listStorages: vi.fn().mockResolvedValue([]),
  putStorage: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))

/** A docker proxy as the server stores it, with its own tag refresh age. */
function storedProxy() {
  return {
    repo: {
      type: 'docker-proxy',
      storage: { type: 'fs', path: '/var/pantera/data' },
      remotes: [{ url: 'https://registry-1.docker.io' }],
      anonymous_read: false,
      anonymous_write: false,
      tag_refresh_seconds: 600,
    },
  }
}

type Vm = { dirty: boolean }
type Sent = { repo: Record<string, unknown> }
type FormVm = { tagRefreshSeconds: number | null }

let wrapper: VueWrapper | null = null

async function mountView() {
  wrapper = mount(RepoEditView, {
    props: { name: 'docker-hub' },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
  })
  await flushPromises()
  return wrapper
}

async function setTagRefresh(w: VueWrapper, seconds: number | null) {
  ;(w.findComponent(RepoConfigForm).vm as unknown as FormVm).tagRefreshSeconds = seconds
  await flushPromises()
}

async function save(w: VueWrapper) {
  await w.find('[data-testid="save-btn"]').trigger('click')
  await flushPromises()
}

function sent(): Sent {
  return putRepoMock.mock.calls[0][1] as Sent
}

beforeEach(() => {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    name: 't', context: 'c',
    permissions: { api_cooldown_permissions: ['read', 'write'], api_repository_permissions: ['read', 'update'] },
  } as unknown as typeof auth.user
  getRepoMock.mockReset().mockResolvedValue(storedProxy())
  putRepoMock.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('RepoEditView: tag refresh of a docker proxy', () => {
  it('shows the saved age and loading it is not an edit', async () => {
    const w = await mountView()

    expect((w.vm as unknown as Vm).dirty, 'nothing changed').toBe(false)
    expect(
      (w.findComponent(RepoConfigForm).vm as unknown as FormVm).tagRefreshSeconds,
      'the saved age is in the field',
    ).toBe(600)
  })

  it('sends a changed age', async () => {
    const w = await mountView()
    await setTagRefresh(w, 60)

    expect((w.vm as unknown as Vm).dirty, 'the change is an edit').toBe(true)
    await save(w)

    expect(putRepoMock, 'one save').toHaveBeenCalledTimes(1)
    expect(sent().repo.tag_refresh_seconds, 'the new age').toBe(60)
  })

  it('removes the key when the field is cleared', async () => {
    const w = await mountView()
    await setTagRefresh(w, null)

    expect((w.vm as unknown as Vm).dirty, 'clearing is an edit').toBe(true)
    await save(w)

    expect('tag_refresh_seconds' in sent().repo, 'the global setting applies again').toBe(false)
    expect(sent().repo.remotes, 'the rest of the config is kept')
      .toEqual([{ url: 'https://registry-1.docker.io' }])
  })
})
