import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Index name used by uv and poetry for the resolve repository. */
const INDEX = 'pantera'
/** Poetry repository name for uploads (sources and upload targets are separate in poetry). */
const POETRY_PUBLISH = 'pantera-publish'

const indexUrl = (ctx: SnippetCtx) => `${ctx.repoUrl}/simple/`

const PACKAGE_HINT = 'Replace <code>requests</code> with any package the repository serves.'

const VERIFY_HINT = 'A local repository only serves what was uploaded to it: replace <code>requests</code> with one of its packages.'

function pip(ctx: SnippetCtx): Client {
  const authIndex = `${ctx.scheme}://${ctx.userEnc}:${ctx.token}@${ctx.repoPath}/simple/`
  const pipConf = [
    '[global]',
    `index-url = ${authIndex}`,
    ...(ctx.insecure ? [`trusted-host = ${ctx.hostNoPort}`] : []),
  ].join('\n')
  const publish: Step[] = ctx.pubUrl
    ? [
        {
          title: 'Configure ~/.pypirc',
          description: `Upload credentials for <code>twine</code>; uploads go to the local repository <code>${ctx.pubRepo}</code>.`,
          code: [
            '[distutils]',
            'index-servers =',
            `    ${INDEX}`,
            '',
            `[${INDEX}]`,
            `repository = ${ctx.pubUrl}`,
            `username = ${ctx.user}`,
            `password = ${ctx.token}`,
          ].join('\n'),
          lang: 'ini',
          file: '~/.pypirc',
          download: '.pypirc',
        },
        {
          title: 'Build and upload',
          description: 'Run in the project directory (the one with <code>pyproject.toml</code>).',
          code: `python3 -m pip install build twine\npython3 -m build\npython3 -m twine upload --repository ${INDEX} dist/*`,
        },
      ]
    : []
  return {
    id: 'pip',
    label: 'pip',
    configure: [
      {
        title: 'Configure pip.conf',
        description:
          'pip sends the credentials in the index URL; the username is percent-encoded. On macOS pip also reads '
          + '<code>~/Library/Application Support/pip/pip.conf</code>, on Windows <code>%APPDATA%\\pip\\pip.ini</code>; '
          + '<code>pip config debug</code> lists the files pip loads.'
          + (ctx.insecure ? ' <code>trusted-host</code> lets pip use the plain-HTTP registry.' : ''),
        code: pipConf,
        lang: 'ini',
        file: '~/.config/pip/pip.conf',
        download: 'pip.conf',
      },
    ],
    resolve: [
      { title: 'Install a package', description: PACKAGE_HINT, code: 'python3 -m pip install requests' },
    ],
    publish,
    verify: [
      {
        title: 'Download a package through the repository',
        description: `Prints <code>Saved …/requests-&lt;version&gt;-py3-none-any.whl</code>. ${VERIFY_HINT}`,
        code: 'python3 -m pip download requests --no-deps -d "$(mktemp -d)"',
      },
    ],
  }
}

function uv(ctx: SnippetCtx): Client {
  const index = [
    '[[tool.uv.index]]',
    `name = "${INDEX}"`,
    `url = "${indexUrl(ctx)}"`,
    ...(ctx.pubUrl ? [`publish-url = "${ctx.pubUrl}"`] : []),
    'default = true',
  ].join('\n')
  const envName = INDEX.toUpperCase()
  return {
    id: 'uv',
    label: 'uv',
    configure: [
      {
        title: 'Add the index to pyproject.toml',
        description:
          '<code>default = true</code> replaces PyPI, so every package resolves through this repository.'
          + (ctx.pubUrl ? ` <code>publish-url</code> points at the local repository <code>${ctx.pubRepo}</code>.` : ''),
        code: index,
        lang: 'toml',
        file: 'pyproject.toml',
      },
      {
        title: 'Set the credentials',
        description: `uv reads index credentials from <code>UV_INDEX_${envName}_USERNAME</code> / <code>UV_INDEX_${envName}_PASSWORD</code>; add them to your shell profile or CI secrets.`,
        code: `export UV_INDEX_${envName}_USERNAME='${ctx.user}'\nexport UV_INDEX_${envName}_PASSWORD='${ctx.token}'`,
      },
    ],
    resolve: [
      { title: 'Add a dependency', description: PACKAGE_HINT, code: 'uv add requests' },
    ],
    publish: ctx.pubUrl
      ? [{ title: 'Build and upload', code: `uv build\nuv publish --index ${INDEX} --trusted-publishing never` }]
      : [],
    verify: [
      {
        title: 'Resolve a package through the index',
        description: `Prints <code>requests==&lt;version&gt;</code> with its dependencies. ${VERIFY_HINT}`,
        code: 'echo requests | uv pip compile - --no-header',
      },
    ],
  }
}

function poetry(ctx: SnippetCtx): Client {
  return {
    id: 'poetry',
    label: 'Poetry',
    configure: [
      {
        title: 'Add the source and credentials',
        description:
          'Run in the project directory. <code>--priority=primary</code> replaces PyPI; poetry stores the '
          + 'credentials in the system keyring when one is available.',
        code: `poetry source add --priority=primary ${INDEX} ${indexUrl(ctx)}\npoetry config http-basic.${INDEX} '${ctx.user}' '${ctx.token}'`,
      },
      ...(ctx.pubUrl
        ? [{
            title: 'Add the upload repository',
            description: `Uploads go to the local repository <code>${ctx.pubRepo}</code>.`,
            code: `poetry config repositories.${POETRY_PUBLISH} ${ctx.pubUrl}\npoetry config http-basic.${POETRY_PUBLISH} '${ctx.user}' '${ctx.token}'`,
          }]
        : []),
    ],
    resolve: [
      { title: 'Add a dependency', description: PACKAGE_HINT, code: 'poetry add requests' },
    ],
    publish: ctx.pubUrl
      ? [{ title: 'Build and upload', code: `poetry publish --build -r ${POETRY_PUBLISH}` }]
      : [],
    verify: [
      {
        title: 'Resolve a package through the source',
        description: `Prints <code>Resolution results</code> listing <code>requests</code> and its dependencies, without changing the project. ${VERIFY_HINT}`,
        code: 'poetry debug resolve requests',
      },
    ],
  }
}

export const pypiSnippets: FormatSnippets = ctx => [pip(ctx), uv(ctx), poetry(ctx)]
