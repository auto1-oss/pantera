import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import SignInFailuresView from '../SignInFailuresView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const listRejectedApiKeysMock = vi.fn()
const listFailedPasswordLoginsMock = vi.fn()
const listFormLimitHitsMock = vi.fn()
const getSignInReportSettingsMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  listRejectedApiKeys: (...a: unknown[]) => listRejectedApiKeysMock(...a),
  listFailedPasswordLogins: (...a: unknown[]) => listFailedPasswordLoginsMock(...a),
  listFormLimitHits: (...a: unknown[]) => listFormLimitHitsMock(...a),
  getSignInReportSettings: (...a: unknown[]) => getSignInReportSettingsMock(...a),
}))

vi.mock('@/api/failedLogins', () => ({
  unlockUser: vi.fn().mockResolvedValue(true),
}))

vi.mock('@/api/users', () => ({
  listUsers: vi.fn().mockResolvedValue({ items: [], page: 0, size: 100, total: 0, hasMore: false }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const KEY = {
  user: 'alice', client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository',
  verified: true, key_id: 'a922f096-2062-4c1f-b619-df3052cf208e', key_name: 'CI token',
  key_created_at: '2026-01-01T00:00:00Z', key_expires_at: '2026-02-01T00:00:00Z',
  reason: 'api_key_expired', rejected: 48,
  first_rejected_at: '2026-11-25T16:12:00Z', last_rejected_at: '2026-11-25T19:29:00Z',
}

const PASSWORD = {
  user: 'bob', client_ip: '192.0.2.8', user_agent: 'curl/8.19.0', path: 'api',
  failed: 1, refused: 0, last_reason: 'wrong_credentials',
  first_failure_at: '2026-11-26T11:00:00Z', last_failure_at: '2026-11-26T11:00:00Z',
  state: {
    failures: 1, locked: false, locked_at: null, delayed_seconds: 0, unlocked_at: null, unlocked_by: null,
  },
}

const LIMIT = {
  user: 'carol', client_ip: '192.0.2.9', user_agent: 'Mozilla/5.0', path: 'ui',
  hits: 2, failures: 5, blocked_until: '2026-11-26T19:33:32Z', blocked: true,
  first_hit_at: '2026-11-26T19:18:32Z', last_hit_at: '2026-11-26T19:19:40Z',
}

function page(items: unknown[]) {
  return { items, page: 0, size: 50, total: items.length, hasMore: false }
}

const ADMIN = ['read', 'create', 'update', 'delete', 'enable']
const READER = ['read']

let wrapper: VueWrapper | null = null

async function mountAt(url: string, actions: string[] = ADMIN): Promise<{ w: VueWrapper; router: Router }> {
  const auth = useAuthStore()
  auth.user = {
    name: 'admin', context: 'local',
    permissions: { api_user_permissions: actions },
  } as unknown as typeof auth.user
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/sign-in-failures', component: { template: '<div />' } },
      { path: '/admin/users', component: { template: '<div />' } },
    ],
  })
  await router.push(url)
  await router.isReady()
  wrapper = mount(SignInFailuresView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], router],
      directives: { tooltip: {} },
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return { w: wrapper, router }
}

function tab(w: VueWrapper, id: string) {
  return w.find(`[data-testid="sign-in-tab-${id}"]`)
}

function panel(w: VueWrapper, id: string) {
  return w.find(`[data-testid="sign-in-panel-${id}"]`)
}

