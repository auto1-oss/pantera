import { describe, it, expect } from 'vitest'
import { buildSnippetCtx } from '../context'
import { gemSnippets } from '../gem'
import type { Client, Step } from '../types'

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(base: string, opts: { token?: string, pub?: boolean, user?: string } = {}): Client[] {
  const pub = opts.pub ?? true
  return gemSnippets(buildSnippetCtx({
    repo: 'gem_group',
    pubRepo: pub ? 'gem_local' : '',
    repoUrl: `${base}/gem_group`,
    pubUrl: pub ? `${base}/gem_local` : '',
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

describe('gemSnippets', () => {
  it('offers gem and bundler', () => {
    expect(render(HTTPS).map(c => c.id)).toEqual(['gem', 'bundler'])
  })

  it('adds the resolve repo as a gem source with the percent-encoded username', () => {
    const gem = byId(render(HTTPS), 'gem')
    expect(gem.configure[0].code)
      .toBe(`gem sources --add https://jane%40corp.com:tok-123@reg.example.com/artifactory/gem_group/`)
    expect(gem.resolve[0].code).toBe('gem install my-gem')
  })

  it('stores a Basic push key for the publish repo in ~/.gem/credentials', () => {
    const gem = byId(render(HTTPS), 'gem')
    const creds = gem.configure.find(s => s.code.includes('~/.gem/credentials'))
    const b64 = btoa('jane@corp.com:tok-123')
    expect(creds?.code).toContain(`echo '${HTTPS}/gem_local: Basic ${b64}' >> ~/.gem/credentials`)
    expect(creds?.code).toContain('chmod 0600 ~/.gem/credentials')
    // RubyGems keeps the quotes of a quoted key, so the host key must be unquoted
    expect(creds?.code).not.toContain(`"${HTTPS}`)
  })

  it('pushes to the publish repo with gem push and offers a curl fallback', () => {
    const gem = byId(render(HTTPS), 'gem')
    expect(gem.publish.map(s => s.code)).toEqual([
      'gem build my-gem.gemspec',
      `gem push my-gem-0.1.0.gem --host ${HTTPS}/gem_local`,
      `curl -f -u 'jane@corp.com:tok-123' --data-binary @my-gem-0.1.0.gem ${HTTPS}/gem_local/api/v1/gems`,
    ])
    expect(gem.publishNote).toBeUndefined()
  })

  it('verifies with a verbose search limited to the resolve repo', () => {
    const gem = byId(render(HTTPS), 'gem')
    expect(gem.verify).toHaveLength(1)
    expect(gem.verify[0].code).toBe(
      'gem search --verbose --remote --clear-sources --source '
      + 'https://jane%40corp.com:tok-123@reg.example.com/artifactory/gem_group/',
    )
    expect(gem.verify[0].description).toContain('200 OK')
  })

  it('keys bundler credentials by the source URL and keeps them out of the Gemfile', () => {
    const bundler = byId(render(HTTPS), 'bundler')
    expect(bundler.configure[0].code)
      .toBe(`bundle config set --global ${HTTPS}/gem_group/ 'jane%40corp.com:tok-123'`)
    const gemfile = bundler.resolve.find(s => s.file === 'Gemfile')
    expect(gemfile?.download).toBe('Gemfile')
    expect(gemfile?.lang).toBe('ruby')
    expect(gemfile?.code).toContain(`source "${HTTPS}/gem_group/" do\n  gem "my-gem"\nend`)
    expect(gemfile?.code).not.toContain('tok-123')
    expect(bundler.resolve.map(s => s.code)).toContain('bundle install')
    expect(bundler.verify[0].code).toBe('bundle info my-gem')
    expect(bundler.publish).toEqual([])
    expect(bundler.publishNote).toContain('gem push')
  })

  it('uses the http URLs as-is and adds no insecure switches', () => {
    const clients = render(HTTP)
    const gem = byId(clients, 'gem')
    expect(gem.configure[0].code)
      .toBe('gem sources --add http://jane%40corp.com:tok-123@localhost:8081/test_prefix/gem_group/')
    expect(gem.publish[1].code).toBe(`gem push my-gem-0.1.0.gem --host ${HTTP}/gem_local`)
    expect(byId(clients, 'bundler').configure[0].code).toContain(`${HTTP}/gem_group/ `)
    const code = allCode(clients)
    expect(code).not.toContain('--force')
    expect(code).not.toContain('https://localhost')
  })

  it('renders the token placeholder when there is no token', () => {
    const clients = render(HTTPS, { token: '' })
    const gem = byId(clients, 'gem')
    expect(gem.configure[0].code).toContain('jane%40corp.com:YOUR_TOKEN@reg.example.com')
    expect(gem.configure[1].code).toContain(`Basic ${btoa('jane@corp.com:YOUR_TOKEN')}`)
    expect(byId(clients, 'bundler').configure[0].code).toContain(`'jane%40corp.com:YOUR_TOKEN'`)
  })

  it('drops publish steps and push credentials without a publish repo', () => {
    const clients = render(HTTPS, { pub: false })
    for (const c of clients) {
      expect(c.publish).toEqual([])
      expect(c.publishNote).toContain('Publish to')
    }
    const gem = byId(clients, 'gem')
    expect(gem.configure).toHaveLength(1)
    expect(allCode(clients)).not.toContain('gem_local')
    expect(allCode(clients)).not.toContain('credentials')
  })

  it('never pipes through base64', () => {
    for (const base of [HTTPS, HTTP]) {
      for (const pub of [true, false]) {
        expect(allCode(render(base, { pub }))).not.toMatch(/\|\s*base64/)
      }
    }
  })
})
