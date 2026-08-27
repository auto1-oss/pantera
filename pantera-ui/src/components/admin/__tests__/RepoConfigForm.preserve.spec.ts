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

function mountForm(initialConfig: RepoConfigEnvelope) {
  return mount(RepoConfigForm, {
    props: { config: null, initialConfig, readOnlyType: true },
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

/**
 * The server stores each repo config as one JSONB document and PUT replaces it
 * wholesale (RepositoryDao: `ON CONFLICT (name) DO UPDATE SET config = ?`), and
 * the form emits its rebuilt envelope on LOAD, before the user touches a field.
 * So any key the form fails to re-emit is deleted from the row by an untouched
 * open-and-save. These tests pin the keys that used to be lost that way.
 */
describe('RepoConfigForm — config round-trip preservation', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('keeps a repo-level url through an untouched load/emit cycle', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'npm',
        url: 'https://packages.example.com/api/npm/npm-local',
        storage: { type: 'fs', path: '/var/pantera/data' },
      },
    })
    await flushPromises()
    expect(lastEmittedRepo(wrapper).url)
      .toBe('https://packages.example.com/api/npm/npm-local')
  })

  it('keeps unmodelled keys (path, settings) verbatim', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'deb',
        url: 'http://localhost:8081/test_prefix/apt-repo',
        storage: { type: 'fs', path: '/var/pantera/data' },
        path: 'apt-repo',
        settings: { Components: 'main', Architectures: 'amd64 arm64 i386' },
      },
    })
    await flushPromises()
    const repo = lastEmittedRepo(wrapper)
    expect(repo.path, 'path must survive a save').toBe('apt-repo')
    expect(repo.settings, 'deb settings block must survive a save').toEqual({
      Components: 'main',
      Architectures: 'amd64 arm64 i386',
    })
  })

  it('drops the url key when the field is cleared', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'npm-proxy',
        url: 'https://artifactory.example.com/npm_proxy',
        storage: { type: 'fs', path: '/var/pantera/data' },
        remotes: [{ url: 'https://registry.npmjs.org' }],
      },
    })
    await flushPromises()
    const inputs = wrapper.findAll('input')
    const baseInput = inputs.find(
      i => (i.element as HTMLInputElement).value
        === 'https://artifactory.example.com/npm_proxy',
    )
    if (!baseInput) throw new Error('base URL input not found')
    await baseInput.setValue('')
    await flushPromises()
    const repo = lastEmittedRepo(wrapper)
    expect('url' in repo, 'cleared base URL must remove the key').toBe(false)
    expect(
      repo.remotes,
      'clearing the base URL must not touch the upstream remotes',
    ).toEqual([{ url: 'https://registry.npmjs.org' }])
  })

  it('does not invent a storage block for a storage-less group repo', async () => {
    const wrapper = mountForm({
      repo: { type: 'maven-group', members: ['maven', 'remotes'] },
    })
    await flushPromises()
    const repo = lastEmittedRepo(wrapper)
    expect('storage' in repo, 'group repo must stay storage-less').toBe(false)
    expect(repo.members).toEqual(['maven', 'remotes'])
  })

  it('reports invalid when the base URL is not absolute http(s)', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'npm',
        storage: { type: 'fs', path: '/var/pantera/data' },
      },
    })
    await flushPromises()
    const valid = () => {
      const events = wrapper.emitted('valid-change') as Array<[boolean]> | undefined
      if (!events || events.length === 0) throw new Error('no valid-change emitted')
      return events[events.length - 1][0]
    }
    expect(valid(), 'empty base URL is valid (the key is simply omitted)').toBe(true)
    const inputs = wrapper.findAll('input')
    const baseInput = inputs.find(
      i => (i.element as HTMLInputElement).placeholder?.includes('packages.example.com'),
    )
    if (!baseInput) throw new Error('base URL input not found')
    await baseInput.setValue('packages.example.com/api/npm/npm-local')
    await flushPromises()
    expect(valid(), 'a scheme-less base URL must block saving').toBe(false)
    await baseInput.setValue('https://packages.example.com/api/npm/npm-local')
    await flushPromises()
    expect(valid()).toBe(true)
  })
})
