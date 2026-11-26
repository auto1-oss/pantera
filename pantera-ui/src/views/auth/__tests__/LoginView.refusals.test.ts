import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import LoginView from '../LoginView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import * as authApi from '@/api/auth'
import { rememberSignOutReason, signOutNotice } from '@/utils/authError'

vi.mock('@/api/auth', () => ({
  getProviders: vi.fn(),
  getProviderRedirect: vi.fn(),
  login: vi.fn(),
  getMe: vi.fn(),
}))

const HOME = 'http://localhost:3000/'
const LOCKED_MESSAGE = 'Account locked. Ask an administrator to unlock it.'
const GENERIC = 'Sign-in failed. Check your credentials and try again.'

let wrapper: VueWrapper | null = null

async function mountLogin(path = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/', name: 'dashboard', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  wrapper = mount(LoginView, {
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }], router] },
  })
  await flushPromises()
  return wrapper
}

/** What the token endpoint answers a refused sign-in with, as axios reports it. */
function refused(status: number, data: unknown, headers: Record<string, string> = {}) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    isAxiosError: true,
    response: { status, data, headers },
  })
}

async function signIn(w: VueWrapper, name = 'alice@example.com', pass = 'wrong-password') {
  await w.find('[data-testid="username"]').setValue(name)
  await w.find('#password').setValue(pass)
  await w.find('[data-testid="login-form"]').trigger('submit')
  await flushPromises()
}

function shownError(w: VueWrapper): string {
  const banner = w.find('[data-testid="login-error"]')
  return banner.exists() ? banner.text() : ''
}

describe('LoginView: refused sign-ins', () => {
  beforeEach(() => {
    window.location.href = HOME
    sessionStorage.clear()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(authApi.getProviders).mockReset().mockResolvedValue({
      providers: [{ type: 'local', enabled: true }],
    })
    vi.mocked(authApi.getProviderRedirect).mockReset()
    vi.mocked(authApi.login).mockReset()
    vi.mocked(authApi.getMe).mockReset()
    // formatAuthError logs status and code for developers.
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('tells a locked user to ask an administrator', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(403, {
      error: 'ACCOUNT_LOCKED', message: 'Account locked. Ask an administrator to unlock it.', status: 403,
    }))
    const w = await mountLogin()

    await signIn(w)

    expect(authApi.login, 'the sign-in must be sent').toHaveBeenCalledWith('alice@example.com', 'wrong-password')
    expect(shownError(w), 'the locked message').toBe(LOCKED_MESSAGE)
    expect(localStorage.getItem('access_token'), 'nobody is signed in').toBeNull()
  })

  it('says how long logins are delayed, from Retry-After', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(
      403,
      { error: 'LOGIN_DELAYED', message: 'Too many failed logins. Try again in 99 seconds.', status: 403 },
      { 'retry-after': '7' },
    ))
    const w = await mountLogin()

    await signIn(w)

    expect(shownError(w)).toBe('Too many failed logins. Try again in 7 seconds.')
  })

  it('does not take a delayed login for a locked account', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(
      403,
      { error: 'LOGIN_DELAYED', message: 'Too many failed logins. Try again in 7 seconds.', status: 403 },
      { 'retry-after': '7' },
    ))
    const w = await mountLogin()

    await signIn(w)

    expect(w.text(), 'no word of a lock').not.toContain('locked')
    expect(signOutNotice(), 'no notice is left for a later visit').toBe('')
    expect(window.location.href, 'the page must stay').toBe(HOME)
  })

  it('shows the server message of a delay when Retry-After is missing', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(403, {
      error: 'LOGIN_DELAYED', message: 'Too many failed logins. Try again in 4 seconds.', status: 403,
    }))
    const w = await mountLogin()

    await signIn(w)

    expect(shownError(w)).toBe('Too many failed logins. Try again in 4 seconds.')
  })

  it('shows the server message when sign-in is unavailable', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(503, {
      error: 'SERVICE_UNAVAILABLE', message: 'Sign-in is temporarily unavailable. Please try again.',
    }))
    const w = await mountLogin()

    await signIn(w)

    expect(shownError(w)).toBe('Sign-in is temporarily unavailable. Please try again.')
  })

  it('keeps the generic message for the older limit of the form', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(
      429,
      { error: 'TOO_MANY_REQUESTS', message: 'Too many sign-in attempts. Please wait and try again.', status: 429 },
      { 'retry-after': '857' },
    ))
    const w = await mountLogin()

    await signIn(w)

    expect(shownError(w)).toBe(GENERIC)
  })

  it('discloses nothing else the server says', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(401, {
      error: 'UNAUTHORIZED', message: 'User alice@example.com exists but the password is wrong', status: 401,
    }))
    const w = await mountLogin()

    await signIn(w)

    expect(shownError(w), 'the generic message').toBe(GENERIC)
    expect(w.text(), 'no server text in the page').not.toContain('exists')
  })

  it('clears the message when the user edits a field', async () => {
    vi.mocked(authApi.login).mockRejectedValue(refused(403, { error: 'ACCOUNT_LOCKED' }))
    const w = await mountLogin()
    await signIn(w)
    expect(shownError(w), 'shown after the refusal').toBe(LOCKED_MESSAGE)

    await w.find('[data-testid="username"]').setValue('bob')

    expect(shownError(w), 'gone once a field changes').toBe('')
  })
})

