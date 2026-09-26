import { TOKEN_PLACEHOLDER, type RepoMode, type SnippetCtx } from './types'

export interface ResolvedBase {
  /** Registry base URL including the global prefix, no trailing slash */
  base: string
  /** False when neither the server nor the UI config names a registry URL */
  configured: boolean
}

/**
 * Registry base URL clients should use: the configured registry URL (server
 * `ui.registry_url` wins over the UI's `REGISTRY_URL`) with the first global
 * prefix appended unless the URL already ends with it. Falls back to the UI
 * origin, flagged as unconfigured so the panel can warn.
 */
export function resolveBase(
  serverUrl: string | undefined,
  uiUrl: string | undefined,
  prefixes: string[],
  origin: string,
): ResolvedBase {
  const configuredUrl = (serverUrl || uiUrl || '').trim()
  const url = (configuredUrl || origin).replace(/\/+$/, '')
  const prefix = (prefixes[0] ?? '').replace(/^\/+|\/+$/g, '')
  const hasPrefix = !prefix || url.endsWith(`/${prefix}`)
  return { base: hasPrefix ? url : `${url}/${prefix}`, configured: configuredUrl !== '' }
}

/** Configured public URL of a repository config (`repo.settings.url` wins over `repo.url`, as on the server). */
export function configuredRepoUrl(config: unknown): string | undefined {
  const repo = (config as { repo?: { url?: unknown, settings?: { url?: unknown } } } | undefined)?.repo
  const url = repo?.settings?.url ?? repo?.url
  return typeof url === 'string' && url.trim() !== '' ? url.trim() : undefined
}

/**
 * URL of one repository. A repository's own configured `url` wins: formats
 * such as Helm, Composer and NuGet embed it in their metadata, so clients
 * must reach the repository under exactly that address.
 */
export function repoUrlFor(base: string, name: string, configuredUrl?: string): string {
  if (configuredUrl && /^https?:\/\//.test(configuredUrl)) return configuredUrl.replace(/\/+$/, '')
  return `${base.replace(/\/+$/, '')}/${name}`
}

function utf8Base64(value: string): string {
  const bytes = new TextEncoder().encode(value)
  let bin = ''
  bytes.forEach(b => { bin += String.fromCharCode(b) })
  return btoa(bin)
}

export interface CtxInput {
  repo: string
  pubRepo: string
  repoUrl: string
  pubUrl: string
  user: string
  token: string
  /** Resolve repository mode; defaults to local when it is also the publish repo, else group */
  mode?: RepoMode
}

export function buildSnippetCtx(input: CtxInput): SnippetCtx {
  const noScheme = (u: string) => u.replace(/^https?:\/\//, '')
  const scheme = input.repoUrl.startsWith('http://') ? 'http' : 'https'
  const host = noScheme(input.repoUrl).split('/')[0]
  const token = input.token || TOKEN_PLACEHOLDER
  const user = input.user || 'YOUR_USERNAME'
  return {
    repo: input.repo,
    pubRepo: input.pubRepo,
    mode: input.mode ?? (input.repo === input.pubRepo ? 'local' : 'group'),
    repoUrl: input.repoUrl,
    pubUrl: input.pubUrl,
    scheme,
    host,
    hostNoPort: host.replace(/:\d+$/, ''),
    repoPath: noScheme(input.repoUrl),
    pubPath: noScheme(input.pubUrl),
    insecure: scheme === 'http',
    user,
    userEnc: encodeURIComponent(user),
    token,
    hasToken: Boolean(input.token),
    b64: utf8Base64(`${user}:${token}`),
  }
}
