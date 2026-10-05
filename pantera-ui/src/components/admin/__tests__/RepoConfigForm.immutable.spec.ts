import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RepoConfigForm from '../RepoConfigForm.vue'
import type { RepoConfigEnvelope } from '@/types/repo'

// Same stubs as the anonymous-access spec: the form fetches storage aliases
// and compatible repos on mount, neither of which matters here.
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

type Exposed = { immutableArtifacts: boolean; supportsImmutable: boolean }

const FS = { type: 'fs' as const, path: '/var/pantera/data' }

describe('RepoConfigForm — immutable artifacts', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('defaults a new hosted repo to immutable and emits it', async () => {
    // Same shape RepoCreateView seeds: type + storage, no immutable key.
    const wrapper = mountForm({ repo: { type: 'npm', storage: FS } }, false)
    await flushPromises()
    const exposed = wrapper.vm as unknown as Exposed
    expect(exposed.supportsImmutable).toBe(true)
    expect(exposed.immutableArtifacts).toBe(true)
    expect(lastEmittedRepo(wrapper).immutable).toBe(true)
    expect(wrapper.find('#immutableArtifacts').exists()).toBe(true)
    expect(wrapper.text()).toContain('Immutable artifacts')
  })

  it('loads immutable: false from an existing config', async () => {
    const wrapper = mountForm({
      repo: { type: 'file', storage: FS, immutable: false },
    })
    await flushPromises()
    expect((wrapper.vm as unknown as Exposed).immutableArtifacts).toBe(false)
    expect(lastEmittedRepo(wrapper).immutable).toBe(false)
  })

  it('treats a string "false" as false and emits a boolean', async () => {
    const wrapper = mountForm({
      repo: { type: 'gem', storage: FS, immutable: 'false' as unknown as boolean },
    })
    await flushPromises()
    expect((wrapper.vm as unknown as Exposed).immutableArtifacts).toBe(false)
    expect(lastEmittedRepo(wrapper).immutable).toBe(false)
  })

  it('honours the deprecated releaseImmutable alias and replaces it with immutable', async () => {
    const wrapper = mountForm({
      repo: { type: 'maven', storage: FS, releaseImmutable: false },
    })
    await flushPromises()
    expect((wrapper.vm as unknown as Exposed).immutableArtifacts).toBe(false)
    const emitted = lastEmittedRepo(wrapper)
    expect(emitted.immutable).toBe(false)
    expect('releaseImmutable' in emitted).toBe(false)
  })

  it('lets immutable win over releaseImmutable when both are set', async () => {
    const wrapper = mountForm({
      repo: { type: 'maven', storage: FS, immutable: true, releaseImmutable: false },
    })
    await flushPromises()
    expect((wrapper.vm as unknown as Exposed).immutableArtifacts).toBe(true)
    expect(lastEmittedRepo(wrapper).immutable).toBe(true)
  })

  it('emits the toggled value in the next update:config payload', async () => {
    const wrapper = mountForm({ repo: { type: 'maven', storage: FS } })
    await flushPromises()
    const exposed = wrapper.vm as unknown as Exposed
    exposed.immutableArtifacts = false
    await flushPromises()
    expect(lastEmittedRepo(wrapper).immutable).toBe(false)
  })

  it.each([
    ['maven-proxy', { remotes: [{ url: 'https://repo1.maven.org/maven2' }] }],
    ['npm-group', { members: ['npm-local'] }],
    ['docker', {}],
  ])('hides the control and never emits immutable for %s', async (type, extra) => {
    const wrapper = mountForm({ repo: { type, storage: FS, ...extra } })
    await flushPromises()
    expect((wrapper.vm as unknown as Exposed).supportsImmutable).toBe(false)
    expect(wrapper.find('#immutableArtifacts').exists()).toBe(false)
    expect('immutable' in lastEmittedRepo(wrapper)).toBe(false)
  })

  it('preserves a stray immutable value on a proxy config untouched', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'maven-proxy',
        storage: FS,
        remotes: [{ url: 'https://repo1.maven.org/maven2' }],
        immutable: false,
      },
    })
    await flushPromises()
    expect(wrapper.find('#immutableArtifacts').exists()).toBe(false)
    expect(lastEmittedRepo(wrapper).immutable).toBe(false)
  })
})
