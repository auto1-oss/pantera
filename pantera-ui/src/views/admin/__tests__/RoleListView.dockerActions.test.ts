import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RoleListView from '../RoleListView.vue'

vi.mock('@/api/roles', () => ({
  listRoles: () => Promise.resolve({ items: [], total: 0 }),
  getRole: () => Promise.resolve({ name: 'devs', permissions: {} }),
  deleteRole: vi.fn(),
  enableRole: vi.fn(),
  disableRole: vi.fn(),
  putRole: vi.fn(),
}))

vi.mock('@/api/repos', () => ({
  listRepos: () => Promise.resolve({ items: [], total: 0 }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasAction: () => true, isAdmin: true }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const GLOBAL = {
  plugins: [[PrimeVue, { theme: { preset: Aura } }]] as never,
  stubs: { 'router-link': true, AppLayout: { template: '<div><slot /></div>' } },
}

interface DockerRepoEntry { repo: string; image: string; actions: string[] }
interface RepoEntry { name: string; actions: string[] }
type Vm = {
  openCreateRole: () => void
  addDockerRepoEntry: () => void
  dockerRepoEntries: DockerRepoEntry[]
  repoEntries: RepoEntry[]
  buildPermissions: () => Record<string, unknown>
  loadPermissionsIntoForm: (perms: Record<string, unknown>) => void
}

let wrapper: VueWrapper | null = null

async function mountWithDockerEntry(actions: string[]): Promise<Vm> {
  wrapper = mount(RoleListView, { attachTo: document.body, global: GLOBAL })
  await flushPromises()
  const vm = wrapper.vm as unknown as Vm
  vm.openCreateRole()
  vm.addDockerRepoEntry()
  vm.dockerRepoEntries[0].repo = 'docker-local'
  vm.dockerRepoEntries[0].actions = actions
  await flushPromises()
  return vm
}

function dockerActions(vm: Vm): unknown {
  const perms = vm.buildPermissions()['docker_repository_permissions'] as
    Record<string, Record<string, string[]>>
  return perms['docker-local']['*']
}

describe('RoleListView — docker image actions', () => {
  beforeEach(() => setActivePinia(createPinia()))

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('offers a delete checkbox for docker image access', async () => {
    await mountWithDockerEntry([])
    expect(document.getElementById('dr_0delete')).not.toBeNull()
  })

  it('does not collapse pull+push+overwrite to * (that would grant delete)', async () => {
    const vm = await mountWithDockerEntry(['pull', 'push', 'overwrite'])
    expect(dockerActions(vm)).toEqual(['pull', 'push', 'overwrite'])
  })

  it('collapses to * only when all four actions incl. delete are selected', async () => {
    const vm = await mountWithDockerEntry(['pull', 'push', 'overwrite', 'delete'])
    expect(dockerActions(vm)).toEqual(['*'])
  })

  it('expands a stored * to all four docker actions on load', async () => {
    const vm = await mountWithDockerEntry([])
    vm.loadPermissionsIntoForm({
      docker_repository_permissions: { 'docker-local': { '*': ['*'] } },
    })
    expect(vm.dockerRepoEntries[0].actions).toEqual(['pull', 'push', 'overwrite', 'delete'])
  })

  it('adapter basic permissions already offer read/write/delete', async () => {
    const vm = await mountWithDockerEntry([])
    vm.repoEntries.push({ name: 'maven-local', actions: [] })
    await flushPromises()
    for (const a of ['read', 'write', 'delete']) {
      expect(document.getElementById('re_0' + a), `missing ${a}`).not.toBeNull()
    }
  })
})
