import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppSidebar from '../AppSidebar.vue'
import { useAuthStore } from '@/stores/auth'

let wrapper: VueWrapper | null = null

async function mountSidebar(permissions: Record<string, string[]>, at = '/') {
  const auth = useAuthStore()
  auth.user = { name: 'u', context: 'c', permissions } as unknown as typeof auth.user
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push(at)
  await router.isReady()
  wrapper = mount(AppSidebar, {
    props: { collapsed: false },
    global: {
      plugins: [router],
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="to"><slot /></a>' },
      },
    },
  })
  return wrapper
}

/** Labels of the sidebar entries, in their order, with where each one leads. */
function entries(w: VueWrapper): Array<{ label: string; to: string }> {
  return w.findAll('a[data-to]').map(a => ({ label: a.text(), to: a.attributes('data-to')! }))
}

function signInFailures(w: VueWrapper) {
  return w.findAll('a[data-to]').find(a => a.attributes('data-to') === '/admin/sign-in-failures')
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('AppSidebar: the Sign-in failures entry', () => {
  it('is shown to an account that may read users', async () => {
    const w = await mountSidebar({ api_user_permissions: ['read'] })

    expect(signInFailures(w)?.text(), 'the entry').toBe('Sign-in failures')
    expect(w.text(), 'under Administration').toContain('Administration')
    expect(
      entries(w).some(e => e.to === '/admin/users'),
      'reading users does not open User Management',
    ).toBe(false)
  })

  it('is not shown to an account that may not read users', async () => {
    const w = await mountSidebar({
      api_repository_permissions: ['read', 'create', 'update', 'delete'],
      api_role_permissions: ['read', 'create'],
    })

    expect(signInFailures(w), 'no entry').toBeUndefined()
    expect(entries(w).some(e => e.to === '/admin/roles'), 'the other entries are there').toBe(true)
  })

  it('is not shown to an account that may change users but not read them', async () => {
    const w = await mountSidebar({ api_user_permissions: ['enable'] })

    expect(signInFailures(w)).toBeUndefined()
  })

  it('stands after User Management among the administration entries', async () => {
    const w = await mountSidebar({
      api_repository_permissions: ['*'], api_user_permissions: ['*'], api_role_permissions: ['*'],
    })
    const labels = entries(w).map(e => e.label)

    expect(labels.indexOf('Sign-in failures'), 'right after User Management')
      .toBe(labels.indexOf('User Management') + 1)
    expect(labels.indexOf('Roles & Permissions'), 'and before the roles')
      .toBe(labels.indexOf('Sign-in failures') + 1)
  })

  it('is marked as the open page', async () => {
    const w = await mountSidebar({ api_user_permissions: ['read'] }, '/admin/sign-in-failures?tab=passwords')

    expect(signInFailures(w)!.classes(), 'the entry of the open page').toContain('font-medium')
  })
})
