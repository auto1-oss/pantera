import { ref, watch } from 'vue'

export function useDebouncedSearch(
  searchFn: (query: string) => Promise<void>,
  delay = 300,
) {
  const query = ref('')
  let timeout: ReturnType<typeof setTimeout> | null = null

  watch(query, (val) => {
    if (timeout) clearTimeout(timeout)
    timeout = setTimeout(() => {
      searchFn(val)
    }, delay)
  })

  function clear() {
    query.value = ''
    if (timeout) clearTimeout(timeout)
  }

  return { query, clear }
}
