import {
  siNpm, siDocker, siHelm, siGo, siNuget,
  siPhp, siDebian, siAnaconda, siGradle,
  siRubygems, siConan, siElixir, siPypi,
  type SimpleIcon,
} from 'simple-icons'
import { repoTypeColor } from './repoTypes'
import type { RepoListItem } from '@/types'

export interface TechDef {
  key: string
  label: string
  color: string
  icon: SimpleIcon | null
  /** Exact repo type values that belong to this technology */
  repoTypes: string[]
  /** Whether auth requires only a token (no username) */
  tokenOnly?: boolean
  /** Whether Quick Setup has client-side publish instructions for this technology */
  publishable: boolean
}

export const SETUP_TECHS: TechDef[] = [
  { key: 'maven',  label: 'Maven',    color: repoTypeColor('maven'),  icon: null,       repoTypes: ['maven', 'maven-proxy', 'maven-group'],    publishable: true },
  { key: 'gradle', label: 'Gradle',   color: repoTypeColor('gradle'), icon: siGradle,   repoTypes: ['gradle', 'gradle-proxy', 'gradle-group'], publishable: true },
  { key: 'npm',    label: 'npm',      color: repoTypeColor('npm'),    icon: siNpm,      repoTypes: ['npm', 'npm-proxy', 'npm-group'],          publishable: true, tokenOnly: true },
  { key: 'docker', label: 'Docker',   color: repoTypeColor('docker'), icon: siDocker,   repoTypes: ['docker', 'docker-proxy', 'docker-group'], publishable: true },
  { key: 'pypi',   label: 'PyPI',     color: repoTypeColor('pypi'),   icon: siPypi,     repoTypes: ['pypi', 'pypi-proxy', 'pypi-group'],       publishable: true },
  { key: 'php',    label: 'PHP',      color: repoTypeColor('php'),    icon: siPhp,      repoTypes: ['php', 'php-proxy', 'php-group'],          publishable: true },
  { key: 'helm',   label: 'Helm',     color: repoTypeColor('helm'),   icon: siHelm,     repoTypes: ['helm'],                                   publishable: true },
  { key: 'go',     label: 'Go',       color: repoTypeColor('go'),     icon: siGo,       repoTypes: ['go', 'go-proxy', 'go-group'],             publishable: false },
  { key: 'nuget',  label: 'NuGet',    color: repoTypeColor('nuget'),  icon: siNuget,    repoTypes: ['nuget'],                                  publishable: true },
  { key: 'deb',    label: 'Debian',   color: repoTypeColor('deb'),    icon: siDebian,   repoTypes: ['deb'],                                    publishable: true },
  { key: 'rpm',    label: 'RPM',      color: repoTypeColor('rpm'),    icon: null,       repoTypes: ['rpm'],                                    publishable: true },
  { key: 'conda',  label: 'Conda',    color: repoTypeColor('conda'),  icon: siAnaconda, repoTypes: ['conda'],                                  publishable: false },
  { key: 'gem',    label: 'RubyGems', color: repoTypeColor('gem'),    icon: siRubygems, repoTypes: ['gem', 'gem-group'],                       publishable: true },
  { key: 'conan',  label: 'Conan',    color: repoTypeColor('conan'),  icon: siConan,    repoTypes: ['conan'],                                  publishable: true },
  { key: 'hexpm',  label: 'Hex',      color: repoTypeColor('hexpm'),  icon: siElixir,   repoTypes: ['hexpm'],                                  publishable: true },
  { key: 'file',   label: 'File',     color: repoTypeColor('file'),   icon: null,       repoTypes: ['file', 'file-proxy', 'file-group'],       publishable: true },
  { key: 'binary', label: 'Binary',   color: repoTypeColor('binary'), icon: null,       repoTypes: ['binary'],                                 publishable: true },
]

export function getTechDef(key: string): TechDef | undefined {
  return SETUP_TECHS.find(t => t.key === key)
}

// ─── Repository selection ──────────────────────────────────────────────────

export type RepoMode = 'local' | 'proxy' | 'group'

