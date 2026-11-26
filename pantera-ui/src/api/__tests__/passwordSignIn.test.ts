import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub the axios client: the wrappers are thin, so what matters is the
// URL and body each one sends.
const get = vi.fn()
const put = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ get, put }),
}))

import { getPasswordSignInSettings, updatePasswordSignInSettings } from '../passwordSignIn'

const SETTINGS = {
  password_sign_in_okta: 'all',
  password_sign_in_keycloak: 'form',
  password_sign_in_github: 'off',
}

beforeEach(() => {
  get.mockReset(); put.mockReset()
})

describe('password sign-in settings', () => {
  it('reads the mode of every provider type from /admin/password-sign-in-settings', async () => {
    get.mockResolvedValue({ data: SETTINGS })
    await expect(getPasswordSignInSettings()).resolves.toEqual(SETTINGS)
    expect(get).toHaveBeenCalledWith('/admin/password-sign-in-settings')
  })

  it('sends only the given key to the same endpoint', async () => {
    put.mockResolvedValue({ status: 204, data: '' })
    await updatePasswordSignInSettings({ password_sign_in_okta: 'form' })
    expect(put).toHaveBeenCalledWith('/admin/password-sign-in-settings', { password_sign_in_okta: 'form' })
  })

  it('passes a refusal on to the caller', async () => {
    const refused = {
      response: {
        status: 400,
        data: {
          error: 'BAD_REQUEST',
          message: "Invalid password-sign-in setting: 'x' is not a password sign-in mode (all, form, off)",
          status: 400,
        },
      },
    }
    put.mockRejectedValue(refused)
    await expect(updatePasswordSignInSettings({ password_sign_in_github: 'x' as never })).rejects.toBe(refused)
  })
})
