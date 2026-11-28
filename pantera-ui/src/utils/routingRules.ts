import type { RoutingRule } from '@/types/repo'

export type RoutingRuleKind = 'prefix' | 'pattern'

/**
 * A rule as it is stored. The server checks rules on save only since
 * 2.2.15, so a stored list can hold an item that is no rule at all; the
 * readers below take anything and never throw.
 */
function field(rule: unknown, key: 'member' | 'prefix' | 'pattern'): string {
  if (!rule || typeof rule !== 'object') return ''
  const value = (rule as Record<string, unknown>)[key]
  return typeof value === 'string' ? value : ''
}

export function ruleMember(rule: unknown): string {
  return field(rule, 'member')
}

/** Like the server: a rule with a prefix is a prefix rule, else its pattern counts. */
export function ruleKind(rule: unknown): RoutingRuleKind | null {
  if (field(rule, 'prefix')) return 'prefix'
  if (field(rule, 'pattern')) return 'pattern'
  return null
}

export function ruleKindLabel(rule: unknown): string {
  const kind = ruleKind(rule)
  if (kind === 'prefix') return 'Prefix'
  if (kind === 'pattern') return 'Pattern'
  return '—'
}

export function ruleValue(rule: unknown): string {
  const kind = ruleKind(rule)
  return kind ? field(rule, kind) : ''
}

/** The `routing` value of a config as a list; anything else is no rules. */
export function rulesOf(routing: unknown): RoutingRule[] {
  return Array.isArray(routing) ? routing as RoutingRule[] : []
}

export interface RoutingProblems {
  /** Whether the message is the server's refusal of routing rules. */
  routing: boolean
  /** Problems by the number of their rule, counted from 1 in the order sent. */
  byRule: Record<number, string[]>
  /** Parts of the message that name no rule. */
  general: string[]
}

const PREFIX = 'Invalid routing rules:'

/**
 * Read the refusal of a save or of the routing test:
 * `Invalid routing rules: rule 2: <text>; rule 3: <text>`. The problems
 * are separated by `; `, but a text may hold one itself, so the message
 * is cut only where the next `rule N:` begins.
 */
export function parseRoutingProblems(message: string): RoutingProblems {
  const res: RoutingProblems = { routing: false, byRule: {}, general: [] }
  const text = (message ?? '').trim()
  if (!text.startsWith(PREFIX)) return res
  res.routing = true
  for (const part of text.slice(PREFIX.length).trim().split(/; (?=rule \d+: )/)) {
    const item = /^rule (\d+): ([\s\S]*)$/.exec(part)
    if (item) {
      const number = Number(item[1])
      ;(res.byRule[number] ??= []).push(item[2].trim())
    } else if (part.trim()) {
      res.general.push(part.trim())
    }
  }
  return res
}