async function openTab(w: VueWrapper, id: string) {
  await tab(w, id).trigger('click')
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  listRejectedApiKeysMock.mockReset().mockResolvedValue(page([KEY]))
  listFailedPasswordLoginsMock.mockReset().mockResolvedValue(page([PASSWORD]))
  listFormLimitHitsMock.mockReset().mockResolvedValue(page([LIMIT]))
  getSignInReportSettingsMock.mockReset().mockResolvedValue({
    sign_in_report_enabled: 'true', sign_in_report_retention_days: '90',
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('SignInFailuresView: what the page says about itself', () => {
  it('says what it is for', async () => {
    const { w } = await mountAt('/admin/sign-in-failures')

    expect(w.find('h1').text(), 'the title').toBe('Sign-in failures')
    expect(w.find('[data-testid="sign-in-failures-purpose"]').text(), 'the purpose')
      .toBe('Who fails to sign in, with what, and from where.')
  })

  it('says how current the lists are and how long lines are kept', async () => {
    const { w } = await mountAt('/admin/sign-in-failures')

    expect(w.find('[data-testid="sign-in-failures-currency"]').text()).toBe(
      'Events of this node are listed at once, events of other nodes within a minute. '
      + 'A line is kept for 90 days after its last event.',
    )
  })

  it('names a retention of one day in the singular', async () => {
    getSignInReportSettingsMock.mockResolvedValue({
      sign_in_report_enabled: 'true', sign_in_report_retention_days: '1',
    })

    const { w } = await mountAt('/admin/sign-in-failures')

    expect(w.find('[data-testid="sign-in-failures-currency"]').text())
      .toContain('A line is kept for 1 day after its last event.')
  })

  it('leaves the number out for an account that may not read the settings', async () => {
    const { w } = await mountAt('/admin/sign-in-failures', READER)

    expect(getSignInReportSettingsMock, 'no request that would be refused').not.toHaveBeenCalled()
    expect(w.find('[data-testid="sign-in-failures-currency"]').text()).toBe(
      'Events of this node are listed at once, events of other nodes within a minute. '
      + 'A line is kept for a limited time after its last event.',
    )
  })

  it('leaves the number out when the settings cannot be read', async () => {
    getSignInReportSettingsMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })

    const { w } = await mountAt('/admin/sign-in-failures')

    expect(w.find('[data-testid="sign-in-failures-currency"]').text(), 'no number')
      .toContain('A line is kept for a limited time after its last event.')
    expect(useNotificationStore().toasts.length, 'and no error').toBe(0)
    expect(panel(w, 'api-keys').findAll('tbody tr').length, 'the list is shown all the same').toBe(1)
  })

  it('leaves the number out when the settings carry no usable retention', async () => {
    getSignInReportSettingsMock.mockResolvedValue({ sign_in_report_enabled: 'true' })

    const { w } = await mountAt('/admin/sign-in-failures')

    expect(w.find('[data-testid="sign-in-failures-currency"]').text())
      .toContain('A line is kept for a limited time after its last event.')
  })

  it('says so when the report is switched off', async () => {
    getSignInReportSettingsMock.mockResolvedValue({
      sign_in_report_enabled: 'false', sign_in_report_retention_days: '90',
    })

    const { w } = await mountAt('/admin/sign-in-failures')

    expect(w.find('[data-testid="sign-in-report-off"]').text()).toBe(
      'The report is switched off: no sign-in failure is noted. '
      + 'Lines already stored stay until their retention ends.',
    )
  })

  it('shows no such notice while the report is on', async () => {
    const { w } = await mountAt('/admin/sign-in-failures')

    expect(w.find('[data-testid="sign-in-report-off"]').exists()).toBe(false)
  })
})

describe('SignInFailuresView: the three tabs', () => {
  it('has a tab for each list and opens the rejected API keys', async () => {
    const { w } = await mountAt('/admin/sign-in-failures')

    expect(tab(w, 'api-keys').text(), 'first tab').toBe('Rejected API keys')
    expect(tab(w, 'passwords').text(), 'second tab').toBe('Failed password logins')
    expect(tab(w, 'form-limit').text(), 'third tab').toBe('Sign-in form limit')
    expect(tab(w, 'api-keys').attributes('aria-selected'), 'the first is selected').toBe('true')
    expect(tab(w, 'passwords').attributes('aria-selected'), 'the second is not').toBe('false')
    expect(panel(w, 'api-keys').text(), 'its lines are shown').toContain('CI token')
  })

  it('loads a list when its tab is opened, not before', async () => {
    const { w } = await mountAt('/admin/sign-in-failures')

    expect(listRejectedApiKeysMock, 'the open tab is loaded').toHaveBeenCalledTimes(1)
    expect(listFailedPasswordLoginsMock, 'the second is not asked for yet').not.toHaveBeenCalled()
    expect(listFormLimitHitsMock, 'nor the third').not.toHaveBeenCalled()

    await openTab(w, 'passwords')

    expect(listFailedPasswordLoginsMock, 'now it is').toHaveBeenCalledTimes(1)
    expect(tab(w, 'passwords').attributes('aria-selected'), 'and selected').toBe('true')
    expect(panel(w, 'passwords').text(), 'with its lines').toContain('bob')
    expect(panel(w, 'passwords').isVisible(), 'its panel is shown').toBe(true)
    expect(panel(w, 'api-keys').isVisible(), 'the first panel is hidden').toBe(false)

    await openTab(w, 'form-limit')

    expect(listFormLimitHitsMock, 'the third on its turn').toHaveBeenCalledTimes(1)
    expect(panel(w, 'form-limit').text(), 'with its lines').toContain('carol')
  })

  it('keeps the filter and the lines of a tab that was left', async () => {
    const { w } = await mountAt('/admin/sign-in-failures')
    const input = panel(w, 'api-keys').find('[data-testid="list-filter-user"]')
    await input.setValue('alice')
    await input.trigger('keyup.enter')
    await flushPromises()

    await openTab(w, 'passwords')
    await openTab(w, 'api-keys')

    expect(listRejectedApiKeysMock, 'not loaded a third time').toHaveBeenCalledTimes(2)
    expect(
      (panel(w, 'api-keys').find('[data-testid="list-filter-user"]').element as HTMLInputElement).value,
      'the filter is still there',
    ).toBe('alice')
    expect(panel(w, 'api-keys').isVisible(), 'and its panel is shown again').toBe(true)
  })

  it('puts the open tab into the address', async () => {
    const { w, router } = await mountAt('/admin/sign-in-failures')

    await openTab(w, 'form-limit')
    expect(router.currentRoute.value.query.tab, 'the third tab').toBe('form-limit')

    await openTab(w, 'api-keys')
    expect(router.currentRoute.value.query.tab, 'the first tab needs none').toBeUndefined()
  })

  it('opens the tab named in the address', async () => {
    const { w } = await mountAt('/admin/sign-in-failures?tab=passwords')

    expect(tab(w, 'passwords').attributes('aria-selected'), 'selected').toBe('true')
    expect(listFailedPasswordLoginsMock, 'loaded').toHaveBeenCalledTimes(1)
    expect(listRejectedApiKeysMock, 'the first tab is not').not.toHaveBeenCalled()
  })

  it('follows a link to another tab while the page is open', async () => {
    const { w, router } = await mountAt('/admin/sign-in-failures')

    await router.push('/admin/sign-in-failures?tab=form-limit')
    await flushPromises()

    expect(tab(w, 'form-limit').attributes('aria-selected'), 'selected').toBe('true')
    expect(listFormLimitHitsMock, 'loaded').toHaveBeenCalledTimes(1)
  })

  it('loads nothing more when the page is left from a tab named in the address', async () => {
    const { router } = await mountAt('/admin/sign-in-failures?tab=passwords')

    await router.push('/admin/users')
    await flushPromises()

    expect(listRejectedApiKeysMock, 'the first tab is not opened on the way out').not.toHaveBeenCalled()
  })

  it('opens the first tab for a tab it does not know', async () => {
    const { w } = await mountAt('/admin/sign-in-failures?tab=tokens')

    expect(tab(w, 'api-keys').attributes('aria-selected')).toBe('true')
    expect(listRejectedApiKeysMock).toHaveBeenCalledTimes(1)
  })

  it('shows the refusal of each list on its own tab', async () => {
    const refused = { response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } } }
    listRejectedApiKeysMock.mockRejectedValue(refused)
    listFailedPasswordLoginsMock.mockRejectedValue(refused)
    listFormLimitHitsMock.mockRejectedValue(refused)
    const { w } = await mountAt('/admin/sign-in-failures', [])

    for (const id of ['api-keys', 'passwords', 'form-limit']) {
      await openTab(w, id)
      expect(panel(w, id).find('[data-testid="list-forbidden"]').text(), `tab ${id}`)
        .toBe('You do not have permission to see this list.')
    }
  })
})
