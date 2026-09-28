import { describe, it, expect } from 'vitest'
import { mavenSnippets } from '../maven'
import { buildSnippetCtx, TOKEN_PLACEHOLDER, type Client, type Step } from '..'

const TOKEN = 'eyJhbGciOiJSUzI1NiJ9.payload.sig'

function render(opts: {
  base?: string
  repo?: string
  pubRepo?: string
  mode?: 'local' | 'proxy' | 'group'
  user?: string
  token?: string
}): Client {
  const base = opts.base ?? 'https://reg.example.com/artifactory'
  const repo = opts.repo ?? 'maven_group'
  const pubRepo = opts.pubRepo ?? 'libs-release-local'
  const ctx = buildSnippetCtx({
    repo,
    pubRepo,
    repoUrl: `${base}/${repo}`,
    pubUrl: pubRepo ? `${base}/${pubRepo}` : '',
    user: opts.user ?? 'jane@corp.com',
    token: opts.token ?? TOKEN,
    mode: opts.mode,
  })
  const clients = mavenSnippets(ctx)
  expect(clients).toHaveLength(1)
  return clients[0]
}

const allCode = (c: Client): string =>
  [...c.configure, ...c.resolve, ...c.publish, ...c.verify].map((s: Step) => s.code).join('\n')

describe('mavenSnippets (group, https, real token, email user)', () => {
  const c = render({})
  const settings = c.configure[0]

  it('is the mvn client with every tab filled', () => {
    expect(c.id).toBe('mvn')
    expect([c.configure.length, c.resolve.length, c.publish.length, c.verify.length]).toEqual([1, 1, 2, 1])
  })

  it('offers settings.xml as a downloadable ~/.m2 file', () => {
    expect(settings.file).toBe('~/.m2/settings.xml')
    expect(settings.download).toBe('settings.xml')
    expect(settings.lang).toBe('xml')
  })

  it('mirrors everything through the resolve repository', () => {
    expect(settings.code).toContain('<mirrorOf>*</mirrorOf>')
    expect(settings.code).toContain('<url>https://reg.example.com/artifactory/maven_group</url>')
  })

  it('stores the raw username and token in the pantera server entry', () => {
    expect(settings.code).toContain('<id>pantera</id>')
    expect(settings.code).toContain('<username>jane@corp.com</username>')
    expect(settings.code).toContain(`<password>${TOKEN}</password>`)
    expect(settings.code).not.toContain('jane%40corp.com')
  })

  it('enables snapshots for repositories and plugin repositories via an active profile', () => {
    expect(settings.code).toContain('<activeProfile>pantera</activeProfile>')
    expect(settings.code).toMatch(/<repository>\s*<id>central<\/id>[\s\S]*?<snapshots><enabled>true<\/enabled><\/snapshots>/)
    expect(settings.code).toMatch(/<pluginRepository>\s*<id>central<\/id>[\s\S]*?<snapshots><enabled>true<\/enabled><\/snapshots>/)
  })

  it('deploys releases and snapshots to the publish repository with the pantera server id', () => {
    const dm = c.publish[0].code
    expect(dm).toMatch(/<repository>\s*<id>pantera<\/id>\s*<url>https:\/\/reg\.example\.com\/artifactory\/libs-release-local<\/url>/)
    expect(dm).toMatch(/<snapshotRepository>\s*<id>pantera<\/id>\s*<url>https:\/\/reg\.example\.com\/artifactory\/libs-release-local<\/url>/)
    expect(c.publish[0].description).toContain('snapshot-local')
    expect(c.publish[1].code).toBe('mvn deploy')
  })

  it('verifies with junit through Maven', () => {
    expect(c.verify[0].code).toContain('dependency:get -Dartifact=junit:junit:4.13.2')
    expect(c.verify[0].description).toContain('BUILD SUCCESS')
  })

  it('never pipes through base64 and needs no insecure switch on https', () => {
    expect(allCode(c)).not.toMatch(/\|\s*base64/)
    expect(allCode(c)).not.toMatch(/insecure|allowInsecure|http\.ssl/i)
  })
})

describe('mavenSnippets (http registry)', () => {
  const c = render({ base: 'http://localhost:8081/test_prefix' })

  it('uses the plain-http URLs without extra switches (Maven only blocks http repositories it does not mirror)', () => {
    expect(c.configure[0].code).toContain('<url>http://localhost:8081/test_prefix/maven_group</url>')
    expect(c.publish[0].code).toContain('<url>http://localhost:8081/test_prefix/libs-release-local</url>')
    expect(allCode(c)).not.toMatch(/insecure|http\.ssl/i)
  })
})

describe('mavenSnippets (no token)', () => {
  it('shows the token placeholder', () => {
    const c = render({ token: '' })
    expect(c.configure[0].code).toContain(`<password>${TOKEN_PLACEHOLDER}</password>`)
  })
})

describe('mavenSnippets (no publish repository)', () => {
  it('returns no publish steps', () => {
    const c = render({ pubRepo: '' })
    expect(c.publish).toEqual([])
    expect(c.configure[0].code).toContain('<mirrorOf>*</mirrorOf>')
  })
})

describe('mavenSnippets (local resolve repository)', () => {
  const c = render({ repo: 'libs-snapshot-local', pubRepo: 'libs-snapshot-local' })

  it('adds the repository next to central instead of mirroring everything', () => {
    const code = c.configure[0].code
    expect(code).not.toContain('<mirrorOf>')
    expect(code).toMatch(/<repository>\s*<id>pantera<\/id>\s*<url>https:\/\/reg\.example\.com\/artifactory\/libs-snapshot-local<\/url>/)
    expect(code).toContain('<snapshots><enabled>true</enabled></snapshots>')
  })

  it('verifies access with curl -u', () => {
    expect(c.verify[0].code).toContain(`-u 'jane@corp.com:${TOKEN}'`)
    expect(c.verify[0].code).toContain("'https://reg.example.com/artifactory/libs-snapshot-local/'")
  })
})

describe('mavenSnippets (XML escaping)', () => {
  it('escapes XML special characters in credentials', () => {
    const c = render({ user: 'a&b<c>' })
    expect(c.configure[0].code).toContain('<username>a&amp;b&lt;c&gt;</username>')
  })
})

describe('mavenSnippets (local resolve repository, different publish repository)', () => {
  it('adds the local repository beside Central instead of mirroring everything to it', () => {
    const c = render({ repo: 'maven', pubRepo: 'libs-snapshot-local', mode: 'local' })
    expect(c.configure[0].code).not.toContain('<mirrorOf>*</mirrorOf>')
  })
})
