import { ref, type Ref } from 'vue'
import type { CursorResponse } from '@/types'

export function useCursorPagination<T>(
  apiFn: (params: { marker?: string; limit?: number }) => Promise<CursorResponse<T>>,
  defaultLimit = 100,
) {
  const items: Ref<T[]> = ref([])
  const marker = ref<string | null>(null)
  const hasMore = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetch() {
    loading.value = true
    error.value = null
    items.value = []
    marker.value = null
    try {
      const resp = await apiFn({ limit: defaultLimit })
      items.value = resp.items
      marker.value = resp.marker
      hasMore.value = resp.hasMore
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : 'Fetch failed'
    } finally {
      loading.value = false
    }
  }

  async function loadMore() {
    if (!marker.value) return
    loading.value = true
    try {
      const resp = await apiFn({ marker: marker.value, limit: defaultLimit })
      items.value.push(...resp.items)
      marker.value = resp.marker
      hasMore.value = resp.hasMore
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : 'Load more failed'
    } finally {
      loading.value = false
    }
  }

  return { items, marker, hasMore, loading, error, fetch, loadMore }
}