/** Repository mode from its type. Groups and proxies are read-only for clients. */
export function repoMode(type: string): RepoMode {
  if (type.endsWith('-group')) return 'group'
  if (type.endsWith('-proxy')) return 'proxy'
  return 'local'
}

const MODE_ORDER: Record<RepoMode, number> = { group: 0, proxy: 1, local: 2 }

/**
 * Repositories of one technology: exact type match (the list endpoint's
 * `type` filter is a substring match, so `maven` also returns `maven-proxy`),
 * one entry per name, groups first because they are the usual resolve target.
 */
export function techRepos(tech: TechDef, items: RepoListItem[]): RepoListItem[] {
  const byName = new Map<string, RepoListItem>()
  for (const item of items) {
    if (tech.repoTypes.includes(item.type) && !byName.has(item.name)) {
      byName.set(item.name, item)
    }
  }
  return [...byName.values()].sort((a, b) =>
    MODE_ORDER[repoMode(a.type)] - MODE_ORDER[repoMode(b.type)] || a.name.localeCompare(b.name)
  )
}

/**
 * Default publish target for a resolve repository: the repository itself
 * when it is local, else the first local member of the group (in declared
 * order), else the first local repository of the technology.
 */
export function defaultPublishRepo(
  resolve: RepoListItem | undefined,
  locals: RepoListItem[],
  groupMembers: string[],
): string {
  if (locals.length === 0) return ''
  if (resolve && repoMode(resolve.type) === 'local') return resolve.name
  const localNames = new Set(locals.map(r => r.name))
  return groupMembers.find(m => localNames.has(m)) ?? locals[0].name
}

// ─── Instructions ──────────────────────────────────────────────────────────

export interface SetupStep {
  title: string
  description?: string
  code: string
  lang?: string
}

export interface SetupContext {
  /** Base registry URL, e.g. `https://pantera.example.com` */
  registryUrl: string
  /** Repository clients resolve from (any mode) */
  resolveRepo: string
  /** Local repository clients publish to; empty when none is available */
  publishRepo: string
}

export interface SetupSteps {
  resolve: SetupStep[]
  /** Empty when there is no publish target or the technology has no publish flow */
  publish: SetupStep[]
}

interface Urls {
  /** Resolve repository URL, no trailing slash */
  r: string
  /** Publish repository URL, no trailing slash */
  p: string
  /** `host[:port]` of the registry — what logins and credential stores key on */
  host: string
  /** Resolve / publish URL without scheme (Docker image references, npm auth keys) */
  rPath: string
  pPath: string
  /** Plain-HTTP registry: clients need their insecure-transport switches */
  insecure: boolean
  withAuth: (u: string) => string
  resolveName: string
}

