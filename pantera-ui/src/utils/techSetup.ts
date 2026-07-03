import {
  siNpm, siDocker, siHelm, siGo, siNuget,
  siPhp, siDebian, siAnaconda,
  siRubygems, siConan, siElixir, siPypi,
  type SimpleIcon,
} from 'simple-icons'
import { repoTypeColor } from './repoTypes'

export interface TechDef {
  key: string
  label: string
  color: string
  icon: SimpleIcon | null
  /** All repo type values that belong to this technology */
  repoTypes: string[]
  /** Whether auth requires only a token (no username) */
  tokenOnly?: boolean
}

export const SETUP_TECHS: TechDef[] = [
  { key: 'maven',  label: 'Maven',    color: repoTypeColor('maven'),  icon: null,       repoTypes: ['maven', 'maven-proxy', 'maven-group'] },
  { key: 'npm',    label: 'npm',      color: repoTypeColor('npm'),    icon: siNpm,      repoTypes: ['npm', 'npm-proxy', 'npm-group'],        tokenOnly: true },
  { key: 'docker', label: 'Docker',   color: repoTypeColor('docker'), icon: siDocker,   repoTypes: ['docker', 'docker-proxy', 'docker-group'] },
  { key: 'pypi',   label: 'PyPI',     color: repoTypeColor('pypi'),   icon: siPypi,     repoTypes: ['pypi', 'pypi-proxy', 'pypi-group'] },
  { key: 'php',    label: 'PHP',      color: repoTypeColor('php'),    icon: siPhp,      repoTypes: ['php', 'php-proxy'] },
  { key: 'helm',   label: 'Helm',     color: repoTypeColor('helm'),   icon: siHelm,      repoTypes: ['helm'] },
  { key: 'go',     label: 'Go',       color: repoTypeColor('go'),     icon: siGo,        repoTypes: ['go-proxy'] },
  { key: 'nuget',  label: 'NuGet',    color: repoTypeColor('nuget'),  icon: siNuget,     repoTypes: ['nuget'] },
  { key: 'deb',    label: 'Debian',   color: repoTypeColor('deb'),    icon: siDebian,    repoTypes: ['deb'] },
  { key: 'rpm',    label: 'RPM',      color: repoTypeColor('rpm'),    icon: null,        repoTypes: ['rpm'] },
  { key: 'conda',  label: 'Conda',    color: repoTypeColor('conda'),  icon: siAnaconda,  repoTypes: ['conda'] },
  { key: 'gem',    label: 'RubyGems', color: repoTypeColor('gem'),    icon: siRubygems,  repoTypes: ['gem'] },
  { key: 'conan',  label: 'Conan',    color: repoTypeColor('conan'),  icon: siConan,     repoTypes: ['conan'] },
  { key: 'hexpm',  label: 'Hex',      color: repoTypeColor('hexpm'),  icon: siElixir,    repoTypes: ['hexpm'] },
  { key: 'file',   label: 'File',     color: repoTypeColor('file'),   icon: null,        repoTypes: ['file', 'file-proxy', 'file-group'] },
  { key: 'binary', label: 'Binary',   color: repoTypeColor('binary'), icon: null,        repoTypes: ['binary'] },
]

export function getTechDef(key: string): TechDef | undefined {
  return SETUP_TECHS.find(t => t.key === key)
}

// ─── Instructions ──────────────────────────────────────────────────────────

export interface SetupStep {
  title: string
  description?: string
  code: string
  lang?: string
}

