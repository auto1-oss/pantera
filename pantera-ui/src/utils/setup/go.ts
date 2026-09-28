import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/**
 * Go modules. The go command has no publish verb, so publishing is three
 * curl PUTs (.info, .mod, .zip) to a `go` local repository.
 *
 * Credentials live in ~/.netrc, never in GOPROXY: a URL userinfo leaks into
 * `go env` and error output and breaks on `@` in SSO usernames. Go only
 * attaches credentials (netrc, GOAUTH or URL userinfo) to https requests,
 * so an http registry cannot serve authenticated `go` clients at all; that
 * variant is curl-only and says so.
 */

const PUBLIC_MODULE = 'rsc.io/quote'
const PUBLIC_VERSION = 'v1.5.2'
const PRIVATE_PREFIX = 'github.com/YOUR_ORG'

function isLocal(ctx: SnippetCtx): boolean {
  return ctx.mode === 'local'
}

/**
 * ~/.netrc lines. Go 1.24+ matches the machine against `host:port` when the
 * URL has a port; curl and older Go match the bare host name. Emitting both
 * covers every client.
 */
function netrcStep(ctx: SnippetCtx, description: string): Step {
  const machines = ctx.host === ctx.hostNoPort ? [ctx.host] : [ctx.host, ctx.hostNoPort]
  const lines = machines.map(m => `machine ${m} login ${ctx.user} password ${ctx.token}`)
  return {
    title: 'Store credentials in ~/.netrc',
    description,
    file: '~/.netrc',
    code: `cat >> ~/.netrc <<'EOF'\n${lines.join('\n')}\nEOF\nchmod 600 ~/.netrc`,
  }
}

/** Verify for a local repository, which cannot serve public modules. */
function localAccessCheck(ctx: SnippetCtx): Step {
  return {
    title: 'Check access',
    description:
      'Prints <code>404</code> on a local repository without that module: the request was authenticated and '
      + 'routed. <code>401</code> means the credentials in <code>~/.netrc</code> are wrong.',
    code: `curl --netrc -s -o /dev/null -w '%{http_code}\\n' ${ctx.repoUrl}/${PUBLIC_MODULE}/@v/list`,
  }
}

function publishSteps(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  return [
    {
      title: 'Package the module',
      description:
        'Run in the module root after committing and tagging. <code>git archive</code> packs the committed files, as '
        + '<code>go</code> would fetch them from VCS; <code>zip -D</code> omits directory entries so '
        + '<code>go mod verify</code> passes for consumers. Uppercase letters in the module path are escaped '
        + '(<code>!</code> + lowercase) for the URL, as the Go module protocol requires. Set <code>VER</code> to a new '
        + 'version: Go versions are immutable, and republishing one breaks every consumer whose '
        + '<code>go.sum</code> already recorded it.',
      code: [
        'MOD=$(go list -m)',
        'VER=v0.1.0',
        'ESC=$(printf %s "$MOD" | perl -pe \'s/([A-Z])/!\\l$1/g\')',
        'STAGE=$(mktemp -d)',
        'mkdir -p "$STAGE/$MOD@$VER"',
        'git archive HEAD | tar -x -C "$STAGE/$MOD@$VER"',
        '(cd "$STAGE" && zip -qrD "$VER.zip" "$MOD@$VER")',
        'cp go.mod "$STAGE/$VER.mod"',
        'printf \'{"Version":"%s","Time":"%s"}\\n\' "$VER" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STAGE/$VER.info"',
      ].join('\n'),
    },
    {
      title: `Upload to ${ctx.pubRepo}`,
      description:
        'The <code>go</code> command cannot publish, so upload the three files with curl (credentials from '
        + '<code>~/.netrc</code>). Upload the <code>.zip</code> last: it adds the version to <code>@v/list</code>. '
        + 'Each upload answers HTTP 201.',
      code: `for f in info mod zip; do\n  curl --netrc -fsS -T "$STAGE/$VER.$f" "${ctx.pubUrl}/$ESC/@v/$VER.$f"\ndone`,
    },
  ]
}

const NO_PUBLISH_NOTE =
  'The <code>go</code> command has no publish command. Pick a <code>go</code> local repository as the publish '
  + 'target to get a curl upload of the module <code>.info</code>, <code>.mod</code> and <code>.zip</code> files.'

