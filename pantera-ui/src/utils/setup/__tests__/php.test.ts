import { describe, it, expect } from 'vitest'
import { buildSnippetCtx, type CtxInput } from '../context'
import { phpSnippets } from '../php'
import type { Client } from '../types'

const TOKEN = 'eyJhbGciOiJSUzI1NiJ9.payload.sig'

function render(input: Partial<CtxInput> = {}): Client {
  const ctx = buildSnippetCtx({
    repo: 'php_group',
    pubRepo: 'php',
    repoUrl: 'https://reg.example.com/artifactory/php_group',
    pubUrl: 'https://reg.example.com/artifactory/php',
    user: 'jane@corp.com',
    token: TOKEN,
    ...input,
  })
  const clients = phpSnippets(ctx)
  expect(clients).toHaveLength(1)
  return clients[0]
}

function allCode(c: Client): string {
  return [...c.configure, ...c.resolve, ...c.publish, ...c.verify].map(s => s.code).join('\n')
}

describe('phpSnippets', () => {
  it('configures composer.json with the group and packagist disabled (https)', () => {
    const c = render()
    expect(c.id).toBe('composer')
    const repo = c.configure[0]
    expect(repo.file).toBe('composer.json')
    const json = JSON.parse(repo.code)
    expect(json.repositories).toEqual([
      { type: 'composer', url: 'https://reg.example.com/artifactory/php_group' },
      { 'packagist.org': false },
    ])
    expect(json.config).toBeUndefined()
    expect(repo.description).not.toContain('Use a php-group instead')
  })

  it('steers a proxy selection to the group', () => {
    const repo = render({ repo: 'php_proxy', mode: 'proxy' }).configure[0]
    expect(repo.description).toContain('Use a php-group instead')
    expect(repo.code).toContain('"packagist.org": false')
  })

  it('keeps packagist enabled for a local resolve repository', () => {
    const repo = render({ repo: 'php', repoUrl: 'https://reg.example.com/artifactory/php', mode: 'local' }).configure[0]
    expect(repo.code).not.toContain('packagist.org')
  })

  it('writes a downloadable auth.json keyed by host with the raw email username', () => {
    const auth = render().configure[1]
    expect(auth.file).toBe('auth.json')
    expect(auth.download).toBe('auth.json')
    expect(JSON.parse(auth.code)).toEqual({
      'http-basic': { 'reg.example.com': { username: 'jane@corp.com', password: TOKEN } },
    })
  })

  it('adds an auth entry for a publish repository on another host', () => {
    const auth = render({ pubUrl: 'http://localhost:8081/test_prefix/api/composer/php' }).configure[1]
    expect(Object.keys(JSON.parse(auth.code)['http-basic'])).toEqual(['reg.example.com', 'localhost:8081'])
  })

  it('turns secure-http off only for a plain-HTTP registry, keyed by host:port', () => {
    const c = render({ repoUrl: 'http://localhost:8081/php_group', pubUrl: 'http://localhost:8081/test_prefix/php' })
    const json = JSON.parse(c.configure[0].code)
    expect(json.config).toEqual({ 'secure-http': false })
    expect(Object.keys(JSON.parse(c.configure[1].code)['http-basic'])).toEqual(['localhost:8081'])
    expect(allCode(render())).not.toContain('secure-http')
  })

  it('keeps packagist enabled and verifies with curl when resolving from the local repo', () => {
    const c = render({ repo: 'php', repoUrl: 'https://reg.example.com/artifactory/php' })
    const json = JSON.parse(c.configure[0].code)
    expect(json.repositories).toEqual([{ type: 'composer', url: 'https://reg.example.com/artifactory/php' }])
    expect(c.verify).toHaveLength(1)
    expect(c.verify[0].code).toBe(
      `curl -fsS -u 'jane@corp.com:${TOKEN}' https://reg.example.com/artifactory/php/packages.json`,
    )
  })

  it('verifies a group with composer show', () => {
    const c = render()
    expect(c.verify).toHaveLength(1)
    expect(c.verify[0].code).toBe('composer show --available psr/log')
    expect(c.resolve.map(s => s.code)).toContain('composer require psr/log')
  })

  it('publishes a versioned archive to the publish repository with curl -u', () => {
    const c = render()
    const version = JSON.parse(c.publish[0].code)
    expect(version.version).toBe('1.0.0')
    expect(version.archive.exclude).toEqual(['/vendor', '/dist'])
    expect(c.publish[1].code).toBe('composer archive --format=zip --dir=dist --file=my-package-1.0.0')
    const upload = c.publish[2].code
    expect(upload).toContain(`-u 'jane@corp.com:${TOKEN}'`)
    expect(upload).toContain('--upload-file dist/my-package-1.0.0.zip')
    expect(upload).toContain('https://reg.example.com/artifactory/php/my-package-1.0.0.zip')
    expect(c.publishNote).toBeUndefined()
  })

  it('uses the placeholder without a token', () => {
    const c = render({ token: '' })
    expect(JSON.parse(c.configure[1].code)['http-basic']['reg.example.com'].password).toBe('YOUR_TOKEN')
    expect(c.publish[2].code).toContain("-u 'jane@corp.com:YOUR_TOKEN'")
  })

  it('has no publish steps without a publish repository', () => {
    const c = render({ pubRepo: '', pubUrl: '' })
    expect(c.publish).toEqual([])
    expect(c.publishNote).toBeTruthy()
    expect(Object.keys(JSON.parse(c.configure[1].code)['http-basic'])).toEqual(['reg.example.com'])
  })

  it('never pipes credentials through base64', () => {
    for (const c of [render(), render({ repoUrl: 'http://h:8081/g', pubUrl: 'http://h:8081/p' }), render({ token: '' })]) {
      expect(allCode(c)).not.toMatch(/\|\s*base64/)
    }
  })
})
