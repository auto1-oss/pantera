import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserInfo, AuthProvider } from '@/types'
import * as authApi from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('jwt'))
  const user = ref<UserInfo | null>(null)
  const providers = ref<AuthProvider[]>([])
  const loading = ref(false)

  const isAuthenticated = computed(() => !!token.value)
  const isAdmin = computed(() => {
    if (!user.value) return false
    return hasAction('api_user_permissions', 'write')
      || hasAction('api_role_permissions', 'write')
  })
  const username = computed(() => user.value?.name ?? '')

  async function login(uname: string, password: string) {
    loading.value = true
    try {
      const resp = await authApi.login(uname, password)
      token.value = resp.token
      localStorage.setItem('jwt', resp.token)
      await fetchUser()
    } finally {
      loading.value = false
    }
  }

  async function fetchUser() {
    if (!token.value) return
    try {
      user.value = await authApi.getMe()
    } catch {
      logout()
    }
  }

  async function fetchProviders() {
    try {
      const resp = await authApi.getProviders()
      providers.value = resp.providers
    } catch {
      providers.value = []
    }
  }

  async function ssoRedirect(providerName: string) {
    const callbackUrl = window.location.origin + '/auth/callback'
    const resp = await authApi.getProviderRedirect(providerName, callbackUrl)
    sessionStorage.setItem('sso_state', resp.state)
    sessionStorage.setItem('sso_provider', providerName)
    sessionStorage.setItem('sso_callback_url', callbackUrl)
    window.location.href = resp.url
  }

  async function handleOAuthCallback(code: string, state: string) {
    const savedState = sessionStorage.getItem('sso_state')
    const provider = sessionStorage.getItem('sso_provider')
    const callbackUrl = sessionStorage.getItem('sso_callback_url')
    sessionStorage.removeItem('sso_state')
    sessionStorage.removeItem('sso_provider')
    sessionStorage.removeItem('sso_callback_url')
    if (!savedState || savedState !== state) {
      throw new Error('Invalid OAuth state — possible CSRF attack')
    }
    if (!provider || !callbackUrl) {
      throw new Error('Missing SSO session data')
    }
    loading.value = true
    try {
      const resp = await authApi.exchangeOAuthCode(code, provider, callbackUrl)
      token.value = resp.token
      localStorage.setItem('jwt', resp.token)
      await fetchUser()
    } finally {
      loading.value = false
    }
  }

  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem('jwt')
  }

  function hasAction(key: string, action: string): boolean {
    const perms = user.value?.permissions ?? {}
    const val = perms[key]
    if (!Array.isArray(val)) return false
    if (val.includes('*')) return true
    if (action === 'write') {
      // Backend returns granular actions (create, update, delete) rather than
      // a generic 'write'. Treat 'write' as "has any write-level access".
      return val.some(a => a !== 'read')
    }
    return val.includes(action)
  }

  return {
    token, user, providers, loading,
    isAuthenticated, isAdmin, username,
    login, logout, fetchUser, fetchProviders,
    ssoRedirect, handleOAuthCallback,
    hasAction,
  }
})