function httpsClient(ctx: SnippetCtx): Client {
  const local = isLocal(ctx)
  const verify: Step = local
    ? localAccessCheck(ctx)
    : {
        title: 'List versions through the registry',
        description: `Prints <code>${PUBLIC_MODULE}</code> followed by its versions (… v1.5.2 v1.5.3-pre1).`,
        code: `go list -m -versions ${PUBLIC_MODULE}`,
      }
  return {
    id: 'go',
    label: 'go',
    configure: [
      netrcStep(
        ctx,
        'Go reads <code>~/.netrc</code> (<code>%USERPROFILE%\\_netrc</code> on Windows) for https proxies, so the '
          + 'token stays out of <code>GOPROXY</code> and out of <code>go env</code> output.',
      ),
      {
        title: 'Point Go at the registry',
        description:
          '<code>go env -w</code> persists the settings for every shell. There is no <code>,direct</code> fallback on '
          + 'purpose: with it, any 404 from Pantera sends <code>go</code> straight to the VCS host, bypassing the '
          + 'cache, cooldown and audit. Resolve through a group or proxy repository to get public modules too. '
          + `Replace <code>${PRIVATE_PREFIX}</code> with your private module prefixes (comma-separated globs); `
          + 'they skip the public checksum database while public modules stay verified. Use '
          + '<code>GONOSUMDB</code>, not <code>GOPRIVATE</code>: <code>GOPRIVATE</code> also makes <code>go</code> '
          + 'bypass the proxy for those modules.',
        code: `go env -w GOPROXY=${ctx.repoUrl} GONOSUMDB=${PRIVATE_PREFIX}`,
      },
    ],
    resolve: [
      {
        title: 'Add a dependency',
        description: 'Inside your module (a directory with <code>go.mod</code>). <code>go build</code>, '
          + '<code>go mod download</code> and <code>go mod tidy</code> use the same proxy.',
        code: local ? 'go get github.com/YOUR_ORG/MODULE@latest' : `go get ${PUBLIC_MODULE}@${PUBLIC_VERSION}`,
      },
    ],
    publish: publishSteps(ctx),
    verify: [verify],
    publishNote: ctx.pubUrl ? undefined : NO_PUBLISH_NOTE,
  }
}

function httpClient(ctx: SnippetCtx): Client {
  const local = isLocal(ctx)
  return {
    id: 'go',
    label: 'go (plain HTTP: curl only)',
    configure: [
      netrcStep(
        ctx,
        '<strong>The <code>go</code> command cannot use this registry.</strong> It is served over plain HTTP, and Go '
          + 'never sends credentials over HTTP: it refuses <code>user:token@</code> in an http <code>GOPROXY</code>, '
          + 'and <code>~/.netrc</code> / <code>GOAUTH</code> apply to https only. <code>GOINSECURE</code> does not '
          + 'affect <code>GOPROXY</code>, and <code>-insecure</code> no longer exists. Ask your administrator to '
          + 'serve Pantera over HTTPS, then use the https setup. Until then, curl can download and publish module '
          + 'files with these credentials.',
      ),
    ],
    resolve: [
      {
        title: 'Download a module file with curl',
        description: 'Fetches the module zip directly; the <code>go</code> command itself cannot authenticate here.',
        code: local
          ? `curl --netrc -fsSLO ${ctx.repoUrl}/github.com/YOUR_ORG/MODULE/@v/v1.0.0.zip`
          : `curl --netrc -fsSLO ${ctx.repoUrl}/${PUBLIC_MODULE}/@v/${PUBLIC_VERSION}.zip`,
      },
    ],
    publish: publishSteps(ctx),
    verify: [
      local
        ? localAccessCheck(ctx)
        : {
            title: 'List versions through the registry',
            description: `Prints the versions of <code>${PUBLIC_MODULE}</code>, one per line.`,
            code: `curl --netrc -fsS ${ctx.repoUrl}/${PUBLIC_MODULE}/@v/list`,
          },
    ],
    publishNote: ctx.pubUrl ? undefined : NO_PUBLISH_NOTE,
  }
}

export const goSnippets: FormatSnippets = ctx => [ctx.insecure ? httpClient(ctx) : httpsClient(ctx)]
