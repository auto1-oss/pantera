import type { Client, FormatSnippets, SnippetCtx } from './types'

/** Placeholder package file. */
const RPM_FILE = 'my-package-1.0.0-1.x86_64.rpm'
const REPO_FILE = '/etc/yum.repos.d/pantera.repo'

function dnfClient(ctx: SnippetCtx): Client {
  return {
    id: 'dnf',
    label: 'dnf / yum',
    configure: [
      {
        title: `Create ${REPO_FILE}`,
        description:
          `dnf and yum send <code>username</code>/<code>password</code> as HTTP Basic auth. `
          + `<code>repo_gpgcheck=0</code> because Pantera does not sign the repository metadata, and `
          + `<code>gpgcheck=0</code> for unsigned packages; set <code>gpgcheck=1</code> with <code>gpgkey=</code> `
          + `if your packages are signed. The file holds your `
          + `token, so it is made readable by root only. <code>skip_if_unavailable=0</code> makes dnf fail loudly `
          + `(instead of silently skipping the repository) when the credentials are wrong.`,
        code:
          `sudo tee ${REPO_FILE} > /dev/null <<'EOF'\n`
          + `[pantera]\n`
          + `name=Pantera ${ctx.repo}\n`
          + `baseurl=${ctx.repoUrl}\n`
          + `username=${ctx.user}\n`
          + `password=${ctx.token}\n`
          + `enabled=1\n`
          + `gpgcheck=0\n`
          + `repo_gpgcheck=0\n`
          + `skip_if_unavailable=0\n`
          + `EOF\n`
          + `sudo chmod 600 ${REPO_FILE}`,
        file: REPO_FILE,
      },
    ],
    resolve: [
      {
        title: 'Install a package',
        code: 'sudo dnf install PACKAGE',
      },
    ],
    publish: ctx.pubUrl
      ? [
        {
          title: 'Upload an .rpm package',
          description:
            `<code>-T</code> sends an HTTP PUT; the trailing <code>/</code> makes curl append the file name. `
            + `The repository metadata is regenerated after the upload. An existing file name answers `
            + `<code>409</code>; to replace it, upload to <code>${ctx.pubUrl}/FILE.rpm?override=true</code>.`,
          code: `curl -f -u '${ctx.user}:${ctx.token}' \\\n  -T ${RPM_FILE} \\\n  ${ctx.pubUrl}/`,
        },
      ]
      : [],
    verify: [
      {
        title: 'Check that dnf reads the repository',
        description:
          `Prints <code>Metadata cache created.</code> `
          + `<code>Status code: 401</code> means the credentials were rejected; <code>404</code> for `
          + `<code>repomd.xml</code> means nothing has been uploaded to the repository yet.`,
        code: 'sudo dnf --repo pantera --refresh makecache',
      },
    ],
  }
}

export const rpmSnippets: FormatSnippets = ctx => [dnfClient(ctx)]