function buildUrls(ctx: SetupContext): Urls {
  const base = ctx.registryUrl.replace(/\/+$/, '')
  const r = `${base}/${ctx.resolveRepo || 'YOUR_REPO'}`
  const p = `${base}/${ctx.publishRepo || 'YOUR_LOCAL_REPO'}`
  const noScheme = (u: string) => u.replace(/^https?:\/\//, '')
  return {
    r,
    p,
    host: noScheme(base).split('/')[0],
    rPath: noScheme(r),
    pPath: noScheme(p),
    insecure: base.startsWith('http://'),
    withAuth: (u: string) => u.replace('://', '://YOUR_USERNAME:YOUR_TOKEN@'),
    resolveName: ctx.resolveRepo || 'YOUR_REPO',
  }
}

const basicAuthHeader = '-H "Authorization: Basic $(printf \'%s\' \'YOUR_USERNAME:YOUR_TOKEN\' | base64)"'

/** Gradle Kotlin DSL `maven {}` repository block, indented for `repositories {}`. */
function gradleMavenBlock(url: string, insecure: boolean): string {
  return [
    'maven {',
    '    name = "pantera"',
    `    url = uri("${url}")`,
    ...(insecure ? ['    isAllowInsecureProtocol = true'] : []),
    '    credentials {',
    '        username = "YOUR_USERNAME"',
    '        password = "YOUR_TOKEN"',
    '    }',
    '}',
  ].map(l => `        ${l}`).join('\n')
}

type StepBuilder = (u: Urls) => SetupSteps

const BUILDERS: Record<string, StepBuilder> = {
  npm: u => ({
    resolve: [
      {
        title: 'Configure .npmrc',
        description: 'Add to your project\'s <code>.npmrc</code> or global <code>~/.npmrc</code>. The path-scoped token lines are for yarn 1, which does not fall back to the host-level entry.',
        code: [...new Set([
          `registry=${u.r}/`,
          `//${u.host}/:_authToken=YOUR_TOKEN`,
          `//${u.rPath}/:_authToken=YOUR_TOKEN`,
          `//${u.pPath}/:_authToken=YOUR_TOKEN`,
        ])].join('\n'),
      },
      { title: 'Install a package', code: 'npm install PACKAGE' },
    ],
    publish: [
      {
        title: 'Point publishConfig at the local repository',
        description: 'In <code>package.json</code>. Keeps <code>npm publish</code> off the resolve registry.',
        code: `"publishConfig": {\n  "registry": "${u.p}/"\n}`,
        lang: 'json',
      },
      { title: 'Publish', code: 'npm publish' },
    ],
  }),

  maven: u => ({
    resolve: [
      {
        title: 'Add mirror and credentials to ~/.m2/settings.xml',
        description: 'Routes every dependency request through Pantera. The <code>pantera</code> server entry is also used for deploys.',
        code: `<settings>
  <mirrors>
    <mirror>
      <id>pantera</id>
      <mirrorOf>*</mirrorOf>
      <url>${u.r}</url>
    </mirror>
  </mirrors>
  <servers>
    <server>
      <id>pantera</id>
      <username>YOUR_USERNAME</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>
</settings>`,
        lang: 'xml',
      },
      { title: 'Resolve dependencies', code: 'mvn dependency:resolve -U' },
    ],
    publish: [
      {
        title: 'Add distributionManagement to pom.xml',
        code: `<distributionManagement>
  <repository>
    <id>pantera</id>
    <url>${u.p}</url>
  </repository>
  <snapshotRepository>
    <id>pantera</id>
    <url>${u.p}</url>
  </snapshotRepository>
</distributionManagement>`,
        lang: 'xml',
      },
      { title: 'Deploy', code: 'mvn deploy' },
    ],
  }),

  gradle: u => ({
    resolve: [
      {
        title: 'Add the repository to settings.gradle.kts',
        code: `dependencyResolutionManagement {\n    repositories {\n${gradleMavenBlock(u.r, u.insecure)}\n    }\n}`,
        lang: 'kotlin',
      },
      { title: 'Resolve dependencies', code: './gradlew build --refresh-dependencies' },
    ],
    publish: [
      {
        title: 'Add a publishing repository to build.gradle.kts',
        description: 'Requires the <code>maven-publish</code> plugin.',
        code: `publishing {\n    repositories {\n${gradleMavenBlock(u.p, u.insecure)}\n    }\n}`,
        lang: 'kotlin',
      },
      { title: 'Publish', code: './gradlew publish' },
    ],
  }),

  docker: u => ({
    resolve: [
      ...(u.insecure ? [{
        title: 'Allow the plain-HTTP registry',
        description: 'Add to <code>/etc/docker/daemon.json</code> (Docker Desktop: Settings → Docker Engine), then restart Docker.',
        code: `{\n  "insecure-registries": ["${u.host}"]\n}`,
        lang: 'json',
      }] : []),
      { title: 'Log in', description: 'Username: your Pantera username. Password: your API token.', code: `docker login ${u.host}` },
      { title: 'Pull an image', code: `docker pull ${u.rPath}/IMAGE:TAG` },
    ],
    publish: [
      { title: 'Tag and push an image', code: `docker tag MY_IMAGE:TAG ${u.pPath}/MY_IMAGE:TAG\ndocker push ${u.pPath}/MY_IMAGE:TAG` },
    ],
  }),

  pypi: u => ({
    resolve: [
      {
        title: 'Configure pip.conf',
        description: 'Location: <code>~/.config/pip/pip.conf</code> on Linux, <code>~/Library/Application Support/pip/pip.conf</code> on macOS.',
        code: `[global]\nindex-url = ${u.withAuth(u.r)}/simple/${u.insecure ? `\ntrusted-host = ${u.host.split(':')[0]}` : ''}`,
        lang: 'ini',
      },
      { title: 'Install a package', code: 'pip install PACKAGE' },
    ],
    publish: [
      {
        title: 'Configure ~/.pypirc',
        code: `[distutils]\nindex-servers =\n    pantera\n\n[pantera]\nrepository = ${u.p}\nusername = YOUR_USERNAME\npassword = YOUR_TOKEN`,
        lang: 'ini',
      },
      { title: 'Build and upload', code: 'python -m build\ntwine upload --repository pantera dist/*' },
    ],
  }),

  php: u => ({
    resolve: [
      {
        title: 'Add the repository to composer.json',
        code: `{\n  "repositories": [\n    {\n      "type": "composer",\n      "url": "${u.r}"\n    }\n  ]${u.insecure ? ',\n  "config": {\n    "secure-http": false\n  }' : ''}\n}`,
        lang: 'json',
      },
      { title: 'Configure credentials', code: `composer config --global http-basic.${u.host} YOUR_USERNAME YOUR_TOKEN` },
      { title: 'Require a package', code: 'composer require vendor/package' },
    ],
    publish: [
      {
        title: 'Upload a package archive',
        code: `curl -X PUT \\\n  ${basicAuthHeader} \\\n  --data-binary @PACKAGE-VERSION.zip \\\n  ${u.p}/PACKAGE-VERSION.zip`,
      },
    ],
  }),

  helm: u => ({
    resolve: [
      { title: 'Add the Helm repository', code: `helm repo add pantera ${u.r} \\\n  --username YOUR_USERNAME \\\n  --password YOUR_TOKEN\nhelm repo update` },
      { title: 'Install a chart', code: 'helm install my-release pantera/CHART' },
    ],
    publish: [
      {
        title: 'Package and upload a chart',
        code: `helm package ./my-chart\ncurl -X PUT \\\n  ${basicAuthHeader} \\\n  --data-binary @CHART-VERSION.tgz \\\n  ${u.p}/CHART-VERSION.tgz\nhelm repo update`,
      },
    ],
  }),

  go: u => {
    const env = [
      ['GOPROXY', `${u.withAuth(u.r)},direct`],
      ['GONOSUMDB', 'YOUR_PRIVATE_MODULE_PREFIX'],
      ...(u.insecure ? [['GOINSECURE', u.host]] : []),
    ]
    return {
      resolve: [
        {
          title: 'Set the Go environment',
          description: '<code>GONOSUMDB</code> lists private module path prefixes (comma-separated, e.g. <code>github.com/your-org/*</code>) that the public checksum database does not know; public modules stay verified.',
          code: env.map(([k, v]) => `export ${k}="${v}"`).join('\n'),
        },
        { title: 'Or persist with go env', code: env.map(([k, v]) => `go env -w ${k}="${v}"`).join('\n') },
        { title: 'Fetch a module', code: 'go get MODULE@VERSION' },
      ],
      publish: [],
    }
  },

  nuget: u => ({
    resolve: [
      { title: 'Add the NuGet source', code: `dotnet nuget add source ${u.r} \\\n  --name pantera \\\n  --username YOUR_USERNAME \\\n  --password YOUR_TOKEN \\\n  --store-password-in-clear-text` },
      { title: 'Install a package', code: 'dotnet add package PACKAGE --source pantera' },
    ],
    publish: [
      { title: 'Push a package', code: `dotnet nuget push PACKAGE.nupkg --source ${u.p} --api-key YOUR_TOKEN` },
    ],
  }),

  deb: u => ({
    resolve: [
      {
        title: 'Add the APT source',
        description: 'The distribution is the repository name.',
        code: `echo "deb [trusted=yes] ${u.withAuth(u.r)} ${u.resolveName} main" \\\n  | sudo tee /etc/apt/sources.list.d/pantera.list`,
      },
      { title: 'Install a package', code: 'sudo apt-get update\nsudo apt-get install PACKAGE' },
    ],
    publish: [
      { title: 'Upload a .deb package', code: `curl ${u.withAuth(u.p)}/main \\\n  --upload-file PACKAGE_VERSION_ARCH.deb` },
    ],
  }),

  rpm: u => ({
    resolve: [
      {
        title: 'Create /etc/yum.repos.d/pantera.repo',
        code: `[pantera]\nname=Pantera Repository\nbaseurl=${u.r}\nusername=YOUR_USERNAME\npassword=YOUR_TOKEN\nenabled=1\ngpgcheck=0`,
        lang: 'ini',
      },
      { title: 'Install a package', code: 'sudo dnf install PACKAGE' },
    ],
    publish: [
      {
        title: 'Upload an .rpm package',
        code: `curl -X PUT \\\n  ${basicAuthHeader} \\\n  --data-binary @PACKAGE.rpm \\\n  ${u.p}/PACKAGE.rpm`,
      },
    ],
  }),

  conda: u => ({
    resolve: [
      { title: 'Add the Conda channel', code: `conda config --add channels ${u.withAuth(u.r)}` },
      { title: 'Install a package', code: 'conda install PACKAGE' },
    ],
    publish: [],
  }),

  gem: u => ({
    resolve: [
      { title: 'Add the gem source', code: `gem sources --add ${u.withAuth(u.r)}/` },
      { title: 'Or in a Gemfile', code: `source "${u.withAuth(u.r)}"`, lang: 'ruby' },
      { title: 'Install a gem', code: 'gem install GEM' },
    ],
    publish: [
      {
        title: 'Push a gem',
        description: 'The API key is your username and token, base64-encoded.',
        code: `GEM_HOST_API_KEY="$(printf '%s' 'YOUR_USERNAME:YOUR_TOKEN' | base64)" \\\n  gem push GEM.gem --host ${u.p}`,
      },
    ],
  }),

  conan: u => ({
    resolve: [
      { title: 'Add the Conan remote', code: `conan remote add pantera ${u.r}` },
      { title: 'Authenticate', code: 'conan remote login pantera YOUR_USERNAME --password YOUR_TOKEN' },
      { title: 'Install a package', code: 'conan install --requires=PACKAGE/VERSION --remote=pantera' },
    ],
    publish: [
      { title: 'Upload a package', code: 'conan upload PACKAGE/VERSION --remote=pantera' },
    ],
  }),

  hexpm: u => ({
    resolve: [
      { title: 'Add the Hex repository', code: `mix hex.repo add pantera ${u.r} \\\n  --auth-key YOUR_TOKEN` },
      {
        title: 'Declare the dependency in mix.exs',
        code: 'defp deps do\n  [\n    {:my_package, "~> 1.0", repo: "pantera"}\n  ]\nend',
        lang: 'elixir',
      },
    ],
    publish: [
      { title: 'Publish a package', code: 'mix hex.publish --repo pantera' },
    ],
  }),

  file: u => ({
    resolve: [
      { title: 'Download a file', code: `curl ${basicAuthHeader} \\\n  -o FILE \\\n  ${u.r}/path/to/FILE` },
    ],
    publish: [
      {
        title: 'Upload a file',
        code: `curl -X PUT \\\n  ${basicAuthHeader} \\\n  --data-binary @FILE \\\n  ${u.p}/path/to/FILE`,
      },
    ],
  }),
}
BUILDERS.binary = BUILDERS.file

/**
 * Client instructions, split into resolve steps (against the resolve
 * repository) and publish steps (against the local publish repository —
 * groups and proxies answer 405 to uploads).
 */
export function getSetupSteps(techKey: string, ctx: SetupContext): SetupSteps {
  const builder = BUILDERS[techKey]
  if (!builder) return { resolve: [], publish: [] }
  const steps = builder(buildUrls(ctx))
  return ctx.publishRepo ? steps : { resolve: steps.resolve, publish: [] }
}
