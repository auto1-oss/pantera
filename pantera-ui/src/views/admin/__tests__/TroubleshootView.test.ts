import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import TroubleshootView from '../TroubleshootView.vue'
import { useNotificationStore } from '@/stores/notifications'
import type { TroubleshootResponse } from '@/api/troubleshoot'

const troubleshootMock = vi.fn()
const fixMock = vi.fn()

vi.mock('@/api/troubleshoot', () => ({
  troubleshootUrl: (...a: unknown[]) => troubleshootMock(...a),
  runTroubleshootFix: (...a: unknown[]) => fixMock(...a),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const URL = '/npm-group/lodash/-/lodash-4.17.21.tgz'

function response(shadowed: boolean): TroubleshootResponse {
  return {
    url: URL,
    repo: { name: 'npm-group', type: 'npm-group', mode: 'group', members: ['npm-local', 'npm-proxy'] },
    parsed: { package: 'lodash', version: '4.17.21', kind: 'artifact' },
    request: {
      status: shadowed ? 404 : 200,
      headers: { 'Content-Type': 'application/octet-stream' },
      bodySnippet: shadowed ? 'Not found' : '',
    },
    checks: [
      { id: 'repo', layer: 'repository', status: 'ok', message: 'npm-group exists (npm group)' },
      shadowed
        ? {
            id: 'neg',
            layer: 'negative-cache',
            status: 'problem',
            message: 'lodash@4.17.21 is negative-cached in npm-group (L2)',
            fix: {
              action: 'invalidate-package',
              endpoint: '/api/v1/admin/neg-cache/invalidate-package',
              body: { artifactName: 'lodash', repoType: 'npm' },
            },
          }
        : { id: 'neg', layer: 'negative-cache', status: 'ok', message: 'No negative cache entry' },
      { id: 'walk', layer: 'group', status: 'info', message: 'Served by npm-proxy (member 2 of 2)' },
    ],
    node: 'pantera-c',
  }
}

let wrapper: VueWrapper | null = null

async function mountAt(url: string): Promise<{ w: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/troubleshoot', component: { template: '<div />' } },
      { path: '/cooldown', component: { template: '<div />' } },
    ],
  })
  await router.push(url)
  await router.isReady()
  wrapper = mount(TroubleshootView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], router],
      directives: { tooltip: {} },
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' },
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
  await flushPromises()
  return { w: wrapper, router }
}

describe('TroubleshootView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    troubleshootMock.mockReset()
    fixMock.mockReset()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('runs the ?url= deep link on load and renders repo, parsed request, response and checks', async () => {
    troubleshootMock.mockResolvedValue(response(true))
    const { w } = await mountAt(`/admin/troubleshoot?url=${encodeURIComponent(URL)}`)

    expect(troubleshootMock).toHaveBeenCalledWith(URL)
    expect(w.get('[data-testid="troubleshoot-repo"]').text()).toContain('npm-group')
    expect(w.get('[data-testid="troubleshoot-repo"]').text()).toContain('npm-proxy')
    expect(w.get('[data-testid="troubleshoot-request"]').text()).toContain('HTTP 404')
    expect(w.text()).toContain('4.17.21')
    expect(w.text()).toContain('1 problem found')
    expect(w.text()).toContain('pantera-c')

    const checks = w.findAll('[data-testid="troubleshoot-check"]')
    expect(checks.map(c => c.attributes('data-status'))).toEqual(['ok', 'problem', 'info'])
    expect(checks[1].text()).toContain('negative-cache')
    expect(checks[1].find('.pi-times-circle').exists()).toBe(true)
    expect(checks[0].find('.pi-check-circle').exists()).toBe(true)
  })

  it('links the parsed package to the cooldown inspector', async () => {
    troubleshootMock.mockResolvedValue(response(true))
    const { w } = await mountAt(`/admin/troubleshoot?url=${encodeURIComponent(URL)}`)
    const link = w.findAll('a').find(a => a.text().includes('Inspect package in cooldown'))!
    expect(JSON.parse(link.attributes('data-to')!)).toEqual({
      path: '/cooldown',
      query: { tab: 'inspect', repoType: 'npm', package: 'lodash', repo: 'npm-group' },
    })
  })

  it('applies a fix with one click and re-runs the troubleshoot', async () => {
    troubleshootMock.mockResolvedValueOnce(response(true)).mockResolvedValueOnce(response(false))
    fixMock.mockResolvedValue({ l1: 1, l2: 1 })
    const { w } = await mountAt(`/admin/troubleshoot?url=${encodeURIComponent(URL)}`)

    const fixButtons = w.findAll('[data-testid="troubleshoot-fix"]')
    expect(fixButtons).toHaveLength(1)
    expect(fixButtons[0].text()).toContain('Invalidate package')
    await fixButtons[0].trigger('click')
    await flushPromises()

    expect(fixMock).toHaveBeenCalledWith({
      action: 'invalidate-package',
      endpoint: '/api/v1/admin/neg-cache/invalidate-package',
      body: { artifactName: 'lodash', repoType: 'npm' },
    })
    expect(troubleshootMock).toHaveBeenCalledTimes(2)
    expect(troubleshootMock.mock.calls[1][0]).toBe(URL)
    expect(w.text()).toContain('No problems found')
    expect(w.findAll('[data-testid="troubleshoot-fix"]')).toHaveLength(0)
    expect(useNotificationStore().toasts.at(-1)!.severity).toBe('success')
  })

  it('submitting a URL writes it to the route query and runs it', async () => {
    troubleshootMock.mockResolvedValue(response(false))
    const { w, router } = await mountAt('/admin/troubleshoot')
    expect(troubleshootMock).not.toHaveBeenCalled()

    await w.get('#troubleshoot-url').setValue(URL)
    await w.get('[data-testid="troubleshoot-run"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.url).toBe(URL)
    expect(troubleshootMock).toHaveBeenCalledTimes(1)
    expect(troubleshootMock).toHaveBeenCalledWith(URL)
  })

  it('package mode opens the cooldown inspector', async () => {
    const { w, router } = await mountAt('/admin/troubleshoot')
    const vm = w.vm as unknown as { mode: string; pkgRepoType: string; pkgName: string; openInspector: () => void }
    vm.mode = 'package'
    vm.pkgRepoType = 'pypi'
    vm.pkgName = 'requests'
    vm.openInspector()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/cooldown')
    expect(router.currentRoute.value.query).toEqual({ tab: 'inspect', repoType: 'pypi', package: 'requests' })
  })
})
