/**
 * Set Me Up snippet model. Every format module turns a {@link SnippetCtx}
 * into the list of client variants it supports; the panel renders them.
 */

export type RepoMode = 'local' | 'proxy' | 'group'

/** Placeholder shown when the user has not generated or entered a token. */
export const TOKEN_PLACEHOLDER = 'YOUR_TOKEN'

export interface SnippetCtx {
  /** Resolve repository name */
  repo: string
  /** Publish (local) repository name; empty when there is none */
  pubRepo: string
  /** Mode of the resolve repository */
  mode: RepoMode
  /** Resolve repository URL, no trailing slash, e.g. `https://reg.example.com/artifactory/npm_group` */
  repoUrl: string
  /** Publish repository URL, no trailing slash; empty when there is no publish repository */
  pubUrl: string
  /** `http` or `https` */
  scheme: string
  /** `host[:port]` of the registry */
  host: string
  /** Host name without the port (netrc `machine`, pip `trusted-host`) */
  hostNoPort: string
  /** Resolve URL without scheme (Docker image references, npm auth keys) */
  repoPath: string
  /** Publish URL without scheme */
  pubPath: string
  /** Plain-HTTP registry: clients need their insecure-transport switches */
  insecure: boolean
  /** Username of the signed-in user */
  user: string
  /** Username percent-encoded for use inside a URL (`jane%40corp.com`) */
  userEnc: string
  /** API token, or {@link TOKEN_PLACEHOLDER} */
  token: string
  /** Whether {@link token} is a real token rather than the placeholder */
  hasToken: boolean
  /** base64(`user:token`) without line wrapping */
  b64: string
}

export interface Step {
  title: string
  /** Trusted HTML (authored in this codebase only) */
  description?: string
  code: string
  /** Highlighting hint: shell (default), xml, json, ini, yaml, kotlin, groovy, toml, ruby, elixir, properties */
  lang?: string
  /** Where the snippet belongs, shown above the block, e.g. `~/.m2/settings.xml` */
  file?: string
  /** File name offered by the Download button; only for file-shaped snippets */
  download?: string
}

export interface Client {
  /** Stable id used in the `client` query parameter */
  id: string
  label: string
  /** One-time client configuration (credentials, registry) */
  configure: Step[]
  /** Fetch / install through the resolve repository */
  resolve: Step[]
  /** Upload to the publish repository; empty when the client cannot publish */
  publish: Step[]
  /** One command that proves routing + auth, with the expected result in the description */
  verify: Step[]
  /** Shown instead of the Publish tab content when {@link publish} is empty */
  publishNote?: string
}

export type FormatSnippets = (ctx: SnippetCtx) => Client[]
