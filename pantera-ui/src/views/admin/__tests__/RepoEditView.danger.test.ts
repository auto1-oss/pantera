import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { ComponentPublicInstance } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import RepoEditView from '../RepoEditView.vue'

const getRepoMock = vi.fn()
const moveRepoMock = vi.fn()
const deleteRepoMock = vi.fn()
const pushMock = vi.fn()
const replaceMock = vi.fn()

vi.mock('@/api/cooldown', () => ({
  getCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
  putCooldownExemptions: vi.fn().mockResolvedValue({ repository: 'r', exemptions: [], released: 0 }),
}))
vi.mock('@/api/repos', () => ({
  getRepo: (...a: unknown[]) => getRepoMock(...a),
  putRepo: vi.fn(),
  moveRepo: (...a: unknown[]) => moveRepoMock(...a),
  deleteRepo: (...a: unknown[]) => deleteRepoMock(...a),
}))
vi.mock('@/api/settings', () => ({
  getCooldown: vi.fn().mockResolvedValue({ enabled: true, minimum_allowed_age: '7d', repo_names: {} }),
  putCooldown: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock, back: vi.fn(), replace: replaceMock }),
  onBeforeRouteLeave: () => {},
}))
vi.mock('@/components/layout/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))
vi.mock('@/components/admin/RepoConfigForm.vue', () => ({
  default: {
    name: 'RepoConfigFormStub',
    props: ['config', 'initialConfig', 'readOnlyType'],
    emits: ['update:config', 'valid-change'],
    mounted(this: ComponentPublicInstance) {
      this.$emit('update:config', { repo: { type: 'maven' } })
      this.$emit('valid-change', true)
    },
    template: '<div />',
  },
}))

type Vm = {
  moveTarget: string
  keepOldName: boolean
  keepOldNameDays: number | null
  retentionMode: 'default' | 'now' | 'custom'
  retentionHours: number | null
}

const GROUP_REFUSAL = {
  response: {
    status: 409,
    data: { error: 'CONFLICT', message: "Repository 'mvn' is a member of group(s) g1, g2; remove it from them or delete with force=true" },
  },
}
const JOB = {
  kind: 'job', jobId: 9, repoKey: 'k', purgeAfter: '2026-11-24T10:00:00Z', removedFromGroups: [] as string[], message: '',
}

function seedAuth(actions: string[] = ['update', 'move', 'delete']) {
  const auth = useAuthStore()
  auth.user = {
    name: 't', context: 'c',
    permissions: { api_repository_permissions: actions },
  } as unknown as typeof auth.user
}

async function mountView() {
  const w = mount(RepoEditView, {
    props: { name: 'mvn' },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]], stubs: { 'router-link': true } },
  })
  await flushPromises()
  return w
}

function el<T extends HTMLElement = HTMLElement>(testid: string): T | null {
  return document.querySelector(`[data-testid="${testid}"]`) as T | null
}

async function click(testid: string) {
  el(testid)!.click()
  await flushPromises()
}

function toasts() {
  return useNotificationStore().toasts.map(t => ({ severity: t.severity, summary: t.summary, detail: t.detail }))
}

