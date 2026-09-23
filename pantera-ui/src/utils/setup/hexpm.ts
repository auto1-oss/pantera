import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/**
 * Repository alias. It must be `pantera`: Pantera stamps every registry record
 * with repository name `pantera`, and Hex rejects records whose origin does not
 * match the alias they were fetched through.
 */
const ALIAS = 'pantera'

/** Package produced by the Publish steps (`app` and `version` in `mix.exs`). */
const PKG_TAR = 'my_package-0.1.0.tar'

/** Single-quote a value for POSIX shells. */
function sq(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

function configure(ctx: SnippetCtx): Step[] {
  const tokenHint = ctx.hasToken
    ? ''
    : ' Generate a token first: the key below encodes the <code>YOUR_TOKEN</code> placeholder.'
  return [
    {
      title: 'Register the repository',
      description:
        'Mix sends <code>--auth-key</code> verbatim as the <code>Authorization</code> header, so the key is '
        + 'a Basic credential built from your username and token. Hex stores it in '
        + '<code>~/.hex/hex.config</code>. Keep the name <code>pantera</code>: Hex checks that registry '
        + `records come from a repository of that name.${tokenHint}`,
      code: `mix hex.repo add ${ALIAS} ${ctx.repoUrl} \\\n  --auth-key ${sq(`Basic ${ctx.b64}`)}`,
    },
  ]
}

function resolve(): Step[] {
  return [
    {
      title: 'Declare the dependency in mix.exs',
      description: 'Replace <code>my_package</code> and the version requirement with the package you need.',
      code: [
        'defp deps do',
        '  [',
        `    {:my_package, "~> 0.1", repo: "${ALIAS}"}`,
        '  ]',
        'end',
      ].join('\n'),
      lang: 'elixir',
      file: 'mix.exs',
    },
    {
      title: 'Fetch dependencies',
      description:
        'Pantera does not sign its Hex registry, so signature checks are switched off for this command with '
        + '<code>HEX_UNSAFE_REGISTRY=1</code>. Package checksums are still verified. Set the variable in CI '
        + 'too, and prefer it over <code>mix hex.config unsafe_registry true</code>, which turns the check '
        + 'off for hex.pm as well.',
      code: 'HEX_UNSAFE_REGISTRY=1 mix deps.get',
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
        '<code>mix hex.publish</code> does not work against Pantera, so upload the tarball with curl. Expect '
        + 'HTTP 201. Use <code>replace=true</code> to overwrite an existing version.',
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
    resolve: resolve(),
    publish: publish(ctx),
    verify: verify(ctx),
    ...(ctx.pubUrl ? {} : { publishNote: 'Packages are uploaded to a local Hex repository; none is selected.' }),
  },
]
