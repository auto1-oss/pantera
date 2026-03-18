import { getApiClient } from './client'
import type { AuthProvidersResponse, TokenResponse, UserInfo } from '@/types'

export async function getProviders(): Promise<AuthProvidersResponse> {
  const { data } = await getApiClient().get<AuthProvidersResponse>('/auth/providers')
  return data
}

export async function login(name: string, pass: string): Promise<TokenResponse> {
  const { data } = await getApiClient().post<TokenResponse>('/auth/token', { name, pass })
  return data
}

export async function getMe(): Promise<UserInfo> {
  const { data } = await getApiClient().get<UserInfo>('/auth/me')
  return data
}

export async function getProviderRedirect(
  name: string,
  callbackUrl: string,
): Promise<{ url: string; state: string }> {
  const { data } = await getApiClient().get<{ url: string; state: string }>(
    `/auth/providers/${name}/redirect`,
    { params: { callback_url: callbackUrl } },
  )
  return data
}

export interface GenerateTokenResponse {
  token: string
  id: string
  label: string
  expires_at?: string
  permanent: boolean
}

/**
 * Generate an API token for the currently authenticated user.
 * Uses the session JWT — no password required.
 */
export async function generateTokenForSession(
  expiryDays = 30,
  label = 'API Token',
): Promise<GenerateTokenResponse> {
  const { data } = await getApiClient().post<GenerateTokenResponse>('/auth/token/generate', {
    expiry_days: expiryDays,
    label,
  })
  return data
}

export interface ApiToken {
  id: string
  label: string
  created_at: string
  expires_at?: string
  expired?: boolean
  permanent?: boolean
}

export async function listTokens(): Promise<ApiToken[]> {
  const { data } = await getApiClient().get<{ tokens: ApiToken[] }>('/auth/tokens')
  return data.tokens
}

export async function revokeToken(tokenId: string): Promise<void> {
  await getApiClient().delete(`/auth/tokens/${tokenId}`)
}

export async function exchangeOAuthCode(
  code: string,
  provider: string,
  callbackUrl: string,
): Promise<TokenResponse> {
  const { data } = await getApiClient().post<TokenResponse>('/auth/callback', {
    code,
    provider,
    callback_url: callbackUrl,
  })
  return data
}
