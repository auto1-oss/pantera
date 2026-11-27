import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RepoConfigForm from '../RepoConfigForm.vue'
import type { RepoConfigEnvelope } from '@/types/repo'

// Same stubs as the immutable spec: the form fetches storage aliases and
// compatible repos on mount, neither of which matters here.
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

function mountForm(initialConfig: RepoConfigEnvelope, readOnlyType = true) {
  return mount(RepoConfigForm, {
    props: { config: null, initialConfig, readOnlyType },
    global: { plugins: [[PrimeVue, { theme: { preset: Aura } }]] },
  })
}

function lastEmittedRepo(wrapper: ReturnType<typeof mountForm>) {
  const events = wrapper.emitted('update:config') as
    Array<[RepoConfigEnvelope]> | undefined
  if (!events || events.length === 0) {
    throw new Error('RepoConfigForm did not emit update:config')
  }
  return events[events.length - 1][0].repo
}

function lastValid(wrapper: ReturnType<typeof mountForm>) {
  const events = wrapper.emitted('valid-change') as Array<[boolean]> | undefined
  if (!events || events.length === 0) {
    throw new Error('RepoConfigForm did not emit valid-change')
  }
  return events[events.length - 1][0]
}

type Exposed = { tagRefreshSeconds: number | null; isDockerProxy: boolean }

const FS = { type: 'fs' as const, path: '/var/pantera/data' }
const REMOTES = [{ url: 'https://registry-1.docker.io' }]

describe('RepoConfigForm — docker-proxy tag refresh', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts a new docker-proxy empty and emits no key', async () => {
    // Same shape RepoCreateView seeds: type + storage only.
    const wrapper = mountForm({ repo: { type: 'docker-proxy', storage: FS } }, false)
    await flushPromises()
    const exposed = wrapper.vm as unknown as Exposed
    expect(exposed.isDockerProxy, 'the field applies').toBe(true)
    expect(exposed.tagRefreshSeconds, 'empty: the global setting applies').toBe(null)
    expect('tag_refresh_seconds' in lastEmittedRepo(wrapper), 'no key is sent').toBe(false)
    expect(wrapper.find('#tagRefreshSeconds').exists(), 'the field is shown').toBe(true)
    expect(wrapper.text(), 'the label').toContain('Tag refresh (seconds)')
    expect(wrapper.text(), 'what empty means').toContain('Empty uses the global setting')
  })

  it('loads a saved age and emits it back as a number', async () => {
    const wrapper = mountForm({
      repo: { type: 'docker-proxy', storage: FS, remotes: REMOTES, tag_refresh_seconds: 600 },
    })
    await flushPromises()
    expect((wrapper.vm as unknown as Exposed).tagRefreshSeconds, 'loaded').toBe(600)
    expect(lastEmittedRepo(wrapper).tag_refresh_seconds, 'round-tripped').toBe(600)
  })

  it('loads 0, which re-checks on every request', async () => {
    const wrapper = mountForm({
      repo: { type: 'docker-proxy', storage: FS, remotes: REMOTES, tag_refresh_seconds: 0 },
    })
    await flushPromises()
    expect(lastEmittedRepo(wrapper).tag_refresh_seconds).toBe(0)
  })

  it('reads digits in a string from a YAML file as a number', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'docker-proxy', storage: FS, remotes: REMOTES,
        tag_refresh_seconds: '900' as unknown as number,
      },
    })
    await flushPromises()
    expect(lastEmittedRepo(wrapper).tag_refresh_seconds).toBe(900)
  })

  it('emits an age that is typed in', async () => {
    const wrapper = mountForm({ repo: { type: 'docker-proxy', storage: FS, remotes: REMOTES } })
    await flushPromises()
    ;(wrapper.vm as unknown as Exposed).tagRefreshSeconds = 120
    await flushPromises()
    expect(lastEmittedRepo(wrapper).tag_refresh_seconds).toBe(120)
  })

  it('removes the key when the field is cleared', async () => {
    const wrapper = mountForm({
      repo: { type: 'docker-proxy', storage: FS, remotes: REMOTES, tag_refresh_seconds: 600 },
    })
    await flushPromises()
    ;(wrapper.vm as unknown as Exposed).tagRefreshSeconds = null
    await flushPromises()
    expect('tag_refresh_seconds' in lastEmittedRepo(wrapper)).toBe(false)
  })

  it.each([[-1], [31536001], [2.5]] as const)('marks the form invalid for %s', async (seconds) => {
    const wrapper = mountForm({ repo: { type: 'docker-proxy', storage: FS, remotes: REMOTES } })
    await flushPromises()
    expect(lastValid(wrapper), 'valid before').toBe(true)
    ;(wrapper.vm as unknown as Exposed).tagRefreshSeconds = seconds
    await flushPromises()
    expect(lastValid(wrapper), 'invalid after').toBe(false)
  })

  it.each([
    ['docker', {}],
    ['maven-proxy', { remotes: [{ url: 'https://repo1.maven.org/maven2' }] }],
    ['docker-group', { members: ['docker-local'] }],
  ])('hides the field and adds no key for %s', async (type, extra) => {
    const wrapper = mountForm({ repo: { type, storage: FS, ...extra } })
    await flushPromises()
    expect((wrapper.vm as unknown as Exposed).isDockerProxy).toBe(false)
    expect(wrapper.find('#tagRefreshSeconds').exists()).toBe(false)
    expect('tag_refresh_seconds' in lastEmittedRepo(wrapper)).toBe(false)
  })

  it('keeps a stray value on another type untouched', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'maven-proxy', storage: FS,
        remotes: [{ url: 'https://repo1.maven.org/maven2' }],
        tag_refresh_seconds: 60,
      },
    })
    await flushPromises()
    expect(lastEmittedRepo(wrapper).tag_refresh_seconds).toBe(60)
  })
})
