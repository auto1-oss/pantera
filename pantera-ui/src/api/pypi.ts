import { getApiClient } from './client'

export async function yankVersion(
  repo: string,
  pkg: string,
  version: string,
  reason?: string,
): Promise<void> {
  await getApiClient().post(`/pypi/${repo}/${pkg}/${version}/yank`, { reason: reason ?? '' })
}

export async function unyankVersion(
  repo: string,
  pkg: string,
  version: string,
): Promise<void> {
  await getApiClient().post(`/pypi/${repo}/${pkg}/${version}/unyank`)
}
