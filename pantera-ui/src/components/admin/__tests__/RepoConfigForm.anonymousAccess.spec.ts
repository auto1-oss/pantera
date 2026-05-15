import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RepoConfigForm from '../RepoConfigForm.vue'
import type { RepoConfigEnvelope } from '@/types/repo'

// The form pulls storage aliases + compatible-repo lists on mount; neither
// shape matters for these tests, so we stub them to empty arrays so the
// component mounts cleanly under jsdom without real network.
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
    props: {
      config: null,
      initialConfig,
      readOnlyType: true,
    },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
    },
  })
}

/**
 * Pull the most recent envelope emitted via update:config. The form re-emits
 * on every field change, so the last entry reflects the current state.
 */
function lastEmittedRepo(wrapper: ReturnType<typeof mountForm>) {
  const events = wrapper.emitted('update:config') as
    Array<[RepoConfigEnvelope]> | undefined
  if (!events || events.length === 0) {
    throw new Error('RepoConfigForm did not emit update:config')
  }
  return events[events.length - 1][0].repo
}

describe('RepoConfigForm — anonymous-access defaults', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('defaults a proxy repo to both flags off (deny-by-default)', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'maven-proxy',
        storage: { type: 'fs', path: '/var/pantera/data' },
        remotes: [{ url: 'https://repo1.maven.org/maven2' }],
      },
    })
    await flushPromises()
    const exposed = wrapper.vm as unknown as {
      anonymousRead: boolean; anonymousWrite: boolean
    }
    expect(exposed.anonymousRead).toBe(false)
    expect(exposed.anonymousWrite).toBe(false)

    const repo = lastEmittedRepo(wrapper)
    expect(repo.anonymous_read).toBe(false)
    expect(repo.anonymous_write).toBe(false)
  })

  it('defaults a hosted repo (no remotes, no -group) to both flags off', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'maven',
        storage: { type: 'fs', path: '/var/pantera/data' },
      },
    })
    await flushPromises()
    const exposed = wrapper.vm as unknown as {
      anonymousRead: boolean; anonymousWrite: boolean
    }
    expect(exposed.anonymousRead).toBe(false)
    expect(exposed.anonymousWrite).toBe(false)

    const repo = lastEmittedRepo(wrapper)
    expect(repo.anonymous_read).toBe(false)
    expect(repo.anonymous_write).toBe(false)
  })

  it('honours an explicit anonymous_read=true on a proxy (admin opt-in)', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'maven-proxy',
        storage: { type: 'fs', path: '/var/pantera/data' },
        remotes: [{ url: 'https://repo1.maven.org/maven2' }],
        anonymous_read: true,
        anonymous_write: false,
      },
    })
    await flushPromises()
    const exposed = wrapper.vm as unknown as { anonymousRead: boolean }
    expect(exposed.anonymousRead).toBe(true)

    const repo = lastEmittedRepo(wrapper)
    expect(repo.anonymous_read).toBe(true)
  })

  it('emits the toggled value in the next update:config payload', async () => {
    const wrapper = mountForm({
      repo: {
        type: 'maven-proxy',
        storage: { type: 'fs', path: '/var/pantera/data' },
        remotes: [{ url: 'https://repo1.maven.org/maven2' }],
      },
    })
    await flushPromises()
    // Default is false for every repo — flip on via the exposed ref
    // (simulating the operator checking the "Allow anonymous reads" box).
    const exposed = wrapper.vm as unknown as { anonymousRead: boolean }
    exposed.anonymousRead = true
    await flushPromises()

    const repo = lastEmittedRepo(wrapper)
    expect(repo.anonymous_read).toBe(true)
    expect(repo.anonymous_write).toBe(false)
  })
})
