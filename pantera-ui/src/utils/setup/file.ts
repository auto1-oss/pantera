import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Placeholder path used in every step so Resolve, Publish and Verify line up. */
const FILE_PATH = 'path/to/FILE'
const FILE_NAME = 'FILE'

const PUBLISH_NOTE = 'Uploads go to a local file repository. Pick one under "Publish to" to see the upload commands.'

/**
 * `~/.netrc` entry for the registry host. curl reads it with `--netrc`,
 * wget reads it automatically; both match on the host name without the port.
 */
function netrcSteps(ctx: SnippetCtx): Step[] {
  return [
    {
      title: 'Store your credentials in ~/.netrc',
      description:
        `Keeps the token off the command line. <code>machine</code> is the host name without the port. `
        + `If you skip this, pass the credentials on each command instead (shown under Resolve).`,
      code: `machine ${ctx.hostNoPort}\nlogin ${ctx.user}\npassword ${ctx.token}\n`,
      lang: 'ini',
      file: '~/.netrc',
      download: '.netrc',
    },
    {
      title: 'Make the file private',
      code: 'chmod 600 ~/.netrc',
    },
  ]
}

function curlClient(ctx: SnippetCtx): Client {
  const creds = `'${ctx.user}:${ctx.token}'`
  return {
    id: 'curl',
    label: 'curl',
    configure: netrcSteps(ctx),
    resolve: [
      {
        title: 'Download a file',
        description: `<code>-f</code> fails on HTTP errors instead of saving the error body; <code>-O</code> keeps the remote file name.`,
        code: `curl -fL --netrc -O ${ctx.repoUrl}/${FILE_PATH}`,
      },
      {
        title: 'Without ~/.netrc',
        code: `curl -fL -u ${creds} -O ${ctx.repoUrl}/${FILE_PATH}`,
      },
    ],
    publish: ctx.pubUrl
      ? [
        {
          title: 'Upload a file',
          description: `<code>-T</code> (<code>--upload-file</code>) sends an HTTP PUT; the server answers <code>201 Created</code>.`,
          code: `curl -f --netrc -T ${FILE_NAME} ${ctx.pubUrl}/${FILE_PATH}`,
        },
        {
          title: 'Without ~/.netrc',
          code: `curl -f -u ${creds} -T ${FILE_NAME} ${ctx.pubUrl}/${FILE_PATH}`,
        },
      ]
      : [],
    publishNote: ctx.pubUrl ? undefined : PUBLISH_NOTE,
    verify: [
      {
        title: 'Check that a file resolves',
        description:
          `Prints <code>200</code> for a file in the repository (for example one you uploaded). `
          + `<code>401</code> means the credentials were rejected, <code>404</code> that the path does not exist.`,
        code: `curl -s --netrc -o /dev/null -w '%{http_code}\\n' ${ctx.repoUrl}/${FILE_PATH}`,
      },
    ],
  }
}

function wgetClient(ctx: SnippetCtx): Client {
  const creds = `--user='${ctx.user}' --password='${ctx.token}'`
  return {
    id: 'wget',
    label: 'wget',
    configure: netrcSteps(ctx),
    resolve: [
      {
        title: 'Download a file',
        description: `wget picks up the credentials from <code>~/.netrc</code> automatically.`,
        code: `wget ${ctx.repoUrl}/${FILE_PATH}`,
      },
      {
        title: 'Without ~/.netrc',
        code: `wget ${creds} \\\n  ${ctx.repoUrl}/${FILE_PATH}`,
      },
    ],
    publish: ctx.pubUrl
      ? [
        {
          title: 'Upload a file',
          description: `Sends an HTTP PUT with the file as the body; the server answers <code>201 Created</code>. Needs wget 1.15 or later.`,
          code: `wget -nv -O /dev/null --method=PUT --body-file=${FILE_NAME} \\\n  ${ctx.pubUrl}/${FILE_PATH}`,
        },
        {
          title: 'Without ~/.netrc',
          code: `wget -nv -O /dev/null --method=PUT --body-file=${FILE_NAME} \\\n  ${creds} \\\n  ${ctx.pubUrl}/${FILE_PATH}`,
        },
      ]
      : [],
    publishNote: ctx.pubUrl ? undefined : PUBLISH_NOTE,
    verify: [
      {
        title: 'Check that a file resolves',
        description:
          `Prints <code>OK</code> for a file in the repository (for example one you uploaded). `
          + `On failure wget prints the status instead, e.g. <code>401 Unauthorized</code> or <code>404 Not Found</code>.`,
        code: `wget -nv -O /dev/null ${ctx.repoUrl}/${FILE_PATH} && echo OK`,
      },
    ],
  }
}

export const fileSnippets: FormatSnippets = ctx => [curlClient(ctx), wgetClient(ctx)]
