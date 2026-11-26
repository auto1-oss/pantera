import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import RepoRoutingRules from '../RepoRoutingRules.vue'
import type { RoutingRule } from '@/types/repo'

const testRoutingMock = vi.fn()
const listReposMock = vi.fn()

vi.mock('@/api/routing', () => ({
  testRouting: (...a: unknown[]) => testRoutingMock(...a),
}))
vi.mock('@/api/repos', () => ({
  listRepos: (...a: unknown[]) => listReposMock(...a),
}))

const MEMBERS = ['libs-release-local', 'maven-central', 'jboss']
const RULES: RoutingRule[] = [
  { member: 'maven-central', pattern: '(?!com/acme/|internal/).*' },
  { member: 'jboss', prefix: 'org/jboss/' },
  { member: 'jboss', prefix: 'org/wildfly/' },
]

/** What the server answers when the rules are fine and no path is sent. */
function listed(members: string[] = MEMBERS, inherited: unknown[] = []) {
  return { members: members.map(member => ({ member, through: '' })), inherited }
}

function refusal(message: string, status = 400) {
  return { response: { status, data: { error: 'BAD_REQUEST', message, status } } }
}

let wrapper: VueWrapper | null = null

/** Mounted the way the form uses it: `v-model:rules` writes the prop back. */
async function mountCard(props: Partial<{ repoName: string; members: string[]; rules: unknown[]; saveError: string }> = {}) {
  wrapper = mount(RepoRoutingRules, {
    props: {
      repoName: 'libs-release',
      members: MEMBERS,
      rules: RULES,
      saveError: '',
      ...props,
      'onUpdate:rules': (rules: unknown) => { void wrapper?.setProps({ rules }) },
    } as never,
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
  })
  await flushPromises()
  return wrapper
}

function lastRules(w: VueWrapper): RoutingRule[] {
  const events = w.emitted('update:rules') as Array<[RoutingRule[]]> | undefined
  if (!events || events.length === 0) throw new Error('no update:rules emitted')
  return events[events.length - 1][0]
}

function inBody<T extends HTMLElement = HTMLElement>(testid: string): T | null {
  return document.querySelector(`[data-testid="${testid}"]`) as T | null
}

