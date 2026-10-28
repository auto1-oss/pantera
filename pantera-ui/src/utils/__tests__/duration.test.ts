import { describe, it, expect } from 'vitest'
import { isoToShortDuration, shortToIsoDuration } from '../duration'

describe('cooldown window conversion', () => {
  it('shows simple ISO windows in the short form', () => {
    expect(isoToShortDuration('P30D'), 'days').toBe('30d')
    expect(isoToShortDuration('PT12H'), 'hours').toBe('12h')
    expect(isoToShortDuration('pt45m'), 'minutes').toBe('45m')
    expect(isoToShortDuration('P1DT12H'), 'compound stays ISO').toBe('P1DT12H')
  })

  it('writes the short form back as ISO and passes ISO through', () => {
    expect(shortToIsoDuration('30d'), 'days').toBe('P30D')
    expect(shortToIsoDuration(' 12H '), 'hours').toBe('PT12H')
    expect(shortToIsoDuration('45m'), 'minutes').toBe('PT45M')
    expect(shortToIsoDuration('p1dt12h'), 'iso').toBe('P1DT12H')
  })

  it('rejects anything else', () => {
    expect(shortToIsoDuration(''), 'empty').toBeNull()
    expect(shortToIsoDuration('0d'), 'zero').toBeNull()
    expect(shortToIsoDuration('7 days'), 'words').toBeNull()
    expect(shortToIsoDuration('P'), 'bare P').toBeNull()
    expect(shortToIsoDuration('PT'), 'bare PT').toBeNull()
  })
})
