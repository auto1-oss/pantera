import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import AutoComplete from 'primevue/autocomplete'
import RepoConfigForm from '../RepoConfigForm.vue'
import RepoRoutingRules from '../RepoRoutingRules.vue'
import type { RepoConfigEnvelope } from '@/types/repo'

const testRoutingMock = vi.fn()

vi.mock('@/api/settings', () => ({
  listStorages: vi.fn().mockResolvedValue([]),
  putStorage: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/api/repos', () => ({
  listRepos: vi.fn().mockResolvedValue({
    items: [], page: 0, size: 20, total: 0, hasMore: false,
  }),
  putRepo: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/api/routing', () => ({
  testRouting: (...a: unknown[]) => testRoutingMock(...a),
}))

const MEMBERS = ['libs-release-local', 'maven-central', 'jboss']
const RULES = [
  { member: 'maven-central', pattern: '(?!com/acme/|internal/).*' },
  { member: 'jboss', prefix: 'org/jboss/' },
  { member: 'jboss', prefix: 'org/wildfly/' },
]

function group(extra: Record<string, unknown> = {}): RepoConfigEnvelope {
  return {
    repo: { type: 'maven-group', members: [...MEMBERS], routing: RULES.map(r => ({ ...r })), ...extra },
  }
}

let wrapper: VueWrapper | null = null

async function mountForm(initialConfig: RepoConfigEnvelope, props: Record<string, unknown> = {}) {
  wrapper = mount(RepoConfigForm, {
    props: { config: null, initialConfig, readOnlyType: true, repoName: 'libs-release', ...props },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
  })
  await flushPromises()
  return wrapper
}

