import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub the axios client: the wrapper is thin, so what matters is the URL
// and body it sends and that a refusal reaches the caller.
const post = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ post }),
}))

import { testRouting } from '../routing'

const ANSWER = {
  path: 'org/jboss/x/1/x-1.pom',
  members: [
    { member: 'libs-release-local', through: '', asked: true, rule: 0, why: 'no rules: asked for every path' },
    { member: 'maven-central', through: 'thirdparty', asked: false, rule: 0, why: "no rule of the group 'thirdparty' matches: skipped" },
    { member: 'jboss', through: '', asked: true, rule: 1, why: 'rule 1 matches' },
  ],
  inherited: [{ group: 'thirdparty', member: 'maven-central', pattern: '(?!org/jboss/).*' }],
}

beforeEach(() => {
  post.mockReset()
})

describe('routing test of a group', () => {
  it('posts path, members and rules to /repositories/:name/routing/test', async () => {
    post.mockResolvedValue({ data: ANSWER })
    const body = {
      path: 'org/jboss/x/1/x-1.pom',
      members: ['libs-release-local', 'thirdparty', 'jboss'],
      routing: [{ member: 'jboss', prefix: 'org/jboss/' }],
    }
    await expect(testRouting('libs-release', body)).resolves.toEqual(ANSWER)
    expect(post).toHaveBeenCalledWith('/repositories/libs-release/routing/test', body)
  })

  it('sends no path when only the rules are checked', async () => {
    post.mockResolvedValue({ data: { members: [{ member: 'a', through: '' }], inherited: [] } })
    await testRouting('g', { members: ['a'], routing: [] })
    expect(post).toHaveBeenCalledWith('/repositories/g/routing/test', { members: ['a'], routing: [] })
  })

  it('answers empty lists when the answer has none', async () => {
    post.mockResolvedValue({ data: {} })
    await expect(testRouting('g', { members: [] })).resolves.toEqual({ members: [], inherited: [] })
  })

  it('passes a refusal on to the caller', async () => {
    const refused = {
      response: {
        status: 400,
        data: { error: 'BAD_REQUEST', message: "Invalid routing rules: rule 1: 'x' is not a member of the group", status: 400 },
      },
    }
    post.mockRejectedValue(refused)
    await expect(testRouting('g', { members: ['a'], routing: [{ member: 'x', prefix: 'p' }] })).rejects.toBe(refused)
  })
})
