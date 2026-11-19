import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import LoginView from '../LoginView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import * as authApi from '@/api/auth'

vi.mock('@/api/auth', () => ({
  getProviders: vi.fn(),
  getProviderRedirect: vi.fn(),
}))

const HOME = 'http://localhost:3000/'
const AUTHORIZE = 'https://id.example.com/oauth2/v1/authorize?client_id=c&state=s1'
const ISSUER = encodeURIComponent('https://id.example.com')

/**
 * Mounts the sign-in page at the given location and waits until the
 * providers are loaded and anything the page starts by itself has run.
 */
async function mountLogin(path = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/', name: 'dashboard', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  const wrapper = mount(LoginView, {
    global: {
      plugins: [
        [PrimeVue, { theme: { preset: Aura } }],
        router,
      ],
    },
  })
  await flushPromises()
  return wrapper
}

function providers(...list: Array<{ type: string; enabled: boolean }>) {
  vi.mocked(authApi.getProviders).mockResolvedValue({ providers: list })
}

describe('LoginView', () => {
  beforeEach(() => {
    window.location.href = HOME
    sessionStorage.clear()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(authApi.getProviders).mockReset()
    vi.mocked(authApi.getProviderRedirect).mockReset()
    providers({ type: 'local', enabled: true })
    vi.mocked(authApi.getProviderRedirect).mockResolvedValue({ url: AUTHORIZE, state: 's1' })
  })

  it('renders login form', async () => {
    const wrapper = await mountLogin()
    expect(wrapper.find('[data-testid="login-form"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Sign in')
  })

  it('has username and password fields', async () => {
    const wrapper = await mountLogin()
    expect(wrapper.find('[data-testid="username"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="password"]').exists()).toBe(true)
  })

  it('waits for a click when opened without an issuer', async () => {
    providers({ type: 'local', enabled: true }, { type: 'okta', enabled: true })

    const wrapper = await mountLogin('/login')

    expect(authApi.getProviderRedirect).not.toHaveBeenCalled()
    expect(window.location.href).toBe(HOME)
    expect(wrapper.text()).toContain('Continue with okta')
  })

  describe('opened by an identity provider (iss parameter)', () => {
    it('starts the sign-in at that provider without a click', async () => {
      providers({ type: 'local', enabled: true }, { type: 'okta', enabled: true })

      await mountLogin(`/login?iss=${ISSUER}`)

      expect(authApi.getProviderRedirect).toHaveBeenCalledWith(
        'okta', 'http://localhost:3000/auth/callback',
      )
      expect(window.location.href).toBe(AUTHORIZE)
      expect(sessionStorage.getItem('sso_provider')).toBe('okta')
    })

    it('waits for a click when the issuer is not the configured provider', async () => {
      providers({ type: 'local', enabled: true }, { type: 'okta', enabled: true })

      const wrapper = await mountLogin(`/login?iss=${encodeURIComponent('https://id.example.org')}`)

      expect(window.location.href).toBe(HOME)
      expect(sessionStorage.getItem('sso_state')).toBeNull()
      expect(wrapper.text()).toContain('Continue with okta')
      expect(wrapper.text()).not.toContain('Redirecting')
    })

    it('does not try a provider that is switched off', async () => {
      providers({ type: 'local', enabled: true }, { type: 'okta', enabled: false })

      await mountLogin(`/login?iss=${ISSUER}`)

      expect(authApi.getProviderRedirect).not.toHaveBeenCalled()
      expect(window.location.href).toBe(HOME)
    })

    it('does not treat a password provider as the identity provider', async () => {
      providers({ type: 'local', enabled: true }, { type: 'keycloak', enabled: true })

      await mountLogin(`/login?iss=${ISSUER}`)

      expect(authApi.getProviderRedirect).not.toHaveBeenCalled()
      expect(window.location.href).toBe(HOME)
    })

    it('shows the sign-in page with a message when the start fails', async () => {
      providers({ type: 'local', enabled: true }, { type: 'okta', enabled: true })
      vi.mocked(authApi.getProviderRedirect).mockRejectedValue(
        Object.assign(new Error('boom'), { isAxiosError: true, response: { status: 503 } }),
      )

      const wrapper = await mountLogin(`/login?iss=${ISSUER}`)

      expect(window.location.href).toBe(HOME)
      expect(wrapper.text()).toContain('Sign-in is temporarily unavailable')
      expect(wrapper.text()).toContain('Continue with okta')
      expect(wrapper.find('[data-testid="login-form"]').exists()).toBe(true)
    })
  })
})
