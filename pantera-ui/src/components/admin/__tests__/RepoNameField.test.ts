import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RepoNameField from '../RepoNameField.vue'

const repoExistsMock = vi.fn()
vi.mock('@/api/repos', () => ({ repoExists: (...a: unknown[]) => repoExistsMock(...a) }))

describe('RepoNameField', () => {
  afterEach(() => { vi.useRealTimers() })

  it('reports invalid, taken and valid names', async () => {
    vi.useFakeTimers()
    repoExistsMock.mockResolvedValue(true)
    const w = mount(RepoNameField, {
      props: { modelValue: '' },
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
    })
    await w.setProps({ modelValue: 'bad name' })
    await flushPromises()
    expect(w.find('[data-testid="name-error"]').text()).toMatch(/letters, digits/)
    expect(w.emitted('valid-change')?.at(-1)).toEqual([false])
    expect(repoExistsMock).not.toHaveBeenCalled()

    await w.setProps({ modelValue: 'taken' })
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(w.find('[data-testid="name-error"]').text()).toMatch(/already exists/i)
    expect(w.emitted('valid-change')?.at(-1)).toEqual([false])

    repoExistsMock.mockResolvedValue(false)
    await w.setProps({ modelValue: 'fresh' })
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(w.find('[data-testid="name-ok"]').exists()).toBe(true)
    expect(w.emitted('valid-change')?.at(-1)).toEqual([true])
  })

  it('does not fail open when the existence check errors', async () => {
    vi.useFakeTimers()
    repoExistsMock.mockRejectedValue(new Error('network'))
    const w = mount(RepoNameField, {
      props: { modelValue: '' },
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
    })
    await w.setProps({ modelValue: 'maybe-taken' })
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(w.find('[data-testid="name-ok"]').exists()).toBe(false)
    expect(w.find('[data-testid="name-error"]').text()).toMatch(/could not check/i)
    expect(w.emitted('valid-change')?.at(-1)).toEqual([false])
  })

  it('is not valid while the existence check is still pending', async () => {
    vi.useFakeTimers()
    repoExistsMock.mockResolvedValue(false)
    const w = mount(RepoNameField, {
      props: { modelValue: '' },
      global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
    })
    await w.setProps({ modelValue: 'pending' })
    await flushPromises()
    expect(w.emitted('valid-change')?.at(-1)).toEqual([false])
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(w.emitted('valid-change')?.at(-1)).toEqual([true])
  })
})
