import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import type { UserInfo } from '@/types'
import { usePermission } from '../usePermission'

function signIn(permissions: UserInfo['permissions']) {
  useAuthStore().user = { name: 'u', context: 'local', permissions }
}

describe('usePermission', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('is false while nobody is signed in', () => {
    expect(usePermission('repository').hasPermission.value).toBe(false)
  })

  // The server sends the allowed actions of a resource as a list.
  it('is true when the user holds an action on the resource', () => {
    signIn({ api_repository_permissions: ['read'] })
    expect(usePermission('repository').hasPermission.value).toBe(true)
  })

  it('is false when the list of actions is empty', () => {
    signIn({ api_repository_permissions: [] })
    expect(usePermission('repository').hasPermission.value).toBe(false)
  })

  it('is false for a resource the user has no entry for', () => {
    signIn({ api_repository_permissions: ['read'] })
    expect(usePermission('role').hasPermission.value).toBe(false)
  })
})
