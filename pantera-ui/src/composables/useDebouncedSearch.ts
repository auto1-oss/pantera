import { ref, watch } from 'vue'

/**
 * Debounced, abortable search runner shared by list pages.
 *
 * `query` is bound to the search input; typing schedules one fetch after
 * `delayMs`. `run()` fetches immediately (Enter, a filter or sort change)
 * and cancels both the pending timer and the in-flight request, so a slow
 * earlier response can never overwrite a newer one.
 */
export function useDebouncedSearch(
  fetch: (signal: AbortSignal) => Promise<void>,
  options: { delayMs?: number } = {},
) {
  const delay = options.delayMs ?? 300
  const query = ref('')
  const loading = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  let ctrl: AbortController | null = null

  async function run(): Promise<void> {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (ctrl) ctrl.abort()
    ctrl = new AbortController()
    const mine = ctrl
    loading.value = true
    try {
      await fetch(mine.signal)
    } catch (err) {
      if (!mine.signal.aborted) throw err
    } finally {
      if (!mine.signal.aborted) loading.value = false
    }
  }

  watch(query, () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      void run()
    }, delay)
  })

  function dispose() {
    if (timer) clearTimeout(timer)
    if (ctrl) ctrl.abort()
  }

  return { query, run, loading, dispose }
}
