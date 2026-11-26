import { getApiClient } from './client'
import type { RoutingRule } from '@/types/repo'

/**
 * POST /repositories/:name/routing/test: check the routing rules of a
 * group and say which repositories it would ask for a path (2.2.15). It
 * asks no repository and saves nothing.
 *
 * `members` and `routing` that are left out are taken from the saved
 * group, so a form sends both to have its unsaved state decided; a group
 * that is not saved yet must send `members`.
 */
export interface RoutingTestRequest {
  /** Path inside the group. Left out: the rules are checked and the repositories listed. */
  path?: string
  members?: string[]
  routing?: unknown[]
}

/** A repository the group asks. `asked`, `rule` and `why` come only with a `path`. */
export interface RoutingTestMember {
  member: string
  /** The nested group the repository is a member of; empty for a direct member. */
  through: string
  asked?: boolean
  /** Number (from 1) of the rule of this group that matched; 0 for none. */
  rule?: number
  why?: string
}

/** A rule of a nested group. It is changed in that group. */
export interface InheritedRoutingRule extends RoutingRule {
  group: string
}

export interface RoutingTestResult {
  path?: string
  /** The repositories in the order the group asks them. */
  members: RoutingTestMember[]
  inherited: InheritedRoutingRule[]
}

/**
 * Rules the server would refuse on save are answered with 400 and the
 * message of the save (`Invalid routing rules: rule 2: …; rule 3: …`).
 */
export async function testRouting(name: string, body: RoutingTestRequest): Promise<RoutingTestResult> {
  const { data } = await getApiClient().post<Partial<RoutingTestResult>>(
    `/repositories/${name}/routing/test`, body,
  )
  const result: RoutingTestResult = { members: data.members ?? [], inherited: data.inherited ?? [] }
  if (typeof data.path === 'string') result.path = data.path
  return result
}
