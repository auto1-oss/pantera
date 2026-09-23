import { describe, it, expect } from 'vitest'
import { hexpmSnippets } from '../hexpm'
import { buildSnippetCtx, type CtxInput } from '../context'
import { TOKEN_PLACEHOLDER, type Client, type Step } from '../types'

const code = (steps: Step[]) => steps.map(s => s.code).join('\n')
const all = (c: Client) => code([...c.configure, ...c.resolve, ...c.publish, ...c.verify])

const B64 = btoa('jane@corp.com:tok-123')

function render(over: Partial<CtxInput> = {}): Client {
  const clients = hexpmSnippets(buildSnippetCtx({
    repo: 'hex',
    pubRepo: 'hex',
    repoUrl: 'https://reg.example.com/artifactory/hex',
    pubUrl: 'https://reg.example.com/artifactory/hex',
    user: 'jane@corp.com',
    token: 'tok-123',
    ...over,
  }))
  expect(clients).toHaveLength(1)
  return clients[0]
}

describe('hexpmSnippets', () => {
  it('downloads the registry key and registers the repository under its own name', () => {
    const cfg = code(render().configure)
    expect(cfg).toContain('-o pantera-hex.pem')
    expect(cfg).toContain('https://reg.example.com/artifactory/hex/public_key')
    expect(cfg).toContain('mix hex.repo add hex https://reg.example.com/artifactory/hex')
    expect(cfg).toContain('--public-key pantera-hex.pem')
    expect(cfg).toContain(`--auth-key 'Basic ${B64}'`)
    expect(code([render().configure[1]])).not.toContain('tok-123')
  })

  it('declares the dependency against the repository and keeps the registry signature check', () => {
    const mix = render()
    expect(mix.resolve[0].code).toContain('repo: "hex"')
    expect(mix.resolve[0].lang).toBe('elixir')
    expect(mix.resolve[1].code).toBe('mix deps.get')
    expect(all(mix)).not.toContain('HEX_UNSAFE_REGISTRY')
  })

  it('builds with mix and uploads the tarball with curl to the publish URL', () => {
    const pub = code(render().publish)
    expect(pub).toContain('mix hex.build')
    expect(pub).toContain("curl -fsS -u 'jane@corp.com:tok-123'")
    expect(pub).toContain('--data-binary @my_package-0.1.0.tar')
    expect(pub).toContain("'https://reg.example.com/artifactory/hex/publish?replace=false'")
    expect(pub).not.toContain('mix hex.publish')
  })

  it('verifies with the same Authorization header Mix sends', () => {
    const mix = render()
    expect(code(mix.verify)).toContain(`-H 'Authorization: Basic ${B64}'`)
    expect(code(mix.verify)).toContain('https://reg.example.com/artifactory/hex/users/me')
    expect(mix.verify[0].description).toContain('204')
  })

  it('never pipes through base64', () => {
    expect(all(render())).not.toMatch(/\|\s*base64/)
  })

  it('adds no insecure switches for either scheme', () => {
    const http = render({ repoUrl: 'http://localhost:8081/test_prefix/hex', pubUrl: 'http://localhost:8081/test_prefix/hex' })
    expect(all(http)).toContain('mix hex.repo add hex http://localhost:8081/test_prefix/hex')
    expect(all(http)).not.toMatch(/unsafe_https|HEX_UNSAFE_HTTPS/)
    expect(all(render())).not.toMatch(/unsafe_https|HEX_UNSAFE_HTTPS/)
  })

  it('encodes the placeholder and says so when there is no token', () => {
    const mix = render({ token: '' })
    expect(code(mix.configure)).toContain(`Basic ${btoa(`jane@corp.com:${TOKEN_PLACEHOLDER}`)}`)
    expect(mix.configure[1].description).toContain(TOKEN_PLACEHOLDER)
    expect(code(mix.publish)).toContain(`'jane@corp.com:${TOKEN_PLACEHOLDER}'`)
    expect(render().configure[1].description).not.toContain(TOKEN_PLACEHOLDER)
  })

  it('has no publish steps without a publish repository', () => {
    const mix = render({ pubRepo: '', pubUrl: '' })
    expect(mix.publish).toEqual([])
    expect(mix.publishNote).toBeTruthy()
    expect(render().publishNote).toBeUndefined()
  })
})
