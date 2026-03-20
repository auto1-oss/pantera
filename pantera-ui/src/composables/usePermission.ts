import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

export function usePermission(resource: string) {
  const auth = useAuthStore()
  const hasPermission = computed(() => {
    if (!auth.user) return false
    const perms = auth.user.permissions ?? {}
    const key = `api_${resource}_permissions`
    return perms[key] === true
  })
  return { hasPermission }
}
