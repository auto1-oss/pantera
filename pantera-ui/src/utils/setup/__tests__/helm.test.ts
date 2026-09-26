import { describe, it, expect } from 'vitest'
import { helmSnippets } from '../helm'
import { buildSnippetCtx, type CtxInput } from '../context'
import { TOKEN_PLACEHOLDER, type Client, type Step } from '../types'

const code = (steps: Step[]) => steps.map(s => s.code).join('\n')
const all = (c: Client) => code([...c.configure, ...c.resolve, ...c.publish, ...c.verify])

function render(over: Partial<CtxInput> = {}): Client {
  const clients = helmSnippets(buildSnippetCtx({
    repo: 'charts',
    pubRepo: 'charts',
    repoUrl: 'https://reg.example.com/artifactory/charts',
    pubUrl: 'https://reg.example.com/artifactory/charts',
    user: 'jane@corp.com',
    token: 'tok-123',
    ...over,
  }))
  expect(clients).toHaveLength(1)
  return clients[0]
}

describe('helmSnippets', () => {
  it('adds the repository with the token on stdin, not on the command line', () => {
    const helm = render()
    const cfg = code(helm.configure)
    expect(cfg).toContain("printf '%s' 'tok-123' | helm repo add pantera https://reg.example.com/artifactory/charts")
    expect(cfg).toContain("--username 'jane@corp.com' --password-stdin")
    expect(cfg).not.toContain('--password ')
    expect(cfg).toContain('helm repo update pantera')
  })

  it('pulls and installs through the alias', () => {
    const res = code(render().resolve)
    expect(res).toContain('helm pull pantera/CHART')
    expect(res).toContain('helm install my-release pantera/CHART')
  })

  it('uploads with curl -u to the publish URL and never pipes through base64', () => {
    const helm = render()
    const pub = code(helm.publish)
    expect(pub).toContain('helm package ./my-chart')
    expect(pub).toContain("curl -fsS -u 'jane@corp.com:tok-123'")
    expect(pub).toContain('--upload-file my-chart-0.1.0.tgz')
    expect(pub).toContain('https://reg.example.com/artifactory/charts/my-chart-0.1.0.tgz')
    expect(all(helm)).not.toMatch(/base64/)
    expect(helm.publishNote).toBeUndefined()
  })

  it('verifies with one update + search command', () => {
    const helm = render()
    expect(helm.verify).toHaveLength(1)
    expect(helm.verify[0].code).toBe('helm repo update pantera && helm search repo pantera/')
    expect(helm.verify[0].description).toContain('Successfully got an update')
  })

  it('needs no insecure switch for http registries', () => {
    const helm = render({
      repoUrl: 'http://localhost:8081/test_prefix/charts',
      pubUrl: 'http://localhost:8081/test_prefix/charts',
    })
    expect(code(helm.configure)).toContain('helm repo add pantera http://localhost:8081/test_prefix/charts')
    expect(code(helm.publish)).toContain('http://localhost:8081/test_prefix/charts/my-chart-0.1.0.tgz')
    expect(all(helm)).not.toMatch(/insecure/)
  })

  it('renders the placeholder when there is no token', () => {
    const helm = render({ token: '' })
    expect(code(helm.configure)).toContain(`printf '%s' '${TOKEN_PLACEHOLDER}'`)
    expect(code(helm.publish)).toContain(`-u 'jane@corp.com:${TOKEN_PLACEHOLDER}'`)
  })

  it('has no publish steps without a publish repository', () => {
    const helm = render({ pubRepo: '', pubUrl: '' })
    expect(helm.publish).toEqual([])
    expect(helm.publishNote).toBeTruthy()
    expect(helm.configure).not.toHaveLength(0)
    expect(helm.verify).toHaveLength(1)
  })

  it('shell-quotes a single quote inside the username', () => {
    expect(code(render({ user: "o'brien" }).configure)).toContain("--username 'o'\\''brien'")
  })
})
