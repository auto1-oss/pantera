import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CodeStep from '../CodeStep.vue'

describe('CodeStep', () => {
  let writeText: ReturnType<typeof vi.fn>

  beforeEach(() => {
    writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
  })

  afterEach(() => { vi.restoreAllMocks() })

  it('shows an always-visible copy button that copies the code', async () => {
    const wrapper = mount(CodeStep, {
      props: { step: { title: 'Configure', code: 'registry=https://r/npm/' }, index: 1 },
    })
    const btn = wrapper.find('[data-testid="copy-btn"]')
    await btn.trigger('click')
    await flushPromises()
    expect({
      copied: writeText.mock.calls[0]?.[0],
      label: btn.text(),
      hidden: btn.classes().includes('opacity-0'),
    }).toEqual({ copied: 'registry=https://r/npm/', label: 'Copied', hidden: false })
  })

  it('downloads file-shaped snippets under their file name', async () => {
    const created: Blob[] = []
    vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => { created.push(b as Blob); return 'blob:x' })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const names: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      names.push(this.download)
    })
    const wrapper = mount(CodeStep, {
      props: {
        step: { title: 'settings', code: '<settings/>', file: '~/.m2/settings.xml', download: 'settings.xml' },
        index: 2,
      },
    })
    await wrapper.find('[data-testid="download-btn"]').trigger('click')
    expect({
      names,
      body: await created[0].text(),
      file: wrapper.find('[data-testid="step-file"]').text(),
    }).toEqual({ names: ['settings.xml'], body: '<settings/>\n', file: '~/.m2/settings.xml' })
  })

  it('has no download button for plain commands', () => {
    const wrapper = mount(CodeStep, { props: { step: { title: 'Run', code: 'npm ping' }, index: 1 } })
    expect(wrapper.find('[data-testid="download-btn"]').exists()).toBe(false)
  })
})