function lastEmittedRepo(w: VueWrapper) {
  const events = w.emitted('update:config') as Array<[RepoConfigEnvelope]> | undefined
  if (!events || events.length === 0) throw new Error('RepoConfigForm did not emit update:config')
  return events[events.length - 1][0].repo
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

async function removeMember(w: VueWrapper, name: string, nth = 0) {
  await w.findAll(`button[aria-label="Remove ${name}"]`)[nth].trigger('click')
  await flushPromises()
}

function ruleValues(w: VueWrapper) {
  return w.findAll('[data-testid="routing-rule-value"]').map(v => v.text())
}

beforeEach(() => {
  setActivePinia(createPinia())
  testRoutingMock.mockReset().mockImplementation((_name: string, body: { members?: string[] }) =>
    Promise.resolve({ members: (body.members ?? []).map(member => ({ member, through: '' })), inherited: [] }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('RepoConfigForm: the Routing rules card', () => {
  it('is shown for a group', async () => {
    const w = await mountForm(group())
    expect(w.find('[data-testid="routing-card"]').exists()).toBe(true)
  })

  const NOT_GROUPS: Array<[string, RepoConfigEnvelope['repo']]> = [
    ['a hosted repository', { type: 'maven', storage: { type: 'fs', path: '/var/pantera/data' } }],
    ['a proxy', { type: 'maven-proxy', storage: { type: 'fs', path: '/var/pantera/data' }, remotes: [{ url: 'https://repo1.maven.org/maven2' }] }],
  ]

  it.each(NOT_GROUPS)('is absent for %s', async (_what, repo) => {
    const w = await mountForm({ repo })
    expect(w.find('[data-testid="routing-card"]').exists()).toBe(false)
    expect(testRoutingMock).not.toHaveBeenCalled()
  })

  it('shows the stored rules and sends them back as they were loaded', async () => {
    const w = await mountForm(group())
    expect(ruleValues(w)).toEqual(['(?!com/acme/|internal/).*', 'org/jboss/', 'org/wildfly/'])
    expect(lastEmittedRepo(w).routing).toEqual(RULES)
  })

  it('sends a stored value it cannot show back untouched', async () => {
    // Not a list: the form shows no rules and must not replace the value by a guess.
    const w = await mountForm(group({ routing: null }))
    expect(w.find('[data-testid="routing-empty"]').exists()).toBe(true)
    expect(lastEmittedRepo(w).routing).toBeNull()
  })

  it('checks the rules under the name of the repository, with the members of the form', async () => {
    await mountForm(group(), { repoName: 'libs-release' })
    expect(testRoutingMock).toHaveBeenCalledWith('libs-release', { members: MEMBERS, routing: RULES })
  })

  it('emits a changed list of rules', async () => {
    const w = await mountForm(group())
    await w.findAll('[data-testid="routing-rule-remove"]')[1].trigger('click')
    await flushPromises()
    expect(lastEmittedRepo(w).routing).toEqual([RULES[0], RULES[2]])
  })

  it('drops the routing key when the last rule is removed', async () => {
    const w = await mountForm(group({ routing: [{ member: 'jboss', prefix: 'org/jboss/' }] }))
    await w.find('[data-testid="routing-rule-remove"]').trigger('click')
    await flushPromises()
    expect('routing' in lastEmittedRepo(w), 'no empty list is saved').toBe(false)
    expect(w.find('[data-testid="routing-empty"]').exists()).toBe(true)
  })

  it('gives the card the members as they are in the form, unsaved ones included', async () => {
    const w = await mountForm(group())
    await w.findAll('button').find(b => b.text() === 'Add member')!.trigger('click')
    await flushPromises()
    const card = w.findComponent(RepoRoutingRules)
    expect(card.props('members'), 'an empty row is no member').toEqual(MEMBERS)

    const added = w.findAllComponents(AutoComplete).at(-1)!
    added.vm.$emit('update:modelValue', 'spring-plugins')
    await flushPromises()
    expect(card.props('members')).toEqual([...MEMBERS, 'spring-plugins'])
  })

  it('shows the rule problems of a refused save on their rows', async () => {
    const w = await mountForm(group())
    await w.setProps({ saveError: "Invalid routing rules: rule 2: 'jboss' is not a member of the group" })
    const problem = w.find('[data-testid="routing-rule-problem"]')
    expect(problem.attributes('data-rule')).toBe('2')
    expect(problem.text()).toBe("'jboss' is not a member of the group")
  })
})

describe('RepoConfigForm: removing a member that has routing rules', () => {
  it('removes a member without rules at once', async () => {
    const w = await mountForm(group())
    await removeMember(w, 'libs-release-local')

    expect(inBody('member-remove-dialog'), 'nothing to confirm').toBeNull()
    expect(lastEmittedRepo(w).members).toEqual(['maven-central', 'jboss'])
    expect(lastEmittedRepo(w).routing).toEqual(RULES)
  })

  it('asks first and names the number of rules that go with it', async () => {
    const w = await mountForm(group())
    await removeMember(w, 'jboss')

    const dialog = inBody('member-remove-dialog')
    expect(dialog, 'the confirmation is open').not.toBeNull()
    expect(dialog!.textContent).toContain('jboss')
    expect(dialog!.textContent).toContain('2 routing rules')
    expect(lastEmittedRepo(w).members, 'nothing is removed yet').toEqual(MEMBERS)
    expect(lastEmittedRepo(w).routing).toEqual(RULES)
  })

  it('says "1 routing rule" for one', async () => {
    const w = await mountForm(group())
    await removeMember(w, 'maven-central')
    expect(inBody('member-remove-dialog')!.textContent).toMatch(/1 routing rule\b(?!s)/)
  })

  it('removes the member and its rules together on confirm', async () => {
    const w = await mountForm(group())
    await removeMember(w, 'jboss')
    await clickInBody('member-remove-confirm')

    expect(inBody('member-remove-dialog'), 'the dialog is closed').toBeNull()
    expect(lastEmittedRepo(w).members).toEqual(['libs-release-local', 'maven-central'])
    expect(lastEmittedRepo(w).routing, 'the rules of the other members stay').toEqual([RULES[0]])
    expect(ruleValues(w)).toEqual(['(?!com/acme/|internal/).*'])
  })

  it('keeps the member and its rules on cancel', async () => {
    const w = await mountForm(group())
    await removeMember(w, 'jboss')
    await clickInBody('member-remove-cancel')

    expect(inBody('member-remove-dialog'), 'the dialog is closed').toBeNull()
    expect(lastEmittedRepo(w).members).toEqual(MEMBERS)
    expect(lastEmittedRepo(w).routing).toEqual(RULES)
    expect(w.findAll('button[aria-label="Remove jboss"]'), 'the member is still listed').toHaveLength(1)
  })

  it('does not ask while the member is listed a second time', async () => {
    const w = await mountForm(group({ members: ['jboss', 'maven-central', 'jboss'] }))
    await removeMember(w, 'jboss', 1)

    expect(inBody('member-remove-dialog'), 'its rules still name a member').toBeNull()
    expect(lastEmittedRepo(w).members).toEqual(['jboss', 'maven-central'])
    expect(lastEmittedRepo(w).routing).toEqual(RULES)
  })

  it('counts the rules as they are now, not as they were loaded', async () => {
    const w = await mountForm(group())
    // Take both rules of jboss out first: then it goes without a question.
    await w.findAll('[data-testid="routing-rule-remove"]')[2].trigger('click')
    await w.findAll('[data-testid="routing-rule-remove"]')[1].trigger('click')
    await flushPromises()
    await removeMember(w, 'jboss')

    expect(inBody('member-remove-dialog')).toBeNull()
    expect(lastEmittedRepo(w).members).toEqual(['libs-release-local', 'maven-central'])
  })
})
