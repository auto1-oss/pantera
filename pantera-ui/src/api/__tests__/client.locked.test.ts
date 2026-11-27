import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  AxiosError,
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ACCOUNT_LOCKED_MESSAGE, signOutNotice } from '@/utils/authError'

const HOME = 'http://localhost:3000/'
const SIGN_IN = 'http://localhost:3000/login'

const LOCKED = {
  status: 403,
  data: { error: 'ACCOUNT_LOCKED', message: 'Account locked. Ask an administrator to unlock it.', status: 403 },
}
const DELAYED = {
  status: 403,
  data: { error: 'LOGIN_DELAYED', message: 'Too many failed logins. Try again in 7 seconds.', status: 403 },
}
const PAUSED = {
  status: 403,
  data: {
    error: 'SIGN_IN_PAUSED',
    message: 'Too many sign-ins without a confirmed second factor. Try again in 15 minutes.',
    status: 403,
  },
}
const FORBIDDEN = {
  status: 403,
  data: { error: 'FORBIDDEN', message: 'Access denied', status: 403 },
}
const EXPIRED = {
  status: 401,
  data: { error: 'UNAUTHORIZED', message: 'Invalid or expired token', status: 401 },
}

interface Answer { status: number; data?: unknown }

/** The requests the client sent, as "METHOD url". */
let sent: string[] = []

/**
 * A server for the client under test: answers each request with what
 * `route` returns for its URL and fails a non-2xx the way axios does.
 */
function serve(route: (url: string) => Answer): AxiosAdapter {
  return async (config: InternalAxiosRequestConfig) => {
    const url = config.url ?? ''
    sent.push(`${(config.method ?? 'get').toUpperCase()} ${url}`)
    const { status, data = {} } = route(url)
    const response = {
      data, status, statusText: String(status), headers: {}, config,
    } as AxiosResponse
    if (status >= 200 && status < 300) {
      return response
    }
    throw new AxiosError(
      `Request failed with status code ${status}`, 'ERR_BAD_RESPONSE', config, null, response,
    )
  }
}

/**
 * A client of its own for each test. The module keeps the state of a
 * running refresh, and the browser drops that state with the page load
 * that follows a sign-out; a test has to load the module again instead.
 */
async function clientAnswering(route: (url: string) => Answer) {
  vi.resetModules()
  const { initApiClient } = await import('../client')
  const client = initApiClient('/api/v1')
  client.defaults.adapter = serve(route)
  return client
}

function signedIn() {
  localStorage.setItem('access_token', 'access-1')
  localStorage.setItem('refresh_token', 'refresh-1')
}

