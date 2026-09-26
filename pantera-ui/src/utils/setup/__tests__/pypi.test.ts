import { describe, it, expect } from 'vitest'
import { pypiSnippets } from '../pypi'
import { buildSnippetCtx } from '../context'
import type { Client, Step } from '../types'

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://reg.example.com:8081/test_prefix'

function render(base: string, opts: { token?: string, pub?: boolean } = {}): Client[] {
  const pub = opts.pub ?? true
  return pypiSnippets(buildSnippetCtx({
    repo: 'pypi_group',
    pubRepo: pub ? 'pypi_local' : '',
    repoUrl: `${base}/pypi_group`,
    pubUrl: pub ? `${base}/pypi_local` : '',
    user: 'jane@corp.com',
    token: opts.token ?? 'tok123',
  }))
}

const client = (clients: Client[], id: string) => clients.find(c => c.id === id)!
const steps = (c: Client): Step[] => [...c.configure, ...c.resolve, ...c.publish, ...c.verify]
const allCode = (c: Client) => steps(c).map(s => s.code).join('\n')

describe('pypiSnippets', () => {
  it('offers pip, uv and poetry', () => {
    expect(render(HTTPS).map(c => c.id)).toEqual(['pip', 'uv', 'poetry'])
  })

  it('every client has a verify step with an expected result', () => {
    for (const c of render(HTTPS)) {
      expect(c.verify, c.id).toHaveLength(1)
      expect(c.verify[0].description, c.id).toMatch(/Prints/)
    }
  })

  it('pip.conf carries the percent-encoded username and the /simple/ index', () => {
    const conf = client(render(HTTPS), 'pip').configure[0]
    expect(conf.file, 'file').toBe('~/.config/pip/pip.conf')
    expect(conf.download, 'download').toBe('pip.conf')
    expect(conf.code, 'index url')
      .toContain('index-url = https://jane%40corp.com:tok123@reg.example.com/artifactory/pypi_group/simple/')
  })

  it('adds trusted-host only for a plain-HTTP registry', () => {
    expect(client(render(HTTPS), 'pip').configure[0].code, 'https').not.toContain('trusted-host')
    const http = client(render(HTTP), 'pip').configure[0].code
    expect(http, 'http index').toContain('index-url = http://jane%40corp.com:tok123@reg.example.com:8081/test_prefix/pypi_group/simple/')
    expect(http, 'http trusted-host').toContain('trusted-host = reg.example.com')
    expect(http, 'no port in trusted-host').not.toContain('trusted-host = reg.example.com:8081')
  })

  it('twine uploads to the local repository through ~/.pypirc', () => {
    const [pypirc, upload] = client(render(HTTPS), 'pip').publish
    expect(pypirc.file, 'file').toBe('~/.pypirc')
    expect(pypirc.code, 'repository').toContain('repository = https://reg.example.com/artifactory/pypi_local')
    expect(pypirc.code, 'username').toContain('username = jane@corp.com')
    expect(pypirc.code, 'password').toContain('password = tok123')
    expect(upload.code, 'twine').toContain('twine upload --repository pantera dist/*')
  })

  it('uv declares the index with publish-url and credentials in env vars', () => {
    const uv = client(render(HTTPS), 'uv')
    const index = uv.configure[0].code
    expect(index, 'url').toContain('url = "https://reg.example.com/artifactory/pypi_group/simple/"')
    expect(index, 'publish-url').toContain('publish-url = "https://reg.example.com/artifactory/pypi_local"')
    expect(index, 'default').toContain('default = true')
    expect(index, 'no creds in url').not.toContain('tok123')
    expect(uv.configure[1].code, 'username env').toContain("export UV_INDEX_PANTERA_USERNAME='jane@corp.com'")
    expect(uv.configure[1].code, 'password env').toContain("export UV_INDEX_PANTERA_PASSWORD='tok123'")
    expect(uv.publish[0].code, 'publish').toContain('uv publish --index pantera --trusted-publishing never')
  })

  it('poetry adds a primary source, credentials and a separate upload repository', () => {
    const code = allCode(client(render(HTTPS), 'poetry'))
    expect(code, 'source').toContain('poetry source add --priority=primary pantera https://reg.example.com/artifactory/pypi_group/simple/')
    expect(code, 'source creds').toContain("poetry config http-basic.pantera 'jane@corp.com' 'tok123'")
    expect(code, 'upload repo').toContain('poetry config repositories.pantera-publish https://reg.example.com/artifactory/pypi_local')
    expect(code, 'upload creds').toContain("poetry config http-basic.pantera-publish 'jane@corp.com' 'tok123'")
    expect(code, 'publish').toContain('poetry publish --build -r pantera-publish')
  })

  it('emits no insecure switches for https and no base64 pipes', () => {
    for (const c of render(HTTPS)) {
      const code = allCode(c)
      expect(code, c.id).not.toMatch(/trusted-host|allow-insecure|--insecure/)
      expect(code, c.id).not.toMatch(/\|\s*base64/)
    }
  })

  it('falls back to the token placeholder', () => {
    const clients = render(HTTPS, { token: '' })
    expect(client(clients, 'pip').configure[0].code, 'pip').toContain('jane%40corp.com:YOUR_TOKEN@')
    expect(allCode(client(clients, 'uv')), 'uv').toContain("UV_INDEX_PANTERA_PASSWORD='YOUR_TOKEN'")
    expect(allCode(client(clients, 'poetry')), 'poetry').toContain("'jane@corp.com' 'YOUR_TOKEN'")
  })

  it('has no publish flow without a publish repository', () => {
    for (const c of render(HTTPS, { pub: false })) {
      expect(c.publish, c.id).toEqual([])
      expect(allCode(c), c.id).not.toContain('pypi_local')
    }
    expect(client(render(HTTPS, { pub: false }), 'uv').configure[0].code).not.toContain('publish-url')
  })
})
