import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import CooldownView from '../CooldownView.vue'
import type { CooldownInspectResponse, InspectSuggestion } from '@/api/cooldown'

const inspectMock = vi.fn()
const refreshMock = vi.fn()
const suggestMock = vi.fn()

vi.mock('@/api/cooldown', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/cooldown')>()),
  inspectCooldownPackage: (...a: unknown[]) => inspectMock(...a),
  refreshCooldownPackage: (...a: unknown[]) => refreshMock(...a),
  suggestCooldownPackages: (...a: unknown[]) => suggestMock(...a),
}))

vi.mock('@/api/settings', () => ({
  getCooldownOverview: vi.fn().mockResolvedValue([]),
  getCooldownBlocked: vi.fn().mockResolvedValue({ items: [], page: 0, size: 50, total: 0, hasMore: false }),
  getCooldownHistory: vi.fn().mockResolvedValue({ items: [], page: 0, size: 50, total: 0, hasMore: false }),
}))

vi.mock('@/api/repos', () => ({
  unblockArtifact: vi.fn(),
  unblockAll: vi.fn(),
  listRepos: vi.fn().mockResolvedValue({
    items: [
      { name: 'npm-proxy', type: 'npm-proxy' },
      { name: 'npm-group', type: 'npm-group' },
      { name: 'pypi-proxy', type: 'pypi-proxy' },
    ],
    total: 3,
  }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

function inspectResponse(hiddenLatest: boolean): CooldownInspectResponse {
  const visible = hiddenLatest ? ['4.17.20'] : ['4.17.20', '4.17.21']
  return {
    package: 'lodash',
    repoType: 'npm',
    node: 'pantera-b',
    repos: [
      {
        name: 'npm-proxy',
        mode: 'proxy',
        metadata: { status: 200, fetchedVia: 'in-process', visibleVersions: visible },
        envelope: { l1: { present: true, ageMs: 120_000 }, l2: { present: true, ttlRemainingMs: 3_000_000 } },
        negativeCache: [],
      },
      {
        name: 'npm-group',
        mode: 'group',
        members: ['npm-local', 'npm-proxy'],
        metadata: { status: 200, fetchedVia: 'in-process', visibleVersions: visible },
        envelope: { l1: { present: false }, l2: { present: false } },
        negativeCache: [{ key: 'npm-group:npm:lodash:', l1: true, l2: false }],
      },
    ],
    versions: [
      {
        version: '4.17.21',
        cooldown: { state: 'released', blockedUntil: null, repo: 'npm-proxy' },
        visibleIn: hiddenLatest ? [] : ['npm-proxy', 'npm-group'],
        hiddenIn: hiddenLatest ? ['npm-proxy', 'npm-group'] : [],
        mismatch: hiddenLatest,
      },
      {
        version: '4.17.20',
        cooldown: { state: 'none' },
        visibleIn: ['npm-proxy', 'npm-group'],
        hiddenIn: [],
        mismatch: false,
      },
    ],
  }
}

const JACKSON: InspectSuggestion = {
  package: 'com.fasterxml.jackson.core:jackson-databind',
  display: 'com.fasterxml.jackson.core:jackson-databind',
  repoType: 'maven',
  sources: ['index', 'cooldown'],
  repos: ['maven_group', 'maven_proxy'],
}

function emptyResponse(pkg: string, didYouMean: InspectSuggestion[]): CooldownInspectResponse {
  return { package: pkg, repoType: 'npm', node: 'pantera-b', repos: [], versions: [], didYouMean }
}

/** Type into the package search and wait out the AutoComplete debounce. */
async function typePackage(w: VueWrapper, text: string) {
  const input = w.get('#inspect-package')
  await input.setValue(text)
  await new Promise(resolve => setTimeout(resolve, 350))
  await flushPromises()
}

let wrapper: VueWrapper | null = null

async function mountAt(url: string, admin = true): Promise<{ w: VueWrapper; router: Router }> {
  const auth = useAuthStore()
  auth.user = {
    name: 'tester',
    context: 'ci',
    permissions: admin
      ? { api_user_permissions: ['write'], api_cooldown_permissions: ['read', 'write'] }
      : { api_cooldown_permissions: ['read'] },
  } as unknown as typeof auth.user
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/cooldown', component: { template: '<div />' } }],
  })
  await router.push(url)
  await router.isReady()
  wrapper = mount(CooldownView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], router],
      directives: { tooltip: {} },
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' },
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
  await flushPromises()
  return { w: wrapper, router }
}

describe('CooldownView — Inspect package tab', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    inspectMock.mockReset()
    refreshMock.mockReset()
    suggestMock.mockReset()
    suggestMock.mockResolvedValue([])
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('is not offered to non-admin users', async () => {
    const { w } = await mountAt('/cooldown?tab=inspect&repoType=npm&package=lodash', false)
    expect(w.find('[data-testid="cooldown-tabs"]').exists()).toBe(false)
    expect(inspectMock).not.toHaveBeenCalled()
  })

  it('deep link opens the tab and inspects the package', async () => {
    inspectMock.mockResolvedValue(inspectResponse(true))
    const { w } = await mountAt('/cooldown?tab=inspect&repoType=npm&package=lodash&repo=npm-proxy')
    expect(inspectMock).toHaveBeenCalledWith({ repoType: 'npm', package: 'lodash', repo: 'npm-proxy' })
    expect(w.get('[data-testid="cooldown-tab-inspect"]').attributes('aria-selected')).toBe('true')
    const text = w.text()
    expect(text).toContain('4.17.21')
    expect(text).toContain('released')
    expect(text).toContain('pantera-b')
  })

  it('flags "released but still hidden" versions with a mismatch badge', async () => {
    inspectMock.mockResolvedValue(inspectResponse(true))
    const { w } = await mountAt('/cooldown?tab=inspect&repoType=npm&package=lodash')
    const badges = w.findAll('[data-testid="inspect-mismatch"]')
    expect(badges).toHaveLength(1)
    expect(w.text()).toContain('1 mismatch')
  })

  it('renders the per-repo cache-layer panel', async () => {
    inspectMock.mockResolvedValue(inspectResponse(true))
    const { w } = await mountAt('/cooldown?tab=inspect&repoType=npm&package=lodash')
    const panel = w.get('[data-testid="inspect-repos"]').text()
    expect(panel).toContain('npm-group')
    expect(panel).toContain('HTTP 200')
    expect(panel).toContain('1 visible versions')
    expect(panel).toContain('age 2m')
    expect(panel).toContain('npm-group:npm:lodash:')
    // Each repo links to the troubleshooter with its metadata path.
    const links = w.findAll('a[data-to*="/admin/troubleshoot"]')
    expect(links.map(l => l.attributes('data-to'))).toContain(
      JSON.stringify({ path: '/admin/troubleshoot', query: { url: '/npm-proxy/lodash' } }),
    )
  })

  it('refresh package renders the before/after diff', async () => {
    inspectMock.mockResolvedValue(inspectResponse(true))
    refreshMock.mockResolvedValue({ before: inspectResponse(true), after: inspectResponse(false) })
    const { w } = await mountAt('/cooldown?tab=inspect&repoType=npm&package=lodash')

    await w.get('[data-testid="inspect-refresh"]').trigger('click')
    await flushPromises()

    expect(refreshMock).toHaveBeenCalledWith({ repoType: 'npm', package: 'lodash', repo: undefined })
    const diff = w.get('[data-testid="inspect-refresh-diff"]').text()
    expect(diff).toContain('Mismatches: 1 before')
    expect(diff).toContain('0 after')
    expect(diff).toContain('became visible: 4.17.21')
    // The table now shows the post-refresh state: no mismatch left.
    expect(w.findAll('[data-testid="inspect-mismatch"]')).toHaveLength(0)
  })

  it('writes the inspected package back into the route query', async () => {
    inspectMock.mockResolvedValue(inspectResponse(false))
    const { w, router } = await mountAt('/cooldown')
    await w.get('[data-testid="cooldown-tab-inspect"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('inspect')

    await w.get('#inspect-package').setValue('lodash')
    await w.get('[data-testid="inspect-run"]').trigger('click')
    await flushPromises()
    expect(inspectMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query).toMatchObject({ tab: 'inspect', repoType: 'npm', package: 'lodash' })
  })

  it('suggests packages from any part of the name as the user types', async () => {
    suggestMock.mockResolvedValue([JACKSON])
    const { w } = await mountAt('/cooldown?tab=inspect')
    await typePackage(w, 'databind')

    expect(suggestMock).toHaveBeenCalledWith({ q: 'databind', repoType: 'npm', limit: 20 })
    const option = document.body.querySelector('[data-testid="inspect-suggestion"]')
    expect(option).not.toBeNull()
    const text = option!.textContent ?? ''
    expect(text).toContain('com.fasterxml.jackson.core:jackson-databind')
    expect(text).toContain('maven')
    expect(text).toContain('indexed')
    expect(text).toContain('cooldown')
    expect(text).toContain('maven_group, maven_proxy')
  })

  it('searches every format when no type is chosen', async () => {
    const { w } = await mountAt('/cooldown?tab=inspect&repoType=npm')
    // "Any type" — the Select's empty value — through the state it is bound to.
    const inspector = w.findComponent({ name: 'CooldownInspector' })
    ;(inspector.vm as unknown as { repoType: string }).repoType = ''
    await flushPromises()
    await typePackage(w, 'http5')
    expect(suggestMock).toHaveBeenCalledWith({ q: 'http5', repoType: undefined, limit: 20 })
  })

  it('does not search below two characters', async () => {
    const { w } = await mountAt('/cooldown?tab=inspect')
    await typePackage(w, 'd')
    expect(suggestMock).not.toHaveBeenCalled()
  })

  it('shows an empty state when nothing matches', async () => {
    const { w } = await mountAt('/cooldown?tab=inspect')
    await typePackage(w, 'zzqq')
    expect(document.body.textContent).toContain('No matching packages')
  })

  it('selecting a suggestion switches the type and inspects it', async () => {
    suggestMock.mockResolvedValue([JACKSON])
    inspectMock.mockResolvedValue(inspectResponse(false))
    const { w, router } = await mountAt('/cooldown?tab=inspect')
    await typePackage(w, 'jackson databind')

    const option = document.body.querySelector('[data-testid="inspect-suggestion"]')
    ;(option!.closest('li') as HTMLElement).click()
    await flushPromises()

    expect(inspectMock).toHaveBeenCalledWith({
      repoType: 'maven',
      package: 'com.fasterxml.jackson.core:jackson-databind',
      repo: undefined,
    })
    expect(router.currentRoute.value.query).toMatchObject({
      tab: 'inspect',
      repoType: 'maven',
      package: 'com.fasterxml.jackson.core:jackson-databind',
    })
  })

  it('Enter inspects free text as typed', async () => {
    inspectMock.mockResolvedValue(inspectResponse(false))
    const { w } = await mountAt('/cooldown?tab=inspect')
    await typePackage(w, 'lodash')
    await w.get('#inspect-package').trigger('keydown', { key: 'Enter', code: 'Enter' })
    await flushPromises()
    expect(inspectMock).toHaveBeenCalledWith({ repoType: 'npm', package: 'lodash', repo: undefined })
  })

  it('offers did-you-mean suggestions when the exact name is unknown', async () => {
    inspectMock.mockResolvedValueOnce(emptyResponse('jackson-databind', [JACKSON]))
    inspectMock.mockResolvedValueOnce(inspectResponse(false))
    const { w } = await mountAt('/cooldown?tab=inspect&repoType=npm&package=jackson-databind')

    const box = w.get('[data-testid="inspect-did-you-mean"]')
    expect(box.text()).toContain('No exact match for jackson-databind')
    expect(box.text()).toContain('com.fasterxml.jackson.core:jackson-databind')

    await w.get('[data-testid="inspect-did-you-mean-option"]').trigger('click')
    await flushPromises()
    expect(inspectMock).toHaveBeenLastCalledWith({
      repoType: 'maven',
      package: 'com.fasterxml.jackson.core:jackson-databind',
      repo: undefined,
    })
  })
})