async function typeInBody(testid: string, value: string) {
  const input = inBody<HTMLInputElement>(testid)
  if (!input) throw new Error(`${testid} not found`)
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

async function clickInBody(testid: string) {
  const el = inBody<HTMLButtonElement>(testid)
  if (!el) throw new Error(`${testid} not found`)
  el.click()
  await flushPromises()
}

/** The editor is a dialog: its PrimeVue pickers are driven through their components. */
async function pickMember(w: VueWrapper, name: string) {
  w.findComponent(Select).vm.$emit('update:modelValue', name)
  await flushPromises()
}

async function pickKind(w: VueWrapper, kind: 'prefix' | 'pattern') {
  w.findComponent(SelectButton).vm.$emit('update:modelValue', kind)
  await flushPromises()
}

function rows(w: VueWrapper) {
  return w.findAll('[data-testid="routing-rule-row"]').map(row => ({
    number: row.find('[data-testid="routing-rule-number"]').text(),
    member: row.find('[data-testid="routing-rule-member"]').text(),
    kind: row.find('[data-testid="routing-rule-kind"]').text(),
    value: row.find('[data-testid="routing-rule-value"]').text(),
  }))
}

function rowProblems(w: VueWrapper): Record<string, string> {
  const out: Record<string, string> = {}
  for (const el of w.findAll('[data-testid="routing-rule-problem"]')) {
    out[el.attributes('data-rule') ?? '?'] = el.text()
  }
  return out
}

beforeEach(() => {
  testRoutingMock.mockReset().mockImplementation((_name: string, body: { members?: string[] }) =>
    Promise.resolve(listed(body.members ?? [])))
  listReposMock.mockReset().mockResolvedValue({ items: [], total: 0 })
})

afterEach(() => {
  vi.useRealTimers()
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('RepoRoutingRules: the rules', () => {
  it('lists the rules in their order with number, member, kind and value', async () => {
    const w = await mountCard()
    expect(rows(w)).toEqual([
      { number: '1', member: 'maven-central', kind: 'Pattern', value: '(?!com/acme/|internal/).*' },
      { number: '2', member: 'jboss', kind: 'Prefix', value: 'org/jboss/' },
      { number: '3', member: 'jboss', kind: 'Prefix', value: 'org/wildfly/' },
    ])
    expect(w.find('[data-testid="routing-empty"]').exists()).toBe(false)
  })

  it('says what no rules means', async () => {
    const w = await mountCard({ rules: [] })
    expect(w.find('[data-testid="routing-empty"]').text())
      .toBe('No routing rules: every member is asked for every path.')
    expect(w.findAll('[data-testid="routing-rule-row"]')).toHaveLength(0)
  })

  it('explains the rules and gives the two typical uses', async () => {
    const w = await mountCard({ rules: [] })
    const help = w.find('[data-testid="routing-help"]').text()
    expect(help, 'asked only for matching paths').toMatch(/asked only for the paths its rules match/)
    expect(help, 'a member without rules').toMatch(/member without rules is asked for every path/)
    expect(help, 'no leading slash').toMatch(/without a leading slash/)
    expect(help, 'prefix').toMatch(/the path starts with the text/)
    expect(help, 'pattern').toMatch(/Java regular expression that must match the whole path/)
    expect(help, 'example: internal names').toContain('(?!com/acme/|internal/).*')
    expect(help, 'example: narrow upstream').toContain('org/jboss/')
  })

  it('shows a stored item that is no rule instead of failing', async () => {
    const w = await mountCard({ rules: ['x', { member: 'jboss' }] })
    expect(rows(w)).toEqual([
      { number: '1', member: '—', kind: '—', value: '"x"' },
      { number: '2', member: 'jboss', kind: '—', value: '—' },
    ])
  })
})

describe('RepoRoutingRules: add, edit, remove', () => {
  it('adds a rule at the end', async () => {
    const w = await mountCard()
    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()
    expect(inBody('routing-rule-editor'), 'the editor is open').not.toBeNull()

    await pickMember(w, 'libs-release-local')
    await typeInBody('routing-editor-value', 'com/acme/')
    await clickInBody('routing-editor-submit')

    expect(lastRules(w)).toEqual([...RULES, { member: 'libs-release-local', prefix: 'com/acme/' }])
    expect(inBody('routing-rule-editor'), 'the editor is closed').toBeNull()
    expect(rows(w)[3]).toEqual({ number: '4', member: 'libs-release-local', kind: 'Prefix', value: 'com/acme/' })
  })

  it('adds a pattern rule', async () => {
    const w = await mountCard({ rules: [] })
    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()
    await pickMember(w, 'maven-central')
    await pickKind(w, 'pattern')
    await typeInBody('routing-editor-value', '(?!com/acme/).*')
    await clickInBody('routing-editor-submit')

    expect(lastRules(w)).toEqual([{ member: 'maven-central', pattern: '(?!com/acme/).*' }])
  })

  it('needs a member and a value', async () => {
    const w = await mountCard()
    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()
    await clickInBody('routing-editor-submit')

    expect(w.emitted('update:rules'), 'nothing is added').toBeUndefined()
    expect(inBody('routing-rule-editor'), 'the editor stays open').not.toBeNull()
    expect(inBody('routing-editor-member-error')?.textContent).toContain('Pick a member')
    expect(inBody('routing-editor-value-error')?.textContent).toContain('Type a prefix')

    await pickMember(w, 'jboss')
    await typeInBody('routing-editor-value', '   ')
    await clickInBody('routing-editor-submit')
    expect(w.emitted('update:rules'), 'blanks are no value').toBeUndefined()
    expect(inBody('routing-editor-member-error'), 'the member is fine now').toBeNull()
  })

  it('leaves the pattern to the server: a Java expression JavaScript cannot read is accepted', async () => {
    // Possessive quantifiers and a leading inline flag are Java, not JavaScript.
    const java = '(?i)com/acme/.*+'
    expect(() => new RegExp(java), 'the fixture must be invalid in JavaScript').toThrow()
    const w = await mountCard({ rules: [] })
    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()
    await pickMember(w, 'maven-central')
    await pickKind(w, 'pattern')
    await typeInBody('routing-editor-value', java)
    await clickInBody('routing-editor-submit')

    expect(lastRules(w)).toEqual([{ member: 'maven-central', pattern: java }])
    expect(testRoutingMock, 'the server checks it').toHaveBeenLastCalledWith(
      'libs-release', { members: MEMBERS, routing: [{ member: 'maven-central', pattern: java }] },
    )
  })

  it('edits a rule in its place', async () => {
    const w = await mountCard()
    await w.findAll('[data-testid="routing-rule-edit"]')[1].trigger('click')
    await flushPromises()
    expect(w.findComponent(Select).props('modelValue'), 'the member of rule 2').toBe('jboss')
    expect(w.findComponent(SelectButton).props('modelValue'), 'the kind of rule 2').toBe('prefix')
    expect(inBody<HTMLInputElement>('routing-editor-value')?.value, 'the value of rule 2').toBe('org/jboss/')

    await pickKind(w, 'pattern')
    await typeInBody('routing-editor-value', 'org/(jboss|wildfly)/.*')
    await clickInBody('routing-editor-submit')

    expect(lastRules(w)).toEqual([
      RULES[0],
      { member: 'jboss', pattern: 'org/(jboss|wildfly)/.*' },
      RULES[2],
    ])
  })

  it('changes nothing when the editor is cancelled', async () => {
    const w = await mountCard()
    await w.findAll('[data-testid="routing-rule-edit"]')[0].trigger('click')
    await flushPromises()
    await typeInBody('routing-editor-value', 'changed')
    await clickInBody('routing-editor-cancel')

    expect(w.emitted('update:rules')).toBeUndefined()
    expect(inBody('routing-rule-editor')).toBeNull()
  })

  it('removes a rule and renumbers the rest', async () => {
    const w = await mountCard()
    await w.findAll('[data-testid="routing-rule-remove"]')[0].trigger('click')
    await flushPromises()

    expect(lastRules(w)).toEqual([RULES[1], RULES[2]])
    expect(rows(w).map(r => `${r.number} ${r.value}`)).toEqual(['1 org/jboss/', '2 org/wildfly/'])
  })
})

describe('RepoRoutingRules: which member a rule can name', () => {
  it('offers the members of the form as it is now, in their order', async () => {
    const w = await mountCard({ members: ['a', 'b'], rules: [] })
    await w.setProps({ members: ['a', 'b', 'unsaved'] })
    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()

    expect(w.findComponent(Select).props('options')).toEqual([
      { name: 'a', group: false },
      { name: 'b', group: false },
      { name: 'unsaved', group: false },
    ])
  })

  it('lists a member that is a group disabled, with where its rules go', async () => {
    listReposMock.mockResolvedValue({ items: [{ name: 'thirdparty', type: 'maven-group' }], total: 1 })
    const w = await mountCard({ members: ['libs-release-local', 'thirdparty'], rules: [] })
    expect(listReposMock, 'the groups are looked up').toHaveBeenCalledWith({ mode: 'group', size: 100 })

    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()
    const select = w.findComponent(Select)
    expect(select.props('options')).toEqual([
      { name: 'libs-release-local', group: false },
      { name: 'thirdparty', group: true },
    ])
    expect(select.props('optionDisabled'), 'a group cannot be selected').toBe('group')

    // Open the list: the hint stands on the disabled option.
    await select.find('[role="combobox"]').trigger('click')
    await flushPromises()
    const hint = inBody('routing-editor-group-hint')
    expect(hint?.textContent).toContain('its repositories get their rules in that group')

    // Even when a group gets into the draft, it is not added.
    await pickMember(w, 'thirdparty')
    await typeInBody('routing-editor-value', 'org/')
    await clickInBody('routing-editor-submit')
    expect(w.emitted('update:rules'), 'no rule for a group').toBeUndefined()
    expect(inBody('routing-editor-member-error')?.textContent).toContain('is a group')
  })

  it('knows a nested group from the answer of the server too', async () => {
    testRoutingMock.mockResolvedValue({
      members: [
        { member: 'libs-release-local', through: '' },
        { member: 'maven-central', through: 'remote-repos' },
      ],
      inherited: [],
    })
    const w = await mountCard({ members: ['libs-release-local', 'remote-repos'], rules: [] })
    await w.find('[data-testid="routing-rule-add"]').trigger('click')
    await flushPromises()

    expect(w.findComponent(Select).props('options')).toEqual([
      { name: 'libs-release-local', group: false },
      { name: 'remote-repos', group: true },
    ])
  })
})

describe('RepoRoutingRules: problems the server finds', () => {
  const PROBLEMS = "Invalid routing rules: rule 2: 'jboss' is not a member of the group; "
    + 'rule 3: pattern is not a valid regular expression: Unclosed character class'

  it('checks the unsaved rules without a path when it opens', async () => {
    await mountCard()
    expect(testRoutingMock).toHaveBeenCalledTimes(1)
    expect(testRoutingMock).toHaveBeenCalledWith('libs-release', { members: MEMBERS, routing: RULES })
  })

  it('shows each problem on the row of its rule', async () => {
    testRoutingMock.mockImplementation((_n: string, body: { routing?: unknown[] }) =>
      (body.routing?.length ? Promise.reject(refusal(PROBLEMS)) : Promise.resolve(listed())))
    const w = await mountCard()

    expect(rowProblems(w)).toEqual({
      2: "'jboss' is not a member of the group",
      3: 'pattern is not a valid regular expression: Unclosed character class',
    })
    expect(w.find('[data-testid="routing-problems"]').exists(), 'nothing without a row').toBe(false)
  })

  it('shows a part that names no rule above the table', async () => {
    testRoutingMock.mockImplementation((_n: string, body: { routing?: unknown[] }) =>
      (body.routing?.length
        ? Promise.reject(refusal('Invalid routing rules: routing is a list of rules; rule 9: a rule names a member'))
        : Promise.resolve(listed())))
    const w = await mountCard()

    const general = w.find('[data-testid="routing-problems"]').text()
    expect(general, 'the part without a number').toContain('routing is a list of rules')
    expect(general, 'a rule number that has no row').toContain('rule 9: a rule names a member')
    expect(rowProblems(w)).toEqual({})
  })

  it('checks again at once when the rules change, and clears a problem that is gone', async () => {
    testRoutingMock.mockImplementation((_n: string, body: { routing?: unknown[] }) =>
      (body.routing?.length === 3 ? Promise.reject(refusal(PROBLEMS)) : Promise.resolve(listed())))
    const w = await mountCard()
    expect(Object.keys(rowProblems(w))).toEqual(['2', '3'])

    await w.findAll('[data-testid="routing-rule-remove"]')[2].trigger('click')
    await flushPromises()

    expect(testRoutingMock).toHaveBeenLastCalledWith('libs-release', { members: MEMBERS, routing: [RULES[0], RULES[1]] })
    expect(rowProblems(w)).toEqual({})
  })

  it('checks again when the members change, once the typing stops', async () => {
    vi.useFakeTimers()
    const w = await mountCard()
    testRoutingMock.mockClear()

    await w.setProps({ members: ['libs-release-local', 'maven-central', 'jbos'] })
    await w.setProps({ members: ['libs-release-local', 'maven-central', 'jboss-eap'] })
    await flushPromises()
    expect(testRoutingMock, 'not on every keystroke').not.toHaveBeenCalled()

    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(testRoutingMock).toHaveBeenCalledTimes(1)
    expect(testRoutingMock).toHaveBeenCalledWith(
      'libs-release', { members: ['libs-release-local', 'maven-central', 'jboss-eap'], routing: RULES },
    )
  })

  it('shows the problems of a refused save on their rows', async () => {
    const w = await mountCard()
    expect(rowProblems(w)).toEqual({})

    await w.setProps({ saveError: PROBLEMS })
    expect(rowProblems(w)).toEqual({
      2: "'jboss' is not a member of the group",
      3: 'pattern is not a valid regular expression: Unclosed character class',
    })

    // A save that failed for another reason says nothing about the rules.
    await w.setProps({ saveError: 'storage path outside approved roots' })
    expect(Object.keys(rowProblems(w)), 'the rule problems stay until the rules change').toEqual(['2', '3'])
  })

  it('says when the rules could not be checked, and checks again on request', async () => {
    testRoutingMock.mockRejectedValue(refusal('boom', 500))
    const w = await mountCard()
    expect(w.find('[data-testid="routing-check-error"]').text()).toContain('boom')

    testRoutingMock.mockReset().mockResolvedValue(listed())
    await w.find('[data-testid="routing-check-retry"]').trigger('click')
    await flushPromises()
    expect(testRoutingMock).toHaveBeenCalledTimes(1)
    expect(w.find('[data-testid="routing-check-error"]').exists()).toBe(false)
  })

  it('asks the server nothing while the repository has no name', async () => {
    const w = await mountCard({ repoName: '' })
    expect(testRoutingMock).not.toHaveBeenCalled()
    expect(w.find('[data-testid="routing-no-name"]').exists()).toBe(true)
    expect(w.find('[data-testid="routing-test-btn"]').attributes('disabled')).toBeDefined()

    await w.setProps({ repoName: 'new-group' })
    await flushPromises()
    expect(testRoutingMock).toHaveBeenCalledWith('new-group', { members: MEMBERS, routing: RULES })
  })
})

describe('RepoRoutingRules: rules of nested groups', () => {
  const INHERITED = [
    { group: 'thirdparty', member: 'maven-central', pattern: '(?!org/jboss/).*' },
    { group: 'thirdparty', member: 'atlassian', prefix: 'com/atlassian/' },
  ]

  function inheritedRows(w: VueWrapper) {
    return w.findAll('[data-testid="routing-inherited-row"]').map(r => r.findAll('td').map(td => td.text()))
  }

  it('lists them read-only and says where they are changed', async () => {
    testRoutingMock.mockResolvedValue(listed(MEMBERS, INHERITED))
    const w = await mountCard()

    const section = w.find('[data-testid="routing-inherited"]')
    expect(section.text()).toContain('They are changed in that group')
    expect(inheritedRows(w)).toEqual([
      ['thirdparty', 'maven-central', 'Pattern', '(?!org/jboss/).*'],
      ['thirdparty', 'atlassian', 'Prefix', 'com/atlassian/'],
    ])
    expect(section.findAll('button'), 'nothing to edit or remove').toHaveLength(0)
  })

  it('is absent when no nested group has rules', async () => {
    const w = await mountCard()
    expect(w.find('[data-testid="routing-inherited"]').exists()).toBe(false)
  })

  it('still loads them when the rules of this group are refused', async () => {
    testRoutingMock.mockImplementation((_n: string, body: { routing?: unknown[] }) =>
      (body.routing?.length
        ? Promise.reject(refusal('Invalid routing rules: rule 1: a rule names a member'))
        : Promise.resolve(listed(MEMBERS, INHERITED))))
    const w = await mountCard()

    expect(testRoutingMock.mock.calls.map(c => c[1])).toEqual([
      { members: MEMBERS, routing: RULES },
      { members: MEMBERS, routing: [] },
    ])
    expect(inheritedRows(w)).toHaveLength(2)
    expect(Object.keys(rowProblems(w))).toEqual(['1'])
  })

  it('loads them again when the members change', async () => {
    vi.useFakeTimers()
    const w = await mountCard({ members: ['libs-release-local'], rules: [] })
    expect(w.find('[data-testid="routing-inherited"]').exists()).toBe(false)

    testRoutingMock.mockResolvedValue(listed(['libs-release-local'], INHERITED))
    await w.setProps({ members: ['libs-release-local', 'thirdparty'] })
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(testRoutingMock).toHaveBeenLastCalledWith(
      'libs-release', { members: ['libs-release-local', 'thirdparty'], routing: [] },
    )
    expect(inheritedRows(w)).toHaveLength(2)
  })
})

describe('RepoRoutingRules: test a path', () => {
  const PATH = 'org/jboss/x/1/x-1.pom'
  const ANSWER = {
    path: PATH,
    members: [
      { member: 'libs-release-local', through: '', asked: true, rule: 0, why: 'no rules: asked for every path' },
      { member: 'maven-central', through: 'thirdparty', asked: false, rule: 0, why: "no rule of the group 'thirdparty' matches: skipped" },
      { member: 'jboss', through: '', asked: true, rule: 2, why: 'rule 2 matches' },
    ],
    inherited: [{ group: 'thirdparty', member: 'maven-central', pattern: '(?!org/jboss/).*' }],
  }

  function steps(w: VueWrapper) {
    return w.findAll('[data-testid="routing-test-step"]').map(step => ({
      member: step.find('[data-testid="routing-test-member"]').text(),
      verdict: step.find('[data-testid="routing-test-verdict"]').text(),
      through: step.find('[data-testid="routing-test-through"]').exists()
        ? step.find('[data-testid="routing-test-through"]').text() : '',
      why: step.find('[data-testid="routing-test-why"]').text(),
    }))
  }

  function answerPaths() {
    testRoutingMock.mockImplementation((_n: string, body: { path?: string; members?: string[] }) =>
      Promise.resolve(body.path ? ANSWER : listed(body.members ?? [])))
  }

  it('sends the path with the members and rules of the form, on Enter', async () => {
    answerPaths()
    const w = await mountCard()
    testRoutingMock.mockClear()

    await w.find('[data-testid="routing-test-path"]').setValue(`  ${PATH} `)
    await w.find('[data-testid="routing-test-path"]').trigger('keydown.enter')
    await flushPromises()

    expect(testRoutingMock).toHaveBeenCalledTimes(1)
    expect(testRoutingMock).toHaveBeenCalledWith('libs-release', { path: PATH, members: MEMBERS, routing: RULES })
  })

  it('shows every repository in the order asked: asked or skipped, why, and its nested group', async () => {
    answerPaths()
    const w = await mountCard()
    await w.find('[data-testid="routing-test-path"]').setValue(PATH)
    await w.find('[data-testid="routing-test-btn"]').trigger('click')
    await flushPromises()

    expect(steps(w)).toEqual([
      { member: 'libs-release-local', verdict: 'Asked', through: '', why: 'no rules: asked for every path' },
      { member: 'maven-central', verdict: 'Skipped', through: 'through thirdparty', why: "no rule of the group 'thirdparty' matches: skipped" },
      { member: 'jboss', verdict: 'Asked', through: '', why: 'rule 2 matches' },
    ])
    expect(w.find('[data-testid="routing-test-result"]').text(), 'the path that was decided').toContain(PATH)
  })

  it('tests the unsaved state: a rule added and a member taken out are in the request', async () => {
    answerPaths()
    const w = await mountCard()
    await w.findAll('[data-testid="routing-rule-remove"]')[0].trigger('click')
    await w.setProps({ members: ['libs-release-local', 'jboss'] })
    await flushPromises()
    testRoutingMock.mockClear()

    await w.find('[data-testid="routing-test-path"]').setValue(PATH)
    await w.find('[data-testid="routing-test-path"]').trigger('keydown.enter')
    await flushPromises()

    expect(testRoutingMock).toHaveBeenCalledWith(
      'libs-release', { path: PATH, members: ['libs-release-local', 'jboss'], routing: [RULES[1], RULES[2]] },
    )
  })

  it('sends nothing for an empty path', async () => {
    const w = await mountCard()
    testRoutingMock.mockClear()
    expect(w.find('[data-testid="routing-test-btn"]').attributes('disabled')).toBeDefined()
    await w.find('[data-testid="routing-test-path"]').trigger('keydown.enter')
    await flushPromises()
    expect(testRoutingMock).not.toHaveBeenCalled()
  })

  it('drops a result when the rules change after it', async () => {
    answerPaths()
    const w = await mountCard()
    await w.find('[data-testid="routing-test-path"]').setValue(PATH)
    await w.find('[data-testid="routing-test-btn"]').trigger('click')
    await flushPromises()
    expect(steps(w)).toHaveLength(3)

    await w.findAll('[data-testid="routing-rule-remove"]')[0].trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="routing-test-result"]').exists(), 'it was for other rules').toBe(false)
  })

  it('puts the problems of refused rules on their rows and shows no result', async () => {
    const w = await mountCard()
    testRoutingMock.mockImplementation((_n: string, body: { path?: string }) =>
      (body.path
        ? Promise.reject(refusal("Invalid routing rules: rule 1: 'maven-central' is not a member of the group"))
        : Promise.resolve(listed())))
    await w.find('[data-testid="routing-test-path"]').setValue(PATH)
    await w.find('[data-testid="routing-test-btn"]').trigger('click')
    await flushPromises()

    expect(rowProblems(w)).toEqual({ 1: "'maven-central' is not a member of the group" })
    expect(w.find('[data-testid="routing-test-result"]').exists()).toBe(false)
    expect(w.find('[data-testid="routing-test-error"]').text()).toContain('Fix the rules')
  })

  it('shows why a test failed for another reason', async () => {
    const w = await mountCard()
    testRoutingMock.mockRejectedValue(refusal('`path` is a string'))
    await w.find('[data-testid="routing-test-path"]').setValue(PATH)
    await w.find('[data-testid="routing-test-btn"]').trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="routing-test-error"]').text()).toContain('`path` is a string')
  })
})