describe('LoginView: after a locked user was signed out', () => {
  beforeEach(() => {
    window.location.href = HOME
    sessionStorage.clear()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(authApi.getProviders).mockReset().mockResolvedValue({
      providers: [{ type: 'local', enabled: true }, { type: 'okta', enabled: true }],
    })
    vi.mocked(authApi.getProviderRedirect).mockReset().mockResolvedValue({
      url: 'https://id.example.com/oauth2/v1/authorize?client_id=c&state=s1', state: 's1',
    })
    vi.mocked(authApi.login).mockReset()
    vi.mocked(authApi.getMe).mockReset()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('shows no message on an ordinary visit', async () => {
    const w = await mountLogin()

    expect(shownError(w)).toBe('')
  })

  it('shows the locked message the API client left', async () => {
    rememberSignOutReason('account_locked')

    const w = await mountLogin()

    expect(shownError(w)).toBe(LOCKED_MESSAGE)
  })

  it('shows it again when the page is mounted a second time', async () => {
    rememberSignOutReason('account_locked')
    const first = await mountLogin()
    expect(shownError(first), 'first mount').toBe(LOCKED_MESSAGE)
    first.unmount()

    const second = await mountLogin()

    expect(shownError(second), 'second mount').toBe(LOCKED_MESSAGE)
  })

  it('forgets it once the user edits a field', async () => {
    rememberSignOutReason('account_locked')
    const w = await mountLogin()

    await w.find('[data-testid="username"]').setValue('a')

    expect(shownError(w), 'the banner is gone').toBe('')
    expect(signOutNotice(), 'a reload shows nothing either').toBe('')
  })

  it('forgets it when a new sign-in succeeds', async () => {
    rememberSignOutReason('account_locked')
    vi.mocked(authApi.login).mockResolvedValue({ token: 'access-1', refresh_token: 'refresh-1' })
    vi.mocked(authApi.getMe).mockResolvedValue({ name: 'alice', context: 'local', permissions: {} })
    const w = await mountLogin()

    await signIn(w, 'alice', 'right-password')

    expect(shownError(w), 'no banner').toBe('')
    expect(signOutNotice(), 'the notice is used up').toBe('')
    expect(localStorage.getItem('access_token'), 'the user is signed in').toBe('access-1')
  })

  it('does not start the identity provider sign-in by itself', async () => {
    rememberSignOutReason('account_locked')

    const w = await mountLogin(`/login?iss=${encodeURIComponent('https://id.example.com')}`)

    expect(authApi.getProviderRedirect, 'no redirect may be started').not.toHaveBeenCalled()
    expect(window.location.href, 'the page must stay').toBe(HOME)
    expect(shownError(w), 'the locked message is shown instead').toBe(LOCKED_MESSAGE)
  })

  it('forgets it when the user clicks the provider button', async () => {
    rememberSignOutReason('account_locked')
    const w = await mountLogin()
    const button = w.findAll('button').find(b => b.text().includes('Continue with okta'))

    await button!.trigger('click')
    await flushPromises()

    expect(authApi.getProviderRedirect, 'the redirect is started').toHaveBeenCalled()
    expect(signOutNotice(), 'the notice is used up').toBe('')
  })
})
