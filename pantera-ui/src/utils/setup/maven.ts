import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Escapes a value for use as XML element text. */
function xml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

const SERVER_ID = 'pantera'

/** A repository entry with releases and snapshots enabled, indented for `<profile>`. */
function repoEntry(tag: string, id: string, url: string): string {
  return `        <${tag}>
          <id>${id}</id>
          <url>${xml(url)}</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </${tag}>`
}

const SETTINGS_OPEN = `<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">`

const ACTIVE_PROFILE = `  <activeProfiles>
    <activeProfile>${SERVER_ID}</activeProfile>
  </activeProfiles>
</settings>`

function serversBlock(ctx: SnippetCtx): string {
  return `  <servers>
    <server>
      <id>${SERVER_ID}</id>
      <username>${xml(ctx.user)}</username>
      <password>${xml(ctx.token)}</password>
    </server>
  </servers>`
}

/**
 * Group or proxy: mirror every repository through Pantera. The `central`
 * override in the profile turns snapshots on — the mirrored super-POM
 * `central` has them disabled, so without it Maven never asks for a SNAPSHOT.
 */
function mirrorSettings(ctx: SnippetCtx): string {
  return `${SETTINGS_OPEN}
  <mirrors>
    <mirror>
      <id>${SERVER_ID}</id>
      <mirrorOf>*</mirrorOf>
      <url>${xml(ctx.repoUrl)}</url>
    </mirror>
  </mirrors>
${serversBlock(ctx)}
  <profiles>
    <profile>
      <id>${SERVER_ID}</id>
      <repositories>
${repoEntry('repository', 'central', ctx.repoUrl)}
      </repositories>
      <pluginRepositories>
${repoEntry('pluginRepository', 'central', ctx.repoUrl)}
      </pluginRepositories>
    </profile>
  </profiles>
${ACTIVE_PROFILE}`
}

/**
 * Local repository: it cannot serve Maven Central or plugins, so it is added
 * next to `central` instead of mirroring everything.
 */
function localSettings(ctx: SnippetCtx): string {
  return `${SETTINGS_OPEN}
${serversBlock(ctx)}
  <profiles>
    <profile>
      <id>${SERVER_ID}</id>
      <repositories>
${repoEntry('repository', SERVER_ID, ctx.repoUrl)}
      </repositories>
    </profile>
  </profiles>
${ACTIVE_PROFILE}`
}

function publishSteps(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  const url = xml(ctx.pubUrl)
  return [
    {
      title: 'Add distributionManagement to pom.xml',
      description: `Both entries use the <code>${SERVER_ID}</code> server from <code>settings.xml</code> for credentials. `
        + `Maven sends releases to <code>&lt;repository&gt;</code> and <code>-SNAPSHOT</code> versions to <code>&lt;snapshotRepository&gt;</code>; `
        + `both point at <code>${xml(ctx.pubRepo)}</code> here. To keep snapshots apart from releases, change the `
        + `<code>&lt;snapshotRepository&gt;</code> URL to a local repository meant for snapshots (e.g. a <code>*-snapshot-local</code> repository). `
        + 'Group and proxy repositories reject uploads.',
      code: `<distributionManagement>
  <repository>
    <id>${SERVER_ID}</id>
    <url>${url}</url>
  </repository>
  <snapshotRepository>
    <id>${SERVER_ID}</id>
    <url>${url}</url>
  </snapshotRepository>
</distributionManagement>`,
      lang: 'xml',
      file: 'pom.xml',
    },
    {
      title: 'Deploy',
      description: 'Builds the project and uploads the jar, pom and checksums. Prints <code>BUILD SUCCESS</code>.',
      code: 'mvn deploy',
    },
  ]
}

export const mavenSnippets: FormatSnippets = ctx => {
  const local = ctx.mode === 'local'
  const mvn: Client = {
    id: 'mvn',
    label: 'Maven',
    configure: [
      {
        title: 'Create ~/.m2/settings.xml',
        description: local
          ? `Adds <code>${xml(ctx.repo)}</code> next to Maven Central, with snapshots enabled, and stores your credentials under the server id <code>${SERVER_ID}</code>. `
            + 'A local repository cannot serve Maven Central or plugins; pick a group repository to route all traffic through Pantera. '
            + 'Merge into an existing <code>settings.xml</code> instead of replacing it if you have one.'
          : `Routes every Maven request, plugins included, through <code>${xml(ctx.repo)}</code> and stores your credentials under the server id <code>${SERVER_ID}</code>, which deploys use too. `
            + 'The active profile enables snapshots so <code>-SNAPSHOT</code> dependencies resolve. '
            + 'Merge into an existing <code>settings.xml</code> instead of replacing it if you have one.',
        code: local ? localSettings(ctx) : mirrorSettings(ctx),
        lang: 'xml',
        file: '~/.m2/settings.xml',
        download: 'settings.xml',
      },
    ],
    resolve: [
      {
        title: 'Resolve dependencies',
        description: 'Run in your project. <code>-U</code> forces Maven to re-check for updated SNAPSHOTs and missing artifacts.',
        code: 'mvn -U dependency:resolve',
      },
    ],
    publish: publishSteps(ctx),
    verify: local
      ? [
          {
            title: 'Check access to the repository',
            description: 'Prints just <code>404</code>: the repository exists and accepted your credentials, and there is no file at its root. '
              + '<code>401</code> means the credentials were rejected; <code>Repository … not found</code> before the code means the URL is wrong. '
              + 'Once you have deployed an artifact, Maven resolves it from here.',
            code: `curl -sS -w '%{http_code}\\n' -u '${ctx.user}:${ctx.token}' '${ctx.repoUrl}/'`,
          },
        ]
      : [
          {
            title: 'Fetch junit through the repository',
            description: `Downloads <code>junit:junit:4.13.2</code> into a throwaway local repository, so nothing comes from your <code>~/.m2</code> cache. Ends with <code>BUILD SUCCESS</code> and <code>Downloaded from ${SERVER_ID}: ${xml(ctx.repoUrl)}/…</code> lines.`,
            code: 'mvn -Dmaven.repo.local="$(mktemp -d)" dependency:get -Dartifact=junit:junit:4.13.2',
          },
        ],
  }
  return [mvn]
}
