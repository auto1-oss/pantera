import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import RepoEditView from '../RepoEditView.vue'

// Cooldown GET/PUT are mocked at module level so we can both seed the
// loaded config and observe the saved payload. The repos GET stub feeds
// RepoConfigForm just enough envelope to render without errors.
const getCooldownMock = vi.fn()
const putCooldownMock = vi.fn().mockResolvedValue(undefined)
const getRepoMock = vi.fn()

vi.mock('@/api/settings', () => ({
  getCooldown: (...args: unknown[]) => getCooldownMock(...args),
  putCooldown: (...args: unknown[]) => putCooldownMock(...args),
}))

vi.mock('@/api/repos', () => ({
  getRepo: (...args: unknown[]) => getRepoMock(...args),
  putRepo: vi.fn().mockResolvedValue(undefined),
}))

// Stub AppLayout to avoid pulling AppHeader's transitive asset imports
// that vite cannot resolve under happy-dom.
vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

// Stub RepoConfigForm — we don't exercise its inputs in this test file;
// the cooldown card is rendered as a sibling regardless of form state.
vi.mock('@/components/admin/RepoConfigForm.vue', () => ({
  default: { name: 'RepoConfigFormStub', template: '<div />' },
}))

// Stub the router used by the Save handler.
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}))

function seedAuth(permissions: Record<string, string[]>) {
  const auth = useAuthStore()
  auth.user = {
    name: 'tester',
    context: 'ci',
    permissions,
  } as unknown as typeof auth.user
}

