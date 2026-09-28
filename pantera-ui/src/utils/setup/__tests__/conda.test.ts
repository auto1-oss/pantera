import { describe, it, expect } from 'vitest'
import { condaSnippets } from '../conda'
import { buildSnippetCtx, type CtxInput } from '../context'
import { TOKEN_PLACEHOLDER, type Client, type Step } from '../types'

const code = (steps: Step[]) => steps.map(s => s.code).join('\n')
const all = (c: Client) => code([...c.configure, ...c.resolve, ...c.publish, ...c.verify])

function render(over: Partial<CtxInput> = {}): Client {
  const clients = condaSnippets(buildSnippetCtx({
    repo: 'conda-local',
    pubRepo: 'conda-local',
    repoUrl: 'https://reg.example.com/artifactory/conda-local',
    pubUrl: 'https://reg.example.com/artifactory/conda-local',
    user: 'jane@corp.com',
    token: 'tok-123',
    ...over,
  }))
  expect(clients).toHaveLength(1)
  return clients[0]
}

describe('condaSnippets', () => {
  it('keeps credentials in ~/.netrc and out of the channel URL', () => {
    const conda = render()
    const cfg = code(conda.configure)
    expect(cfg, 'netrc entry').toContain('machine reg.example.com login jane@corp.com password tok-123')
    expect(cfg, 'netrc permissions').toContain('chmod 600 ~/.netrc')
    expect(cfg, 'channel').toContain(
      'conda config --prepend channels https://reg.example.com/artifactory/conda-local',
    )
    expect(all(conda), 'no userinfo in URLs').not.toMatch(/\/\/[^/\s]*@/)
    expect(all(conda), 'no encoded username').not.toContain('jane%40corp.com')
  })

  it('uses the host without the port as the netrc machine', () => {
    const cfg = code(render({
      repoUrl: 'http://localhost:8081/test_prefix/conda-local',
      pubUrl: 'http://localhost:8081/test_prefix/conda-local',
    }).configure)
    expect(cfg).toContain('machine localhost login')
  })

  it('installs through the configured channels', () => {
    expect(code(render().resolve)).toBe('conda install PACKAGE')
  })

  it('uploads with the token scheme to the publish URL and never pipes through base64', () => {
    const conda = render({ repoUrl: 'https://reg.example.com/artifactory/conda-group' })
    const pub = code(conda.publish)
    expect(pub, 'auth header').toContain("curl -fsS -H 'Authorization: token tok-123'")
    expect(pub, 'multipart field').toContain('-F "file=@$PKG"')
    expect(pub, 'target').toContain(
      '"https://reg.example.com/artifactory/conda-local/$SUBDIR/$(basename "$PKG")"',
    )
    expect(all(conda), 'no base64').not.toMatch(/base64/)
    expect(conda.publishNote, 'no note').toBeUndefined()
  })

  it('verifies with one search against the resolve channel', () => {
    const conda = render()
    expect(conda.verify).toHaveLength(1)
    expect(conda.verify[0].code).toBe(
      "conda search --override-channels -c https://reg.example.com/artifactory/conda-local '*'",
    )
  })

  it('needs no insecure switch for http registries', () => {
    const conda = render({
      repoUrl: 'http://localhost:8081/test_prefix/conda-local',
      pubUrl: 'http://localhost:8081/test_prefix/conda-local',
    })
    expect(all(conda), 'no ssl_verify').not.toContain('ssl_verify')
    expect(all(conda), 'no curl -k').not.toMatch(/curl -[a-zA-Z]*k/)
    expect(all(conda), 'http channel').toContain('http://localhost:8081/test_prefix/conda-local')
  })

  it('renders the token placeholder when no token is set', () => {
    const conda = render({ token: '' })
    expect(code(conda.configure), 'netrc').toContain(`password ${TOKEN_PLACEHOLDER}`)
    expect(code(conda.publish), 'upload').toContain(`Authorization: token ${TOKEN_PLACEHOLDER}`)
  })

  it('has no publish steps without a publish repository', () => {
    const conda = render({ pubRepo: '', pubUrl: '' })
    expect(conda.publish).toEqual([])
  })
})
