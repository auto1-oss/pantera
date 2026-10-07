import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RepoCreateView from '../RepoCreateView.vue'

const putRepoMock = vi.fn()
const pushMock = vi.fn()
vi.mock('@/api/repos', () => ({
  putRepo: (...a: unknown[]) => putRepoMock(...a),
  repoExists: vi.fn().mockResolvedValue(false),
  listRepos: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))
vi.mock('@/api/settings', () => ({ listStorages: vi.fn().mockResolvedValue([]), putStorage: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock, back: vi.fn() }) }))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))
vi.mock('@/components/admin/RepoConfigForm.vue', () => ({
  default: {
    name: 'RepoConfigFormStub',
    props: ['config', 'initialConfig', 'readOnlyType'],
    emits: ['update:config', 'valid-change'],
    mounted() {
      this.$emit('update:config', { repo: { type: 'npm' } })
      this.$emit('valid-change', true)
    },
    template: '<div data-testid="config-form" />',
  },
}))
vi.mock('@/components/admin/RepoNameField.vue', () => ({
  default: {
    name: 'RepoNameFieldStub',
    props: ['modelValue'],
    emits: ['update:modelValue', 'valid-change'],
    mounted() {
      this.$emit('update:modelValue', 'npm-new')
      this.$emit('valid-change', true)
    },
    template: '<input data-testid="name" />',
  },
}))

function mountView() {
  return mount(RepoCreateView, { global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] } })
}

describe('RepoCreateView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    putRepoMock.mockReset()
    pushMock.mockReset()
  })

  it('hides the form until a format card is picked, then creates and highlights', async () => {
    putRepoMock.mockResolvedValue(undefined)
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-testid="config-form"]').exists()).toBe(false)
    expect(w.find('[data-testid="format-card-npm"]').exists()).toBe(true)
    await w.find('[data-testid="format-card-npm"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="config-form"]').exists()).toBe(true)
    await w.find('[data-testid="create-btn"]').trigger('click')
    await flushPromises()
    expect(putRepoMock).toHaveBeenCalledWith('npm-new', { repo: { type: 'npm' } })
    expect(pushMock).toHaveBeenCalledWith({ path: '/admin/repositories', query: { highlight: 'npm-new' } })
  })

  it('shows proxy cards under the Proxy tab only', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-testid="format-card-npm-proxy"]').exists()).toBe(false)
    await w.find('[data-testid="format-tab-proxy"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="format-card-npm-proxy"]').exists()).toBe(true)
    expect(w.find('[data-testid="format-card-npm"]').exists()).toBe(false)
  })

  it('shows a server error inline', async () => {
    putRepoMock.mockRejectedValue({ response: { data: { message: 'storage path outside approved roots' } } })
    const w = mountView()
    await flushPromises()
    await w.find('[data-testid="format-card-npm"]').trigger('click')
    await flushPromises()
    await w.find('[data-testid="create-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="create-error"]').text()).toContain('approved roots')
  })
})