function mountView(repoName = 'my-internal-mvn') {
  return mount(RepoEditView, {
    props: { name: repoName },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: {
        'router-link': true,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
}

describe('RepoEditView — Cooldown card', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getCooldownMock.mockReset()
    putCooldownMock.mockReset()
    putCooldownMock.mockResolvedValue(undefined)
    getRepoMock.mockReset()
    getRepoMock.mockResolvedValue({
      repo: { type: 'maven-proxy' },
    })
  })

  it('toggle is OFF when no override exists; saving omits repo_names entry', async () => {
    seedAuth({ api_cooldown_permissions: ['read', 'write'] })
    getCooldownMock.mockResolvedValue({
      enabled: true,
      minimum_allowed_age: '7d',
      repo_types: {},
      repo_names: {},
    })

    const wrapper = mountView('my-internal-mvn')
    await flushPromises()

    // Toggle starts off; the four override fields are hidden.
    expect(wrapper.find('[data-testid="repo-cooldown-fields"]').exists()).toBe(false)

    // Click Save without flipping the toggle on.
    const saveBtn = wrapper.find('[data-testid="repo-cooldown-save"]')
    expect(saveBtn.exists()).toBe(true)
    await saveBtn.trigger('click')
    await flushPromises()

    expect(putCooldownMock).toHaveBeenCalledTimes(1)
    const sentPayload = putCooldownMock.mock.calls[0][0] as {
      repo_names?: Record<string, unknown>
    }
    // repo_names exists (we always send the WHOLE config back) but the
    // specific name MUST NOT be present when override is off.
    expect(sentPayload.repo_names).toBeDefined()
    expect(sentPayload.repo_names!['my-internal-mvn']).toBeUndefined()
  })

  it('flipping the toggle ON reveals the four override fields with global placeholders', async () => {
    seedAuth({ api_cooldown_permissions: ['read', 'write'] })
    getCooldownMock.mockResolvedValue({
      enabled: true,
      minimum_allowed_age: '7d',
      snapshots: { enabled: true, minimum_allowed_age: '14d' },
      repo_types: {},
      repo_names: {},
    })

    const wrapper = mountView('my-internal-mvn')
    await flushPromises()

    // Programmatically flip the toggle (PrimeVue's InputSwitch doesn't
    // expose a plain checkbox we can click reliably under happy-dom).
    const vm = wrapper.vm as unknown as { overrideEnabled: boolean }
    vm.overrideEnabled = true
    await flushPromises()

    expect(wrapper.find('[data-testid="repo-cooldown-fields"]').exists()).toBe(true)

    // Both age inputs render with the global-inherited placeholder.
    const cooldownAgeEl = wrapper
      .find('[data-testid="repo-cooldown-age"]')
      .element as HTMLInputElement
    expect(cooldownAgeEl.placeholder).toBe('7d')
    const snapshotAgeEl = wrapper
      .find('[data-testid="repo-snapshot-age"]')
      .element as HTMLInputElement
    expect(snapshotAgeEl.placeholder).toBe('14d')
  })

  it('saves the override shape when toggle ON with all fields populated', async () => {
    seedAuth({ api_cooldown_permissions: ['read', 'write'] })
    getCooldownMock.mockResolvedValue({
      enabled: true,
      minimum_allowed_age: '7d',
      repo_types: {},
      repo_names: {},
    })

    const wrapper = mountView('my-internal-mvn')
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      overrideEnabled: boolean
      repoCooldownEnabled: boolean
      repoCooldownAge: string
      repoSnapshotEnabled: boolean | null
      repoSnapshotAge: string
    }
    vm.overrideEnabled = true
    vm.repoCooldownEnabled = true
    vm.repoCooldownAge = '21d'
    vm.repoSnapshotEnabled = true
    vm.repoSnapshotAge = '30d'
    await flushPromises()

    await wrapper.find('[data-testid="repo-cooldown-save"]').trigger('click')
    await flushPromises()

    expect(putCooldownMock).toHaveBeenCalledTimes(1)
    const sent = putCooldownMock.mock.calls[0][0] as {
      repo_names: Record<string, {
        enabled?: boolean
        minimum_allowed_age?: string
        snapshots?: { enabled?: boolean; minimum_allowed_age?: string }
      }>
    }
    expect(sent.repo_names['my-internal-mvn']).toEqual({
      enabled: true,
      minimum_allowed_age: '21d',
      snapshots: { enabled: true, minimum_allowed_age: '30d' },
    })
  })

  it('preloads form state from an existing repo_names override', async () => {
    seedAuth({ api_cooldown_permissions: ['read', 'write'] })
    getCooldownMock.mockResolvedValue({
      enabled: true,
      minimum_allowed_age: '7d',
      repo_types: {},
      repo_names: {
        'my-internal-mvn': {
          enabled: true,
          minimum_allowed_age: '21d',
          snapshots: { enabled: false, minimum_allowed_age: '60d' },
        },
      },
    })

    const wrapper = mountView('my-internal-mvn')
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      overrideEnabled: boolean
      repoCooldownEnabled: boolean
      repoCooldownAge: string
      repoSnapshotEnabled: boolean | null
      repoSnapshotAge: string
    }
    expect(vm.overrideEnabled).toBe(true)
    expect(vm.repoCooldownEnabled).toBe(true)
    expect(vm.repoCooldownAge).toBe('21d')
    expect(vm.repoSnapshotEnabled).toBe(false)
    expect(vm.repoSnapshotAge).toBe('60d')
  })

  it('without api_cooldown_permissions:write the toggle is disabled and Save is hidden', async () => {
    seedAuth({ api_cooldown_permissions: ['read'] })
    getCooldownMock.mockResolvedValue({
      enabled: true,
      minimum_allowed_age: '7d',
      repo_types: {},
      repo_names: {},
    })

    const wrapper = mountView('my-internal-mvn')
    await flushPromises()

    // Toggle is present but disabled — InputSwitch surfaces aria-disabled.
    const toggle = wrapper.find('[data-testid="repo-cooldown-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('data-p-disabled')).toBeTruthy()

    // Save button is hidden for read-only users; the read-only note shows.
    expect(wrapper.find('[data-testid="repo-cooldown-save"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="repo-cooldown-readonly-note"]').exists()).toBe(true)
  })
})
