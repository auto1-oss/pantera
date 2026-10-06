import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import RepoEditView from '../RepoEditView.vue'

const getRepoMock = vi.fn()
const putRepoMock = vi.fn()
const getCooldownMock = vi.fn()
const putCooldownMock = vi.fn()
type Guard = (to: unknown, from: unknown, next: (v?: boolean) => void) => void
let leaveGuard: Guard | null = null

vi.mock('@/api/repos', () => ({
  getRepo: (...a: unknown[]) => getRepoMock(...a),
  putRepo: (...a: unknown[]) => putRepoMock(...a),
  moveRepo: vi.fn(),
  deleteRepo: vi.fn(),
}))
vi.mock('@/api/settings', () => ({
  getCooldown: (...a: unknown[]) => getCooldownMock(...a),
  putCooldown: (...a: unknown[]) => putCooldownMock(...a),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: (g: Guard) => { leaveGuard = g },
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))
vi.mock('@/components/admin/RepoConfigForm.vue', () => ({
  default: {
    name: 'RepoConfigFormStub',
    props: ['config', 'initialConfig', 'readOnlyType'],
    emits: ['update:config', 'valid-change'],
    mounted() { this.$emit('valid-change', true) },
    template: '<button data-testid="mutate" @click="$emit(\'update:config\', { repo: { type: \'maven-proxy\', url: \'https://x\' } })" />',
  },
}))

type Vm = { dirty: boolean; save: () => Promise<void>; reset: () => void; overrideEnabled: boolean }

function seedAuth() {
  const auth = useAuthStore()
  auth.user = {
    name: 't', context: 'c',
    permissions: { api_cooldown_permissions: ['read', 'write'], api_repository_permissions: ['update', 'move', 'delete'] },
  } as unknown as typeof auth.user
}

function mountView() {
  return mount(RepoEditView, {
    props: { name: 'mvn' },
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
  })
}

describe('RepoEditView save, reset and leave guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedAuth()
    leaveGuard = null
    getRepoMock.mockReset(); putRepoMock.mockReset(); getCooldownMock.mockReset(); putCooldownMock.mockReset()
    getRepoMock.mockResolvedValue({ repo: { type: 'maven-proxy' } })
    getCooldownMock.mockResolvedValue({ enabled: true, minimum_allowed_age: '7d', repo_names: {} })
    putRepoMock.mockResolvedValue(undefined)
    putCooldownMock.mockResolvedValue(undefined)
  })

  it('stays on the page after save, resets dirty, and saves cooldown only when its card changed', async () => {
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as Vm
    expect(vm.dirty).toBe(false)
    await w.find('[data-testid="mutate"]').trigger('click')
    await flushPromises()
    expect(vm.dirty).toBe(true)
    await vm.save()
    await flushPromises()
    expect(putRepoMock).toHaveBeenCalledTimes(1)
    expect(putCooldownMock).not.toHaveBeenCalled()
    expect(vm.dirty).toBe(false)
    expect(w.find('[data-testid="save-btn"]').exists()).toBe(true)
  })

  it('saveStillWorksWhenCooldownLoadFails', async () => {
    getCooldownMock.mockRejectedValue(new Error('503'))
    const w = mountView()
    await flushPromises()
    await w.find('[data-testid="mutate"]').trigger('click')
    await flushPromises()
    await (w.vm as unknown as Vm).save()
    await flushPromises()
    expect(putRepoMock).toHaveBeenCalledTimes(1)
  })

  it('reports when the repo saved but the cooldown override failed', async () => {
    putCooldownMock.mockRejectedValue(new Error('cooldown down'))
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as Vm
    vm.overrideEnabled = true
    await flushPromises()
    expect(vm.dirty).toBe(true)
    await vm.save()
    await flushPromises()
    expect(putRepoMock).toHaveBeenCalledTimes(1)
    expect(putCooldownMock).toHaveBeenCalledTimes(1)
    expect(w.find('[data-testid="save-error"]').text()).toMatch(/Repository saved; cooldown override failed/)
  })

  it('reset restores the loaded state', async () => {
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as Vm
    vm.overrideEnabled = true
    await flushPromises()
    expect(vm.dirty).toBe(true)
    vm.reset()
    await flushPromises()
    expect(vm.dirty).toBe(false)
    expect(vm.overrideEnabled).toBe(false)
  })

  it('asks before leaving with unsaved changes and lets a clean page go', async () => {
    const w = mountView()
    await flushPromises()
    const clean = vi.fn()
    leaveGuard!({}, {}, clean)
    expect(clean).toHaveBeenCalledWith()
    await w.find('[data-testid="mutate"]').trigger('click')
    await flushPromises()
    const next = vi.fn()
    leaveGuard!({}, {}, next)
    await flushPromises()
    expect(next).not.toHaveBeenCalled()
    expect(document.querySelector('[data-testid="leave-dialog"]')).not.toBeNull()
    w.unmount()
  })
})
