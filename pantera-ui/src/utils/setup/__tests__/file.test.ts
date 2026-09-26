import { describe, it, expect } from 'vitest'
import { buildSnippetCtx } from '../context'
import { fileSnippets } from '../file'
import type { Client, Step } from '../types'

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(base: string, opts: { token?: string, pub?: boolean, user?: string } = {}): Client[] {
  const pub = opts.pub ?? true
  return fileSnippets(buildSnippetCtx({
    repo: 'file_group',
    pubRepo: pub ? 'file' : '',
    repoUrl: `${base}/file_group`,
    pubUrl: pub ? `${base}/file` : '',
    user: opts.user ?? 'jane@corp.com',
    token: opts.token ?? 'tok-123',
  }))
}

const byId = (clients: Client[], id: string): Client => {
  const c = clients.find(x => x.id === id)
  if (!c) throw new Error(`missing client ${id}`)
  return c
}

const allSteps = (c: Client): Step[] => [...c.configure, ...c.resolve, ...c.publish, ...c.verify]
const allCode = (clients: Client[]): string => clients.flatMap(allSteps).map(s => s.code).join('\n')

describe('fileSnippets', () => {
  it('offers curl and wget', () => {
    expect(render(HTTPS).map(c => c.id)).toEqual(['curl', 'wget'])
  })

  it('writes a ~/.netrc entry keyed by the host without the port', () => {
    for (const c of render(HTTP)) {
      const netrc = c.configure.find(s => s.file === '~/.netrc')
      expect(netrc?.download).toBe('.netrc')
      expect(netrc?.code).toContain('machine localhost\n')
      expect(netrc?.code).not.toContain('8081')
      expect(netrc?.code).toContain('login jane@corp.com\n')
      expect(netrc?.code).toContain('password tok-123')
      expect(c.configure.map(s => s.code)).toContain('chmod 600 ~/.netrc')
    }
  })

  it('curl resolves via the repo URL and publishes via the local repo URL', () => {
    const curl = byId(render(HTTPS), 'curl')
    expect(curl.resolve[0].code).toBe(`curl -fL --netrc -O ${HTTPS}/file_group/path/to/FILE`)
    expect(curl.resolve[1].code).toContain(`-u 'jane@corp.com:tok-123'`)
    expect(curl.publish[0].code).toBe(`curl -f --netrc -T FILE ${HTTPS}/file/path/to/FILE`)
    expect(curl.publish[1].code).toContain(`curl -f -u 'jane@corp.com:tok-123' -T FILE ${HTTPS}/file/path/to/FILE`)
    expect(curl.verify).toHaveLength(1)
    expect(curl.verify[0].code).toContain(`-w '%{http_code}\\n' ${HTTPS}/file_group/path/to/FILE`)
    expect(curl.verify[0].description).toContain('200')
  })

  it('wget resolves, uploads with PUT and verifies', () => {
    const wget = byId(render(HTTPS), 'wget')
    expect(wget.resolve[0].code).toBe(`wget ${HTTPS}/file_group/path/to/FILE`)
    expect(wget.resolve[1].code).toContain(`--user='jane@corp.com' --password='tok-123'`)
    expect(wget.publish[0].code).toContain('--method=PUT --body-file=FILE')
    expect(wget.publish[0].code).toContain(`${HTTPS}/file/path/to/FILE`)
    expect(wget.publish[1].code).toContain(`--user='jane@corp.com' --password='tok-123'`)
    expect(wget.verify).toHaveLength(1)
    expect(wget.verify[0].code).toContain(`${HTTPS}/file_group/path/to/FILE && echo OK`)
  })

  it('never builds an Authorization header with base64', () => {
    for (const base of [HTTPS, HTTP]) {
      const code = allCode(render(base))
      expect(code).not.toContain('base64')
      expect(code).not.toContain('Authorization')
    }
  })

  it('needs no insecure switches for http or https', () => {
    for (const base of [HTTPS, HTTP]) {
      const code = allCode(render(base))
      expect(code).not.toMatch(/(^|\s)(-k|--insecure|--no-check-certificate)(\s|$)/m)
    }
  })

  it('uses URLs on the http base as given', () => {
    const curl = byId(render(HTTP), 'curl')
    expect(curl.resolve[0].code).toContain(`${HTTP}/file_group/path/to/FILE`)
    expect(curl.publish[0].code).toContain(`${HTTP}/file/path/to/FILE`)
  })

  it('uses the token placeholder when no token is given', () => {
    const clients = render(HTTPS, { token: '' })
    const code = allCode(clients)
    expect(code).toContain('password YOUR_TOKEN')
    expect(code).toContain(`-u 'jane@corp.com:YOUR_TOKEN'`)
    expect(code).toContain(`--password='YOUR_TOKEN'`)
  })

  it('has no publish steps and explains why when there is no publish repository', () => {
    for (const c of render(HTTPS, { pub: false })) {
      expect(c.publish).toEqual([])
      expect(c.publishNote).toBeTruthy()
      expect(allSteps(c).map(s => s.code).join('\n')).not.toContain('/file/path')
    }
  })

  it('omits the publish note when a publish repository is chosen', () => {
    for (const c of render(HTTPS)) {
      expect(c.publish.length).toBeGreaterThan(0)
      expect(c.publishNote).toBeUndefined()
    }
  })
})
