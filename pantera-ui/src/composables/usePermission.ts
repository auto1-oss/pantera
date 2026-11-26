import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * Whether the signed-in user holds any action on a resource
 * (`repository`, `user`, `role`, ...). The server sends the allowed actions
 * of each resource as a list; `auth.hasAction` asks for one of them.
 */
export function usePermission(resource: string) {
  const auth = useAuthStore()
  const hasPermission = computed(() => {
    if (!auth.user) return false
    const perms = auth.user.permissions ?? {}
    const actions = perms[`api_${resource}_permissions`]
    return Array.isArray(actions) && actions.length > 0
  })
  return { hasPermission }
}