describe('API client: a user locked while signed in', () => {
  beforeEach(() => {
    sent = []
    localStorage.clear()
    sessionStorage.clear()
    window.location.href = HOME
  })

  it('ends the session and sends the user to the sign-in page with the reason', async () => {
    signedIn()
    const client = await clientAnswering(() => LOCKED)

    await expect(client.get('/repositories')).rejects.toMatchObject({ response: { status: 403 } })

    expect(localStorage.getItem('access_token'), 'the access token must be removed').toBeNull()
    expect(localStorage.getItem('refresh_token'), 'the refresh token must be removed').toBeNull()
    expect(window.location.href, 'the sign-in page must be loaded').toBe(SIGN_IN)
    expect(signOutNotice(), 'the sign-in page must be told why').toBe(ACCOUNT_LOCKED_MESSAGE)
    expect(sent, 'no refresh may be tried for a locked user').toEqual(['GET /repositories'])
  })

  it('leaves an ordinary 403 to the caller and keeps the session', async () => {
    signedIn()
    const client = await clientAnswering(() => FORBIDDEN)

    await expect(client.delete('/repositories/maven')).rejects.toMatchObject({
      response: { status: 403, data: { error: 'FORBIDDEN' } },
    })

    expect(localStorage.getItem('access_token'), 'the session must be kept').toBe('access-1')
    expect(localStorage.getItem('refresh_token'), 'the refresh token must be kept').toBe('refresh-1')
    expect(window.location.href, 'the page must stay').toBe(HOME)
    expect(signOutNotice(), 'no notice for the sign-in page').toBe('')
  })

  // Delayed logins and paused sign-ins are answered 403 as a lock is; only the code tells them apart.
  it.each([
    ['a delayed login', DELAYED, '/repositories'],
    ['a delayed login', DELAYED, '/auth/token'],
    ['a delayed login', DELAYED, '/auth/callback'],
    ['a paused sign-in', PAUSED, '/repositories'],
    ['a paused sign-in', PAUSED, '/auth/token'],
    ['a paused sign-in', PAUSED, '/auth/callback'],
  ] as const)(
    'does not take %s at %s for a locked account',
    async (_name, answer, url) => {
      signedIn()
      const client = await clientAnswering(() => answer)

      await expect(client.post(url, {})).rejects.toMatchObject({
        response: { status: 403, data: { error: answer.data.error } },
      })

      expect(localStorage.getItem('access_token'), 'the session must be kept').toBe('access-1')
      expect(localStorage.getItem('refresh_token'), 'the refresh token must be kept').toBe('refresh-1')
      expect(window.location.href, 'the page must stay').toBe(HOME)
      expect(signOutNotice(), 'no notice for the sign-in page').toBe('')
      expect(sent, 'no refresh may be tried').toEqual([`POST ${url}`])
    },
  )

  it('treats a 403 whose body is not the API error object as an ordinary 403', async () => {
    signedIn()
    const client = await clientAnswering(() => ({ status: 403, data: 'ACCOUNT_LOCKED' }))

    await expect(client.get('/repositories')).rejects.toMatchObject({ response: { status: 403 } })

    expect(localStorage.getItem('access_token'), 'the session must be kept').toBe('access-1')
    expect(window.location.href, 'the page must stay').toBe(HOME)
    expect(signOutNotice(), 'no notice for the sign-in page').toBe('')
  })

  it('does not take the code from another status', async () => {
    signedIn()
    const client = await clientAnswering(() => ({ status: 409, data: { error: 'ACCOUNT_LOCKED' } }))

    await expect(client.get('/repositories')).rejects.toMatchObject({ response: { status: 409 } })

    expect(localStorage.getItem('access_token'), 'the session must be kept').toBe('access-1')
    expect(window.location.href, 'the page must stay').toBe(HOME)
  })

  it.each(['/auth/token', '/auth/callback'])(
    'leaves a refused sign-in at %s to the view that sent it',
    async (url) => {
      // A stale session of another account may still be in storage.
      signedIn()
      const client = await clientAnswering(() => LOCKED)

      await expect(client.post(url, { name: 'alice', pass: 'x' })).rejects.toMatchObject({
        response: { status: 403, data: { error: 'ACCOUNT_LOCKED' } },
      })

      expect(window.location.href, 'the sign-in page must not be reloaded').toBe(HOME)
      expect(localStorage.getItem('access_token'), 'storage must not be touched').toBe('access-1')
      expect(signOutNotice(), 'the view shows the message itself').toBe('')
    },
  )

  it.each(['/auth/tokens', '/auth/token/generate', '/auth/me'])(
    'ends the session for the signed-in call %s',
    async (url) => {
      signedIn()
      const client = await clientAnswering(() => LOCKED)

      await expect(client.get(url)).rejects.toMatchObject({ response: { status: 403 } })

      expect(localStorage.getItem('access_token'), 'the access token must be removed').toBeNull()
      expect(window.location.href, 'the sign-in page must be loaded').toBe(SIGN_IN)
      expect(signOutNotice(), 'the sign-in page must be told why').toBe(ACCOUNT_LOCKED_MESSAGE)
    },
  )

  it('tells the reason when the refresh of an expired session is refused as locked', async () => {
    signedIn()
    const client = await clientAnswering(url => (url === '/auth/refresh' ? LOCKED : EXPIRED))

    await expect(client.get('/repositories')).rejects.toMatchObject({ response: { status: 401 } })

    expect(sent, 'one refresh, no retry').toEqual(['GET /repositories', 'POST /auth/refresh'])
    expect(localStorage.getItem('access_token'), 'the access token must be removed').toBeNull()
    expect(localStorage.getItem('refresh_token'), 'the refresh token must be removed').toBeNull()
    expect(window.location.href, 'the sign-in page must be loaded').toBe(SIGN_IN)
    expect(signOutNotice(), 'the sign-in page must be told why').toBe(ACCOUNT_LOCKED_MESSAGE)
  })

  it('still sends an expired session to the sign-in page without a notice', async () => {
    localStorage.setItem('access_token', 'access-1')
    const client = await clientAnswering(() => EXPIRED)

    await expect(client.get('/repositories')).rejects.toMatchObject({ response: { status: 401 } })

    expect(localStorage.getItem('access_token'), 'the access token must be removed').toBeNull()
    expect(window.location.href, 'the sign-in page must be loaded').toBe(SIGN_IN)
    expect(signOutNotice(), 'an expired session has no notice').toBe('')
  })

  it('still refreshes an expired session and repeats the request', async () => {
    signedIn()
    let refreshed = false
    const client = await clientAnswering((url) => {
      if (url === '/auth/refresh') {
        refreshed = true
        return { status: 200, data: { token: 'access-2', refresh_token: 'refresh-2' } }
      }
      return refreshed ? { status: 200, data: { items: [] } } : EXPIRED
    })

    await expect(client.get('/repositories')).resolves.toMatchObject({ status: 200 })

    expect(sent, 'the request must be repeated after the refresh')
      .toEqual(['GET /repositories', 'POST /auth/refresh', 'GET /repositories'])
    expect(localStorage.getItem('access_token'), 'the new access token must be stored').toBe('access-2')
    expect(window.location.href, 'the page must stay').toBe(HOME)
  })
})
