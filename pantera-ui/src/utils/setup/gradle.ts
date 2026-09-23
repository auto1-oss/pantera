import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/**
 * Gradle speaks the Maven layout, so `gradle*` and `maven*` repositories both
 * work. Credentials live in `~/.gradle/gradle.properties` as
 * `panteraUsername` / `panteraPassword` and are bound by
 * `credentials(PasswordCredentials)` on a repository named `pantera`, so no
 * token ends up in a build script.
 */

const GROUP = 'com.example'
const ARTIFACT = 'my-app'
const VERSION = '1.0.0'
const VERIFY_DEP = 'junit:junit:4.13.2'

interface Dsl {
  id: string
  label: string
  lang: string
  settingsFile: string
  buildFile: string
  /** `maven { ... }` block named `pantera`, indented by `indent` spaces */
  repoBlock: (url: string, insecure: boolean, indent: number) => string
  rootName: string
  plugins: (withPublish: boolean) => string
  dependency: (coordinates: string) => string
  coordinates: string
  publication: string
}

function indentLines(lines: string[], indent: number): string {
  const pad = ' '.repeat(indent)
  return lines.map(l => pad + l).join('\n')
}

const KOTLIN: Dsl = {
  id: 'gradle-kotlin',
  label: 'Gradle (Kotlin DSL)',
  lang: 'kotlin',
  settingsFile: 'settings.gradle.kts',
  buildFile: 'build.gradle.kts',
  repoBlock: (url, insecure, indent) => indentLines([
    'maven {',
    '    name = "pantera"',
    `    url = uri("${url}")`,
    '    credentials(PasswordCredentials::class)',
    ...(insecure ? ['    isAllowInsecureProtocol = true'] : []),
    '}',
  ], indent),
  rootName: `rootProject.name = "${ARTIFACT}"`,
  plugins: withPublish => withPublish
    ? 'plugins {\n    `java-library`\n    `maven-publish`\n}'
    : 'plugins {\n    `java-library`\n}',
  dependency: dep => `dependencies {\n    implementation("${dep}")\n}`,
  coordinates: `group = "${GROUP}"\nversion = "${VERSION}"`,
  publication: 'create<MavenPublication>("mavenJava") {\n            from(components["java"])\n        }',
}

const GROOVY: Dsl = {
  id: 'gradle-groovy',
  label: 'Gradle (Groovy DSL)',
  lang: 'groovy',
  settingsFile: 'settings.gradle',
  buildFile: 'build.gradle',
  repoBlock: (url, insecure, indent) => indentLines([
    'maven {',
    "    name = 'pantera'",
    `    url = '${url}'`,
    '    credentials(PasswordCredentials)',
    ...(insecure ? ['    allowInsecureProtocol = true'] : []),
    '}',
  ], indent),
  rootName: `rootProject.name = '${ARTIFACT}'`,
  plugins: withPublish => withPublish
    ? "plugins {\n    id 'java-library'\n    id 'maven-publish'\n}"
    : "plugins {\n    id 'java-library'\n}",
  dependency: dep => `dependencies {\n    implementation '${dep}'\n}`,
  coordinates: `group = '${GROUP}'\nversion = '${VERSION}'`,
  publication: 'mavenJava(MavenPublication) {\n            from components.java\n        }',
}

function propertiesStep(ctx: SnippetCtx): Step {
  return {
    title: 'Store your credentials',
    description: 'Gradle binds <code>panteraUsername</code> and <code>panteraPassword</code> to the '
      + 'repository named <code>pantera</code>, so the token never appears in a build script. '
      + 'Set <code>ORG_GRADLE_PROJECT_panteraUsername</code> / <code>ORG_GRADLE_PROJECT_panteraPassword</code> '
      + 'instead on CI.',
    code: `panteraUsername=${ctx.user}\npanteraPassword=${ctx.token}`,
    lang: 'properties',
    file: '~/.gradle/gradle.properties',
  }
}

