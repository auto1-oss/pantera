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
  /** Whether Set Me Up has client-side publish instructions for this technology */
  publishable: boolean
}

/**
 * Set Me Up technologies, in tile order: the most used formats first
 * (Maven, Gradle, npm, Docker, PyPI, PHP, Go, File), then the rest.
 */
export const SETUP_TECHS: TechDef[] = [
  { key: 'maven',  label: 'Maven',    color: repoTypeColor('maven'),  icon: null,       repoTypes: ['maven', 'maven-proxy', 'maven-group'],    publishable: true },
  { key: 'gradle', label: 'Gradle',   color: repoTypeColor('gradle'), icon: siGradle,   repoTypes: ['gradle', 'gradle-proxy', 'gradle-group'], publishable: true },
  { key: 'npm',    label: 'npm',      color: repoTypeColor('npm'),    icon: siNpm,      repoTypes: ['npm', 'npm-proxy', 'npm-group'],          publishable: true, tokenOnly: true },
  { key: 'docker', label: 'Docker',   color: repoTypeColor('docker'), icon: siDocker,   repoTypes: ['docker', 'docker-proxy', 'docker-group'], publishable: true },
  { key: 'pypi',   label: 'PyPI',     color: repoTypeColor('pypi'),   icon: siPypi,     repoTypes: ['pypi', 'pypi-proxy', 'pypi-group'],       publishable: true },
  { key: 'php',    label: 'PHP',      color: repoTypeColor('php'),    icon: siPhp,      repoTypes: ['php', 'php-proxy', 'php-group'],          publishable: true },
  { key: 'go',     label: 'Go',       color: repoTypeColor('go'),     icon: siGo,       repoTypes: ['go', 'go-proxy', 'go-group'],             publishable: true },
  { key: 'file',   label: 'File',     color: repoTypeColor('file'),   icon: null,       repoTypes: ['file', 'file-proxy', 'file-group'],       publishable: true },
  { key: 'helm',   label: 'Helm',     color: repoTypeColor('helm'),   icon: siHelm,     repoTypes: ['helm'],                                   publishable: true },
  { key: 'nuget',  label: 'NuGet',    color: repoTypeColor('nuget'),  icon: siNuget,    repoTypes: ['nuget'],                                  publishable: true },
  { key: 'deb',    label: 'Debian',   color: repoTypeColor('deb'),    icon: siDebian,   repoTypes: ['deb'],                                    publishable: true },
  { key: 'rpm',    label: 'RPM',      color: repoTypeColor('rpm'),    icon: null,       repoTypes: ['rpm'],                                    publishable: true },
  { key: 'conda',  label: 'Conda',    color: repoTypeColor('conda'),  icon: siAnaconda, repoTypes: ['conda'],                                  publishable: true },
  { key: 'gem',    label: 'RubyGems', color: repoTypeColor('gem'),    icon: siRubygems, repoTypes: ['gem', 'gem-group'],                       publishable: true },
  { key: 'conan',  label: 'Conan',    color: repoTypeColor('conan'),  icon: siConan,    repoTypes: ['conan'],                                  publishable: true },
  { key: 'hexpm',  label: 'Hex',      color: repoTypeColor('hexpm'),  icon: siElixir,   repoTypes: ['hexpm'],                                  publishable: true },
]

export function getTechDef(key: string): TechDef | undefined {
  return SETUP_TECHS.find(t => t.key === key)
}

/** Set Me Up technology serving a repository type, if any. */
export function techForRepoType(type: string | undefined): TechDef | undefined {
  if (!type) return undefined
  return SETUP_TECHS.find(t => t.repoTypes.includes(type))
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
