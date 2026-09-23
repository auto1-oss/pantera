import { describe, it, expect } from 'vitest'
import {
  SETUP_TECHS, getTechDef, techForRepoType, techRepos, repoMode, defaultPublishRepo,
} from '../techSetup'
import { REPO_TYPE_CREATE_OPTIONS } from '../repoTypes'
import { FORMAT_SNIPPETS } from '../setup'
import type { RepoListItem } from '@/types'

const maven = getTechDef('maven')!

describe('SETUP_TECHS', () => {
  it('orders the most used formats first', () => {
    expect(SETUP_TECHS.slice(0, 8).map(t => t.key))
      .toEqual(['maven', 'gradle', 'npm', 'docker', 'pypi', 'php', 'go', 'file'])
  })

  it('offers no binary technology or repository type', () => {
    expect({
      tech: getTechDef('binary'),
      create: REPO_TYPE_CREATE_OPTIONS.some(o => (o.value as string) === 'binary'),
    }).toEqual({ tech: undefined, create: false })
  })

  it('has a snippet module for every technology', () => {
    expect(SETUP_TECHS.filter(t => typeof FORMAT_SNIPPETS[t.key] !== 'function').map(t => t.key))
      .toEqual([])
  })
})

describe('techForRepoType', () => {
  it('maps every mode of a type to its technology', () => {
    expect(['npm', 'npm-proxy', 'npm-group', 'deb'].map(t => techForRepoType(t)?.key))
      .toEqual(['npm', 'npm', 'npm', 'deb'])
  })

  it('has no technology for unknown or missing types', () => {
    expect([techForRepoType('binary'), techForRepoType(undefined)]).toEqual([undefined, undefined])
  })
})

describe('techRepos', () => {
  it('lists each repository once even when the API returns it repeatedly', () => {
    // The list endpoint's type filter is a substring match: `type=maven`
    // already returns maven-proxy and maven-group, so the old per-type
    // fan-out produced every proxy/group twice.
    const items: RepoListItem[] = [
      { name: 'maven-local', type: 'maven' },
      { name: 'maven-central', type: 'maven-proxy' },
      { name: 'maven-all', type: 'maven-group' },
      { name: 'maven-central', type: 'maven-proxy' },
      { name: 'maven-all', type: 'maven-group' },
    ]
    expect(techRepos(maven, items).map(r => r.name)).toEqual(['maven-all', 'maven-central', 'maven-local'])
  })

  it('matches repository types exactly', () => {
    const items: RepoListItem[] = [
      { name: 'g', type: 'gradle' },
      { name: 'm', type: 'maven' },
      { name: 'x', type: 'maven-something' },
    ]
    expect(techRepos(maven, items).map(r => r.name)).toEqual(['m'])
  })
})

describe('repoMode', () => {
  it('derives the mode from the type suffix', () => {
    expect([repoMode('npm'), repoMode('npm-proxy'), repoMode('npm-group')])
      .toEqual(['local', 'proxy', 'group'])
  })
})

describe('defaultPublishRepo', () => {
  const locals: RepoListItem[] = [
    { name: 'a-local', type: 'npm' },
    { name: 'b-local', type: 'npm' },
  ]

  it('publishes to the resolve repository when it is local', () => {
    expect(defaultPublishRepo({ name: 'b-local', type: 'npm' }, locals, [])).toBe('b-local')
  })

  it('publishes to the first local member of a group', () => {
    expect(defaultPublishRepo(
      { name: 'npm-all', type: 'npm-group' }, locals, ['npmjs', 'b-local', 'a-local'],
    )).toBe('b-local')
  })

  it('falls back to the first local repository', () => {
    expect(defaultPublishRepo({ name: 'npmjs', type: 'npm-proxy' }, locals, [])).toBe('a-local')
  })

  it('has no target when there is no local repository', () => {
    expect(defaultPublishRepo({ name: 'npm-all', type: 'npm-group' }, [], ['npmjs'])).toBe('')
  })
})
