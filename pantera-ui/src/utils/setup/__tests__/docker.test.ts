import { describe, it, expect } from 'vitest'
import { dockerSnippets } from '../docker'
import { buildSnippetCtx, type CtxInput } from '../context'
import { TOKEN_PLACEHOLDER, type Client, type Step } from '../types'

const code = (steps: Step[]) => steps.map(s => s.code).join('\n')
const all = (c: Client) => code([...c.configure, ...c.resolve, ...c.publish, ...c.verify])

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(over: Partial<CtxInput> = {}, base = HTTPS): Client[] {
  return dockerSnippets(buildSnippetCtx({
    repo: 'docker_group',
    pubRepo: 'docker_local',
    repoUrl: `${base}/docker_group`,
    pubUrl: `${base}/docker_local`,
    user: 'jane@corp.com',
    token: 'tok-123',
    ...over,
  }))
}

const byId = (clients: Client[], id: string) => {
  const c = clients.find(x => x.id === id)
  expect(c).toBeDefined()
  return c as Client
}

describe('dockerSnippets', () => {
  it('offers docker and podman', () => {
    expect(render().map(c => c.id)).toEqual(['docker', 'podman'])
  })

  it('logs in with the token on stdin and the raw (unencoded) username', () => {
    for (const c of render()) {
      const cfg = code(c.configure)
      expect(cfg).toContain(`echo 'tok-123' | ${c.id} login reg.example.com -u 'jane@corp.com' --password-stdin`)
      expect(cfg).not.toContain('jane%40corp.com')
      expect(cfg).not.toMatch(/ -p /)
    }
  })

  it('writes a config.json auths entry from the precomputed base64, never a base64 pipe', () => {
    const docker = byId(render(), 'docker')
    const file = docker.configure.find(s => s.download === 'config.json')
    expect(file?.file).toBe('~/.docker/config.json')
    const auths = JSON.parse(file?.code ?? '{}').auths
    expect(auths['reg.example.com'].auth).toBe(btoa('jane@corp.com:tok-123'))
    for (const c of render()) expect(all(c)).not.toMatch(/\|\s*base64/)
  })

  it('references images by the path-routed repository path', () => {
    for (const c of render()) {
      expect(code(c.resolve)).toContain(`${c.id} pull reg.example.com/artifactory/docker_group/library/alpine:3.20`)
      expect(code(c.verify)).toContain(`${c.id} pull reg.example.com/artifactory/docker_group/library/alpine:3.20`)
      expect(code(c.resolve)).toContain('FROM reg.example.com/artifactory/docker_group/library/alpine:3.20')
      expect(c.resolve[0].description).toContain('library/')
    }
  })

  it('tags and pushes to the publish repository', () => {
    for (const c of render()) {
      const pub = code(c.publish)
      expect(pub).toContain(`${c.id} tag my-app:1.0.0 reg.example.com/artifactory/docker_local/my-app:1.0.0`)
      expect(pub).toContain(`${c.id} push reg.example.com/artifactory/docker_local/my-app:1.0.0`)
      expect(c.publishNote).toBeUndefined()
    }
  })

  it('adds the insecure-registry switches only for an http registry', () => {
    const [docker, podman] = render({}, HTTP)
    const daemon = docker.configure.find(s => s.file === '/etc/docker/daemon.json')
    expect(JSON.parse(daemon?.code ?? '{}')['insecure-registries']).toEqual(['localhost:8081'])
    const conf = podman.configure.find(s => s.file === '/etc/containers/registries.conf.d/pantera.conf')
    expect(conf?.code).toContain('location = "localhost:8081"')
    expect(conf?.code).toContain('insecure = true')
    expect(code(docker.resolve)).toContain('docker pull localhost:8081/test_prefix/docker_group/library/alpine:3.20')

    for (const c of render()) {
      expect(all(c)).not.toContain('insecure')
      expect(c.configure.some(s => s.file?.startsWith('/etc/'))).toBe(false)
    }
  })

  it('uses the placeholder token when none is given', () => {
    for (const c of render({ token: '' })) {
      expect(code(c.configure)).toContain(`echo '${TOKEN_PLACEHOLDER}' | ${c.id} login`)
      expect(c.configure.find(s => s.title === 'Log in')?.description).toContain(TOKEN_PLACEHOLDER)
    }
  })

  it('has no publish steps and explains why when there is no publish repository', () => {
    for (const c of render({ pubRepo: '', pubUrl: '' })) {
      expect(c.publish).toEqual([])
      expect(c.publishNote).toBeTruthy()
    }
  })

  it('resolves and verifies a local repository without an upstream image', () => {
    const local = render({
      repo: 'docker_local',
      repoUrl: `${HTTP}/docker_local`,
      pubUrl: `${HTTP}/docker_local`,
    }, HTTP)
    for (const c of local) {
      expect(code(c.resolve)).toContain(`${c.id} pull localhost:8081/test_prefix/docker_local/my-app:1.0.0`)
      expect(code(c.resolve)).not.toContain('alpine')
      expect(code(c.verify)).toBe(
        "curl -fsS -u 'jane@corp.com:tok-123' http://localhost:8081/v2/test_prefix/docker_local/my-app/tags/list",
      )
    }
  })
})
