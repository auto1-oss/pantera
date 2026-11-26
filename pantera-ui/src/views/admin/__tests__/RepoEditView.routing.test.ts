import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import Select from 'primevue/select'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import RepoEditView from '../RepoEditView.vue'

// This spec mounts the REAL RepoConfigForm: what is sent on save is what
// the form builds from the loaded config. PUT replaces the whole config,
// so a `routing` key the form did not send back would be deleted.
const getRepoMock = vi.fn()
const putRepoMock = vi.fn()
const testRoutingMock = vi.fn()

vi.mock('@/api/cooldown', () => ({
  getCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
  putCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
}))
vi.mock('@/api/repos', () => ({
  getRepo: (...a: unknown[]) => getRepoMock(...a),
  putRepo: (...a: unknown[]) => putRepoMock(...a),
  moveRepo: vi.fn(),
  deleteRepo: vi.fn(),
  listRepos: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))
vi.mock('@/api/routing', () => ({
  testRouting: (...a: unknown[]) => testRoutingMock(...a),
}))
vi.mock('@/api/settings', () => ({
  getCooldown: vi.fn().mockResolvedValue({ enabled: true, minimum_allowed_age: '7d', repo_names: {} }),
  putCooldown: vi.fn().mockResolvedValue(undefined),
  listStorages: vi.fn().mockResolvedValue([]),
  putStorage: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))

const RULES = [
  { member: 'maven-central', pattern: '(?!com/acme/|internal/).*' },
  { member: 'jboss', prefix: 'org/jboss/' },
  { member: 'jboss', prefix: 'org/wildfly/' },
]
/** A group as the server stores it: no storage, no anonymous_* keys. */
function storedGroup() {
  return {
    repo: {
      type: 'maven-group',
      members: ['libs-release-local', 'maven-central', 'jboss'],
      routing: RULES.map(r => ({ ...r })),
    },
  }
}

type Vm = { dirty: boolean; save: () => Promise<void>; reset: () => void }
type Sent = { repo: Record<string, unknown> }

let wrapper: VueWrapper | null = null

async function mountView(name = 'libs-release') {
  wrapper = mount(RepoEditView, {
    props: { name },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
  })
  await flushPromises()
  return wrapper
}

function sent(call = 0): Sent {
  return putRepoMock.mock.calls[call][1] as Sent
}

async function editBaseUrl(w: VueWrapper, value: string) {
  const input = w.findAll('input').find(
    i => (i.element as HTMLInputElement).placeholder?.includes('packages.example.com'),
  )
  if (!input) throw new Error('base URL input not found')
  await input.setValue(value)
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

async function typeInBody(testid: string, value: string) {
  const input = inBody<HTMLInputElement>(testid)
  if (!input) throw new Error(`${testid} not found`)
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

/** The member picker of the rule editor; the storage picker of the form is a Select too. */
function memberPicker(w: VueWrapper) {
  const picker = w.findAllComponents(Select).find(s => s.attributes('data-testid') === 'routing-editor-member')
  if (!picker) throw new Error('the rule editor is not open')
  return picker
}

async function save(w: VueWrapper) {
  await w.find('[data-testid="save-btn"]').trigger('click')
  await flushPromises()
}

function shownRules(w: VueWrapper) {
  return w.findAll('[data-testid="routing-rule-row"]').map(row => [
    row.find('[data-testid="routing-rule-number"]').text(),
    row.find('[data-testid="routing-rule-member"]').text(),
    row.find('[data-testid="routing-rule-kind"]').text(),
    row.find('[data-testid="routing-rule-value"]').text(),
  ].join(' | '))
}

function rowProblems(w: VueWrapper): Record<string, string> {
  const out: Record<string, string> = {}
  for (const el of w.findAll('[data-testid="routing-rule-problem"]')) {
    out[el.attributes('data-rule') ?? '?'] = el.text()
  }
  return out
}

beforeEach(() => {
  setActivePinia(createPinia())
  testRoutingMock.mockReset().mockImplementation((_name: string, body: { members?: string[] }) =>
    Promise.resolve({ members: (body.members ?? []).map(member => ({ member, through: '' })), inherited: [] }))
  const auth = useAuthStore()
  auth.user = {
    name: 't', context: 'c',
    permissions: { api_cooldown_permissions: ['read', 'write'], api_repository_permissions: ['read', 'update'] },
  } as unknown as typeof auth.user
  getRepoMock.mockReset().mockResolvedValue(storedGroup())
  putRepoMock.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('RepoEditView: routing rules the admin did not touch', () => {
  it('sends the loaded rules back unchanged when another field is saved', async () => {
    const w = await mountView()
    expect((w.vm as unknown as Vm).dirty, 'loading a group with rules is not an edit').toBe(false)

    await editBaseUrl(w, 'https://packages.example.com/libs-release')
    await w.find('[data-testid="save-btn"]').trigger('click')
    await flushPromises()

    expect(putRepoMock, 'one save').toHaveBeenCalledTimes(1)
    expect(putRepoMock.mock.calls[0][0], 'saved under its name').toBe('libs-release')
    expect(sent().repo.routing, 'the rules, in their order').toEqual(RULES)
    expect(sent().repo.members, 'the members, in their order')
      .toEqual(['libs-release-local', 'maven-central', 'jboss'])
  })

  it('sends them back unchanged from a form nobody touched', async () => {
    const w = await mountView()
    await (w.vm as unknown as Vm).save()
    await flushPromises()

    expect(sent().repo.routing).toEqual(RULES)
  })

  it('adds no routing key to a group that has none', async () => {
    getRepoMock.mockResolvedValue({ repo: { type: 'maven-group', members: ['a', 'b'] } })
    const w = await mountView()
    await editBaseUrl(w, 'https://packages.example.com/g')
    await w.find('[data-testid="save-btn"]').trigger('click')
    await flushPromises()

    expect('routing' in sent().repo).toBe(false)
  })
})

describe('RepoEditView: the Routing rules card of a group', () => {
  it('shows the rules the repository was loaded with', async () => {
    const w = await mountView()
    expect(shownRules(w)).toEqual([
      '1 | maven-central | Pattern | (?!com/acme/|internal/).*',
      '2 | jboss | Prefix | org/jboss/',
      '3 | jboss | Prefix | org/wildfly/',
    ])
  })

  it('is absent for a repository that is no group', async () => {
    getRepoMock.mockResolvedValue({
      repo: { type: 'maven-proxy', storage: { type: 'fs', path: '/var/pantera/data' }, remotes: [{ url: 'https://repo1.maven.org/maven2' }] },
    })
    const w = await mountView('maven-central')
    expect(w.find('[data-testid="routing-card"]').exists()).toBe(false)
    expect(testRoutingMock).not.toHaveBeenCalled()
  })

  it('saves an added rule after the loaded ones', async () => {
    const w = await mountView()
    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()
    memberPicker(w).vm.$emit('update:modelValue', 'libs-release-local')
    await typeInBody('routing-editor-value', 'com/acme/')
    await clickInBody('routing-editor-submit')
    expect((w.vm as unknown as Vm).dirty, 'a new rule is an unsaved change').toBe(true)

    await save(w)
    expect(sent().repo.routing).toEqual([...RULES, { member: 'libs-release-local', prefix: 'com/acme/' }])
    expect((w.vm as unknown as Vm).dirty, 'saved').toBe(false)
  })

  it('saves an edited rule in its place', async () => {
    const w = await mountView()
    await w.findAll('[data-testid="routing-rule-edit"]')[2].trigger('click')
    await flushPromises()
    await typeInBody('routing-editor-value', 'org/wildfly/core/')
    await clickInBody('routing-editor-submit')

    await save(w)
    expect(sent().repo.routing).toEqual([RULES[0], RULES[1], { member: 'jboss', prefix: 'org/wildfly/core/' }])
  })

  it('saves the list without a removed rule', async () => {
    const w = await mountView()
    await w.findAll('[data-testid="routing-rule-remove"]')[0].trigger('click')
    await flushPromises()

    await save(w)
    expect(sent().repo.routing).toEqual([RULES[1], RULES[2]])
    expect(sent().repo.members, 'the members are untouched').toEqual(['libs-release-local', 'maven-central', 'jboss'])
  })

  it('saves no routing key when every rule was removed', async () => {
    const w = await mountView()
    for (let left = 3; left > 0; left -= 1) {
      await w.find('[data-testid="routing-rule-remove"]').trigger('click')
      await flushPromises()
    }
    await save(w)
    expect('routing' in sent().repo).toBe(false)
  })

  it('puts the loaded rules back on Discard', async () => {
    const w = await mountView()
    await w.findAll('[data-testid="routing-rule-remove"]')[0].trigger('click')
    await flushPromises()
    expect(shownRules(w)).toHaveLength(2)

    await w.find('[data-testid="reset-btn"]').trigger('click')
    await flushPromises()
    expect(shownRules(w)).toHaveLength(3)
    expect((w.vm as unknown as Vm).dirty).toBe(false)
  })
})

describe('RepoEditView: a member that has routing rules is removed', () => {
  it('saves the group without the member and without its rules after the confirmation', async () => {
    const w = await mountView()
    await w.find('button[aria-label="Remove jboss"]').trigger('click')
    await flushPromises()
    expect(inBody('member-remove-dialog')!.textContent).toContain('2 routing rules')
    await clickInBody('member-remove-confirm')

    await save(w)
    expect(sent().repo.members).toEqual(['libs-release-local', 'maven-central'])
    expect(sent().repo.routing).toEqual([RULES[0]])
  })

  it('changes nothing when the confirmation is cancelled', async () => {
    const w = await mountView()
    await w.find('button[aria-label="Remove jboss"]').trigger('click')
    await flushPromises()
    await clickInBody('member-remove-cancel')

    expect((w.vm as unknown as Vm).dirty, 'nothing to save').toBe(false)
    expect(shownRules(w)).toHaveLength(3)
  })
})

describe('RepoEditView: a save the server refuses for its routing rules', () => {
  const REFUSAL = {
    response: {
      status: 400,
      data: {
        error: 'BAD_REQUEST',
        message: "Invalid routing rules: rule 1: 'maven-central' is not a member of the group; "
          + 'rule 3: pattern is not a valid regular expression: Unclosed character class',
        status: 400,
      },
    },
  }

  it('shows each problem on its rule, stays unsaved and reports no success', async () => {
    putRepoMock.mockRejectedValue(REFUSAL)
    const w = await mountView()
    await editBaseUrl(w, 'https://packages.example.com/libs-release')
    await save(w)

    expect(rowProblems(w)).toEqual({
      1: "'maven-central' is not a member of the group",
      3: 'pattern is not a valid regular expression: Unclosed character class',
    })
    expect((w.vm as unknown as Vm).dirty, 'the changes are not saved').toBe(true)
    expect(w.find('[data-testid="dirty-note"]').exists(), 'the save bar says so').toBe(true)
    expect(w.find('[data-testid="save-error"]').text()).toContain('Invalid routing rules')
    const toasts = useNotificationStore().toasts
    expect(toasts.some(t => t.severity === 'success'), 'no success toast').toBe(false)
    expect(toasts.some(t => t.severity === 'error'), 'an error toast').toBe(true)
  })

  it('saves once the rules are fixed', async () => {
    putRepoMock.mockRejectedValueOnce(REFUSAL)
    const w = await mountView()
    await editBaseUrl(w, 'https://packages.example.com/libs-release')
    await save(w)
    expect(Object.keys(rowProblems(w))).toEqual(['1', '3'])

    await w.findAll('[data-testid="routing-rule-remove"]')[2].trigger('click')
    await w.findAll('[data-testid="routing-rule-remove"]')[0].trigger('click')
    await flushPromises()
    expect(rowProblems(w), 'the old problems are gone with the old rules').toEqual({})
    await save(w)

    expect(putRepoMock).toHaveBeenCalledTimes(2)
    expect(sent(1).repo.routing).toEqual([RULES[1]])
    expect((w.vm as unknown as Vm).dirty).toBe(false)
    expect(w.find('[data-testid="save-error"]').exists()).toBe(false)
  })
})

describe('RepoEditView: test a path', () => {
  it('asks the server with the unsaved members and rules of the page', async () => {
    const w = await mountView()
    // Unsaved: maven-central leaves the group with its rule.
    await w.find('button[aria-label="Remove maven-central"]').trigger('click')
    await flushPromises()
    await clickInBody('member-remove-confirm')
    testRoutingMock.mockClear()
    testRoutingMock.mockResolvedValue({
      path: 'org/jboss/x/1/x-1.pom',
      members: [
        { member: 'libs-release-local', through: '', asked: true, rule: 0, why: 'no rules: asked for every path' },
        { member: 'jboss', through: '', asked: true, rule: 1, why: 'rule 1 matches' },
      ],
      inherited: [],
    })

    await w.find('[data-testid="routing-test-path"]').setValue('org/jboss/x/1/x-1.pom')
    await w.find('[data-testid="routing-test-path"]').trigger('keydown.enter')
    await flushPromises()

    expect(testRoutingMock).toHaveBeenCalledWith('libs-release', {
      path: 'org/jboss/x/1/x-1.pom',
      members: ['libs-release-local', 'jboss'],
      routing: [RULES[1], RULES[2]],
    })
    expect(w.findAll('[data-testid="routing-test-verdict"]').map(v => v.text())).toEqual(['Asked', 'Asked'])
    expect(putRepoMock, 'a test saves nothing').not.toHaveBeenCalled()
  })
})
