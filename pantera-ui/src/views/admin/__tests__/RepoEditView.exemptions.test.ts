import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import DatePicker from 'primevue/datepicker'
import RepoEditView from '../RepoEditView.vue'

const getExemptionsMock = vi.fn()
const putExemptionsMock = vi.fn()
const getRepoMock = vi.fn()

vi.mock('@/api/settings', () => ({
  getCooldown: vi.fn().mockResolvedValue({ enabled: true, minimum_allowed_age: '7d', repo_types: {}, repo_names: {} }),
  putCooldown: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/api/cooldown', () => ({
  getCooldownExemptions: (...args: unknown[]) => getExemptionsMock(...args),
  putCooldownExemptions: (...args: unknown[]) => putExemptionsMock(...args),
}))

vi.mock('@/api/repos', () => ({
  getRepo: (...args: unknown[]) => getRepoMock(...args),
  putRepo: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

vi.mock('@/components/admin/RepoConfigForm.vue', () => ({
  default: { name: 'RepoConfigFormStub', template: '<div />' },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))

function seedAuth() {
  const auth = useAuthStore()
  auth.user = {
    name: 'tester',
    context: 'ci',
    permissions: {
      api_cooldown_permissions: ['read', 'write'],
      api_repository_permissions: ['read', 'update'],
    },
  } as unknown as typeof auth.user
}

function mountView(repoName = 'npm-proxy') {
  return mount(RepoEditView, {
    props: { name: repoName },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: { 'router-link': true, AppLayout: { template: '<div><slot /></div>' } },
    },
  })
}

describe('RepoEditView — cooldown exemptions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedAuth()
    getExemptionsMock.mockReset()
    putExemptionsMock.mockReset()
    getRepoMock.mockReset()
    getRepoMock.mockResolvedValue({ repo: { type: 'npm-proxy' } })
    getExemptionsMock.mockResolvedValue({
      repository: 'npm-proxy',
      exemptions: [
        { pattern: '@myorg/*', expires_at: null, reason: 'internal', active: true },
        { pattern: 'left-pad', expires_at: '2020-01-01T12:00:00Z', reason: '', active: false },
      ],
      released: 0,
    })
  })

  it('lists the stored exemptions of a proxy repository, marking expired ones', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(getExemptionsMock).toHaveBeenCalledWith('npm-proxy')
    expect(wrapper.findAll('[data-testid="repo-exemption-row"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="repo-exemption-expired"]')).toHaveLength(1)
    expect((wrapper.vm as unknown as { dirty: boolean }).dirty).toBe(false)
  })

  it('does not show or load exemptions for a hosted repository', async () => {
    getRepoMock.mockResolvedValue({ repo: { type: 'npm' } })
    const wrapper = mountView('npm-local')
    await flushPromises()
    expect(getExemptionsMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="repo-exemptions"]').exists()).toBe(false)
  })

  it('saves the edited list, keeping an unchanged stored expiry as-is', async () => {
    putExemptionsMock.mockResolvedValue({ repository: 'npm-proxy', exemptions: [], released: 2 })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="repo-exemption-add"]').trigger('click')
    const patterns = wrapper.findAll('[data-testid="repo-exemption-pattern"]')
    await patterns[2].setValue('com.example.*')
    wrapper.findAllComponents(DatePicker)[2].vm.$emit('update:modelValue', new Date(2099, 0, 1))
    const vm = wrapper.vm as unknown as { dirty: boolean; save: () => Promise<void> }
    expect(vm.dirty).toBe(true)
    await vm.save()
    await flushPromises()
    expect(putExemptionsMock).toHaveBeenCalledWith('npm-proxy', [
      { pattern: '@myorg/*', expires_at: null, reason: 'internal' },
      { pattern: 'left-pad', expires_at: '2020-01-01T12:00:00Z', reason: '' },
      { pattern: 'com.example.*', expires_at: '2099-01-01T00:00:00Z', reason: '' },
    ])
  })

  it('disables Save while a pattern is invalid or duplicated', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { exemptionsValid: boolean }
    const pattern = () => wrapper.findAll('[data-testid="repo-exemption-pattern"]')[0]
    await pattern().setValue('a*b')
    expect(vm.exemptionsValid).toBe(false)
    expect(wrapper.find('[data-testid="save-btn"]').attributes('disabled')).toBeDefined()
    await pattern().setValue('LEFT-PAD')
    expect(vm.exemptionsValid).toBe(false)
    await pattern().setValue('a*')
    expect(vm.exemptionsValid).toBe(true)
  })
})
