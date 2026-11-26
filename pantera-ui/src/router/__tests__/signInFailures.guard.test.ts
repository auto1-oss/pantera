import { describe, it, expect } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { createAppRouter, routes } from '@/router'
import { useAuthStore } from '@/stores/auth'

describe('router guard: the sign-in failures page', () => {
  function signIn(permissions: Record<string, string[]>) {
    localStorage.clear()
    localStorage.setItem('access_token', 't')
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = { name: 'u', context: 'c', permissions } as unknown as typeof auth.user
  }

  it('has a route of its own', () => {
    const route = routes.find(r => r.path === '/admin/sign-in-failures')

    expect(route?.name).toBe('admin-sign-in-failures')
  })

  it('opens for a user who may only read users', async () => {
    signIn({ api_user_permissions: ['read'] })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin/sign-in-failures')

    expect(router.currentRoute.value.name).toBe('admin-sign-in-failures')
  })

  it('keeps the tab named in the address', async () => {
    signIn({ api_user_permissions: ['read', 'enable'] })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin/sign-in-failures?tab=passwords')

    expect(router.currentRoute.value.name, 'the page').toBe('admin-sign-in-failures')
    expect(router.currentRoute.value.query.tab, 'the tab').toBe('passwords')
  })

  it('sends a user who may not read users to the dashboard', async () => {
    signIn({ api_repository_permissions: ['read', 'create', 'update', 'delete'] })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin/sign-in-failures')

    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('sends a user who may change users but not read them to the dashboard', async () => {
    signIn({ api_user_permissions: ['enable'] })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin/sign-in-failures')

    expect(router.currentRoute.value.name).toBe('dashboard')
  })
})
