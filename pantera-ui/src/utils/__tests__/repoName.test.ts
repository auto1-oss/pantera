import { describe, it, expect } from 'vitest'
import { validateRepoName } from '../repoName'

describe('validateRepoName', () => {
  it('accepts server-valid names', () => {
    expect(validateRepoName('maven-central')).toBeNull()
    expect(validateRepoName('team/npm.local_1')).toBeNull()
  })

  it('rejects what the server rejects', () => {
    expect(validateRepoName('')).toMatch(/required/i)
    expect(validateRepoName('-leading')).toMatch(/letter or digit/i)
    expect(validateRepoName('a..b')).toMatch(/\.\./)
    expect(validateRepoName('trailing/')).toMatch(/slash/i)
    expect(validateRepoName('x'.repeat(201))).toMatch(/200/)
    expect(validateRepoName('has space')).toMatch(/letters, digits/i)
  })
})