export function getSetupSteps(techKey: string, repoUrl: string): SetupStep[] {
  const host = repoUrl.replace(/^https?:\/\//, '').replace(/\/$/, '')
  const url = repoUrl.replace(/\/$/, '')
  const withAuth = (u: string) => u.replace('://', '://YOUR_USERNAME:YOUR_TOKEN@')

  switch (techKey) {
    case 'npm': {
      const hostname = host.split('/')[0]
      return [
        {
          title: 'Configure .npmrc',
          description: 'Add to your project\'s <code>.npmrc</code> or global <code>~/.npmrc</code>.',
          code: `registry=${url}/\n//${hostname}/:_authToken=YOUR_TOKEN\n//${host}/:_authToken=YOUR_TOKEN`,
        },
        {
          title: 'Or set via CLI',
          code: `npm config set registry ${url}/`,
        },
        {
          title: 'Install a package',
          code: `npm install PACKAGE`,
        },
        {
          title: 'Publish a package',
          code: `npm publish --registry ${url}/`,
        },
      ]
    }

    case 'maven':
      return [
        {
          title: 'Add mirror to ~/.m2/settings.xml',
          description: 'This routes all Maven dependency requests through Pantera.',
          code: `<settings>
  <mirrors>
    <mirror>
      <id>pantera</id>
      <mirrorOf>*</mirrorOf>
      <url>${url}</url>
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
        {
          title: 'Deploy an artifact',
          code: `mvn deploy -DaltDeploymentRepository=pantera::default::${url}`,
        },
      ]

    case 'docker':
      return [
        {
          title: 'Log in',
          code: `docker login ${host}`,
        },
        {
          title: 'Pull an image',
          code: `docker pull ${host}/IMAGE:TAG`,
        },
        {
          title: 'Tag and push an image',
          code: `docker tag MY_IMAGE:TAG ${host}/MY_IMAGE:TAG\ndocker push ${host}/MY_IMAGE:TAG`,
        },
      ]

    case 'pypi':
      return [
        {
          title: 'Install a package',
          code: `pip install --index-url ${withAuth(url)}/simple/ PACKAGE`,
        },
        {
          title: 'Configure pip.conf (persistent)',
          description: 'Location: <code>~/.config/pip/pip.conf</code> on Linux, <code>~/Library/Application Support/pip/pip.conf</code> on macOS.',
          code: `[global]\nindex-url = ${withAuth(url)}/simple/`,
        },
        {
          title: 'Publish a package',
          code: `twine upload --repository-url ${url}/ dist/*\n# Username: YOUR_USERNAME  Password: YOUR_TOKEN`,
        },
      ]

    case 'php':
      return [
        {
          title: 'Add repository to composer.json',
          code: `{\n  "repositories": [\n    {\n      "type": "composer",\n      "url": "${url}"\n    }\n  ]\n}`,
          lang: 'json',
        },
        {
          title: 'Configure credentials',
          code: `composer config http-basic.${host} YOUR_USERNAME YOUR_TOKEN`,
        },
        {
          title: 'Require a package',
          code: `composer require vendor/package`,
        },
      ]

    case 'helm':
      return [
        {
          title: 'Add the Helm repository',
          code: `helm repo add pantera ${url}/\nhelm repo update`,
        },
        {
          title: 'Search for charts',
          code: `helm search repo pantera/`,
        },
        {
          title: 'Install a chart',
          code: `helm install my-release pantera/CHART`,
        },
        {
          title: 'Push a chart',
          code: `helm package ./my-chart\ncurl -u YOUR_USERNAME:YOUR_TOKEN --upload-file my-chart-*.tgz ${url}/`,
        },
      ]

    case 'go':
      return [
        {
          title: 'Set GOPROXY environment variable',
          code: `export GOPROXY="${withAuth(url)},direct"\nexport GONOSUMCHECK="${host}"`,
        },
        {
          title: 'Or persist in go env',
          code: `go env -w GOPROXY="${withAuth(url)},direct"\ngo env -w GONOSUMCHECK="${host}"`,
        },
        {
          title: 'Fetch a module',
          code: `go get MODULE@VERSION`,
        },
      ]

    case 'nuget':
      return [
        {
          title: 'Add NuGet source',
          code: `dotnet nuget add source ${url}/ \\\n  --name pantera \\\n  --username YOUR_USERNAME \\\n  --password YOUR_TOKEN`,
        },
        {
          title: 'Install a package',
          code: `dotnet add package PACKAGE`,
        },
        {
          title: 'Push a package',
          code: `dotnet nuget push *.nupkg --source pantera --api-key YOUR_TOKEN`,
        },
      ]

    case 'deb':
      return [
        {
          title: 'Add the APT repository',
          code: `echo "deb [trusted=yes] ${url}/ stable main" \\\n  | sudo tee /etc/apt/sources.list.d/pantera.list`,
        },
        {
          title: 'Update package index',
          code: `sudo apt-get update`,
        },
        {
          title: 'Install a package',
          code: `sudo apt-get install PACKAGE`,
        },
      ]

    case 'rpm':
      return [
        {
          title: 'Create /etc/yum.repos.d/pantera.repo',
          code: `[pantera]\nname=Pantera Repository\nbaseurl=${url}/\nenabled=1\ngpgcheck=0`,
          lang: 'ini',
        },
        {
          title: 'Install a package',
          code: `sudo dnf install PACKAGE`,
        },
        {
          title: 'Upload an RPM',
          code: `curl -u YOUR_USERNAME:YOUR_TOKEN --upload-file PACKAGE.rpm ${url}/`,
        },
      ]

    case 'conda':
      return [
        {
          title: 'Add the Conda channel',
          code: `conda config --add channels ${withAuth(url)}/`,
        },
        {
          title: 'Install a package',
          code: `conda install PACKAGE`,
        },
      ]

    case 'gem':
      return [
        {
          title: 'Add the gem source',
          code: `gem sources --add ${withAuth(url)}/`,
        },
        {
          title: 'Install a gem',
          code: `gem install GEM`,
        },
        {
          title: 'Push a gem',
          code: `gem push GEM.gem --host ${url}/`,
        },
      ]

    case 'conan':
      return [
        {
          title: 'Add the Conan remote',
          code: `conan remote add pantera ${url}/`,
        },
        {
          title: 'Authenticate',
          code: `conan remote login pantera YOUR_USERNAME --password YOUR_TOKEN`,
        },
        {
          title: 'Install a package',
          code: `conan install --requires=PACKAGE/VERSION --remote=pantera`,
        },
        {
          title: 'Upload a package',
          code: `conan upload PACKAGE/VERSION --remote=pantera`,
        },
      ]

    case 'hexpm':
      return [
        {
          title: 'Add the Hex repository',
          code: `mix hex.repo add pantera ${url}/ \\\n  --auth-key YOUR_TOKEN`,
        },
        {
          title: 'Declare dependency in mix.exs',
          code: `defp deps do\n  [\n    {:my_package, "~> 1.0", repo: "pantera"}\n  ]\nend`,
          lang: 'elixir',
        },
        {
          title: 'Publish a package',
          code: `mix hex.publish --repo pantera`,
        },
      ]

    case 'file':
    case 'binary':
      return [
        {
          title: 'Download a file',
          code: `curl -u YOUR_USERNAME:YOUR_TOKEN \\\n  "${url}/path/to/file" \\\n  -o filename`,
        },
        {
          title: 'Upload a file',
          code: `curl -u YOUR_USERNAME:YOUR_TOKEN \\\n  --upload-file ./myfile \\\n  "${url}/path/to/myfile"`,
        },
        {
          title: 'Download with wget',
          code: `wget --user=YOUR_USERNAME --password=YOUR_TOKEN \\\n  "${url}/path/to/file"`,
        },
      ]

    default:
      return []
  }
}
