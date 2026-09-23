import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import UserListView from '../UserListView.vue'
import { useNotificationStore } from '@/stores/notifications'

const putUserMock = vi.fn()

vi.mock('@/api/users', () => ({
  listUsers: () => Promise.resolve({ items: [], total: 0 }),
  deleteUser: vi.fn(),
  enableUser: vi.fn(),
  disableUser: vi.fn(),
  putUser: (...args: unknown[]) => putUserMock(...args),
}))

vi.mock('@/api/roles', () => ({
  listRoles: () => Promise.resolve({ items: [{ name: 'readers' }], total: 1 }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasAction: () => true }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

interface CreateVm {
  createVisible: boolean
  newUsername: string
  newPassword: string
  handleCreate: () => Promise<void>
}

let wrapper: VueWrapper | null = null

function mountView() {
  wrapper = mount(UserListView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: { 'router-link': true, AppLayout: { template: '<div><slot /></div>' } },
    },
  })
  return wrapper
}

describe('UserListView — Create User dialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    putUserMock.mockReset()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('shows the password policy while the admin types the password', async () => {
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as CreateVm
    vm.createVisible = true
    await flushPromises()
    expect(document.body.textContent).toContain('At least 12 characters')
  })

  it('shows the server rejection message and keeps the dialog open', async () => {
    putUserMock.mockRejectedValue({
      response: { status: 400, data: { error: 'BAD_REQUEST', message: 'Password must contain at least one digit' } },
    })
    const w = mountView()
    await flushPromises()
    const vm = w.vm as unknown as CreateVm
    vm.createVisible = true
    vm.newUsername = 'alice'
    vm.newPassword = 'Abcdefghijk!x'
    await flushPromises()
    await vm.handleCreate()
    await flushPromises()
    const toasts = useNotificationStore().toasts
    expect(
      document.body.textContent,
      'the dialog must render the server message inline',
    ).toContain('Password must contain at least one digit')
    expect(
      toasts.some(t => t.severity === 'error' && t.detail === 'Password must contain at least one digit'),
      'the error toast must carry the server message',
    ).toBe(true)
    expect(vm.createVisible, 'the dialog must stay open so the admin can fix the input').toBe(true)
  })
})
