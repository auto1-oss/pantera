import { describe, it, expect } from 'vitest'
import { buildSnippetCtx, TOKEN_PLACEHOLDER } from '../index'
import { nugetSnippets } from '../nuget'
import type { Client } from '../types'

const HTTPS = 'https://reg.example.com/artifactory'
const HTTP = 'http://localhost:8081/test_prefix'

function render(base: string, token: string, pubRepo = 'nuget_local'): Client[] {
  return nugetSnippets(buildSnippetCtx({
    repo: 'nuget_local',
    pubRepo,
    repoUrl: `${base}/nuget_local`,
    pubUrl: pubRepo ? `${base}/${pubRepo}` : '',
    user: 'jane@corp.com',
    token,
  }))
}

const byId = (clients: Client[], id: string) => clients.find(c => c.id === id)!
const allCode = (c: Client) => [...c.configure, ...c.resolve, ...c.publish, ...c.verify].map(s => s.code).join('\n')

describe('nugetSnippets', () => {
  it('offers the dotnet CLI and a nuget.config file', () => {
    expect(render(HTTPS, 'tok').map(c => c.id)).toEqual(['dotnet', 'nuget-config'])
  })

  it('points every URL at the /index.json service index', () => {
    const cli = byId(render(HTTPS, 'tok'), 'dotnet')
    expect(cli.configure[0].code).toContain(`dotnet nuget add source ${HTTPS}/nuget_local/index.json`)
    expect(cli.resolve[0].code).toBe(`dotnet add package PACKAGE --source ${HTTPS}/nuget_local/index.json`)
    expect(cli.publish.map(s => s.code).join('\n')).not.toContain('nuget_local')
  })

  it('stores the username and token with the source (https)', () => {
    const cli = byId(render(HTTPS, 'tok'), 'dotnet')
    const code = cli.configure[0].code
    expect(code).toContain('--name pantera')
    expect(code).toContain('--username jane@corp.com')
    expect(code).toContain('--password tok')
    expect(code).toContain('--store-password-in-clear-text')
    expect(code).not.toContain('--allow-insecure-connections')
    expect(cli.verify[0].code).toContain("-u 'jane@corp.com:tok'")
    expect(cli.verify[0].code).toContain(`${HTTPS}/nuget_local/registrations/pantera.verify/index.json`)
  })

  it('pushes with the stored source credentials, no API key', () => {
    const cli = byId(render(HTTPS, 'tok'), 'dotnet')
    expect(cli.publish.map(s => s.title)).toEqual(['Pack the project', 'Push the package'])
    expect(cli.publish[1].code).toBe('dotnet nuget push "nupkg/*.nupkg" --source pantera --skip-duplicate')
    expect(allCode(cli)).not.toContain('--api-key')
  })

  it('adds a separate publish source when publishing to another repository', () => {
    const cli = byId(render(HTTPS, 'tok', 'nuget_release'), 'dotnet')
    expect(cli.publish[0].code, 'publish source').toContain(`dotnet nuget add source ${HTTPS}/nuget_release/index.json`)
    expect(cli.publish[0].code, 'publish source name').toContain('--name pantera-publish')
    expect(cli.publish[2].code, 'push target').toContain('--source pantera-publish')
  })

  it('adds the insecure switches only for an http registry', () => {
    const clients = render(HTTP, 'tok')
    expect(byId(clients, 'dotnet').configure[0].code, 'cli flag').toContain('--allow-insecure-connections')
    expect(byId(clients, 'nuget-config').configure[0].code, 'config attribute').toContain('allowInsecureConnections="true"')
    const secure = render(HTTPS, 'tok')
    expect(byId(secure, 'nuget-config').configure[0].code, 'no attribute for https').not.toContain('allowInsecureConnections')
  })

  it('writes a nuget.config with escaped credentials', () => {
    const cfg = byId(render(HTTPS, 'a&b'), 'nuget-config').configure[0]
    expect(cfg.file, 'file').toBe('nuget.config')
    expect(cfg.download, 'download').toBe('nuget.config')
    expect(cfg.code, 'source').toContain(`<add key="pantera" value="${HTTPS}/nuget_local/index.json" protocolVersion="3" />`)
    expect(cfg.code, 'username').toContain('<add key="Username" value="jane@corp.com" />')
    expect(cfg.code, 'password').toContain('<add key="ClearTextPassword" value="a&amp;b" />')
  })

  it('falls back to the token placeholder', () => {
    const clients = render(HTTPS, '')
    expect(byId(clients, 'dotnet').configure[0].code, 'cli').toContain(`--password ${TOKEN_PLACEHOLDER}`)
    expect(byId(clients, 'nuget-config').configure[0].code, 'config').toContain(`value="${TOKEN_PLACEHOLDER}"`)
  })

  it('has no publish steps without a publish repository', () => {
    for (const c of render(HTTPS, 'tok', '')) {
      expect(c.publish, `${c.id} publish`).toEqual([])
      expect(c.publishNote, `${c.id} note`).toBeTruthy()
    }
  })

  it('never pipes through base64', () => {
    for (const base of [HTTPS, HTTP]) {
      for (const c of render(base, 'tok')) expect(allCode(c)).not.toMatch(/\|\s*base64/)
    }
  })
})
