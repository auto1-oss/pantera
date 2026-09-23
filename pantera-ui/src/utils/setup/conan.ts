import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Local remote name the snippets register the repository under. */
const REMOTE = 'pantera'

/** Recipe reference used in every step so Resolve, Publish and Verify line up. */
const REF = 'my_package/1.0@'

/**
 * Conan repositories are served on their own port, at the root of that port.
 * The URL the panel passes is the repository's configured `url` when it has one;
 * otherwise it is the main-port URL, which Conan cannot use.
 */
const PORT_NOTE =
  'Conan repositories are served on a dedicated port, so the URL must be the repository\'s own Conan URL '
  + '(its <code>url</code> setting, e.g. <code>http://registry.example.com:9300</code>), not the main '
  + 'registry address. If login fails with <code>Wrong user or password</code> although the token is right, '
  + 'the URL is the main-port one: ask your administrator for the Conan URL.'

/** Single-quote a value for POSIX shells. */
function sq(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

function configure(ctx: SnippetCtx): Step[] {
  return [
    {
      title: 'Add the remote',
      description:
        'Pantera speaks the Conan 1.x protocol; these steps are for Conan 1.60 (Python 3.11 or older). '
        + `Conan 2 clients are not tested. ${PORT_NOTE}`,
      code: `conan remote add ${REMOTE} ${ctx.repoUrl}`,
    },
    {
      title: 'Log in',
      description:
        'Conan exchanges the token for a session token and keeps it in <code>~/.conan/.conan.db</code>. '
        + 'In CI, set <code>CONAN_LOGIN_USERNAME_PANTERA</code> and <code>CONAN_PASSWORD_PANTERA</code> '
        + 'instead.',
      code: `conan user ${sq(ctx.user)} -r ${REMOTE} -p ${sq(ctx.token)}`,
    },
  ]
}

function resolve(): Step[] {
  return [
    {
      title: 'Install a package',
      description:
        'Replace <code>my_package/1.0</code> with a recipe from the repository; the trailing <code>@</code> '
        + 'means no user or channel.',
      code: `conan install ${REF} -r ${REMOTE}`,
    },
  ]
}

function publish(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  return [
    {
      title: 'Create the package',
      description: 'Run next to your <code>conanfile.py</code> (name <code>my_package</code>, version <code>1.0</code>).',
      code: 'conan create .',
    },
    {
      title: 'Upload recipe and binaries',
      code: `conan upload ${REF} -r ${REMOTE} --all --confirm`,
    },
  ]
}

function verify(): Step[] {
  return [
    {
      title: 'Search the remote',
      description:
        'Prints <code>Existing package recipes:</code> with the recipes in the repository, or '
        + '<code>There are no packages matching the \'*\' pattern</code> when it is empty.',
      code: `conan search '*' -r ${REMOTE}`,
    },
  ]
}

export const conanSnippets: FormatSnippets = (ctx: SnippetCtx): Client[] => [
  {
    id: 'conan',
    label: 'Conan 1.x',
    configure: configure(ctx),
    resolve: resolve(),
    publish: publish(ctx),
    verify: verify(),
    ...(ctx.pubUrl ? {} : { publishNote: 'Packages are uploaded to a local Conan repository; none is selected.' }),
  },
]
