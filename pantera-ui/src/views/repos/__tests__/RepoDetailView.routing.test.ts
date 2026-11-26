import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import RepoDetailView from '../RepoDetailView.vue'

const getRepoMock = vi.fn()

vi.mock('@/api/repos', () => ({
  getRepo: (...a: unknown[]) => getRepoMock(...a),
  getTree: vi.fn().mockResolvedValue({ items: [], marker: null, hasMore: false }),
  getArtifactDetail: vi.fn(),
  deleteArtifacts: vi.fn(),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div/>' } },
    { path: '/repositories/:name', name: 'repo-detail', component: RepoDetailView, props: true },
  ],
})

async function mountView(name = 'libs-release') {
  await router.push(`/repositories/${name}`)
  await router.isReady()
  const w = mount(RepoDetailView, {
    props: { name },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], router],
      stubs: {
        'router-link': true,
        'router-view': true,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
  await flushPromises()
  return w
}

describe('RepoDetailView: routing rules of a group', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getRepoMock.mockReset()
  })

  it('lists the rules read-only, in their order', async () => {
    getRepoMock.mockResolvedValue({
      repo: {
        type: 'maven-group',
        members: ['libs-release-local', 'maven-central', 'jboss'],
        routing: [
          { member: 'maven-central', pattern: '(?!com/acme/|internal/).*' },
          { member: 'jboss', prefix: 'org/jboss/' },
        ],
      },
    })
    const w = await mountView()

    const card = w.find('[data-testid="repo-routing-rules"]')
    expect(card.exists(), 'the card is shown').toBe(true)
    expect(card.findAll('[data-testid="repo-routing-rule"]').map(r => r.findAll('td').map(td => td.text()))).toEqual([
      ['1', 'maven-central', 'Pattern', '(?!com/acme/|internal/).*'],
      ['2', 'jboss', 'Prefix', 'org/jboss/'],
    ])
    expect(card.text(), 'what a rule means').toMatch(/asked only for the paths its rules match/)
    expect(card.findAll('button'), 'nothing to edit here').toHaveLength(0)
  })

  it('shows no rules card for a group without rules', async () => {
    getRepoMock.mockResolvedValue({ repo: { type: 'maven-group', members: ['a', 'b'] } })
    const w = await mountView()
    expect(w.find('[data-testid="repo-routing-rules"]').exists()).toBe(false)
  })

  it('shows no rules card for a repository that is no group', async () => {
    getRepoMock.mockResolvedValue({ repo: { type: 'maven', storage: {}, routing: [{ member: 'x', prefix: 'y' }] } })
    const w = await mountView('maven')
    expect(w.find('[data-testid="repo-routing-rules"]').exists()).toBe(false)
  })
})
