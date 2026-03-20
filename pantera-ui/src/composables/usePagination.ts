import { ref, type Ref } from 'vue'
import type { PaginatedResponse } from '@/types'

export function usePagination<T>(
  apiFn: (params: { page: number; size: number }) => Promise<PaginatedResponse<T>>,
  defaultSize = 20,
) {
  const items: Ref<T[]> = ref([])
  const page = ref(0)
  const size = ref(defaultSize)
  const total = ref(0)
  const hasMore = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetch() {
    loading.value = true
    error.value = null
    try {
      const resp = await apiFn({ page: page.value, size: size.value })
      items.value = resp.items
      total.value = resp.total
      hasMore.value = resp.hasMore
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : 'Fetch failed'
    } finally {
      loading.value = false
    }
  }

  async function nextPage() {
    page.value++
    await fetch()
  }

  async function prevPage() {
    if (page.value > 0) {
      page.value--
      await fetch()
    }
  }

  async function goToPage(p: number) {
    page.value = p
    await fetch()
  }

  return { items, page, size, total, hasMore, loading, error, fetch, nextPage, prevPage, goToPage }
}
