import type { Client, SnippetCtx, Step } from './types'

/** NuGet v3 clients only speak to the service index, never the bare repository URL. */
const index = (url: string) => `${url}/index.json`

const xml = (value: string) => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')

/** Source name used for publishing: the resolve source when both are the same repository. */
function publishSource(ctx: SnippetCtx): string {
  return ctx.pubUrl === ctx.repoUrl ? 'pantera' : 'pantera-publish'
}

function addSource(ctx: SnippetCtx, url: string, name: string): string {
  const lines = [
    `dotnet nuget add source ${index(url)}`,
    `  --name ${name}`,
    `  --username ${ctx.user}`,
    `  --password ${ctx.token}`,
    '  --store-password-in-clear-text',
  ]
  if (ctx.insecure) lines.push('  --allow-insecure-connections')
  return lines.join(' \\\n')
}

const INSECURE_NOTE = ' The registry uses plain HTTP, so the source needs'
  + ' <code>--allow-insecure-connections</code> (required by .NET SDK 9 and later).'
  + ' The .NET 8 SDK does not know the option: drop that line there.'

function addSourceDescription(ctx: SnippetCtx, what: string): string {
  return `Registers ${what} in your user <code>NuGet.Config</code>. Use the <code>/index.json</code>`
    + ' service index URL: without it dotnet treats the source as a v2 feed and gets 404.'
    + (ctx.insecure ? INSECURE_NOTE : '')
}

function publishSteps(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  const source = publishSource(ctx)
  const steps: Step[] = []
  if (source !== 'pantera') {
    steps.push({
      title: 'Add the publish source',
      description: addSourceDescription(ctx, `<code>${ctx.pubRepo}</code> as <code>${source}</code>`),
      code: addSource(ctx, ctx.pubUrl, source),
    })
  }
  steps.push(
    {
      title: 'Pack the project',
      description: 'Run in the project directory. Writes the <code>.nupkg</code> to <code>./nupkg</code>.',
      code: 'dotnet pack -c Release -o nupkg',
    },
    {
      title: 'Push the package',
      description: 'Pantera authenticates the push with the username and token stored for the source'
        + ' (passing the token as <code>--api-key</code> works too). <code>--skip-duplicate</code> skips'
        + ' versions already in the repository (older packages left in <code>./nupkg</code>).',
      code: `dotnet nuget push "nupkg/*.nupkg" --source ${source} --skip-duplicate`,
    },
  )
  return steps
}

/**
 * `dotnet add package --source` takes a URL or folder, not a source name (a name is read as a
 * local folder and the command reports "no versions available"). The stored credentials are
 * matched by URL.
 */
const resolveStep = (ctx: SnippetCtx) => ({
  description: 'Run in the project directory. Replace <code>PACKAGE</code> with the package id;'
    + ' add <code>--version</code> to pin one.',
  code: `dotnet add package PACKAGE --source ${index(ctx.repoUrl)}`,
})

const PUBLISH_NOTE = 'Select a local NuGet repository to publish packages.'

/**
 * The adapter advertises no search service, so dotnet has no read-only probe. The service
 * index itself answers 200 to any Basic header, so it cannot prove the token; the
 * registration endpoint checks it and answers an empty page for an unknown id.
 */
const verifySteps = (ctx: SnippetCtx): Step[] => [
  {
    title: 'Query the registration endpoint',
    description: 'Expected: <code>{"count":0,"items":[]}</code> and <code>HTTP 200</code>'
      + ' (the id <code>pantera.verify</code> does not need to exist). <code>HTTP 401</code> means the'
      + ' username or token is wrong.',
    code: `curl -sS -w '\\nHTTP %{http_code}\\n' -u '${ctx.user}:${ctx.token}' ${ctx.repoUrl}/registrations/pantera.verify/index.json`,
  },
]

const dotnetCli = (ctx: SnippetCtx): Client => ({
  id: 'dotnet',
  label: 'dotnet CLI',
  configure: [
    {
      title: 'Add the package source',
      description: addSourceDescription(ctx, `<code>${ctx.repo}</code> as <code>pantera</code>`),
      code: addSource(ctx, ctx.repoUrl, 'pantera'),
    },
  ],
  resolve: [
    {
      title: 'Add a package to your project',
      ...resolveStep(ctx),
    },
  ],
  publish: publishSteps(ctx),
  verify: verifySteps(ctx),
  publishNote: ctx.pubUrl ? undefined : PUBLISH_NOTE,
})

function nugetConfig(ctx: SnippetCtx): string {
  const insecure = ctx.insecure ? ' allowInsecureConnections="true"' : ''
  return [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<configuration>',
    '  <packageSources>',
    `    <add key="pantera" value="${xml(index(ctx.repoUrl))}" protocolVersion="3"${insecure} />`,
    '  </packageSources>',
    '  <packageSourceCredentials>',
    '    <pantera>',
    `      <add key="Username" value="${xml(ctx.user)}" />`,
    `      <add key="ClearTextPassword" value="${xml(ctx.token)}" />`,
    '    </pantera>',
    '  </packageSourceCredentials>',
    '</configuration>',
  ].join('\n')
}

const configFile = (ctx: SnippetCtx): Client => ({
  id: 'nuget-config',
  label: 'nuget.config',
  configure: [
    {
      title: 'Add nuget.config',
      description: 'Save next to your solution or project; dotnet, msbuild and Visual Studio pick it up.'
        + ' Keep it out of version control because it holds your token.'
        + (ctx.insecure ? ' <code>allowInsecureConnections</code> lets .NET SDK 9 and later use the plain-HTTP registry.' : ''),
      code: nugetConfig(ctx),
      lang: 'xml',
      file: 'nuget.config',
      download: 'nuget.config',
    },
  ],
  resolve: [
    {
      title: 'Add a package to your project',
      ...resolveStep(ctx),
    },
  ],
  publish: publishSteps(ctx),
  verify: verifySteps(ctx),
  publishNote: ctx.pubUrl ? undefined : PUBLISH_NOTE,
})

export const nugetSnippets = (ctx: SnippetCtx): Client[] => [dotnetCli(ctx), configFile(ctx)]
