import { describe, it, expect } from 'vitest'
import { buildSnippetCtx } from '../context'
import { debSnippets } from '../deb'
import type { Client, Step } from '../types'

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(base: string, opts: { token?: string, pub?: boolean } = {}): Client {
  const pub = opts.pub ?? true
  const clients = debSnippets(buildSnippetCtx({
    repo: 'deb',
    pubRepo: pub ? 'deb' : '',
    repoUrl: `${base}/deb`,
    pubUrl: pub ? `${base}/deb` : '',
    user: 'jane@corp.com',
    token: opts.token ?? 'tok-123',
  }))
  expect(clients.map(c => c.id)).toEqual(['apt'])
  return clients[0]
}

const allSteps = (c: Client): Step[] => [...c.configure, ...c.resolve, ...c.publish, ...c.verify]
const allCode = (c: Client): string => allSteps(c).map(s => s.code).join('\n')
const authStep = (c: Client): Step => {
  const s = c.configure.find(x => x.file === '/etc/apt/auth.conf.d/pantera.conf')
  if (!s) throw new Error('missing auth.conf step')
  return s
}

describe('debSnippets', () => {
  it('keeps credentials in auth.conf.d, not in the source line', () => {
    const c = render(HTTPS)
    const auth = authStep(c).code
    expect(auth, 'machine').toContain('machine reg.example.com/artifactory/deb\n')
    expect(auth, 'login').toContain('login jane@corp.com\n')
    expect(auth, 'password').toContain('password tok-123\n')
    expect(auth, 'private').toContain('sudo chmod 600 /etc/apt/auth.conf.d/pantera.conf')
    const list = c.configure.find(s => s.file === '/etc/apt/sources.list.d/pantera.list')
    expect(list?.code, 'source line').toContain(
      'deb [trusted=yes] https://reg.example.com/artifactory/deb deb main',
    )
    expect(list?.code, 'no creds in URL').not.toMatch(/tok-123|jane/)
  })

  it('annotates the auth.conf machine with http:// only for a plain-HTTP registry', () => {
    expect(authStep(render(HTTP)).code, 'http').toContain(
      'machine http://localhost:8081/test_prefix/deb\n',
    )
    expect(authStep(render(HTTPS)).code, 'https').not.toContain('://')
  })

  it('uploads each package under its own name in pool/main', () => {
    const pub = render(HTTPS).publish
    expect(pub).toHaveLength(1)
    expect(pub[0].code, 'target').toContain('https://reg.example.com/artifactory/deb/pool/main/')
    expect(pub[0].code, 'auth').toContain("-u 'jane@corp.com:tok-123'")
    expect(pub[0].code, 'bare component key').not.toMatch(/\/deb\/main\b/)
  })

  it('returns no publish steps without a publish repository', () => {
    expect(render(HTTPS, { pub: false }).publish).toEqual([])
  })

  it('shows the placeholder when there is no token', () => {
    const c = render(HTTPS, { token: '' })
    expect(authStep(c).code, 'auth.conf').toContain('password YOUR_TOKEN\n')
    expect(c.publish[0].code, 'publish').toContain("'jane@corp.com:YOUR_TOKEN'")
  })

  it('verifies against the resolve URL and never pipes through base64', () => {
    const c = render(HTTP)
    expect(c.verify[0].code, 'verify').toContain("apt-cache policy | grep -F 'http://localhost:8081/test_prefix/deb '")
    expect(allCode(c), 'base64').not.toContain('base64')
  })
})
