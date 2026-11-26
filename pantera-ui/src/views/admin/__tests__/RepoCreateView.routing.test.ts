import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import AutoComplete from 'primevue/autocomplete'
import Select from 'primevue/select'
import RepoCreateView from '../RepoCreateView.vue'

// This spec mounts the REAL RepoConfigForm: a group that is not saved yet
// gets its routing rules checked under the name typed on the page.
const putRepoMock = vi.fn()
const testRoutingMock = vi.fn()
let typedName = { name: 'new-group', valid: true }

vi.mock('@/api/repos', () => ({
  putRepo: (...a: unknown[]) => putRepoMock(...a),
  repoExists: vi.fn().mockResolvedValue(false),
  listRepos: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))
vi.mock('@/api/routing', () => ({
  testRouting: (...a: unknown[]) => testRoutingMock(...a),
}))
vi.mock('@/api/settings', () => ({ listStorages: vi.fn().mockResolvedValue([]), putStorage: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn(), back: vi.fn() }) }))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))
vi.mock('@/components/admin/RepoNameField.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'RepoNameFieldStub',
      props: { modelValue: { type: String, default: '' } },
      emits: ['update:modelValue', 'valid-change'],
      mounted() {
        this.$emit('update:modelValue', typedName.name)
        this.$emit('valid-change', typedName.valid)
      },
      render: () => h('input', { 'data-testid': 'name' }),
    }),
  }
})

let wrapper: VueWrapper | null = null

async function mountGroupForm() {
  wrapper = mount(RepoCreateView, {
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
  })
  await flushPromises()
  await wrapper.find('[data-testid="format-tab-group"]').trigger('click')
  await wrapper.find('[data-testid="format-card-maven-group"]').trigger('click')
  await flushPromises()
  return wrapper
}

async function addMember(w: VueWrapper, name: string) {
  await w.findAll('button').find(b => b.text() === 'Add member')!.trigger('click')
  await flushPromises()
  w.findAllComponents(AutoComplete).at(-1)!.vm.$emit('update:modelValue', name)
  await flushPromises()
}

async function addRule(w: VueWrapper, member: string, prefix: string) {
  await w.find('[data-testid="routing-rule-add"]').trigger('click')
  await flushPromises()
  w.findAllComponents(Select).find(s => s.attributes('data-testid') === 'routing-editor-member')!
    .vm.$emit('update:modelValue', member)
  await flushPromises()
  const value = document.querySelector('[data-testid="routing-editor-value"]') as HTMLInputElement
  value.value = prefix
  value.dispatchEvent(new Event('input'))
  await flushPromises()
  ;(document.querySelector('[data-testid="routing-editor-submit"]') as HTMLButtonElement).click()
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  typedName = { name: 'new-group', valid: true }
  putRepoMock.mockReset().mockResolvedValue(undefined)
  testRoutingMock.mockReset().mockImplementation((_name: string, body: { members?: string[] }) =>
    Promise.resolve({ members: (body.members ?? []).map(member => ({ member, through: '' })), inherited: [] }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('RepoCreateView: routing rules of a new group', () => {
  it('checks the rules under the name typed on the page and creates the group with them', async () => {
    const w = await mountGroupForm()
    expect(w.find('[data-testid="routing-card"]').exists(), 'a group gets the card').toBe(true)

    await addMember(w, 'jboss')
    await addRule(w, 'jboss', 'org/jboss/')
    expect(testRoutingMock, 'the unsaved members go along: the group is not saved yet').toHaveBeenLastCalledWith(
      'new-group', { members: ['jboss'], routing: [{ member: 'jboss', prefix: 'org/jboss/' }] },
    )

    await w.find('[data-testid="create-btn"]').trigger('click')
    await flushPromises()
    const [name, body] = putRepoMock.mock.calls[0] as [string, { repo: Record<string, unknown> }]
    expect(name).toBe('new-group')
    expect(body.repo.members).toEqual(['jboss'])
    expect(body.repo.routing).toEqual([{ member: 'jboss', prefix: 'org/jboss/' }])
  })

  it('creates a group without a routing key when no rule was added', async () => {
    const w = await mountGroupForm()
    await addMember(w, 'jboss')
    await w.find('[data-testid="create-btn"]').trigger('click')
    await flushPromises()
    const body = putRepoMock.mock.calls[0][1] as { repo: Record<string, unknown> }
    expect('routing' in body.repo).toBe(false)
  })

  it('does not ask the server while the name is not usable', async () => {
    typedName = { name: 'taken', valid: false }
    const w = await mountGroupForm()
    await addMember(w, 'jboss')
    await addRule(w, 'jboss', 'org/jboss/')

    expect(testRoutingMock).not.toHaveBeenCalled()
    expect(w.find('[data-testid="routing-no-name"]').exists()).toBe(true)
  })

  it('shows the rule problems of a refused create on their rows', async () => {
    putRepoMock.mockRejectedValue({
      response: { status: 400, data: { message: "Invalid routing rules: rule 1: 'jboss' is not a member of the group" } },
    })
    const w = await mountGroupForm()
    await addMember(w, 'jboss')
    await addRule(w, 'jboss', 'org/jboss/')
    await w.find('[data-testid="create-btn"]').trigger('click')
    await flushPromises()

    const problem = w.find('[data-testid="routing-rule-problem"]')
    expect(problem.attributes('data-rule')).toBe('1')
    expect(problem.text()).toBe("'jboss' is not a member of the group")
    expect(w.find('[data-testid="create-error"]').text()).toContain('Invalid routing rules')
  })

  it('shows no card for a format that is no group', async () => {
    wrapper = mount(RepoCreateView, {
      attachTo: document.body,
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
    })
    await flushPromises()
    await wrapper.find('[data-testid="format-card-npm"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="routing-card"]').exists()).toBe(false)
  })
})
