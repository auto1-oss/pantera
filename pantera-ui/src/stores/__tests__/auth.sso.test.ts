import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'

vi.mock('@/api/auth', () => ({
  getProviderRedirect: vi.fn(),
}))

const HOME = 'http://localhost:3000/'
const AUTHORIZE = 'https://id.example.com/oauth2/v1/authorize?client_id=c&state=s1'

describe('auth store: redirect to a sign-in provider', () => {
  beforeEach(() => {
    window.location.href = HOME
    sessionStorage.clear()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(authApi.getProviderRedirect).mockReset()
    vi.mocked(authApi.getProviderRedirect).mockResolvedValue({ url: AUTHORIZE, state: 's1' })
  })

  it('goes to the provider when no issuer is named', async () => {
    const started = await useAuthStore().ssoRedirect('okta')

    expect(started).toBe(true)
    expect(window.location.href).toBe(AUTHORIZE)
    expect(sessionStorage.getItem('sso_state')).toBe('s1')
    expect(sessionStorage.getItem('sso_provider')).toBe('okta')
  })

  it('goes to the provider when its authorize URL is on the named issuer', async () => {
    const started = await useAuthStore().ssoRedirect('okta', 'https://id.example.com')

    expect(started).toBe(true)
    expect(window.location.href).toBe(AUTHORIZE)
    expect(sessionStorage.getItem('sso_state')).toBe('s1')
  })

  it('stays on the page when the named issuer is another host', async () => {
    const started = await useAuthStore().ssoRedirect('okta', 'https://id.example.org')

    expect(started).toBe(false)
    expect(window.location.href).toBe(HOME)
    expect(sessionStorage.getItem('sso_state')).toBeNull()
    expect(sessionStorage.getItem('sso_provider')).toBeNull()
  })

  it('stays on the page when neither the provider nor the issuer has an origin', async () => {
    vi.mocked(authApi.getProviderRedirect).mockResolvedValue({ url: 'data:text/plain,a', state: 's1' })

    const started = await useAuthStore().ssoRedirect('okta', 'data:text/plain,b')

    expect(started).toBe(false)
    expect(window.location.href).toBe(HOME)
    expect(sessionStorage.getItem('sso_state')).toBeNull()
  })

  it('stays on the page when the named issuer is not a URL', async () => {
    const started = await useAuthStore().ssoRedirect('okta', 'javascript:alert(1)//id.example.com')

    expect(started).toBe(false)
    expect(window.location.href).toBe(HOME)
    expect(sessionStorage.getItem('sso_state')).toBeNull()
  })
})
