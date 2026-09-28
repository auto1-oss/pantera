import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Placeholder package file; its architecture must be one the repository serves. */
const DEB_FILE = 'my-package_1.0.0_amd64.deb'
const AUTH_FILE = '/etc/apt/auth.conf.d/pantera.conf'
const LIST_FILE = '/etc/apt/sources.list.d/pantera.list'

/**
 * `machine` value for apt's auth.conf. apt only sends auth.conf credentials
 * over plain HTTP when the entry names the `http://` scheme explicitly.
 */
function authMachine(ctx: SnippetCtx): string {
  return ctx.insecure ? `http://${ctx.repoPath}` : ctx.repoPath
}

function configureSteps(ctx: SnippetCtx): Step[] {
  return [
    {
      title: `Store your credentials in ${AUTH_FILE}`,
      description:
        `apt reads credentials from <code>auth.conf.d</code>, so they stay out of the source list `
        + `(which any user can read). The file must be owned by root and not world-readable.`,
      code:
        `sudo tee ${AUTH_FILE} > /dev/null <<'EOF'\n`
        + `machine ${authMachine(ctx)}\n`
        + `login ${ctx.user}\n`
        + `password ${ctx.token}\n`
        + `EOF\n`
        + `sudo chmod 600 ${AUTH_FILE}`,
      file: AUTH_FILE,
    },
    {
      title: 'Add the APT source',
      description:
        `The distribution is the repository name (<code>${ctx.repo}</code>) and the component is `
        + `<code>main</code>. <code>[trusted=yes]</code> is needed because Pantera only signs the `
        + `indexes when the repository has a GPG key configured; for a signed repository replace it with `
        + `<code>[signed-by=/etc/apt/keyrings/pantera.gpg]</code> pointing at the repository's public key.`,
      code: `echo "deb [trusted=yes] ${ctx.repoUrl} ${ctx.repo} main" \\\n  | sudo tee ${LIST_FILE}`,
      file: LIST_FILE,
    },
  ]
}

function aptClient(ctx: SnippetCtx): Client {
  return {
    id: 'apt',
    label: 'apt',
    configure: configureSteps(ctx),
    resolve: [
      {
        title: 'Install a package',
        description: `<code>apt-cache policy</code> shows the candidate version and that it comes from this repository.`,
        code: 'sudo apt-get update\napt-cache policy PACKAGE\nsudo apt-get install PACKAGE',
      },
    ],
    publish: ctx.pubUrl
      ? [
        {
          title: 'Upload a .deb package',
          description:
            `<code>-T</code> sends an HTTP PUT; the trailing <code>/</code> makes curl append the file name, `
            + `so each package is stored under its own name in <code>pool/main/</code>. The package's `
            + `<code>Architecture</code> must be one of the repository's architectures, otherwise the `
            + `server answers <code>400</code>.`,
          code: `curl -f -u '${ctx.user}:${ctx.token}' \\\n  -T ${DEB_FILE} \\\n  ${ctx.pubUrl}/pool/main/`,
        },
      ]
      : [],
    verify: [
      {
        title: 'Check that apt reads the repository',
        description:
          `Prints a line such as <code>500 ${ctx.repoUrl} ${ctx.repo}/main amd64 Packages</code>. `
          + `No output means the index was not fetched: look for <code>401 Unauthorized</code> in the `
          + `<code>apt-get update</code> output. A <code>404</code> for <code>Packages</code> means the repository `
          + `holds no package for your machine's architecture yet (or does not serve that architecture): `
          + `upload one first.`,
        code: `sudo apt-get update && apt-cache policy | grep -F '${ctx.repoUrl} '`,
      },
    ],
  }
}

export const debSnippets: FormatSnippets = ctx => [aptClient(ctx)]
