import { describe, it, expect } from 'vitest'
import { gradleSnippets } from '../gradle'
import { buildSnippetCtx, TOKEN_PLACEHOLDER, type Client, type RepoMode, type Step } from '..'

const TOKEN = 'eyJhbGciOiJSUzI1NiJ9.payload.sig'

function render(opts: {
  base?: string
  repo?: string
  pubRepo?: string
  mode?: RepoMode
  token?: string
}): Client[] {
  const base = opts.base ?? 'https://reg.example.com/artifactory'
  const repo = opts.repo ?? 'gradle_group'
  const pubRepo = opts.pubRepo ?? 'gradle'
  const ctx = buildSnippetCtx({
    repo,
    pubRepo,
    repoUrl: `${base}/${repo}`,
    pubUrl: pubRepo ? `${base}/${pubRepo}` : '',
    user: 'jane@corp.com',
    token: opts.token ?? TOKEN,
    mode: opts.mode,
  })
  return gradleSnippets(ctx)
}

const steps = (c: Client): Step[] => [...c.configure, ...c.resolve, ...c.publish, ...c.verify]
const allCode = (c: Client): string => steps(c).map(s => s.code).join('\n')
const byId = (clients: Client[], id: string): Client => {
  const c = clients.find(x => x.id === id)
  expect(c).toBeDefined()
  return c as Client
}

describe('gradleSnippets (group, https, real token, email user)', () => {
  const clients = render({})
  const kts = byId(clients, 'gradle-kotlin')
  const groovy = byId(clients, 'gradle-groovy')

  it('offers the Kotlin and Groovy DSL clients with every tab filled', () => {
    expect(clients.map(c => c.id)).toEqual(['gradle-kotlin', 'gradle-groovy'])
    for (const c of clients) {
      expect(c.configure.length, `${c.id} configure`).toBeGreaterThan(0)
      expect(c.resolve.length, `${c.id} resolve`).toBeGreaterThan(0)
      expect(c.publish.length, `${c.id} publish`).toBeGreaterThan(0)
      expect(c.verify, `${c.id} verify`).toHaveLength(1)
    }
  })

  it('stores credentials in ~/.gradle/gradle.properties', () => {
    for (const c of clients) {
      const props = c.configure[0]
      expect(props.file).toBe('~/.gradle/gradle.properties')
      expect(props.code).toBe(`panteraUsername=jane@corp.com\npanteraPassword=${TOKEN}`)
    }
  })

  it('keeps the token out of every build script', () => {
    for (const c of clients) {
      for (const s of [c.configure[1], ...c.resolve, ...c.publish, ...c.verify]) {
        expect(s.code, `${c.id} ${s.title}`).not.toContain(TOKEN)
      }
    }
  })

  it('binds the pantera repository in pluginManagement and dependencyResolutionManagement', () => {
    const settings = kts.configure[1]
    expect(settings.file).toBe('settings.gradle.kts')
    expect(settings.code).toContain('pluginManagement {')
    expect(settings.code).toContain('dependencyResolutionManagement {')
    expect(settings.code.match(/name = "pantera"/g)).toHaveLength(2)
    expect(settings.code.match(/credentials\(PasswordCredentials::class\)/g)).toHaveLength(2)
    expect(settings.code).toContain('url = uri("https://reg.example.com/artifactory/gradle_group")')

    const gsettings = groovy.configure[1]
    expect(gsettings.file).toBe('settings.gradle')
    expect(gsettings.code.match(/name = 'pantera'/g)).toHaveLength(2)
    expect(gsettings.code.match(/credentials\(PasswordCredentials\)/g)).toHaveLength(2)
    expect(gsettings.code).toContain("url = 'https://reg.example.com/artifactory/gradle_group'")
  })

  it('emits no insecure-protocol switch for https', () => {
    for (const c of clients) {
      expect(allCode(c)).not.toMatch(/AllowInsecureProtocol|allowInsecureProtocol/)
    }
  })

  it('publishes a real publication to the local repository', () => {
    const [build, run] = kts.publish
    expect(build.file).toBe('build.gradle.kts')
    expect(build.code).toContain('`maven-publish`')
    expect(build.code).toContain('create<MavenPublication>("mavenJava")')
    expect(build.code).toContain('from(components["java"])')
    expect(build.code).toContain('url = uri("https://reg.example.com/artifactory/gradle")')
    expect(run.code).toBe('./gradlew publish')

    const gbuild = groovy.publish[0]
    expect(gbuild.file).toBe('build.gradle')
    expect(gbuild.code).toContain("id 'maven-publish'")
    expect(gbuild.code).toContain('mavenJava(MavenPublication)')
    expect(gbuild.code).toContain('from components.java')
    expect(gbuild.code).toContain("url = 'https://reg.example.com/artifactory/gradle'")
  })

  it('resolves a public dependency and verifies with the Gradle dependency report', () => {
    expect(kts.resolve[0].code).toContain('implementation("junit:junit:4.13.2")')
    expect(groovy.resolve[0].code).toContain("implementation 'junit:junit:4.13.2'")
    for (const c of clients) {
      expect(c.verify[0].code).toBe('./gradlew dependencies --configuration compileClasspath --refresh-dependencies')
    }
  })

  it('never pipes through base64', () => {
    for (const c of clients) expect(allCode(c)).not.toMatch(/\|\s*base64/)
  })
})

describe('gradleSnippets (http registry)', () => {
  const clients = render({ base: 'http://localhost:8081/test_prefix' })

  it('allows the insecure protocol on every repository block', () => {
    const kts = byId(clients, 'gradle-kotlin')
    expect(kts.configure[1].code.match(/isAllowInsecureProtocol = true/g)).toHaveLength(2)
    expect(kts.publish[0].code).toContain('isAllowInsecureProtocol = true')
    const groovy = byId(clients, 'gradle-groovy')
    expect(groovy.configure[1].code.match(/allowInsecureProtocol = true/g)).toHaveLength(2)
    expect(groovy.publish[0].code).toContain('allowInsecureProtocol = true')
    expect(groovy.configure[1].code).toContain("url = 'http://localhost:8081/test_prefix/gradle_group'")
  })
})

describe('gradleSnippets (no token)', () => {
  it('puts the placeholder in gradle.properties', () => {
    for (const c of render({ token: '' })) {
      expect(c.configure[0].code).toContain(`panteraPassword=${TOKEN_PLACEHOLDER}`)
    }
  })
})

describe('gradleSnippets (no publish repository)', () => {
  it('returns no publish steps', () => {
    for (const c of render({ repo: 'gradle_proxy', pubRepo: '', mode: 'proxy' })) {
      expect(c.publish).toEqual([])
      expect(c.verify[0].code).toContain('./gradlew dependencies')
    }
  })
})

describe('gradleSnippets (local repository)', () => {
  const clients = render({ repo: 'gradle', pubRepo: 'gradle' })

  it('resolves the published module instead of a public one', () => {
    const kts = byId(clients, 'gradle-kotlin')
    expect(kts.resolve[0].code).toContain('implementation("com.example:my-app:1.0.0")')
    expect(kts.resolve[0].code).not.toContain('junit')
  })

  it('verifies the published metadata with credentials, no base64 pipe', () => {
    for (const c of clients) {
      const v = c.verify[0].code
      expect(v).toContain(`-u 'jane@corp.com:${TOKEN}'`)
      expect(v).toContain('https://reg.example.com/artifactory/gradle/com/example/my-app/maven-metadata.xml')
      expect(v).not.toMatch(/base64/)
    }
  })
})
