import { computed, ref } from 'vue'
import { listUsers } from '@/api/users'
import { useAuthStore } from '@/stores/auth'

/** Lookups that run at the same time. */
const LOOKUPS_AT_ONCE = 4
/** The largest page of the user list. */
const LOOKUP_SIZE = 100

/**
 * Which names of the sign-in failures report have a user page.
 *
 * The report counts names in lower case, and a name need not belong to a
 * Pantera user: a login that is checked with an identity provider is
 * counted for the directory account behind it. The user page is opened
 * by the name as it is stored, so each name is looked up in the user
 * list once and matched without regard to case.
 */
export function useUserPages() {
  const auth = useAuthStore()

  /** The users list links to a user's page with this permission. */
  const canOpen = computed(() => auth.hasAction('api_user_permissions', 'update'))

  /** Name in lower case → name of the user as stored. */
  const found = ref<Record<string, string>>({})
  const asked = new Set<string>()

  function key(name: string): string {
    return name.trim().toLowerCase()
  }

  /** @returns the stored name of the user behind a name of the report, or null */
  function userOf(name: string): string | null {
    return found.value[key(name)] ?? null
  }

  async function lookUpOne(name: string): Promise<void> {
    try {
      const resp = await listUsers({ q: name, size: LOOKUP_SIZE })
      const hit = resp.items.find(user => user.name === name)
        ?? resp.items.find(user => user.name.toLowerCase() === name)
      if (hit) {
        found.value = { ...found.value, [name]: hit.name }
      }
    } catch {
      // No link for this name; it is asked for again with the next load.
      asked.delete(name)
    }
  }

  /** Looks up the names not asked for yet. Never fails: a name stays without a link. */
  async function lookUp(names: string[]): Promise<void> {
    if (!canOpen.value) return
    const todo = [...new Set(names.map(key))].filter(name => name !== '' && !asked.has(name))
    todo.forEach(name => asked.add(name))
    const worker = async () => {
      for (let name = todo.shift(); name !== undefined; name = todo.shift()) {
        await lookUpOne(name)
      }
    }
    await Promise.all(Array.from({ length: Math.min(LOOKUPS_AT_ONCE, todo.length) }, worker))
  }

  return { canOpen, userOf, lookUp }
}
