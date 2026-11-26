import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import ProfileView from '../ProfileView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { localDateTime } from '@/utils/relativeTime'
import type { RejectedApiKey } from '@/api/signInFailures'

const listTokensMock = vi.fn()
const listMyRejectedKeysMock = vi.fn()

vi.mock('@/api/auth', () => ({
  listTokens: (...a: unknown[]) => listTokensMock(...a),
  revokeToken: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/api/users', () => ({
  changePassword: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/api/signInFailures', () => ({
  listMyRejectedKeys: (...a: unknown[]) => listMyRejectedKeysMock(...a),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const KEY_ID = 'a922f096-2062-4c1f-b619-df3052cf208e'

const EXPIRED: RejectedApiKey = {
  user: 'alice', client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository',
  verified: true, key_id: KEY_ID, key_name: 'CI token',
  key_created_at: '2026-01-01T00:00:00Z', key_expires_at: '2026-02-01T00:00:00Z',
  reason: 'api_key_expired', rejected: 48,
  first_rejected_at: '2026-11-25T16:12:00Z', last_rejected_at: '2026-11-25T19:29:00Z',
}

/** A key that is no longer stored: only its id is known. */
const REMOVED: RejectedApiKey = {
  ...EXPIRED, key_name: null, key_created_at: null, key_expires_at: null,
  reason: 'api_key_revoked', rejected: 1, client_ip: '', user_agent: '',
  last_rejected_at: '2026-11-26T08:00:00Z',
}

const UNKNOWN: RejectedApiKey = {
  ...REMOVED, verified: false, key_id: '', reason: 'api_key_unknown',
}

let wrapper: VueWrapper | null = null

async function mountView() {
  const auth = useAuthStore()
  // A provider's user: the password card is for local accounts only.
  auth.user = {
    name: 'alice', context: 'okta', email: 'alice@example.com', permissions: {},
  } as unknown as typeof auth.user
  wrapper = mount(ProfileView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
      stubs: { 'router-link': true },
    },
  })
  await flushPromises()
  return wrapper
}

function section(w: VueWrapper) {
  return w.find('[data-testid="rejected-keys"]')
}

function lines(w: VueWrapper) {
  return w.findAll('[data-testid="rejected-key"]')
}

beforeEach(() => {
  setActivePinia(createPinia())
  listTokensMock.mockReset().mockResolvedValue([
    { id: 't1', label: 'Laptop', created_at: '2026-11-01T00:00:00Z', permanent: true },
  ])
  listMyRejectedKeysMock.mockReset().mockResolvedValue([])
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('ProfileView: rejected API keys', () => {
  it('lists a rejected key with its name, reason, count, last time, client address and program', async () => {
    listMyRejectedKeysMock.mockResolvedValue([EXPIRED])

    const w = await mountView()
    const line = lines(w)[0]

    expect(listMyRejectedKeysMock, 'one request').toHaveBeenCalledTimes(1)
    expect(section(w).text(), 'the title').toContain('Rejected API keys')
    expect(line.find('[data-testid="rejected-key-name"]').text(), 'the name of the key').toBe('CI token')
    const reason = line.find('[data-testid="rejected-key-reason"]')
    expect(reason.text(), 'the reason as text').toBe('Expired')
    expect(reason.attributes('title'), 'with the token as tooltip').toBe('api_key_expired')
    expect(line.find('[data-testid="rejected-key-count"]').text(), 'how often').toBe('Rejected 48 times')
    const last = line.find('[data-testid="rejected-key-last"]')
    expect(last.text(), 'the last time').toContain(localDateTime('2026-11-25T19:29:00Z'))
    expect(last.find('[title="2026-11-25T19:29:00Z"]').exists(), 'with the exact time').toBe(true)
    expect(line.find('[data-testid="rejected-key-address"]').text(), 'the client address').toContain('192.0.2.7')
    expect(line.find('[data-testid="rejected-key-program"]').text(), 'the client program')
      .toContain('Apache-Maven/3.9.9')
  })

  it('says what to do', async () => {
    listMyRejectedKeysMock.mockResolvedValue([EXPIRED])

    const w = await mountView()

    expect(section(w).text()).toContain('A tool still sends this key. Replace it there.')
  })

  it('shows the shortened id of a key without a name, and dashes for what is unknown', async () => {
    listMyRejectedKeysMock.mockResolvedValue([REMOVED])

    const w = await mountView()
    const line = lines(w)[0]

    const name = line.find('[data-testid="rejected-key-name"]')
    expect(name.text(), 'the id, shortened').toBe('a922f096…')
    expect(name.attributes('title'), 'the whole id as tooltip').toBe(KEY_ID)
    expect(line.find('[data-testid="rejected-key-reason"]').text(), 'the reason').toBe('Revoked')
    expect(line.find('[data-testid="rejected-key-count"]').text(), 'once, in the singular').toBe('Rejected 1 time')
    expect(line.find('[data-testid="rejected-key-address"]').text(), 'no address').toContain('—')
    expect(line.find('[data-testid="rejected-key-program"]').text(), 'no program').toContain('—')
  })

  it('names a key that this Pantera did not issue', async () => {
    listMyRejectedKeysMock.mockResolvedValue([UNKNOWN])

    const w = await mountView()
    const line = lines(w)[0]

    expect(line.find('[data-testid="rejected-key-name"]').text(), 'it has neither name nor id').toBe('Unknown key')
    expect(line.find('[data-testid="rejected-key-reason"]').text(), 'the reason').toBe('Not issued here')
  })

  it('shows one line per rejected key and client', async () => {
    listMyRejectedKeysMock.mockResolvedValue([EXPIRED, REMOVED, UNKNOWN])

    const w = await mountView()

    expect(lines(w).length).toBe(3)
  })

  it('shows no section when no key was rejected', async () => {
    const w = await mountView()

    expect(listMyRejectedKeysMock, 'the list is asked for').toHaveBeenCalledTimes(1)
    expect(section(w).exists(), 'nothing to show').toBe(false)
    expect(w.text(), 'not even a title').not.toContain('Rejected API keys')
  })

  it('shows nothing when the list cannot be loaded, and the page still works', async () => {
    listMyRejectedKeysMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })

    const w = await mountView()

    expect(section(w).exists(), 'no section').toBe(false)
    expect(useNotificationStore().toasts.length, 'no error is shown').toBe(0)
    expect(w.text(), 'the profile is shown').toContain('alice@example.com')
    expect(w.text(), 'with the tokens of the user').toContain('Laptop')
  })

  it('still shows the rejected keys when the tokens cannot be loaded', async () => {
    listTokensMock.mockRejectedValue(new Error('Network Error'))
    listMyRejectedKeysMock.mockResolvedValue([EXPIRED])

    const w = await mountView()

    expect(lines(w).length).toBe(1)
  })
})
