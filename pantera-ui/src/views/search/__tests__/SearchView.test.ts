import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, RouterLinkStub, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SearchView from '../SearchView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { search as searchApi } from '@/api/search'
import type { SearchResult } from '@/types'

vi.mock('@/api/search', () => ({
  search: vi.fn(),
}))

function makeResult(overrides: Partial<SearchResult>): SearchResult {
  return {
    repo_type: 'file',
    repo_name: 'repo',
    artifact_path: 'artifact',
    size: 100,
    ...overrides,
  }
}

async function mountWithResult(item: SearchResult) {
  vi.mocked(searchApi).mockResolvedValue({
    items: [item],
    page: 0,
    size: 20,
    total: 1,
  })
  const wrapper = mount(SearchView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: {
        'router-link': RouterLinkStub,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
  await wrapper.find('[data-testid="search-input"]').setValue('anything')
  const searchButton = wrapper.findAll('button').find((b) => b.text() === 'Search')
  await searchButton!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('SearchView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders search input', () => {
    vi.mocked(searchApi).mockResolvedValue({
      items: [{ repo_type: 'maven', repo_name: 'central', artifact_path: 'com/example/foo', size: 512 }],
      page: 0, size: 20, total: 1,
    })
    const wrapper = mount(SearchView, {
      global: {
        plugins: [[PrimeVue, { theme: { preset: Aura } }]],
        stubs: {
          'router-link': true,
          AppLayout: { template: '<div><slot /></div>' },
        },
      },
    })
    expect(wrapper.find('[data-testid="search-input"]').exists() || wrapper.text().includes('Search')).toBe(true)
  })

  describe('browse target and displayed artifact name', () => {
    it('flat dotted file key (reported bug) browses to repo root and shows the full name', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'services',
        artifact_path: 'wkda.services.b2x-vehicle-store-service.1.0.0-SNAPSHOT.b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom',
        version: '1.0.0-SNAPSHOT',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/services?path=%2F&from=search')
      expect(wrapper.text()).toContain(
        'wkda.services.b2x-vehicle-store-service.1.0.0-SNAPSHOT.b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom',
      )
    })

    it('slashed file key browses to its parent dir and shows the trailing segment as name', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'files',
        artifact_path: '/a/b/c.txt',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/files?path=%2Fa%2Fb&from=search')
      expect(wrapper.text()).toContain('c.txt')
    })

    it('slashed maven key with version dots is not dot-mangled', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'maven',
        repo_name: 'central',
        artifact_path: '/com/google/guava/guava/31.0-jre/guava-31.0-jre.jar',
        version: '31.0-jre',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      const to = link.props('to') as string
      expect(to).not.toContain('31/0-jre')
    })

    // maven/gradle artifact_path is the DOTTED GAV coordinate
    // ("com.fasterxml.jackson.core.jackson-databind"), never a slashed
    // storage key -- MavenScanner records the real version directory in
    // path_prefix instead, and only for proxy repositories.
    it('maven proxy browses to the version directory recorded in path_prefix', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'maven-proxy',
        repo_name: 'maven_proxy',
        artifact_path: 'com.fasterxml.jackson.core.jackson-databind',
        version: '2.22.0',
        path_prefix: 'com/fasterxml/jackson/core/jackson-databind/2.22.0',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/maven_proxy?path=%2Fcom%2Ffasterxml%2Fjackson%2Fcore%2Fjackson-databind%2F2.22.0&from=search',
      )
    })

    // An artifactId that itself contains dots is why path_prefix must win
    // over any dots-to-slashes guess: "javax.inject:javax.inject" lives at
    // javax/inject/javax.inject/1, not javax/inject/javax/inject/1.
    it('maven proxy path_prefix beats the dotted guess for a dotted artifactId', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'maven-proxy',
        repo_name: 'maven_proxy',
        artifact_path: 'javax.inject.javax.inject',
        version: '1',
        path_prefix: 'javax/inject/javax.inject/1',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/maven_proxy?path=%2Fjavax%2Finject%2Fjavax.inject%2F1&from=search',
      )
    })

    // Hosted maven records no path_prefix by design, so the GAV must be
    // expanded: groupId dots are directory separators and the version is a
    // separate segment (the version itself is never dot-split).
    it('maven local expands the dotted GAV and appends the version', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'maven',
        repo_name: 'maven',
        artifact_path: 'com.example.sample-fatjar',
        version: '1.3.0',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/maven?path=%2Fcom%2Fexample%2Fsample-fatjar%2F1.3.0&from=search',
      )
    })

    it('maven local with no version browses to the artifact directory', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'maven',
        repo_name: 'maven',
        artifact_path: 'com.example.sample-fatjar',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/maven?path=%2Fcom%2Fexample%2Fsample-fatjar&from=search',
      )
    })

    it('maven path_prefix with a leading slash does not produce a doubled root', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'maven-proxy',
        repo_name: 'maven_proxy',
        artifact_path: 'com.example.thing',
        version: '1.0.0',
        path_prefix: '/com/example/thing/1.0.0',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/maven_proxy?path=%2Fcom%2Fexample%2Fthing%2F1.0.0&from=search',
      )
    })

    it('npm scoped package path is unchanged', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'npm',
        repo_name: 'npm-local',
        artifact_path: '@scope/pkg',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/npm-local?path=%2F%40scope%2Fpkg&from=search')
    })

    it('does not split on a dot that is part of a directory name', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'files',
        artifact_path: '/com.example/foo/bar.jar',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/files?path=%2Fcom.example%2Ffoo&from=search')
    })

    // helm/debian/rpm fall through to the catch-all branch, which used to
    // emit a parent dir with no leading slash (e.g. "charts" instead of
    // "/charts") -- an absolute-vs-relative mismatch every other branch
    // already avoided.
    it.each([
      ['helm', 'charts', '/charts/mychart-1.2.3.tgz', '%2Fcharts'],
      ['deb', 'debian', '/pool/main/n/nginx/nginx_1.2.3.deb', '%2Fpool%2Fmain%2Fn%2Fnginx'],
      ['rpm', 'rpms', '/packages/foo-1.2.3-1.x86_64.rpm', '%2Fpackages'],
    ])('%s browse path is absolute', async (rtype, repo, path, expected) => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: rtype,
        repo_name: repo,
        artifact_path: path,
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(`/repositories/${repo}?path=${expected}&from=search`)
    })

    it('catch-all repo type at the root still browses to the root', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'helm',
        repo_name: 'charts',
        artifact_path: 'mychart-1.2.3.tgz',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/charts?path=%2F&from=search')
    })

    // gem/hex/conda: artifact_path is not a real directory for any of
    // these (gem/hex storage is flat, conda's artifact_path is a synthetic
    // "name_arch" composite) -- when the backend has recorded the real
    // storage key as path_prefix, browse to ITS parent directory instead.
    it('gem with a real key browses to the flat gems/ directory, not a per-package dir', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'gem',
        repo_name: 'gems',
        artifact_path: 'rails',
        path_prefix: 'gems/rails-7.0.4.gem',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/gems?path=%2Fgems&from=search')
    })

    it('hex with a real key browses to the flat tarballs/ directory, not a per-package dir', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'hexpm',
        repo_name: 'hex',
        artifact_path: 'phoenix',
        path_prefix: 'tarballs/phoenix-1.6.0.tar',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/hex?path=%2Ftarballs&from=search')
    })

    it('conda with a real key browses to the real per-arch directory, not the synthetic name_arch path', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'conda',
        repo_name: 'conda-forge',
        artifact_path: 'numpy_linux-64',
        path_prefix: 'linux-64/numpy-1.21.0-py39_0.tar.bz2',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/conda-forge?path=%2Flinux-64&from=search')
    })

    it('conan browses to its parent directory using artifact_path -- no path_prefix needed', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'conan',
        repo_name: 'conan-local',
        artifact_path: 'zlib/1.2.13/_/_/0/export/conanfile.py',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/conan-local?path=%2Fzlib%2F1.2.13%2F_%2F_%2F0%2Fexport&from=search',
      )
    })

    it('conan artifact at repository root browses to "/", not "//" or empty', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'conan',
        repo_name: 'conan-local',
        artifact_path: 'conanfile.py',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/conan-local?path=%2F&from=search')
    })

    // Rows indexed before path_prefix existed (or any writer that hasn't
    // been updated to populate it yet) must fall back to today's per-format
    // guess -- exactly the pre-fix behaviour -- rather than crash or browse
    // to an empty path.
    it.each([
      ['gem', 'gems', 'rails', '%2Frails'],
      ['hexpm', 'hex', 'phoenix', '%2Fphoenix'],
      ['conda', 'conda-forge', 'numpy_linux-64', '%2Fnumpy_linux-64'],
    ])('%s falls back to the historical guess when path_prefix is absent', async (rtype, repo, path, expected) => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: rtype,
        repo_name: repo,
        artifact_path: path,
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(`/repositories/${repo}?path=${expected}&from=search`)
    })

    // A generic-file artifact_path is the DOTTED display name: the writer
    // flattens every separator into a dot, and that cannot be reversed
    // because filenames and versions carry dots of their own. It holds no
    // slashes, so the pre-fix parentDir() guess returned '' and every result
    // browsed to the repository root. path_prefix is the real key.
    it('file repo browses to the real directory recorded in path_prefix', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'services',
        artifact_path:
          'wkda.services.b2x-vehicle-store-service.1.0.0-SNAPSHOT.b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom',
        version: '1.0.0-SNAPSHOT',
        path_prefix:
          'wkda/services/b2x-vehicle-store-service/1.0.0-SNAPSHOT/b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/services?path=%2Fwkda%2Fservices%2Fb2x-vehicle-store-service%2F1.0.0-SNAPSHOT&from=search',
      )
    })

    it('file repo with a leading slash in path_prefix does not double the root', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file-proxy',
        repo_name: 'files',
        artifact_path: 'a.b.thing-1.0.jar',
        path_prefix: '/a/b/thing-1.0.jar',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/files?path=%2Fa%2Fb&from=search')
    })

    it('file repo falls back to the historical guess without path_prefix', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'file',
        repo_name: 'services',
        artifact_path: 'wkda.services.thing.pom',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe('/repositories/services?path=%2F&from=search')
    })

    // helm/debian/rpm artifact_path is a package name or a name_arch
    // composite, never a directory, so these landed on the root too.
    it.each([
      ['helm', 'charts', 'nginx', 'nginx/nginx-1.2.3.tgz', '%2Fnginx'],
      ['debian', 'deb', 'nginx_amd64', 'pool/main/n/nginx/nginx_1.2.3_amd64.deb', '%2Fpool%2Fmain%2Fn%2Fnginx'],
    ])('%s browses to the directory from path_prefix', async (rtype, repo, name, prefix, expected) => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: rtype,
        repo_name: repo,
        artifact_path: name,
        path_prefix: prefix,
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(`/repositories/${repo}?path=${expected}&from=search`)
    })

    // Docker images live under the registry's "repositories/" root, so the
    // image name is enough. path_prefix points at the tag's manifest link,
    // whose parent holds only a link file, so it is deliberately unused here.
    it('docker browses to the image directory, not the manifest link', async () => {
      const wrapper = await mountWithResult(makeResult({
        repo_type: 'docker',
        repo_name: 'docker_local',
        artifact_path: 'auto1/hello',
        version: 'sha256:abc',
        path_prefix: 'repositories/auto1/hello/_manifests/tags/1.0.0/current/link',
      }))

      const link = wrapper.findComponent(RouterLinkStub)
      expect(link.props('to')).toBe(
        '/repositories/docker_local?path=%2Frepositories%2Fauto1%2Fhello&from=search',
      )
    })
  })
})