describe('RepoEditView danger zone', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    setActivePinia(createPinia())
    seedAuth()
    getRepoMock.mockReset(); moveRepoMock.mockReset(); deleteRepoMock.mockReset()
    pushMock.mockReset(); replaceMock.mockReset()
    getRepoMock.mockResolvedValue({ repo: { type: 'maven' } })
  })

  describe('rename', () => {
    it('renames without keeping the old name by default', async () => {
      moveRepoMock.mockResolvedValue({
        name: 'mvn2', previous_name: 'mvn', repo_key: 'k', keep_old_name_days: 0, groups_updated: [], roles_updated: [],
      })
      const w = await mountView()
      await click('rename-btn')
      expect(el('rename-dialog')!.textContent).toContain('instant and moves no data')
      expect(el<HTMLButtonElement>('rename-confirm')!.disabled).toBe(true)
      ;(w.vm as unknown as Vm).moveTarget = 'mvn2'
      await flushPromises()
      await click('rename-confirm')
      expect(moveRepoMock).toHaveBeenCalledWith('mvn', 'mvn2', undefined)
      expect(replaceMock).toHaveBeenCalledWith('/admin/repositories/mvn2/edit')
      expect(toasts()).toEqual([{ severity: 'success', summary: 'Repository renamed', detail: 'mvn → mvn2' }])
      w.unmount()
    })

    it('keeps the old name for 30 days once the box is ticked and says so', async () => {
      moveRepoMock.mockResolvedValue({
        name: 'mvn2', previous_name: 'mvn', repo_key: 'k', keep_old_name_days: 30, groups_updated: [], roles_updated: [],
      })
      const w = await mountView()
      await click('rename-btn')
      const vm = w.vm as unknown as Vm
      vm.moveTarget = 'mvn2'
      await flushPromises()
      const box = document.querySelector('[data-testid="rename-keep-old"] input') as HTMLInputElement
      box.click()
      await flushPromises()
      expect(vm.keepOldName).toBe(true)
      expect(vm.keepOldNameDays).toBe(30)
      await click('rename-confirm')
      expect(moveRepoMock).toHaveBeenCalledWith('mvn', 'mvn2', 30)
      expect(toasts()[0].detail).toBe('mvn → mvn2. The old name keeps working for 30 days.')
      w.unmount()
    })

    it('sends the chosen number of days and refuses one outside 1–365', async () => {
      moveRepoMock.mockResolvedValue(null)
      const w = await mountView()
      await click('rename-btn')
      const vm = w.vm as unknown as Vm
      vm.moveTarget = 'mvn2'
      vm.keepOldName = true
      vm.keepOldNameDays = 400
      await flushPromises()
      expect(el<HTMLButtonElement>('rename-confirm')!.disabled).toBe(true)
      vm.keepOldNameDays = null
      await flushPromises()
      expect(el<HTMLButtonElement>('rename-confirm')!.disabled).toBe(true)
      vm.keepOldNameDays = 7
      await flushPromises()
      await click('rename-confirm')
      expect(moveRepoMock).toHaveBeenCalledWith('mvn', 'mvn2', 7)
      // a server that answers without a body: the toast uses what was asked for
      expect(toasts()[0].detail).toBe('mvn → mvn2. The old name keeps working for 7 days.')
      w.unmount()
    })

    it('shows the 409 message of the server and stays in the dialog', async () => {
      const message = "'mvn2' is the former name of repository 'other' and keeps serving it until 2026-12-01T00:00:00Z"
      moveRepoMock.mockRejectedValue({ response: { status: 409, data: { error: 'CONFLICT', message } } })
      const w = await mountView()
      await click('rename-btn')
      ;(w.vm as unknown as Vm).moveTarget = 'mvn2'
      await flushPromises()
      await click('rename-confirm')
      expect(el('rename-error')!.textContent).toContain(message)
      expect(toasts()).toEqual([{ severity: 'error', summary: 'Failed to rename repository', detail: message }])
      expect(replaceMock).not.toHaveBeenCalled()
      expect(el('rename-dialog')).not.toBeNull()
      w.unmount()
    })
  })

  describe('delete', () => {
    it('explains the delete and uses the server default restore window', async () => {
      deleteRepoMock.mockResolvedValue(JOB)
      const w = await mountView()
      await click('delete-btn')
      const text = el('delete-dialog')!.textContent!
      expect(text).toContain('It disappears at once')
      expect(text).toContain('removed in the background after the restore window')
      expect(text).toContain('can be restored')
      expect(deleteRepoMock).not.toHaveBeenCalled()
      await click('delete-confirm')
      expect(deleteRepoMock).toHaveBeenCalledWith('mvn', {})
      // the job page is where the purge is followed and the delete undone
      expect(pushMock).toHaveBeenCalledWith('/admin/repository-jobs')
      const toast = toasts()[0]
      expect(toast.summary).toBe('Repository deleted')
      expect(toast.detail).toContain('it can be restored until then')
      expect(toast.detail).toContain('Repository Jobs')
      w.unmount()
    })

    it('purges immediately when asked', async () => {
      deleteRepoMock.mockResolvedValue(JOB)
      const w = await mountView()
      await click('delete-btn')
      ;(document.querySelector('[data-testid="delete-retention-now"] input') as HTMLInputElement).click()
      await flushPromises()
      expect((w.vm as unknown as Vm).retentionMode).toBe('now')
      await click('delete-confirm')
      expect(deleteRepoMock).toHaveBeenCalledWith('mvn', { retentionHours: 0 })
      expect(toasts()[0].detail).not.toContain('restored')
      w.unmount()
    })

    it('sends a custom restore window and refuses an empty one', async () => {
      deleteRepoMock.mockResolvedValue(JOB)
      const w = await mountView()
      await click('delete-btn')
      const vm = w.vm as unknown as Vm
      vm.retentionMode = 'custom'
      vm.retentionHours = null
      await flushPromises()
      expect(el<HTMLButtonElement>('delete-confirm')!.disabled).toBe(true)
      // the server refuses more than a year
      vm.retentionHours = 8761
      await flushPromises()
      expect(el<HTMLButtonElement>('delete-confirm')!.disabled).toBe(true)
      vm.retentionHours = 8760
      await flushPromises()
      expect(el<HTMLButtonElement>('delete-confirm')!.disabled).toBe(false)
      vm.retentionHours = 72
      await flushPromises()
      await click('delete-confirm')
      expect(deleteRepoMock).toHaveBeenCalledWith('mvn', { retentionHours: 72 })
      w.unmount()
    })

    it('offers a forced delete after a group-membership 409 and names the groups', async () => {
      deleteRepoMock
        .mockRejectedValueOnce(GROUP_REFUSAL)
        .mockResolvedValueOnce({ ...JOB, removedFromGroups: ['g1', 'g2'] })
      const w = await mountView()
      await click('delete-btn')
      ;(w.vm as unknown as Vm).retentionMode = 'custom'
      ;(w.vm as unknown as Vm).retentionHours = 12
      await flushPromises()
      await click('delete-confirm')
      // nothing was deleted yet: the refusal is shown and force is a separate, explicit step
      expect(deleteRepoMock).toHaveBeenCalledTimes(1)
      expect(deleteRepoMock).toHaveBeenLastCalledWith('mvn', { retentionHours: 12 })
      expect(pushMock).not.toHaveBeenCalled()
      expect(el('delete-blocked')!.textContent).toContain('member of group(s) g1, g2')
      expect(el('delete-confirm')).toBeNull()
      expect(toasts()).toEqual([])
      await click('delete-force-confirm')
      // the restore window chosen before is kept
      expect(deleteRepoMock).toHaveBeenLastCalledWith('mvn', { force: true, retentionHours: 12 })
      expect(pushMock).toHaveBeenCalledWith('/admin/repository-jobs')
      expect(toasts()[0].detail).toContain('Removed from group(s) g1, g2.')
      w.unmount()
    })

    it('shows any other failure with the message of the server and stays', async () => {
      deleteRepoMock.mockRejectedValue({ response: { status: 404, data: { message: "Repository 'mvn' not found" } } })
      const w = await mountView()
      await click('delete-btn')
      await click('delete-confirm')
      expect(el('delete-error')!.textContent).toContain("Repository 'mvn' not found")
      expect(el('delete-force-confirm')).toBeNull()
      expect(pushMock).not.toHaveBeenCalled()
      expect(toasts()[0]).toEqual({ severity: 'error', summary: 'Failed to delete repository', detail: "Repository 'mvn' not found" })
      w.unmount()
    })

    it('goes back to the list after a delete without a job', async () => {
      deleteRepoMock.mockResolvedValueOnce({ kind: 'deleting', message: '' }).mockResolvedValueOnce({ kind: 'deleted' })
      let w = await mountView()
      await click('delete-btn')
      await click('delete-confirm')
      expect(pushMock).toHaveBeenLastCalledWith('/admin/repositories')
      expect(toasts()[0]).toEqual({ severity: 'info', summary: 'Repository is being deleted', detail: 'mvn' })
      w.unmount()
      document.body.innerHTML = ''
      w = await mountView()
      await click('delete-btn')
      await click('delete-confirm')
      expect(pushMock).toHaveBeenLastCalledWith('/admin/repositories')
      expect(toasts()[1]).toEqual({ severity: 'success', summary: 'Repository deleted', detail: 'mvn' })
      w.unmount()
    })
  })

  it('offers rename and delete only with the matching permission', async () => {
    seedAuth(['update', 'move'])
    const w = await mountView()
    expect(el('rename-btn')).not.toBeNull()
    expect(el('delete-btn')).toBeNull()
    w.unmount()
  })
})
