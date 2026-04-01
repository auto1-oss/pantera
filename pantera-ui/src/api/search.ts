import { getApiClient } from './client'
import type { PaginatedResponse, SearchResult, LocateResponse, ReindexResponse } from '@/types'

export async function search(params: {
  q: string; page?: number; size?: number
  type?: string; repo?: string; sort?: string; sort_dir?: string
}, signal?: AbortSignal): Promise<PaginatedResponse<SearchResult>> {
  const { data } = await getApiClient().get('/search', { params, signal })
  return data
}

export async function locate(path: string): Promise<LocateResponse> {
  const { data } = await getApiClient().get<LocateResponse>('/search/locate', {
    params: { path },
  })
  return data
}

export async function reindex(): Promise<ReindexResponse> {
  const { data } = await getApiClient().post<ReindexResponse>('/search/reindex')
  return data
}

export async function searchStats(): Promise<Record<string, unknown>> {
  const { data } = await getApiClient().get('/search/stats')
  return data
}
