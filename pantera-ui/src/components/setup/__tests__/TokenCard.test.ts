import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import TokenCard from '../TokenCard.vue'
import { generateTokenForSession } from '@/api/auth'

vi.mock('@/api/auth', () => ({
  generateTokenForSession: vi.fn(),
}))

const SECRET = 'eyJ.secret-token-value'

function mountCard(username = 'jane') {
  return mount(TokenCard, {
    props: { format: 'npm', repo: 'npm_group', username },
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
  })
}

describe('TokenCard', () => {
  beforeEach(() => {
    vi.mocked(generateTokenForSession).mockReset()
    localStorage.clear()
  })

  it('generates a labelled token, emits it and shows it masked', async () => {
    vi.mocked(generateTokenForSession).mockResolvedValue({
      token: SECRET, id: '1', label: 'x', expires_at: '2026-10-23T00:00:00Z', permanent: false,
    })
    const wrapper = mountCard()
    await wrapper.find('[data-testid="generate-token"]').trigger('click')
    await flushPromises()
    const emitted = (wrapper.emitted('token') ?? []).map(e => e[0])
    expect({
      call: vi.mocked(generateTokenForSession).mock.calls[0],
      last: emitted[emitted.length - 1],
      shown: wrapper.find('[data-testid="token-value"]').text().includes(SECRET),
      notice: wrapper.find('[data-testid="token-once-notice"]').exists(),
      stored: JSON.stringify({ ...localStorage }).includes(SECRET),
    }).toEqual({
      call: [30, 'set-me-up:npm:npm_group'],
      last: SECRET,
      shown: false,
      notice: true,
      stored: false,
    })
  })

  it('reveals the generated token on request', async () => {
    vi.mocked(generateTokenForSession).mockResolvedValue({
      token: SECRET, id: '1', label: 'x', permanent: true,
    })
    const wrapper = mountCard()
    await wrapper.find('[data-testid="generate-token"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="reveal-token"]').trigger('click')
    expect(wrapper.find('[data-testid="token-value"]').text()).toBe(SECRET)
  })

  it('emits an empty token (placeholders) until one is generated or entered', () => {
    const wrapper = mountCard()
    expect(wrapper.emitted('token')).toEqual([['']])
  })

  it('warns that an @ in the username is URL-encoded', () => {
    const wrapper = mountCard('jane@corp.com')
    expect(wrapper.find('[data-testid="username-at-note"]').text()).toContain('jane%40corp.com')
  })

  it('shows the server error when generation fails', async () => {
    vi.mocked(generateTokenForSession).mockRejectedValue({ response: { data: { message: 'expiry too long' } } })
    const wrapper = mountCard()
    await wrapper.find('[data-testid="generate-token"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="token-error"]').text()).toBe('expiry too long')
  })
})
