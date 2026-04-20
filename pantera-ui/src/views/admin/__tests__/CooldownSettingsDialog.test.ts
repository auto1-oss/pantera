import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primevue/themes/aura'
import CooldownSettingsDialog from '../CooldownSettingsDialog.vue'

// Mock the settings API so we can assert the payload shape posted
// from the dialog and simulate backend validation errors without
// wiring the real axios client.
const getCooldownConfigMock = vi.fn()
const updateCooldownConfigMock = vi.fn()

vi.mock('@/api/settings', () => ({
  getCooldownConfig: (...args: unknown[]) => getCooldownConfigMock(...args),
  updateCooldownConfig: (...args: unknown[]) => updateCooldownConfigMock(...args),
}))

// PrimeVue's Dialog teleports to body; attach to the document so we
// can query/screenshot its rendered content via `document`.
function mountDialog() {
  return mount(CooldownSettingsDialog, {
    props: { modelValue: true },
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
    },
  })
}

describe('CooldownSettingsDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getCooldownConfigMock.mockReset()
    updateCooldownConfigMock.mockReset()
    getCooldownConfigMock.mockResolvedValue({
      enabled: true,
      minimum_allowed_age: '7d',
      history_retention_days: 90,
      cleanup_batch_limit: 10000,
    })
    updateCooldownConfigMock.mockResolvedValue(undefined)
  })

  it('loads current values and submits changes', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    // Prefill happens via the visible watcher on open.
    expect(getCooldownConfigMock).toHaveBeenCalled()
    const vm = wrapper.vm as unknown as {
      form: {
        enabled: boolean
        minimum_allowed_age: string
        history_retention_days?: number
        cleanup_batch_limit?: number
      }
      save: () => Promise<void>
    }
    expect(vm.form.enabled).toBe(true)
    expect(vm.form.minimum_allowed_age).toBe('7d')
    expect(vm.form.history_retention_days).toBe(90)
    expect(vm.form.cleanup_batch_limit).toBe(10000)

    // Simulate the user lowering retention to 30 days.
    vm.form.history_retention_days = 30
    await vm.save()
    await flushPromises()

    expect(updateCooldownConfigMock).toHaveBeenCalledTimes(1)
    const payload = updateCooldownConfigMock.mock.calls[0][0]
    expect(payload.history_retention_days).toBe(30)
    expect(payload.enabled).toBe(true)
    expect(payload.minimum_allowed_age).toBe('7d')

    // Successful save closes the dialog and emits 'saved'.
    expect(wrapper.emitted('saved')).toBeTruthy()
    const mv = wrapper.emitted('update:modelValue') ?? []
    expect(mv.some(([v]) => v === false)).toBe(true)

    wrapper.unmount()
  })

  it('shows backend validation error message on save failure', async () => {
    updateCooldownConfigMock.mockRejectedValueOnce({
      response: {
        data: { message: 'history_retention_days must be in (0, 3650]' },
      },
    })
    const wrapper = mountDialog()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      save: () => Promise<void>
      error: string | null
    }
    await vm.save()
    await flushPromises()

    expect(vm.error).toBe('history_retention_days must be in (0, 3650]')

    // Dialog stays open (modelValue not flipped to false after the
    // initial open).
    expect(wrapper.emitted('saved')).toBeUndefined()

    // Error also renders in the DOM (Dialog content is teleported to
    // document.body).
    const err = document.querySelector('[data-testid="dialog-error"]')
    expect(err?.textContent).toContain('history_retention_days must be in (0, 3650]')

    wrapper.unmount()
  })

  it('falls back to generic error when response has no message', async () => {
    updateCooldownConfigMock.mockRejectedValueOnce(new Error('network'))
    const wrapper = mountDialog()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      save: () => Promise<void>
      error: string | null
    }
    await vm.save()
    await flushPromises()

    expect(vm.error).toBe('Save failed')
    wrapper.unmount()
  })
})
