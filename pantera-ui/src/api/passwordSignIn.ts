import { getApiClient } from './client'

/**
 * Where a provider that checks passwords outside Pantera (`okta`,
 * `keycloak`, `github`) takes a user name and password (2.2.15):
 *
 *   - `all`  — on the sign-in form, on `POST /auth/token`, and with
 *     repository requests (`mvn`, `npm`, `docker login`, `curl -u`);
 *   - `form` — on the sign-in form and `POST /auth/token` only;
 *   - `off`  — nowhere.
 *
 * Not the provider's enable flag (`toggleAuthProvider` in `./settings`)
 * and not its sign-in button: the browser redirect sends no password
 * through Pantera. API keys are not affected either.
 */
export type PasswordSignInMode = 'all' | 'form' | 'off'

/** The provider types that have the setting. */
export const PASSWORD_SIGN_IN_TYPES = ['okta', 'keycloak', 'github'] as const
export type PasswordSignInType = typeof PASSWORD_SIGN_IN_TYPES[number]

/**
 * GET/PUT /admin/password-sign-in-settings. The GET lists every type,
 * whether a provider of that type is configured or not; the PUT takes any
 * subset of the keys and answers 204.
 */
export type PasswordSignInSettings = {
  [K in PasswordSignInType as `password_sign_in_${K}`]: PasswordSignInMode
}

export async function getPasswordSignInSettings(): Promise<PasswordSignInSettings> {
  const { data } = await getApiClient().get<PasswordSignInSettings>(
    '/admin/password-sign-in-settings',
  )
  return data
}

export async function updatePasswordSignInSettings(
  settings: Partial<PasswordSignInSettings>,
): Promise<void> {
  await getApiClient().put('/admin/password-sign-in-settings', settings)
}
