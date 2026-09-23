import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/*
 * npm-family clients. npm, pnpm and yarn 1 read `.npmrc`; yarn 2+ (berry)
 * ignores it and reads `.yarnrc.yml`. Credentials are `_authToken` lines keyed
 * to the exact registry paths (resolve and publish), so the token is only
 * sent to this registry. `npm login` / `npm adduser` do not work against
 * Pantera: tokens come from the Credentials card.
 */

const TOKEN_NOTE = 'Use an API token from the <b>Credentials</b> card. <code>npm login</code> and <code>npm adduser</code> do not work with Pantera.'

const NO_PUBLISH = 'Groups and proxies do not accept uploads. Choose a local npm repository as the publish target to see the publish steps.'

/** Registry URL as npm writes it: always with a trailing slash. */
const slash = (url: string) => `${url}/`

/** `_authToken` lines for the resolve and (when different) publish paths. */
function authLines(ctx: SnippetCtx): string[] {
  const paths = [ctx.repoPath, ...(ctx.pubPath ? [ctx.pubPath] : [])]
  return [...new Set(paths)].map(p => `//${p}/:_authToken=${ctx.token}`)
}

function npmrcStep(ctx: SnippetCtx, extra: string[], description: string): Step {
  return {
    title: 'Add the registry and token to ~/.npmrc',
    description,
    file: '~/.npmrc',
    download: '.npmrc',
    lang: 'ini',
    code: [`registry=${slash(ctx.repoUrl)}`, ...authLines(ctx), ...extra].join('\n'),
  }
}

function scopeStep(ctx: SnippetCtx, file: string): Step {
  return {
    title: 'Optional: route only one scope through Pantera',
    description: 'Instead of the <code>registry=</code> line, send only <code>@your-scope</code> packages to Pantera (the token lines stay). '
      + 'A scope line wins over <code>registry=</code> and over <code>--registry</code>, so remove stale <code>@scope:registry</code> lines that point elsewhere.',
    file,
    lang: 'ini',
    code: `@your-scope:registry=${slash(ctx.repoUrl)}`,
  }
}

function publishConfigStep(ctx: SnippetCtx): Step {
  return {
    title: 'Point package.json at the publish repository',
    description: `Groups and proxies do not accept uploads, so publishing goes to <code>${ctx.pubRepo}</code>. Set your own package <code>name</code> and <code>version</code>.`,
    file: 'package.json',
    lang: 'json',
    code: JSON.stringify({
      name: 'my-package',
      version: '1.0.0',
      publishConfig: { registry: slash(ctx.pubUrl) },
    }, null, 2),
  }
}

/**
 * Verify through the resolve repository: a local repository cannot serve
 * public packages, so there the token check is `whoami` (groups answer
 * `whoami` with 403, so it is not used for them).
 */
function isLocal(ctx: SnippetCtx): boolean {
  return ctx.mode === 'local'
}

/** Install step: a public package through a group/proxy, the user's own package on a local repository. */
function installStep(ctx: SnippetCtx, cmd: string): Step {
  return isLocal(ctx)
    ? {
        title: 'Install a package',
        description: `A local repository serves only what was published to it; replace <code>my-package</code> with one of its packages.`,
        code: `${cmd} my-package`,
      }
    : { title: 'Install a package', code: `${cmd} lodash` }
}

function verifyStep(ctx: SnippetCtx, viewCmd: string, whoamiCmd: string, whoamiNote = ''): Step {
  return isLocal(ctx)
    ? { title: 'Check the token', description: `${whoamiNote}Prints your username.`, code: whoamiCmd }
    : { title: 'Fetch package metadata', description: 'Prints the latest lodash version, fetched through Pantera.', code: viewCmd }
}

function npmClient(ctx: SnippetCtx): Client {
  return {
    id: 'npm',
    label: 'npm',
    configure: [
      npmrcStep(ctx, [], `${TOKEN_NOTE} Keep the token lines out of a committed project <code>.npmrc</code>.`),
      scopeStep(ctx, '~/.npmrc'),
    ],
    resolve: [installStep(ctx, 'npm install')],
    publish: ctx.pubUrl
      ? [publishConfigStep(ctx), { title: 'Publish', code: 'npm publish' }]
      : [],
    publishNote: ctx.pubUrl ? undefined : NO_PUBLISH,
    verify: [verifyStep(ctx, 'npm view lodash version', 'npm whoami')],
  }
}

