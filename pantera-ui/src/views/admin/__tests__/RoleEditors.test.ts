import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RoleDetailView from '../RoleDetailView.vue'
import RoleListView from '../RoleListView.vue'
import { useNotificationStore } from '@/stores/notifications'

const putRoleMock = vi.fn()
const SERVER_MSG =
  'Invalid role permissions: Permission type maven-central is not found'

vi.mock('@/api/roles', () => ({
  listRoles: () => Promise.resolve({ items: [], total: 0 }),
  getRole: () => Promise.resolve({ name: 'devs', permissions: {} }),
  deleteRole: vi.fn(),
  enableRole: vi.fn(),
  disableRole: vi.fn(),
  putRole: (...args: unknown[]) => putRoleMock(...args),
}))

vi.mock('@/api/repos', () => ({
  listRepos: () => Promise.resolve({ items: [], total: 0 }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasAction: () => true, isAdmin: true }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const GLOBAL = {
  plugins: [[PrimeVue, { theme: { preset: Aura } }]] as never,
  stubs: { 'router-link': true, AppLayout: { template: '<div><slot /></div>' } },
}

let wrapper: VueWrapper | null = null

function hasServerToast(): boolean {
  return useNotificationStore().toasts.some(t => t.severity === 'error' && t.detail === SERVER_MSG)
}

describe('role editors surface the server rejection', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    putRoleMock.mockReset()
    putRoleMock.mockRejectedValue({ response: { status: 400, data: { message: SERVER_MSG } } })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('RoleDetailView shows the server message in the error toast', async () => {
    wrapper = mount(RoleDetailView, { props: { name: 'devs' }, attachTo: document.body, global: GLOBAL })
    await flushPromises()
    const vm = wrapper.vm as unknown as { permissionsJson: string; handleSave: () => Promise<void> }
    vm.permissionsJson = '{"maven-central":["read"]}'
    await vm.handleSave()
    await flushPromises()
    expect(hasServerToast()).toBe(true)
  })

  it('RoleListView shows the server message and keeps the dialog open', async () => {
    wrapper = mount(RoleListView, { attachTo: document.body, global: GLOBAL })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      dialogVisible: boolean
      newRoleName: string
      advancedMode: boolean
      newPermissions: string
      handleSave: () => Promise<void>
    }
    vm.dialogVisible = true
    vm.newRoleName = 'devs'
    vm.advancedMode = true
    vm.newPermissions = '{"maven-central":["read"]}'
    await vm.handleSave()
    await flushPromises()
    expect(hasServerToast(), 'the error toast must carry the server message').toBe(true)
    expect(vm.dialogVisible, 'the dialog must stay open so the admin can fix the input').toBe(true)
  })
})
