import { describe, it, expect } from 'vitest'
import { buildSnippetCtx } from '../context'
import { npmSnippets } from '../npm'
import type { Client } from '../types'

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(base: string, opts: { token?: string, pub?: string, repo?: string } = {}): Client[] {
  const repo = opts.repo ?? 'npm_group'
  const pub = opts.pub ?? 'npm_local'
  return npmSnippets(buildSnippetCtx({
    repo,
    pubRepo: pub,
    repoUrl: `${base}/${repo}`,
    pubUrl: pub ? `${base}/${pub}` : '',
    user: 'jane@corp.com',
    token: opts.token ?? 'tok123',
  }))
}

const byId = (clients: Client[], id: string) => clients.find(c => c.id === id)!
const allCode = (c: Client) => [...c.configure, ...c.resolve, ...c.publish, ...c.verify].map(s => s.code).join('\n')

describe('npmSnippets', () => {
  it('offers npm, pnpm, yarn 1 and yarn berry', () => {
    expect(render(HTTPS).map(c => c.id)).toEqual(['npm', 'pnpm', 'yarn1', 'yarn-berry'])
  })

  it('writes .npmrc with the registry and path-keyed tokens for resolve and publish', () => {
    const npmrc = byId(render(HTTPS), 'npm').configure[0]
    expect(npmrc.file).toBe('~/.npmrc')
    expect(npmrc.download).toBe('.npmrc')
    const lines = npmrc.code.split('\n')
    expect(lines).toContain(`registry=${HTTPS}/npm_group/`)
    expect(lines).toContain('//reg.example.com/artifactory/npm_group/:_authToken=tok123')
    expect(lines).toContain('//reg.example.com/artifactory/npm_local/:_authToken=tok123')
    expect(lines.some(l => /^\/\/reg\.example\.com\/:_authToken/.test(l))).toBe(false)
    expect(npmrc.code).not.toContain('always-auth')
    expect(npmrc.description).toContain('npm login')
  })

  it('adds always-auth for yarn 1 only', () => {
    const clients = render(HTTPS)
    expect(byId(clients, 'yarn1').configure[0].code).toContain('always-auth=true')
    expect(byId(clients, 'pnpm').configure[0].code).not.toContain('always-auth')
  })

  it('keeps one token line when resolve and publish repos are the same', () => {
    const npmrc = byId(render(HTTPS, { repo: 'npm_local', pub: 'npm_local' }), 'npm').configure[0]
    expect(npmrc.code.match(/_authToken/g)).toHaveLength(1)
  })

  it('offers an optional scoped-registry step', () => {
    const clients = render(HTTPS)
    expect(byId(clients, 'npm').configure[1].code).toBe(`@your-scope:registry=${HTTPS}/npm_group/`)
    const berryScope = byId(clients, 'yarn-berry').configure[1].code
    expect(berryScope).toContain('npmScopes:')
    expect(berryScope).toContain(`npmRegistryServer: "${HTTPS}/npm_group"`)
    expect(berryScope).toContain('npmAuthToken: "tok123"')
  })

  it('configures yarn berry through .yarnrc.yml without insecure switches on https', () => {
    const rc = byId(render(HTTPS), 'yarn-berry').configure[0]
    expect(rc.file).toBe('~/.yarnrc.yml')
    expect(rc.code).toContain(`npmRegistryServer: "${HTTPS}/npm_group"`)
    expect(rc.code).toContain('npmAlwaysAuth: true')
    expect(rc.code).toContain('npmAuthToken: "tok123"')
    expect(rc.code).toContain(`npmPublishRegistry: "${HTTPS}/npm_local"`)
    expect(rc.code).not.toContain('unsafeHttpWhitelist')
  })

  it('whitelists the plain-HTTP host for yarn berry only on http', () => {
    const clients = render(HTTP)
    const rc = byId(clients, 'yarn-berry').configure[0].code
    expect(rc).toContain('unsafeHttpWhitelist:\n  - "localhost"')
    expect(byId(clients, 'yarn-berry').configure[1].code).toContain('unsafeHttpWhitelist')
    expect(byId(clients, 'npm').configure[0].code).toContain('//localhost:8081/test_prefix/npm_group/:_authToken=tok123')
  })

  it('uses the placeholder token when none is set', () => {
    for (const c of render(HTTPS, { token: '' })) {
      expect(c.configure[0].code, c.id).toContain('YOUR_TOKEN')
    }
  })

  it('publishes to the local repository via publishConfig', () => {
    const clients = render(HTTPS)
    const npm = byId(clients, 'npm')
    const pkg = JSON.parse(npm.publish[0].code)
    expect(pkg.publishConfig.registry).toBe(`${HTTPS}/npm_local/`)
    expect(npm.publish[1].code).toBe('npm publish')
    expect(byId(clients, 'pnpm').publish[1].code).toContain('pnpm publish')
    expect(byId(clients, 'yarn1').publish[1].code).toBe('yarn publish --non-interactive')
    expect(byId(clients, 'yarn-berry').publish[0].code).toBe('yarn npm publish')
  })

  it('has no publish steps and explains why when there is no publish repository', () => {
    for (const c of render(HTTPS, { pub: '' })) {
      expect(c.publish, c.id).toEqual([])
      expect(c.publishNote, c.id).toBeTruthy()
      expect(allCode(c), c.id).not.toContain('npmPublishRegistry')
    }
  })

  it('verifies through a public package on groups and with whoami on local repositories', () => {
    expect(byId(render(HTTPS), 'npm').verify[0].code).toBe('npm view lodash version')
    const local = render(HTTPS, { repo: 'npm_local', pub: 'npm_local' })
    expect(byId(local, 'npm').verify[0].code).toBe('npm whoami')
    expect(byId(local, 'yarn-berry').verify[0].code).toBe('yarn npm whoami')
    expect(byId(local, 'npm').resolve[0].code).toBe('npm install my-package')
  })

  it('never pipes through base64 and keeps the username out of URLs', () => {
    for (const c of render(HTTPS)) {
      expect(allCode(c), c.id).not.toContain('base64')
      expect(allCode(c), c.id).not.toContain('jane')
    }
  })
})