function pnpmClient(ctx: SnippetCtx): Client {
  return {
    id: 'pnpm',
    label: 'pnpm',
    configure: [
      npmrcStep(ctx, [], `pnpm reads <code>.npmrc</code>. ${TOKEN_NOTE}`),
      scopeStep(ctx, '~/.npmrc'),
    ],
    resolve: [installStep(ctx, 'pnpm add')],
    publish: ctx.pubUrl
      ? [
          publishConfigStep(ctx),
          {
            title: 'Publish',
            description: '<code>--no-git-checks</code> skips pnpm\'s clean-branch check; drop it when publishing from a clean main branch.',
            code: 'pnpm publish --no-git-checks',
          },
        ]
      : [],
    publishNote: ctx.pubUrl ? undefined : NO_PUBLISH,
    verify: [verifyStep(ctx, 'pnpm view lodash version', 'pnpm whoami')],
  }
}

function yarn1Client(ctx: SnippetCtx): Client {
  return {
    id: 'yarn1',
    label: 'yarn 1 (classic)',
    configure: [
      npmrcStep(ctx, ['always-auth=true'],
        `yarn 1 reads <code>.npmrc</code> and sends the token for unscoped packages only with <code>always-auth=true</code> (npm warns about that line; it is harmless). ${TOKEN_NOTE}`),
      scopeStep(ctx, '~/.npmrc'),
    ],
    resolve: [installStep(ctx, 'yarn add')],
    publish: ctx.pubUrl
      ? [
          publishConfigStep(ctx),
          {
            title: 'Publish',
            description: 'Publishes the version in <code>package.json</code> without prompting.',
            code: 'yarn publish --non-interactive',
          },
        ]
      : [],
    publishNote: ctx.pubUrl ? undefined : NO_PUBLISH,
    verify: [verifyStep(ctx, 'yarn info lodash version', 'npm whoami',
      'yarn 1 has no <code>whoami</code>; npm reads the same <code>~/.npmrc</code> (and warns about <code>always-auth</code>). ')],
  }
}

function berryRc(ctx: SnippetCtx): string {
  const lines = [
    `npmRegistryServer: "${ctx.repoUrl}"`,
    'npmAlwaysAuth: true',
    `npmAuthToken: "${ctx.token}"`,
  ]
  if (ctx.pubUrl && ctx.pubUrl !== ctx.repoUrl) {
    lines.push(`npmPublishRegistry: "${ctx.pubUrl}"`)
  }
  if (ctx.insecure) {
    lines.push('unsafeHttpWhitelist:', `  - "${ctx.hostNoPort}"`)
  }
  return lines.join('\n')
}

function berryScopeRc(ctx: SnippetCtx): string {
  return [
    'npmScopes:',
    '  your-scope:',
    `    npmRegistryServer: "${ctx.repoUrl}"`,
    ...(ctx.pubUrl && ctx.pubUrl !== ctx.repoUrl ? [`    npmPublishRegistry: "${ctx.pubUrl}"`] : []),
    '    npmAlwaysAuth: true',
    `    npmAuthToken: "${ctx.token}"`,
    ...(ctx.insecure ? ['unsafeHttpWhitelist:', `  - "${ctx.hostNoPort}"`] : []),
  ].join('\n')
}

function yarnBerryClient(ctx: SnippetCtx): Client {
  return {
    id: 'yarn-berry',
    label: 'yarn 2+ (berry)',
    configure: [
      {
        title: 'Add the registry and token to ~/.yarnrc.yml',
        description: `yarn 2+ ignores <code>.npmrc</code>. The token applies to the registry and publish registry below. ${TOKEN_NOTE}`
          + (ctx.insecure ? ' <code>unsafeHttpWhitelist</code> allows the plain-HTTP registry.' : '')
          + ' yarn 4 skips versions published less than a day ago (<code>npmMinimalAgeGate</code>); list your own packages under <code>npmPreapprovedPackages</code> to install them right after publishing.',
        file: '~/.yarnrc.yml',
        download: '.yarnrc.yml',
        lang: 'yaml',
        code: berryRc(ctx),
      },
      {
        title: 'Optional: route only one scope through Pantera',
        description: 'Use this <em>instead of</em> the block above to send only <code>@your-scope</code> packages (and their token) to Pantera.',
        file: '~/.yarnrc.yml',
        lang: 'yaml',
        code: berryScopeRc(ctx),
      },
    ],
    resolve: [installStep(ctx, 'yarn add')],
    publish: ctx.pubUrl
      ? [{
          title: 'Publish',
          description: `Publishes to <code>${ctx.pubRepo}</code> (<code>npmPublishRegistry</code>, or the registry when they are the same).`,
          code: 'yarn npm publish',
        }]
      : [],
    publishNote: ctx.pubUrl ? undefined : NO_PUBLISH,
    verify: [verifyStep(ctx, 'yarn npm info lodash --fields version', 'yarn npm whoami')],
  }
}

export const npmSnippets: FormatSnippets = ctx => [
  npmClient(ctx),
  pnpmClient(ctx),
  yarn1Client(ctx),
  yarnBerryClient(ctx),
]
