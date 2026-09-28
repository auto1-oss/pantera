import { describe, it, expect } from 'vitest'
import { buildSnippetCtx } from '../context'
import { goSnippets } from '../go'
import { TOKEN_PLACEHOLDER, type Client, type Step } from '../types'

const HTTPS = 'https://reg.example.com:8443/artifactory'
const HTTPS_NO_PORT = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(
  base: string,
  opts: { token?: string, pub?: boolean, repo?: string, user?: string } = {},
): Client {
  const pub = opts.pub ?? true
  const repo = opts.repo ?? 'go_group'
  const clients = goSnippets(buildSnippetCtx({
    repo,
    pubRepo: pub ? 'go' : '',
    repoUrl: `${base}/${repo}`,
    pubUrl: pub ? `${base}/go` : '',
    user: opts.user ?? 'jane@corp.com',
    token: opts.token ?? 'tok-123',
  }))
  expect(clients).toHaveLength(1)
  return clients[0]
}

const allSteps = (c: Client): Step[] => [...c.configure, ...c.resolve, ...c.publish, ...c.verify]
const allCode = (c: Client): string => allSteps(c).map(s => s.code).join('\n')

describe('goSnippets over https', () => {
  const c = render(HTTPS)

  it('keeps credentials in ~/.netrc for host:port and bare host', () => {
    const netrc = c.configure.find(s => s.file === '~/.netrc')
    expect(netrc?.code).toContain('machine reg.example.com:8443 login jane@corp.com password tok-123\n')
    expect(netrc?.code).toContain('machine reg.example.com login jane@corp.com password tok-123\n')
    expect(netrc?.code).toContain('chmod 600 ~/.netrc')
  })

  it('emits a single machine line when the URL has no port', () => {
    const netrc = render(HTTPS_NO_PORT).configure.find(s => s.file === '~/.netrc')
    expect(netrc?.code.match(/^machine /gm)).toHaveLength(1)
  })

  it('persists GOPROXY without credentials or a direct fallback', () => {
    const env = c.configure.find(s => s.code.startsWith('go env -w'))
    expect(env?.code).toBe(`go env -w GOPROXY=${HTTPS}/go_group GONOSUMDB=github.com/YOUR_ORG`)
    expect(allCode(c)).not.toContain(',direct')
    expect(allCode(c)).not.toMatch(/https?:\/\/[^\s/]*@/)
    expect(allCode(c)).not.toContain('GOPRIVATE=')
  })

  it('never emits insecure switches', () => {
    expect(allCode(c)).not.toContain('GOINSECURE')
    expect(allCode(c)).not.toContain('-insecure')
  })

  it('resolves and verifies with a public module', () => {
    expect(c.resolve[0].code).toBe('go get rsc.io/quote@v1.5.2')
    expect(c.verify).toHaveLength(1)
    expect(c.verify[0].code).toBe('go list -m -versions rsc.io/quote')
  })

  it('publishes .info, .mod and .zip (zip last) to the local repo with curl --netrc', () => {
    const pack = c.publish[0].code
    expect(pack).toContain('zip -qrD')
    expect(pack).toContain('git archive HEAD')
    const upload = c.publish[1].code
    expect(upload).toContain('for f in info mod zip; do')
    expect(upload).toContain(`curl --netrc -fsS -T "$STAGE/$VER.$f" "${HTTPS}/go/$ESC/@v/$VER.$f"`)
    expect(c.publishNote).toBeUndefined()
  })

  it('does not pipe through base64', () => {
    expect(allCode(c)).not.toContain('base64')
  })
})

describe('goSnippets over plain http', () => {
  const c = render(HTTP)

  it('states that the go command cannot authenticate', () => {
    expect(c.label).toContain('curl only')
    expect(c.configure[0].description).toContain('cannot use this registry')
  })

  it('does not emit a GOPROXY that cannot work', () => {
    expect(allCode(c)).not.toContain('GOPROXY')
    expect(allCode(c)).not.toContain('GOINSECURE')
  })

  it('resolves, publishes and verifies with curl --netrc', () => {
    expect(c.configure[0].code).toContain('machine localhost:8081 login jane@corp.com password tok-123\n')
    expect(c.configure[0].code).toContain('machine localhost login jane@corp.com password tok-123\n')
    expect(c.resolve[0].code).toBe(`curl --netrc -fsSLO ${HTTP}/go_group/rsc.io/quote/@v/v1.5.2.zip`)
    expect(c.verify[0].code).toBe(`curl --netrc -fsS ${HTTP}/go_group/rsc.io/quote/@v/list`)
    expect(c.publish[1].code).toContain(`"${HTTP}/go/$ESC/@v/$VER.$f"`)
  })
})

describe('goSnippets edge cases', () => {
  it('uses the placeholder when there is no token', () => {
    const c = render(HTTPS, { token: '' })
    expect(c.configure[0].code).toContain(`password ${TOKEN_PLACEHOLDER}`)
  })

  it('leaves publish empty with a note when there is no publish repo', () => {
    for (const base of [HTTPS, HTTP]) {
      const c = render(base, { pub: false })
      expect(c.publish).toEqual([])
      expect(c.publishNote).toContain('no publish command')
    }
  })

  it('checks access without a public module on a local repo', () => {
    const c = render(HTTPS, { repo: 'go' })
    expect(c.verify[0].code).toBe(`curl --netrc -s -o /dev/null -w '%{http_code}\\n' ${HTTPS}/go/rsc.io/quote/@v/list`)
    expect(c.resolve[0].code).not.toContain('rsc.io')
  })
})
