import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Version and archive name used in the publish example; the user edits the version. */
const EXAMPLE_VERSION = '1.0.0'
const EXAMPLE_ARCHIVE = `my-package-${EXAMPLE_VERSION}`

/**
 * `host[:port]` of a URL. Composer keys `http-basic` credentials by the
 * request origin, which carries the port when it is not the default one.
 */
function origin(url: string): string {
  return url.replace(/^https?:\/\//, '').split('/')[0]
}

/**
 * A group or proxy serves Packagist through Pantera: turn off the built-in
 * packagist.org so no request bypasses the registry. A local repository only
 * holds your own packages, so Packagist stays on for public dependencies.
 */
function coversPackagist(ctx: SnippetCtx): boolean {
  return ctx.mode !== 'local'
}

function composerJson(ctx: SnippetCtx): string {
  const repos = [`    {\n      "type": "composer",\n      "url": "${ctx.repoUrl}"\n    }`]
  if (coversPackagist(ctx)) repos.push('    {\n      "packagist.org": false\n    }')
  const config = ctx.insecure ? ',\n  "config": {\n    "secure-http": false\n  }' : ''
  return `{\n  "repositories": [\n${repos.join(',\n')}\n  ]${config}\n}`
}

/**
 * auth.json with one `http-basic` entry per registry origin. Composer only
 * sends credentials to the exact origin, and dist URLs point at the
 * repository's configured URL, so the publish repository's origin is added
 * when it differs from the resolve one.
 */
function authJson(ctx: SnippetCtx): string {
  const hosts = [origin(ctx.repoUrl)]
  if (ctx.pubUrl && !hosts.includes(origin(ctx.pubUrl))) hosts.push(origin(ctx.pubUrl))
  const user = JSON.stringify(ctx.user)
  const token = JSON.stringify(ctx.token)
  const entries = hosts.map(h =>
    `    "${h}": {\n      "username": ${user},\n      "password": ${token}\n    }`)
  return `{\n  "http-basic": {\n${entries.join(',\n')}\n  }\n}`
}

function configure(ctx: SnippetCtx): Step[] {
  return [
    {
      title: 'Add the repository to composer.json',
      description:
        'Merge these keys into your project\'s <code>composer.json</code>.'
        + (ctx.mode === 'proxy'
          ? ' <strong>Use a php-group instead:</strong> a php-proxy used directly cannot answer '
            + '<code>packages.json</code>, so pick the group that contains this proxy.'
          : '')
        + (coversPackagist(ctx)
          ? ' <code>"packagist.org": false</code> sends every package through Pantera instead of packagist.org.'
          : ' Packagist stays enabled for public packages; resolve from a group to route those through Pantera too.')
        + (ctx.insecure ? ' <code>secure-http: false</code> lets Composer use this plain-HTTP registry.' : ''),
      code: composerJson(ctx),
      lang: 'json',
      file: 'composer.json',
    },
    {
      title: 'Store your credentials in auth.json',
      description:
        'Save next to <code>composer.json</code>. Composer reads it for this project only; '
        + 'keep it out of version control (<code>echo auth.json &gt;&gt; .gitignore</code>).',
      code: authJson(ctx),
      lang: 'json',
      file: 'auth.json',
      download: 'auth.json',
    },
  ]
}

function publish(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  return [
    {
      title: 'Set the version in the package\'s composer.json',
      description:
        'Merge these keys into the <code>composer.json</code> of the package you publish. Pantera takes '
        + 'the version from here (without it the upload becomes <code>dev-master</code>); '
        + '<code>archive.exclude</code> keeps <code>vendor/</code> and earlier builds out of the archive.',
      code: `{\n  "version": "${EXAMPLE_VERSION}",\n  "archive": {\n    "exclude": ["/vendor", "/dist"]\n  }\n}`,
      lang: 'json',
      file: 'composer.json',
    },
    {
      title: 'Build the archive',
      code: `composer archive --format=zip --dir=dist --file=${EXAMPLE_ARCHIVE}`,
    },
    {
      title: 'Upload the archive',
      description: `Uploads to <code>${ctx.pubRepo}</code>. Prints <code>201</code> once the package is indexed.`,
      code: `curl -sS -w '%{http_code}\\n' -u '${ctx.user}:${ctx.token}' \\\n  --upload-file dist/${EXAMPLE_ARCHIVE}.zip \\\n  ${ctx.pubUrl}/${EXAMPLE_ARCHIVE}.zip`,
    },
  ]
}

function verify(ctx: SnippetCtx): Step[] {
  if (coversPackagist(ctx)) {
    return [{
      title: 'Show a public package through the repository',
      description: 'Run in the project. Prints the <code>psr/log</code> versions, served by Pantera.',
      code: 'composer show --available psr/log',
    }]
  }
  return [{
    title: 'Fetch the repository index',
    description: 'Prints a JSON document with a <code>metadata-url</code>; a 401 means the credentials are wrong.',
    code: `curl -fsS -u '${ctx.user}:${ctx.token}' ${ctx.repoUrl}/packages.json`,
  }]
}

export const phpSnippets: FormatSnippets = ctx => {
  const composer: Client = {
    id: 'composer',
    label: 'Composer',
    configure: configure(ctx),
    resolve: [
      { title: 'Install the project dependencies', code: 'composer install' },
      { title: 'Add a package', code: 'composer require psr/log' },
    ],
    publish: publish(ctx),
    verify: verify(ctx),
  }
  if (!ctx.pubUrl) {
    composer.publishNote = 'Publishing needs a local <code>php</code> repository. Choose one under Publish to.'
  }
  return [composer]
}
