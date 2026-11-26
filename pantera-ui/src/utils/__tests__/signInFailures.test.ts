import { describe, it, expect } from 'vitest'
import {
  rejectedKeyReason, passwordFailureReason, shortKeyId, rejectedKeyName, userOrNoName,
} from '../signInFailures'

describe('rejectedKeyReason', () => {
  it.each([
    ['api_key_expired', 'Expired'],
    ['api_key_revoked', 'Revoked'],
    ['api_key_refused', 'Not accepted'],
    ['api_key_unknown', 'Not issued here'],
  ])('reads %s as "%s"', (token, text) => {
    expect(rejectedKeyReason(token)).toBe(text)
  })

  it('shows a token it does not know as it is', () => {
    expect(rejectedKeyReason('api_key_of_tomorrow')).toBe('api_key_of_tomorrow')
  })

  it('shows a dash for no reason', () => {
    expect(rejectedKeyReason('')).toBe('—')
  })
})

describe('passwordFailureReason', () => {
  it.each([
    ['wrong_credentials', 'Wrong credentials'],
    ['login_delayed', 'Login delayed'],
    ['account_locked', 'Account locked'],
  ])('reads %s as "%s"', (token, text) => {
    expect(passwordFailureReason(token)).toBe(text)
  })

  it('shows a token it does not know as it is', () => {
    expect(passwordFailureReason('sign_in_busy')).toBe('sign_in_busy')
  })
})

describe('shortKeyId', () => {
  it('keeps the first eight characters of a key id', () => {
    expect(shortKeyId('a922f096-2062-4c1f-b619-df3052cf208e')).toBe('a922f096…')
  })

  it('leaves a short id whole', () => {
    expect(shortKeyId('a922f096')).toBe('a922f096')
  })

  it('is empty for no id', () => {
    expect(shortKeyId('')).toBe('')
  })
})

describe('rejectedKeyName', () => {
  it('is the name the owner gave the key', () => {
    expect(rejectedKeyName({ key_name: 'CI token', key_id: 'a922f096-2062-4c1f-b619-df3052cf208e' }))
      .toBe('CI token')
  })

  it('is the shortened id of a key without a name', () => {
    expect(rejectedKeyName({ key_name: null, key_id: 'a922f096-2062-4c1f-b619-df3052cf208e' }))
      .toBe('a922f096…')
  })

  it('is a dash for a key with neither', () => {
    expect(rejectedKeyName({ key_name: null, key_id: '' })).toBe('—')
  })
})

describe('userOrNoName', () => {
  it('is the name', () => {
    expect(userOrNoName('alice')).toBe('alice')
  })

  it('says so when the request carried no name', () => {
    expect(userOrNoName('')).toBe('(no name)')
  })
})
