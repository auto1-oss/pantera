import { describe, it, expect } from 'vitest'
import { REPO_MODE_FILTERS, REPO_TYPE_DESCRIPTIONS, repoModeLabel } from '../repoTypes'

describe('repo mode helpers', () => {
  it('labels the mode from the API field and falls back to the type suffix', () => {
    expect(repoModeLabel('proxy', 'maven-proxy')).toBe('Proxy')
    expect(repoModeLabel(undefined, 'npm-group')).toBe('Group')
    expect(repoModeLabel(undefined, 'npm')).toBe('Hosted')
  })

  it('offers the three modes plus all', () => {
    expect(REPO_MODE_FILTERS.map(o => o.value)).toEqual([null, 'hosted', 'proxy', 'group'])
  })

  it('describes every create option base type', () => {
    for (const base of ['maven', 'docker', 'npm', 'pypi', 'go', 'gradle', 'helm', 'nuget', 'deb', 'rpm', 'conda', 'gem', 'conan', 'hexpm', 'php', 'file']) {
      expect(REPO_TYPE_DESCRIPTIONS[base], base).toBeTruthy()
    }
  })
})
