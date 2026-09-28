import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** File the repository's registry public key is saved to. */
const KEY_FILE = 'pantera-hex.pem'

/** Package produced by the Publish steps (`app` and `version` in `mix.exs`). */
const PKG_TAR = 'my_package-0.1.0.tar'

/** Single-quote a value for POSIX shells. */
function sq(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

/**
 * Repository alias. It must be the Pantera repository name: Pantera stamps every
 * registry record with it, and Hex rejects records whose origin does not match
 * the alias they were fetched through.
 */
function alias(ctx: SnippetCtx): string {
  return ctx.repo
}

function configure(ctx: SnippetCtx): Step[] {
  const tokenHint = ctx.hasToken
    ? ''
    : ' Generate a token first: the key below encodes the <code>YOUR_TOKEN</code> placeholder.'
  return [
    {
      title: 'Download the registry public key',
      description: 'Pantera signs its Hex registry; Hex verifies every registry record with this key.',
      code: `curl -fsS -u ${sq(`${ctx.user}:${ctx.token}`)} -o ${KEY_FILE} \\\n  ${ctx.repoUrl}/public_key`,
    },
    {
      title: 'Register the repository',
      description:
        'Mix sends <code>--auth-key</code> verbatim as the <code>Authorization</code> header, so the key is '
        + 'a Basic credential built from your username and token. Hex stores it in '
        + `<code>~/.hex/hex.config</code>. Keep the name <code>${alias(ctx)}</code> (the Pantera repository `
        + `name): Hex checks that registry records come from a repository of that name.${tokenHint}`,
      code: `mix hex.repo add ${alias(ctx)} ${ctx.repoUrl} \\\n  --public-key ${KEY_FILE} \\\n  --auth-key ${sq(`Basic ${ctx.b64}`)}`,
    },
  ]
}

function resolve(ctx: SnippetCtx): Step[] {
  return [
    {
      title: 'Declare the dependency in mix.exs',
      description: 'Replace <code>my_package</code> and the version requirement with the package you need.',
      code: [
        'defp deps do',
        '  [',
        `    {:my_package, "~> 0.1", repo: "${alias(ctx)}"}`,
        '  ]',
        'end',
      ].join('\n'),
      lang: 'elixir',
      file: 'mix.exs',
    },
    {
      title: 'Fetch dependencies',
      description: 'Hex verifies the registry signature and the package checksums.',
      code: 'mix deps.get',
    },
  ]
}

function publish(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  return [
    {
      title: 'Build the package',
      description:
        `Run in the project to publish. <code>project</code> in <code>mix.exs</code> needs a <code>description</code> `
        + `and <code>package: [licenses: [...], links: %{...}]</code>. Writes <code>${PKG_TAR}</code>, named after `
        + '<code>app</code> and <code>version</code>.',
      code: 'mix hex.build',
    },
    {
      title: 'Upload the package',
      description:
        'Upload the tarball with curl. Expect HTTP 201. Use <code>replace=true</code> to overwrite an existing '
        + 'version.',
      code: [
        `curl -fsS -u ${sq(`${ctx.user}:${ctx.token}`)} \\`,
        `  -H 'Content-Type: application/octet-stream' \\`,
        `  --data-binary @${PKG_TAR} \\`,
        `  '${ctx.pubUrl}/publish?replace=false'`,
      ].join('\n'),
    },
  ]
}

function verify(ctx: SnippetCtx): Step[] {
  return [
    {
      title: 'Check the credentials',
      description:
        'Sends the same header Mix uses. Prints <code>204</code>; <code>401</code> means the key is wrong.',
      code: [
        `curl -s -o /dev/null -w '%{http_code}\\n' \\`,
        `  -H ${sq(`Authorization: Basic ${ctx.b64}`)} \\`,
        `  ${ctx.repoUrl}/users/me`,
      ].join('\n'),
    },
  ]
}

export const hexpmSnippets: FormatSnippets = (ctx: SnippetCtx): Client[] => [
  {
    id: 'mix',
    label: 'Mix',
    configure: configure(ctx),
    resolve: resolve(ctx),
    publish: publish(ctx),
    verify: verify(ctx),
    ...(ctx.pubUrl ? {} : { publishNote: 'Packages are uploaded to a local Hex repository; none is selected.' }),
  },
]
