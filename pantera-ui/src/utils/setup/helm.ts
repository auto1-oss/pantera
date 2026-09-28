import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Local alias the snippets register the repository under. */
const ALIAS = 'pantera'

/** Chart produced by `helm create my-chart` (version 0.1.0). */
const CHART_DIR = 'my-chart'
const CHART_FILE = 'my-chart-0.1.0.tgz'

/** Single-quote a value for POSIX shells. */
function sq(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

function configure(ctx: SnippetCtx): Step[] {
  return [
    {
      title: 'Add the Helm repository',
      description:
        'Registers the repository as <code>pantera</code>. The token is read from stdin so it stays out of '
        + 'the process list; Helm stores it in its <code>repositories.yaml</code>. A repository without any '
        + 'chart has no <code>index.yaml</code> yet, so <code>helm repo add</code> answers <code>404</code> '
        + 'until the first chart is uploaded (see Publish). If <code>helm pull</code> '
        + 'later fails with <code>401</code> on a different host, the chart URLs in <code>index.yaml</code> '
        + 'point elsewhere: re-add the repository with <code>--pass-credentials</code>.',
      code: [
        `printf '%s' ${sq(ctx.token)} | helm repo add ${ALIAS} ${ctx.repoUrl} \\`,
        `  --username ${sq(ctx.user)} --password-stdin`,
        `helm repo update ${ALIAS}`,
      ].join('\n'),
    },
  ]
}

function resolve(): Step[] {
  return [
    {
      title: 'Install a chart',
      description: 'Replace <code>CHART</code> with a chart name from <code>helm search repo pantera/</code>.',
      code: [
        `helm repo update ${ALIAS}`,
        `helm pull ${ALIAS}/CHART`,
        `helm install my-release ${ALIAS}/CHART`,
      ].join('\n'),
    },
  ]
}

function publish(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  return [
    {
      title: 'Package the chart',
      description:
        `Creates <code>${CHART_FILE}</code>; the file name comes from <code>name</code> and `
        + '<code>version</code> in <code>Chart.yaml</code>.',
      code: `helm package ./${CHART_DIR}`,
    },
    {
      title: 'Upload the chart',
      description:
        'Pantera adds the chart to <code>index.yaml</code>. curl prints nothing on success and exits '
        + 'non-zero on an HTTP error.',
      code: [
        `curl -fsS -u ${sq(`${ctx.user}:${ctx.token}`)} \\`,
        `  --upload-file ${CHART_FILE} \\`,
        `  ${ctx.pubUrl}/${CHART_FILE}`,
      ].join('\n'),
    },
    {
      title: 'Refresh the local index',
      description: 'Makes the new chart visible to <code>helm search</code> and <code>helm install</code>.',
      code: `helm repo update ${ALIAS}`,
    },
  ]
}

function verify(): Step[] {
  return [
    {
      title: 'List the charts',
      description:
        'Prints <code>Successfully got an update from the "pantera" chart repository</code> followed by a '
        + 'table of the charts in the repository.',
      code: `helm repo update ${ALIAS} && helm search repo ${ALIAS}/`,
    },
  ]
}

export const helmSnippets: FormatSnippets = (ctx: SnippetCtx): Client[] => [
  {
    id: 'helm',
    label: 'Helm',
    configure: configure(ctx),
    resolve: resolve(),
    publish: publish(ctx),
    verify: verify(),
    ...(ctx.pubUrl ? {} : { publishNote: 'Charts are uploaded to a local Helm repository; none is selected.' }),
  },
]
