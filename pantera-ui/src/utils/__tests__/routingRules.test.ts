import { describe, it, expect } from 'vitest'
import {
  parseRoutingProblems, ruleKind, ruleKindLabel, ruleMember, ruleValue, rulesOf,
} from '../routingRules'

describe('parseRoutingProblems', () => {
  it('puts every `rule N:` item on its rule', () => {
    const parsed = parseRoutingProblems(
      "Invalid routing rules: rule 2: 'jboss' is not a member of the group; "
      + 'rule 3: pattern is not a valid regular expression: Unclosed character class',
    )
    expect(parsed).toEqual({
      routing: true,
      byRule: {
        2: ["'jboss' is not a member of the group"],
        3: ['pattern is not a valid regular expression: Unclosed character class'],
      },
      general: [],
    })
  })

  it('reads all problems the server knows', () => {
    const parsed = parseRoutingProblems(
      "Invalid routing rules: rule 1: 'g' is a group: give its repositories their rules in 'g'; "
      + "rule 2: 'r' is a member of the group 'g': give it its rules there; "
      + 'rule 3: the same rule as rule 1; rule 4: a rule names a member; '
      + 'rule 5: a rule needs a prefix or a pattern; rule 6: a rule has a prefix or a pattern, not both; '
      + 'rule 10: a rule is an object with a member and a prefix or a pattern',
    )
    expect(Object.keys(parsed.byRule).map(Number)).toEqual([1, 2, 3, 4, 5, 6, 10])
    expect(parsed.byRule[1]).toEqual(["'g' is a group: give its repositories their rules in 'g'"])
    expect(parsed.byRule[3]).toEqual(['the same rule as rule 1'])
    expect(parsed.general).toEqual([])
  })

  it('keeps a part without a rule number as a general problem', () => {
    expect(parseRoutingProblems('Invalid routing rules: routing is a list of rules')).toEqual({
      routing: true, byRule: {}, general: ['routing is a list of rules'],
    })
    const mixed = parseRoutingProblems('Invalid routing rules: something else; rule 1: a rule names a member')
    expect(mixed.general).toEqual(['something else'])
    expect(mixed.byRule).toEqual({ 1: ['a rule names a member'] })
  })

  it('does not split the text of one problem at a semicolon', () => {
    const parsed = parseRoutingProblems(
      'Invalid routing rules: rule 1: pattern is not a valid regular expression: odd; text; rule 2: a rule names a member',
    )
    expect(parsed.byRule).toEqual({
      1: ['pattern is not a valid regular expression: odd; text'],
      2: ['a rule names a member'],
    })
  })

  it('says when a message is not about routing rules', () => {
    expect(parseRoutingProblems('storage path outside approved roots')).toEqual({
      routing: false, byRule: {}, general: [],
    })
    expect(parseRoutingProblems('')).toEqual({ routing: false, byRule: {}, general: [] })
  })
})

describe('reading a stored rule', () => {
  it('tells prefix from pattern', () => {
    expect(ruleKind({ member: 'jboss', prefix: 'org/jboss/' })).toBe('prefix')
    expect(ruleKind({ member: 'central', pattern: '(?!com/acme/).*' })).toBe('pattern')
    expect(ruleKindLabel({ member: 'jboss', prefix: 'org/jboss/' })).toBe('Prefix')
    expect(ruleKindLabel({ member: 'central', pattern: '.*' })).toBe('Pattern')
    expect(ruleValue({ member: 'jboss', prefix: 'org/jboss/' })).toBe('org/jboss/')
    expect(ruleValue({ member: 'central', pattern: '.*' })).toBe('.*')
    expect(ruleMember({ member: 'jboss', prefix: 'org/jboss/' })).toBe('jboss')
  })

  it('reads a rule the server would refuse without failing', () => {
    expect(ruleKind('x'), 'not an object').toBeNull()
    expect(ruleKindLabel('x')).toBe('—')
    expect(ruleMember('x')).toBe('')
    expect(ruleValue('x')).toBe('')
    expect(ruleKind({ member: 'a' }), 'neither prefix nor pattern').toBeNull()
    expect(ruleKind({ member: 'a', prefix: '', pattern: 'p' }), 'an empty prefix is no prefix').toBe('pattern')
    expect(ruleMember({ member: 5, prefix: 'p' }), 'a member that is no text').toBe('')
  })

  it('takes the rules of a config only when they are a list', () => {
    expect(rulesOf([{ member: 'a', prefix: 'p' }])).toEqual([{ member: 'a', prefix: 'p' }])
    expect(rulesOf(undefined)).toEqual([])
    expect(rulesOf(null)).toEqual([])
    expect(rulesOf({ member: 'a' })).toEqual([])
  })
})
