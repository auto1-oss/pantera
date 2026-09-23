import { describe, it, expect } from 'vitest'
import {
  SETUP_TECHS, getTechDef, getSetupSteps, techRepos, repoMode, defaultPublishRepo,
} from '../techSetup'
import type { RepoListItem } from '@/types'

const maven = getTechDef('maven')!

const allCode = (steps: { code: string }[]) => steps.map(s => s.code).join('\n')

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

describe('getSetupSteps', () => {
  const ctx = {
    registryUrl: 'https://pantera.example.com',
    resolveRepo: 'grp',
    publishRepo: 'loc',
  }

  it.each(SETUP_TECHS.map(t => t.key))('%s resolves from the resolve repository', (key) => {
    const { resolve } = getSetupSteps(key, ctx)
    expect(resolve.length).toBeGreaterThan(0)
    expect(allCode(resolve)).toContain('pantera.example.com/grp')
  })

  it.each(SETUP_TECHS.filter(t => t.publishable).map(t => t.key))(
    '%s publishes to the local repository, never the group', (key) => {
      const code = allCode(getSetupSteps(key, ctx).publish)
      const target = key === 'conan' || key === 'hexpm' ? 'pantera' : 'pantera.example.com/loc'
      expect({ hasTarget: code.includes(target), hitsGroup: code.includes('/grp') })
        .toEqual({ hasTarget: true, hitsGroup: false })
    },
  )

  it('omits publish steps when there is no publish repository', () => {
    expect(getSetupSteps('npm', { ...ctx, publishRepo: '' }).publish).toEqual([])
  })

  it('keys host-level credentials on host and port only', () => {
    const code = allCode(getSetupSteps('php', { ...ctx, registryUrl: 'https://pantera.example.com:8443/prefix' }).resolve)
    expect(code).toContain('http-basic.pantera.example.com:8443 ')
  })

  it('logs docker in to the registry host, not the repository path', () => {
    expect(allCode(getSetupSteps('docker', ctx).resolve)).toContain('docker login pantera.example.com\n')
  })

  it('names the repository as the APT distribution', () => {
    expect(allCode(getSetupSteps('deb', { ...ctx, resolveRepo: 'my-debian' }).resolve))
      .toContain('/my-debian my-debian main')
  })

  it('adds insecure-transport settings only for plain-HTTP registries', () => {
    const http = { ...ctx, registryUrl: 'http://localhost:8081' }
    expect({
      goHttp: allCode(getSetupSteps('go', http).resolve).includes('GOINSECURE="localhost:8081"'),
      goHttps: allCode(getSetupSteps('go', ctx).resolve).includes('GOINSECURE'),
    }).toEqual({ goHttp: true, goHttps: false })
  })
})
