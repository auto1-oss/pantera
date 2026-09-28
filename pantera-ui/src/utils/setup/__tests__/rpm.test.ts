import { describe, it, expect } from 'vitest'
import { buildSnippetCtx } from '../context'
import { rpmSnippets } from '../rpm'
import type { Client, Step } from '../types'

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(base: string, opts: { token?: string, pub?: boolean } = {}): Client {
  const pub = opts.pub ?? true
  const clients = rpmSnippets(buildSnippetCtx({
    repo: 'rpm',
    pubRepo: pub ? 'rpm' : '',
    repoUrl: `${base}/rpm`,
    pubUrl: pub ? `${base}/rpm` : '',
    user: 'jane@corp.com',
    token: opts.token ?? 'tok-123',
  }))
  expect(clients.map(c => c.id)).toEqual(['dnf'])
  return clients[0]
}

const allSteps = (c: Client): Step[] => [...c.configure, ...c.resolve, ...c.publish, ...c.verify]
const allCode = (c: Client): string => allSteps(c).map(s => s.code).join('\n')
const repoFile = (c: Client): string => {
  const s = c.configure.find(x => x.file === '/etc/yum.repos.d/pantera.repo')
  if (!s) throw new Error('missing .repo step')
  return s.code
}

describe('rpmSnippets', () => {
  it('writes a root-only .repo file with the credentials as separate keys', () => {
    const repo = repoFile(render(HTTPS))
    expect(repo, 'section').toContain('[pantera]\n')
    expect(repo, 'baseurl').toContain('baseurl=https://reg.example.com/artifactory/rpm\n')
    expect(repo, 'username').toContain('username=jane@corp.com\n')
    expect(repo, 'password').toContain('password=tok-123\n')
    expect(repo, 'fail loudly').toContain('skip_if_unavailable=0\n')
    expect(repo, 'private').toContain('sudo chmod 600 /etc/yum.repos.d/pantera.repo')
  })

  it('adds no insecure switches for either scheme', () => {
    expect(repoFile(render(HTTP)), 'http').not.toMatch(/sslverify/)
    expect(repoFile(render(HTTPS)), 'https').not.toMatch(/sslverify/)
  })

  it('uploads with a PUT to the publish repository', () => {
    const pub = render(HTTP).publish
    expect(pub).toHaveLength(1)
    expect(pub[0].code, 'auth').toContain("-u 'jane@corp.com:tok-123'")
    expect(pub[0].code, 'target').toMatch(/-T \S+\.rpm \\\n {2}http:\/\/localhost:8081\/test_prefix\/rpm\/$/)
  })

  it('returns no publish steps without a publish repository', () => {
    expect(render(HTTPS, { pub: false }).publish).toEqual([])
  })

  it('shows the placeholder when there is no token', () => {
    const c = render(HTTPS, { token: '' })
    expect(repoFile(c), 'repo file').toContain('password=YOUR_TOKEN\n')
    expect(c.publish[0].code, 'publish').toContain("'jane@corp.com:YOUR_TOKEN'")
  })

  it('verifies with a forced metadata refresh and never pipes through base64', () => {
    const c = render(HTTPS)
    expect(c.verify[0].code, 'verify').toBe('sudo dnf --repo pantera --refresh makecache')
    expect(allCode(c), 'base64').not.toContain('base64')
  })
})
