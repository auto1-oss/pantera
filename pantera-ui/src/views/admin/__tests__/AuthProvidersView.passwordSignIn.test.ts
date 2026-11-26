import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import Select from 'primevue/select'
import AuthProvidersView from '../AuthProvidersView.vue'
import { useNotificationStore } from '@/stores/notifications'

const getSettingsMock = vi.fn()
const getPasswordSignInMock = vi.fn()
const updatePasswordSignInMock = vi.fn()

vi.mock('@/api/settings', () => ({
  getSettings: (...a: unknown[]) => getSettingsMock(...a),
  toggleAuthProvider: vi.fn().mockResolvedValue(undefined),
  updateAuthProviderConfig: vi.fn().mockResolvedValue(undefined),
  createAuthProvider: vi.fn().mockResolvedValue(undefined),
  deleteAuthProvider: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/api/roles', () => ({
  listRoles: vi.fn().mockResolvedValue({ items: [] }),
}))
vi.mock('@/api/passwordSignIn', async (original) => ({
  ...(await original<typeof import('@/api/passwordSignIn')>()),
  getPasswordSignInSettings: (...a: unknown[]) => getPasswordSignInMock(...a),
  updatePasswordSignInSettings: (...a: unknown[]) => updatePasswordSignInMock(...a),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

interface ProviderRow { id: number; type: string; priority: number; enabled: boolean; config: Record<string, unknown> }

const LOCAL: ProviderRow = { id: 1, type: 'local', priority: 0, enabled: true, config: {} }
const JWT: ProviderRow = { id: 2, type: 'jwt-password', priority: 1, enabled: true, config: {} }
const KEYCLOAK: ProviderRow = { id: 5, type: 'keycloak', priority: 2, enabled: true, config: { url: 'https://kc', realm: 'r' } }
const OKTA: ProviderRow = { id: 7, type: 'okta', priority: 3, enabled: true, config: { issuer: 'https://x.okta.com' } }
const GITHUB: ProviderRow = { id: 8, type: 'github', priority: 4, enabled: true, config: {} }
const ENV: ProviderRow = { id: 9, type: 'env', priority: 5, enabled: true, config: {} }

/** What the server answers: every type, configured or not. */
const STORED = {
  password_sign_in_okta: 'all',
  password_sign_in_keycloak: 'form',
  password_sign_in_github: 'off',
}

function refusal(status: number, message: string) {
  return { response: { status, data: { error: 'X', message, status } } }
}

let wrapper: VueWrapper | null = null

async function mountView(providers: ProviderRow[] = [LOCAL, JWT, KEYCLOAK, OKTA, GITHUB]) {
  getSettingsMock.mockResolvedValue({ credentials: providers.map(p => ({ ...p })) })
  wrapper = mount(AuthProvidersView, {
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
  })
  await flushPromises()
  return wrapper
}

/** The Password sign-in block on the card of one provider type. */
function block(w: VueWrapper, type: string) {
  return w.find(`[data-testid="password-sign-in"][data-provider-type="${type}"]`)
}

function modeSelect(w: VueWrapper, type: string) {
  const select = block(w, type).findComponent(Select)
  if (!select.exists()) throw new Error(`no Password sign-in control for ${type}`)
  return select
}

/** Pick a mode the way an admin does: open the list, press the option (PrimeVue selects on mousedown). */
async function pick(w: VueWrapper, type: string, label: string) {
  await modeSelect(w, type).find('[role="combobox"]').trigger('click')
  await flushPromises()
  const option = Array.from(document.querySelectorAll<HTMLElement>('[role="option"]'))
    .find(o => o.getAttribute('aria-label') === label)
  if (!option) throw new Error(`option "${label}" not found`)
  option.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  await flushPromises()
}

function inBody<T extends HTMLElement = HTMLElement>(testid: string): T | null {
  return document.querySelector(`[data-testid="${testid}"]`) as T | null
}

async function clickInBody(testid: string) {
  const el = inBody<HTMLButtonElement>(testid)
  if (!el) throw new Error(`${testid} not found`)
  el.click()
  await flushPromises()
}

function toasts() {
  return useNotificationStore().toasts
}

beforeEach(() => {
  setActivePinia(createPinia())
  getSettingsMock.mockReset()
  getPasswordSignInMock.mockReset().mockResolvedValue({ ...STORED })
  updatePasswordSignInMock.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('AuthProvidersView: Password sign-in, where it is shown', () => {
  it('is on the card of an okta, keycloak and github provider only', async () => {
    const w = await mountView([LOCAL, JWT, KEYCLOAK, OKTA, GITHUB, ENV])
    const shown = w.findAll('[data-testid="password-sign-in"]').map(b => b.attributes('data-provider-type'))
    expect(shown.sort()).toEqual(['github', 'keycloak', 'okta'])
    for (const type of ['local', 'jwt-password', 'env']) {
      expect(block(w, type).exists(), `none for ${type}`).toBe(false)
    }
  })

  it('does not ask for the setting when no such provider is configured', async () => {
    await mountView([LOCAL, JWT, ENV])
    expect(getPasswordSignInMock).not.toHaveBeenCalled()
  })

  it('is named Password sign-in and offers the three modes', async () => {
    const w = await mountView()
    const okta = block(w, 'okta')
    expect(okta.find('[data-testid="password-sign-in-label"]').text()).toBe('Password sign-in')
    expect(modeSelect(w, 'okta').props('options')).toEqual([
      { label: 'Sign-in form and repositories', value: 'all' },
      { label: 'Sign-in form only', value: 'form' },
      { label: 'Off', value: 'off' },
    ])
  })

  it('shows the stored mode of each type and says what it means', async () => {
    const w = await mountView()

    expect(modeSelect(w, 'okta').props('modelValue')).toBe('all')
    expect(modeSelect(w, 'okta').text()).toContain('Sign-in form and repositories')
    expect(block(w, 'okta').find('[data-testid="password-sign-in-description"]').text())
      .toMatch(/on the sign-in form.*POST \/api\/v1\/auth\/token.*repository requests/)

    expect(modeSelect(w, 'keycloak').props('modelValue')).toBe('form')
    expect(modeSelect(w, 'keycloak').text()).toContain('Sign-in form only')
    const form = block(w, 'keycloak').find('[data-testid="password-sign-in-description"]').text()
    expect(form, 'what a repository request gets').toMatch(/answered 401/)
    expect(form, 'what clients use').toMatch(/API key/)

    expect(modeSelect(w, 'github').props('modelValue')).toBe('off')
    expect(block(w, 'github').find('[data-testid="password-sign-in-description"]').text())
      .toMatch(/checks no password/)
  })

  it('says what the setting does not touch and when it applies', async () => {
    const w = await mountView()
    const notes = block(w, 'okta').find('[data-testid="password-sign-in-notes"]').text()
    expect(notes, 'the sign-in button and API keys').toMatch(/does not affect.*sign-in button.*API keys/)
    expect(notes, 'no restart').toMatch(/next login.*without a restart/)
  })

  it('says on the github card only that GitHub takes a personal access token', async () => {
    const w = await mountView()
    expect(block(w, 'github').text()).toContain('GitHub takes a personal access token as the password')
    expect(block(w, 'okta').text()).not.toContain('personal access token')
    expect(block(w, 'keycloak').text()).not.toContain('personal access token')
  })

  it('stays on a disabled provider and says that it is disabled', async () => {
    const w = await mountView([LOCAL, { ...OKTA, enabled: false }, KEYCLOAK])
    expect(modeSelect(w, 'okta').props('disabled'), 'the mode can still be changed').toBe(false)
    expect(block(w, 'okta').find('[data-testid="password-sign-in-provider-disabled"]').text())
      .toContain('This provider is disabled')
    expect(block(w, 'keycloak').find('[data-testid="password-sign-in-provider-disabled"]').exists()).toBe(false)
  })
})

describe('AuthProvidersView: Password sign-in, changing the mode', () => {
  it('asks before a change to Sign-in form only and says what stops working', async () => {
    const w = await mountView()
    await pick(w, 'okta', 'Sign-in form only')

    const dialog = inBody('password-sign-in-confirm-dialog')
    expect(dialog, 'the confirmation is open').not.toBeNull()
    const text = dialog!.textContent ?? ''
    expect(text, 'names the provider').toContain('Okta')
    expect(text, 'what stops working').toMatch(/Directory passwords stop working in build tools and other repository clients/)
    expect(text, 'the sign-in form keeps working').not.toMatch(/and on the sign-in form/)
    expect(text, 'failed logins').toMatch(/failed login.*login delay.*account lock/)
    expect(text, 'what users need').toMatch(/API key/)
    expect(text, 'no word about GitHub for another provider').not.toContain('personal access token')
    expect(updatePasswordSignInMock, 'nothing is sent before the confirmation').not.toHaveBeenCalled()
  })

  it('says for Off that the sign-in form stops taking passwords too', async () => {
    const w = await mountView()
    await pick(w, 'okta', 'Off')

    const text = inBody('password-sign-in-confirm-dialog')!.textContent ?? ''
    expect(text).toMatch(/Directory passwords stop working in build tools and other repository clients, and on the sign-in form/)
    expect(text).toMatch(/failed login.*login delay.*account lock/)
    expect(text).toMatch(/API key/)
  })

  it('saves on confirm with the one key that changed', async () => {
    const w = await mountView()
    await pick(w, 'okta', 'Sign-in form only')
    await clickInBody('password-sign-in-confirm')

    expect(updatePasswordSignInMock).toHaveBeenCalledTimes(1)
    expect(updatePasswordSignInMock).toHaveBeenCalledWith({ password_sign_in_okta: 'form' })
    expect(inBody('password-sign-in-confirm-dialog'), 'the dialog is closed').toBeNull()
    expect(modeSelect(w, 'okta').props('modelValue')).toBe('form')
    expect(block(w, 'okta').find('[data-testid="password-sign-in-description"]').text()).toMatch(/answered 401/)
    expect(toasts().filter(t => t.severity === 'success').map(t => `${t.summary} ${t.detail ?? ''}`))
      .toEqual([expect.stringMatching(/Password sign-in.*Sign-in form only/)])
    expect(modeSelect(w, 'keycloak').props('modelValue'), 'the other providers keep their mode').toBe('form')
    expect(modeSelect(w, 'github').props('modelValue')).toBe('off')
  })

  it('saves a change back to Sign-in form and repositories without a question', async () => {
    const w = await mountView()
    await pick(w, 'github', 'Sign-in form and repositories')

    expect(inBody('password-sign-in-confirm-dialog'), 'no confirmation').toBeNull()
    expect(updatePasswordSignInMock).toHaveBeenCalledTimes(1)
    expect(updatePasswordSignInMock).toHaveBeenCalledWith({ password_sign_in_github: 'all' })
    expect(modeSelect(w, 'github').props('modelValue')).toBe('all')
  })

  it('asks for a change from Off to Sign-in form only too', async () => {
    const w = await mountView()
    await pick(w, 'github', 'Sign-in form only')
    expect(inBody('password-sign-in-confirm-dialog')).not.toBeNull()
    expect(inBody('password-sign-in-confirm-dialog')!.textContent, 'what a password is with GitHub')
      .toContain('personal access token')
    expect(updatePasswordSignInMock).not.toHaveBeenCalled()
  })

  it('sends nothing and shows the stored mode again on cancel', async () => {
    const w = await mountView()
    await pick(w, 'okta', 'Off')
    expect(modeSelect(w, 'okta').props('modelValue'), 'the pick is shown while it is confirmed').toBe('off')
    await clickInBody('password-sign-in-cancel')

    expect(updatePasswordSignInMock).not.toHaveBeenCalled()
    expect(inBody('password-sign-in-confirm-dialog')).toBeNull()
    expect(modeSelect(w, 'okta').props('modelValue')).toBe('all')
    expect(modeSelect(w, 'okta').text()).toContain('Sign-in form and repositories')
    expect(toasts()).toHaveLength(0)
  })

  it('sends nothing when the stored mode is picked again', async () => {
    const w = await mountView()
    await pick(w, 'keycloak', 'Sign-in form only')
    expect(inBody('password-sign-in-confirm-dialog')).toBeNull()
    expect(updatePasswordSignInMock).not.toHaveBeenCalled()
  })

  it('puts the stored mode back and shows the message of the server when the save fails', async () => {
    updatePasswordSignInMock.mockRejectedValue(
      refusal(400, "Invalid password-sign-in setting: 'x' is not a password sign-in mode (all, form, off)"),
    )
    const w = await mountView()
    await pick(w, 'okta', 'Sign-in form only')
    await clickInBody('password-sign-in-confirm')

    expect(modeSelect(w, 'okta').props('modelValue'), 'back to the stored mode').toBe('all')
    expect(modeSelect(w, 'okta').text()).toContain('Sign-in form and repositories')
    expect(block(w, 'okta').find('[data-testid="password-sign-in-save-error"]').text())
      .toContain("'x' is not a password sign-in mode (all, form, off)")
    expect(toasts().filter(t => t.severity === 'success'), 'no success toast').toHaveLength(0)
    expect(toasts().filter(t => t.severity === 'error').map(t => t.detail))
      .toEqual([expect.stringContaining('is not a password sign-in mode')])
    expect(inBody('password-sign-in-confirm-dialog'), 'the dialog is closed').toBeNull()
  })

  it('puts the stored mode back when a change without confirmation fails', async () => {
    updatePasswordSignInMock.mockRejectedValue(refusal(500, 'database is down'))
    const w = await mountView()
    await pick(w, 'github', 'Sign-in form and repositories')

    expect(modeSelect(w, 'github').props('modelValue')).toBe('off')
    expect(block(w, 'github').find('[data-testid="password-sign-in-save-error"]').text()).toContain('database is down')
  })

  it('clears the message of a failed save with the next save that works', async () => {
    updatePasswordSignInMock.mockRejectedValueOnce(refusal(500, 'database is down'))
    const w = await mountView()
    await pick(w, 'github', 'Sign-in form and repositories')
    expect(block(w, 'github').find('[data-testid="password-sign-in-save-error"]').exists()).toBe(true)

    await pick(w, 'github', 'Sign-in form and repositories')
    expect(block(w, 'github').find('[data-testid="password-sign-in-save-error"]').exists()).toBe(false)
    expect(modeSelect(w, 'github').props('modelValue')).toBe('all')
  })
})

describe('AuthProvidersView: Password sign-in, when the setting cannot be read', () => {
  it('shows no control when the account may not read it (403)', async () => {
    getPasswordSignInMock.mockRejectedValue(refusal(403, 'Access denied'))
    const w = await mountView()

    expect(w.findAll('[data-testid="password-sign-in"]')).toHaveLength(0)
    expect(w.text(), 'the providers are still listed').toContain('Okta (OIDC SSO)')
    expect(toasts(), 'no error toast for a missing permission').toHaveLength(0)
  })

  it('shows the control disabled with a hint and a retry on another failure', async () => {
    getPasswordSignInMock.mockRejectedValueOnce(refusal(500, 'boom'))
    const w = await mountView()

    expect(modeSelect(w, 'okta').props('disabled')).toBe(true)
    expect(block(w, 'okta').find('[data-testid="password-sign-in-load-error"]').text())
      .toContain('could not be loaded')
    expect(block(w, 'okta').find('[data-testid="password-sign-in-description"]').exists(), 'no mode is claimed').toBe(false)

    await block(w, 'okta').find('[data-testid="password-sign-in-retry"]').trigger('click')
    await flushPromises()

    expect(getPasswordSignInMock).toHaveBeenCalledTimes(2)
    expect(modeSelect(w, 'okta').props('disabled')).toBe(false)
    expect(modeSelect(w, 'okta').props('modelValue')).toBe('all')
    expect(block(w, 'okta').find('[data-testid="password-sign-in-load-error"]').exists()).toBe(false)
    expect(modeSelect(w, 'keycloak').props('modelValue'), 'the retry loads every card').toBe('form')
  })
})
