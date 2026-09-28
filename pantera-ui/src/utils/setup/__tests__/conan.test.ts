import { describe, it, expect } from 'vitest'
import { conanSnippets } from '../conan'
import { buildSnippetCtx, type CtxInput } from '../context'
import { TOKEN_PLACEHOLDER, type Client, type Step } from '../types'

const code = (steps: Step[]) => steps.map(s => s.code).join('\n')
const all = (c: Client) => code([...c.configure, ...c.resolve, ...c.publish, ...c.verify])

function render(over: Partial<CtxInput> = {}): Client {
  const clients = conanSnippets(buildSnippetCtx({
    repo: 'conan',
    pubRepo: 'conan',
    repoUrl: 'https://conan.example.com:9300',
    pubUrl: 'https://conan.example.com:9300',
    user: 'jane@corp.com',
    token: 'tok-123',
    ...over,
  }))
  expect(clients).toHaveLength(1)
  return clients[0]
}

describe('conanSnippets', () => {
  it('adds the remote at the repository URL and logs in with the token', () => {
    const cfg = code(render().configure)
    expect(cfg).toContain('conan remote add pantera https://conan.example.com:9300')
    expect(cfg).toContain("conan user 'jane@corp.com' -r pantera -p 'tok-123'")
  })

  it('explains when the URL must be the dedicated Conan URL', () => {
    const desc = render().configure[0].description ?? ''
    expect(desc).toContain('dedicated port')
    expect(desc).toContain('ask your administrator for the Conan URL')
  })

  it('installs, uploads and searches through the pantera remote', () => {
    const conan = render()
    expect(code(conan.resolve)).toBe('conan install my_package/1.0@ -r pantera')
    expect(code(conan.publish)).toContain('conan create .')
    expect(code(conan.publish)).toContain('conan upload my_package/1.0@ -r pantera --all --confirm')
    expect(code(conan.verify)).toBe("conan search '*' -r pantera")
  })

  it('never pipes through base64 or puts credentials in the URL', () => {
    const text = all(render())
    expect(text).not.toMatch(/\|\s*base64/)
    expect(text).not.toContain('jane%40corp.com')
  })

  it('adds no insecure switches for either scheme', () => {
    const http = render({ repoUrl: 'http://localhost:9300', pubUrl: 'http://localhost:9300' })
    expect(code(http.configure)).toContain('conan remote add pantera http://localhost:9300\n')
    expect(all(http)).not.toMatch(/False|--insecure/)
    expect(all(render())).not.toMatch(/False|--insecure/)
  })

  it('shows the placeholder when there is no token', () => {
    expect(code(render({ token: '' }).configure)).toContain(`-p '${TOKEN_PLACEHOLDER}'`)
  })

  it('has no publish steps without a publish repository', () => {
    const conan = render({ pubRepo: '', pubUrl: '' })
    expect(conan.publish).toEqual([])
    expect(conan.publishNote).toBeTruthy()
    expect(render().publishNote).toBeUndefined()
  })
})
