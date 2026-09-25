import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RepoManagementView from '../RepoManagementView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'

// Mock the repo API so listRepos returns a deterministic page and
// bulkUpdateAccessPolicy never actually fires.
const listReposMock = vi.fn()
const bulkUpdateAccessPolicyMock = vi.fn()

vi.mock('@/api/repos', () => ({
  listRepos: (...args: unknown[]) => listReposMock(...args),
  deleteRepo: vi.fn().mockResolvedValue('deleted'),
  moveRepo: vi.fn().mockResolvedValue(undefined),
  bulkUpdateAccessPolicy: (...args: unknown[]) => bulkUpdateAccessPolicyMock(...args),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

vi.mock('@/composables/useConfirmDelete', () => ({
  useConfirmDelete: () => ({
    visible: { value: false },
    targetName: { value: '' },
    confirm: vi.fn().mockResolvedValue(true),
    accept: vi.fn(),
    reject: vi.fn(),
  }),
}))

// Auth store stub — hasAction() returns true for every call. Declared at the
// module top level: a vi.mock is hoisted before everything else, so vitest
// (v5+) rejects one nested in a hook.
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasAction: () => true,
  }),
}))

// Auth store stub — the bulk button is gated by
// auth.hasAction('api_repository_permissions', 'update'). Default these
// tests as full-admin; individual tests can override.
function adminAuth() {
  return {
    hasAction: () => true,
  }
}

function mountView() {
  return mount(RepoManagementView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: {
        'router-link': true,
        'router-view': true,
        AppLayout: { template: '<div><slot /></div>' },
        // Stub the dialog — we only assert opening behaviour from
        // RepoManagementView; the dialog itself has its own spec.
        BulkAccessPolicyDialog: { template: '<div class="bulk-dialog-stub" />' },
      },
      provide: {
        auth: adminAuth(),
      },
    },
  })
}

describe('RepoManagementView — bulk access policy', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listReposMock.mockReset()
    bulkUpdateAccessPolicyMock.mockReset()
    listReposMock.mockResolvedValue({
      items: ['maven-central', 'npm-public', 'internal-snapshots'],
      total: 3,
    })
  })

  it('renders the Set-access-policy button on the admin view', async () => {
    const wrapper = mountView()
    await flushPromises()
    const btn = wrapper.find('[data-testid="bulk-access-policy-btn"]')
    expect(btn.exists()).toBe(true)
  })

  it('disables the Set-access-policy button while selection is empty', async () => {
    const wrapper = mountView()
    await flushPromises()
    const btn = wrapper.find('[data-testid="bulk-access-policy-btn"]')
    // PrimeVue Button renders a <button> with `disabled` attribute when :disabled is truthy.
    expect(btn.attributes('disabled')).toBeDefined()
  })
})