function settingsStep(ctx: SnippetCtx, dsl: Dsl): Step {
  const block = dsl.repoBlock(ctx.repoUrl, ctx.insecure, 8)
  return {
    title: 'Point plugin and dependency resolution at Pantera',
    description: `Community plugins resolve through <code>${ctx.repo}</code> only when it proxies `
      + 'the Gradle Plugin Portal (<code>https://plugins.gradle.org/m2</code>); otherwise add '
      + '<code>gradlePluginPortal()</code> after the <code>pantera</code> entry in '
      + '<code>pluginManagement</code>. Core plugins such as <code>java-library</code> need no repository.',
    code: `pluginManagement {\n    repositories {\n${block}\n    }\n}\n\n`
      + `dependencyResolutionManagement {\n    repositories {\n${block}\n    }\n}\n\n`
      + dsl.rootName,
    lang: dsl.lang,
    file: dsl.settingsFile,
    download: dsl.settingsFile,
  }
}

/** A local repository only serves what was published to it, so it cannot resolve public packages. */
function isLocal(ctx: SnippetCtx): boolean {
  return ctx.mode === 'local'
}

function resolveSteps(ctx: SnippetCtx, dsl: Dsl): Step[] {
  const local = isLocal(ctx)
  const dep = local ? `${GROUP}:${ARTIFACT}:${VERSION}` : VERIFY_DEP
  const which = local
    ? `<code>${dep}</code> stands for a module published to <code>${ctx.repo}</code>; replace it with yours. `
    : ''
  return [
    {
      title: 'Declare dependencies',
      description: `${which}Do not declare a <code>repositories</code> block here: the one in `
        + `<code>${dsl.settingsFile}</code> applies to every project.`,
      code: `${dsl.plugins(false)}\n\n${dsl.dependency(dep)}`,
      lang: dsl.lang,
      file: dsl.buildFile,
      download: dsl.buildFile,
    },
    { title: 'Build', code: './gradlew build --refresh-dependencies' },
  ]
}

function publishSteps(ctx: SnippetCtx, dsl: Dsl): Step[] {
  if (!ctx.pubUrl) return []
  const repo = dsl.repoBlock(ctx.pubUrl, ctx.insecure, 8)
  return [
    {
      title: 'Add a publication and the publish repository',
      description: 'Without the <code>publications</code> block <code>./gradlew publish</code> '
        + 'succeeds but uploads nothing. Keep your <code>dependencies</code> block alongside. '
        + 'The artifact id is the project name from <code>rootProject.name</code>.',
      code: `${dsl.plugins(true)}\n\n${dsl.coordinates}\n\n`
        + `publishing {\n    publications {\n        ${dsl.publication}\n    }\n`
        + `    repositories {\n${repo}\n    }\n}`,
      lang: dsl.lang,
      file: dsl.buildFile,
      download: dsl.buildFile,
    },
    {
      title: 'Publish',
      description: `Uploads <code>${ARTIFACT}-${VERSION}.jar</code> and its POM to <code>${ctx.pubRepo}</code>.`,
      code: './gradlew publish',
    },
  ]
}

function verifyStep(ctx: SnippetCtx): Step {
  if (isLocal(ctx)) {
    const path = `${GROUP.replace(/\./g, '/')}/${ARTIFACT}/maven-metadata.xml`
    return {
      title: 'Check the published artifact',
      description: 'Prints <code>200</code> after the Publish step. <code>404</code> means nothing '
        + 'has been published yet; <code>401</code> means the credentials are wrong.',
      code: `curl -s -o /dev/null -w '%{http_code}\\n' -u '${ctx.user}:${ctx.token}' '${ctx.repoUrl}/${path}'`,
    }
  }
  return {
    title: 'Resolve a dependency through Pantera',
    description: `Lists <code>${VERIFY_DEP}</code> with its <code>hamcrest-core</code> dependency and `
      + 'no <code>FAILED</code> entries (uses the dependency declared in the Resolve step).',
    code: './gradlew dependencies --configuration compileClasspath --refresh-dependencies',
  }
}

function client(ctx: SnippetCtx, dsl: Dsl): Client {
  return {
    id: dsl.id,
    label: dsl.label,
    configure: [propertiesStep(ctx), settingsStep(ctx, dsl)],
    resolve: resolveSteps(ctx, dsl),
    publish: publishSteps(ctx, dsl),
    verify: [verifyStep(ctx)],
  }
}

export const gradleSnippets: FormatSnippets = ctx => [client(ctx, KOTLIN), client(ctx, GROOVY)]
