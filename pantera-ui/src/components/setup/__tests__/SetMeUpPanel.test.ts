import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SetMeUpPanel from '../SetMeUpPanel.vue'
import { listRepos, getRepo } from '@/api/repos'
import { getUiSettings } from '@/api/settings'
import type { Client, SnippetCtx } from '@/utils/setup'

vi.mock('@/api/repos', () => ({ listRepos: vi.fn(), getRepo: vi.fn() }))
vi.mock('@/api/settings', () => ({ getUiSettings: vi.fn() }))
vi.mock('@/api/auth', () => ({ generateTokenForSession: vi.fn() }))

const snippets = vi.hoisted(() => ({ npm: vi.fn() }))
vi.mock('@/utils/setup', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/setup')>()
  return { ...actual, FORMAT_SNIPPETS: snippets }
})

function npmClients(ctx: SnippetCtx): Client[] {
  return [
    {
      id: 'npm',
      label: 'npm',
      configure: [{ title: '.npmrc', code: `registry=${ctx.repoUrl}/`, file: '~/.npmrc', download: '.npmrc' }],
      resolve: [{ title: 'Install', code: 'npm install lodash' }],
      publish: ctx.pubUrl ? [{ title: 'Publish', code: `npm publish --registry ${ctx.pubUrl}/` }] : [],
      verify: [{ title: 'Ping', code: 'npm ping' }],
    },
    {
      id: 'yarn-berry',
      label: 'Yarn Berry',
      configure: [],
      resolve: [],
      publish: [],
      verify: [],
      publishNote: 'Publish with npm.',
    },
  ]
}

function page(items: { name: string; type: string }[]) {
  return { items, page: 0, size: 100, total: items.length, hasMore: false }
}

async function mountPanel(props: Record<string, unknown> = {}) {
  const wrapper = mount(SetMeUpPanel, {
    props: { tech: 'npm', ...props },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: { 'router-link': { template: '<a><slot /></a>' } },
    },
  })
  await flushPromises()
  return wrapper
}

describe('SetMeUpPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listRepos).mockReset()
    vi.mocked(getRepo).mockReset().mockResolvedValue({ repo: { type: 'npm' } })
    vi.mocked(getUiSettings).mockReset()
      .mockResolvedValue({ ui: { registry_url: 'http://localhost:8081', prefixes: ['test_prefix'] } })
    snippets.npm.mockReset().mockImplementation(npmClients)
  })

  it('renders the format\'s clients and the selected client\'s steps with the prefixed URL', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npm_local', type: 'npm' }]))
    const wrapper = await mountPanel()
    expect({
      chips: wrapper.findAll('[data-testid="client-chips"] button').map(b => b.text()),
      code: wrapper.find('[data-testid="panel-configure"] pre').text(),
      banner: wrapper.find('[data-testid="unconfigured-banner"]').exists(),
    }).toEqual({
      chips: ['npm', 'Yarn Berry'],
      code: 'registry=http://localhost:8081/test_prefix/npm_local/',
      banner: false,
    })
  })

  it('warns when no registry URL is configured', async () => {
    vi.mocked(getUiSettings).mockResolvedValue({ ui: {} })
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npm_local', type: 'npm' }]))
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="unconfigured-banner"]').text()).toContain('Ask an administrator')
  })

  it('explains an empty Publish tab when there is no local repository', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npmjs', type: 'npm-proxy' }]))
    const wrapper = await mountPanel({ tab: 'publish' })
    expect(wrapper.find('[data-testid="publish-reason"]').text())
      .toContain('no local npm repository')
  })

  it('shows the client\'s publish note when it cannot publish', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npm_local', type: 'npm' }]))
    const wrapper = await mountPanel({ tab: 'publish', client: 'yarn-berry' })
    expect(wrapper.find('[data-testid="publish-reason"]').text()).toBe('Publish with npm.')
  })

  it('handles a format whose snippet module has no clients yet', async () => {
    snippets.npm.mockReturnValue([])
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npm_local', type: 'npm' }]))
    const wrapper = await mountPanel()
    expect({
      empty: wrapper.find('[data-testid="no-clients"]').exists(),
      url: wrapper.find('[data-testid="resolve-url"]').text(),
    }).toEqual({ empty: true, url: 'http://localhost:8081/test_prefix/npm_local/' })
  })

  it('passes the resolve repository\'s mode to the snippets', async () => {
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npmjs', type: 'npm-proxy' }]))
    await mountPanel()
    const ctx = snippets.npm.mock.calls[snippets.npm.mock.calls.length - 1][0] as SnippetCtx
    expect(ctx.mode).toBe('proxy')
  })

  it('uses a repository\'s configured settings.url', async () => {
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm', settings: { url: 'https://php.example.com/p' } } })
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npm_local', type: 'npm' }]))
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="resolve-url"]').text()).toBe('https://php.example.com/p/')
  })

  it('uses a repository\'s own configured URL', async () => {
    vi.mocked(getRepo).mockResolvedValue({ repo: { type: 'npm', url: 'https://npm.example.com/custom/' } })
    vi.mocked(listRepos).mockResolvedValue(page([{ name: 'npm_local', type: 'npm' }]))
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="resolve-url"]').text()).toBe('https://npm.example.com/custom/')
  })
})
