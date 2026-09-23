import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Image used by Resolve / Verify on repositories that can proxy Docker Hub. */
const HUB_IMAGE = 'library/alpine:3.20'
/** Image name used by the Publish steps and by Resolve on a local repository. */
const APP_IMAGE = 'my-app:1.0.0'

/** Single-quote a value for POSIX shells. */
function sq(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

function isLocal(ctx: SnippetCtx): boolean {
  return ctx.mode === 'local'
}

/** Registry API URL of one image in the resolve repository (`/v2/<prefix>/<repo>/<image>/...`). */
function tagsListUrl(ctx: SnippetCtx, image: string): string {
  const path = ctx.repoPath.slice(ctx.host.length).replace(/^\/+/, '')
  return `${ctx.scheme}://${ctx.host}/v2/${path ? `${path}/` : ''}${image}/tags/list`
}

function loginStep(ctx: SnippetCtx, cli: string, extra: string): Step {
  const tokenHint = ctx.hasToken ? '' : ' Replace <code>YOUR_TOKEN</code> with an API token.'
  return {
    title: 'Log in',
    description: `Uses your Pantera username and API token as the password.${tokenHint} `
      + `<code>--password-stdin</code> keeps the token out of the process list.${extra}`,
    code: `echo ${sq(ctx.token)} | ${cli} login ${ctx.host} -u ${sq(ctx.user)} --password-stdin`,
  }
}

function resolveSteps(ctx: SnippetCtx, cli: string): Step[] {
  if (isLocal(ctx)) {
    return [{
      title: 'Pull an image',
      description: 'Pulls an image pushed to this repository (see Publish). '
        + 'Image references are <code>registry/path/repository/image:tag</code>.',
      code: `${cli} pull ${ctx.repoPath}/${APP_IMAGE}`,
    }]
  }
  return [
    {
      title: 'Pull an image',
      description: 'Prefix the upstream image name with the repository path. Docker Hub official images '
        + 'live under <code>library/</code>: <code>alpine</code> is <code>library/alpine</code> (Pantera also '
        + 'accepts the short form). Other images keep their namespace, e.g. <code>grafana/grafana</code>.',
      code: `${cli} pull ${ctx.repoPath}/${HUB_IMAGE}`,
    },
    {
      title: 'Use it as a base image',
      description: 'In a <code>Dockerfile</code>, reference images through the same path.',
      code: `FROM ${ctx.repoPath}/${HUB_IMAGE}`,
      file: 'Dockerfile',
    },
  ]
}

function publishSteps(ctx: SnippetCtx, cli: string): Step[] {
  if (!ctx.pubUrl) return []
  const target = `${ctx.pubPath}/${APP_IMAGE}`
  return [{
    title: 'Tag and push an image',
    description: `Replace <code>${APP_IMAGE}</code> with your local image. Nested names such as `
      + '<code>team/my-app</code> work too.',
    code: `${cli} tag ${APP_IMAGE} ${target}\n${cli} push ${target}`,
  }]
}

function verifySteps(ctx: SnippetCtx, cli: string): Step[] {
  if (isLocal(ctx)) {
    return [{
      title: 'Check access',
      description: 'Prints <code>{"name":"my-app","tags":[...]}</code> with HTTP 200 (an empty tag list before '
        + 'the first push). HTTP 401 means the credentials were rejected.',
      code: `curl -fsS -u ${sq(`${ctx.user}:${ctx.token}`)} ${tagsListUrl(ctx, 'my-app')}`,
    }]
  }
  const expected = cli === 'docker'
    ? `Ends with <code>Status: Downloaded newer image for ${ctx.repoPath}/${HUB_IMAGE}</code> `
      + '(or <code>Image is up to date</code>).'
    : 'Ends with the pulled image ID (64 hex characters).'
  return [{
    title: 'Pull a small image',
    description: `${expected} <code>unauthorized</code> or <code>no basic auth credentials</code> means the login is missing.`,
    code: `${cli} pull ${ctx.repoPath}/${HUB_IMAGE}`,
  }]
}

function publishNote(ctx: SnippetCtx): Pick<Client, 'publishNote'> {
  return ctx.pubUrl ? {} : { publishNote: 'Images are pushed to a local Docker repository; none is available to you.' }
}

function dockerClient(ctx: SnippetCtx): Client {
  const configure: Step[] = []
  if (ctx.insecure) {
    configure.push({
      title: 'Allow the plain-HTTP registry',
      description: 'Merge into <code>/etc/docker/daemon.json</code> (Docker Desktop: Settings → Docker Engine), '
        + 'then restart Docker. Docker already allows <code>localhost</code> / <code>127.0.0.1</code> over HTTP.',
      code: `{\n  "insecure-registries": ["${ctx.host}"]\n}`,
      lang: 'json',
      file: '/etc/docker/daemon.json',
    })
  }
  configure.push(
    loginStep(ctx, 'docker', ''),
    {
      title: 'Or write the credentials file',
      description: 'Alternative to <code>docker login</code> for CI or hosts without a credential helper. '
        + 'Merge the <code>auths</code> entry into an existing file; when it sets <code>credsStore</code>, '
        + 'use <code>docker login</code> instead.',
      code: `{\n  "auths": {\n    "${ctx.host}": {\n      "auth": "${ctx.b64}"\n    }\n  }\n}`,
      lang: 'json',
      file: '~/.docker/config.json',
      download: 'config.json',
    },
  )
  return {
    id: 'docker',
    label: 'Docker',
    configure,
    resolve: resolveSteps(ctx, 'docker'),
    publish: publishSteps(ctx, 'docker'),
    verify: verifySteps(ctx, 'docker'),
    ...publishNote(ctx),
  }
}

function podmanClient(ctx: SnippetCtx): Client {
  const configure: Step[] = []
  if (ctx.insecure) {
    configure.push({
      title: 'Allow the plain-HTTP registry',
      description: 'On macOS and Windows the file lives inside the Podman machine '
        + '(<code>podman machine ssh</code>). Alternatively pass <code>--tls-verify=false</code> to each command.',
      code: `[[registry]]\nlocation = "${ctx.host}"\ninsecure = true`,
      lang: 'toml',
      file: '/etc/containers/registries.conf.d/pantera.conf',
      download: 'pantera.conf',
    })
  }
  configure.push(loginStep(ctx, 'podman', ' Credentials go to <code>${XDG_RUNTIME_DIR}/containers/auth.json</code>.'))
  return {
    id: 'podman',
    label: 'Podman',
    configure,
    resolve: resolveSteps(ctx, 'podman'),
    publish: publishSteps(ctx, 'podman'),
    verify: verifySteps(ctx, 'podman'),
    ...publishNote(ctx),
  }
}

export const dockerSnippets: FormatSnippets = ctx => [dockerClient(ctx), podmanClient(ctx)]
