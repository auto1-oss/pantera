import { computed, ref } from 'vue'
import { listLockedUsers, unlockAllUsers, unlockUser } from '@/api/failedLogins'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { apiErrorMessage } from '@/utils/apiError'
import type { FailedLogins } from '@/types'

/**
 * The names locked after failed logins, and their unlock.
 *
 * A locked name is in lower case and need not belong to a Pantera user,
 * so a user name is looked up in lower case ({@link isLocked}). Every
 * unlock loads the list again: another node, or the automatic unlock, may
 * have changed it meanwhile.
 */
export function useLockedUsers() {
  const auth = useAuthStore()
  const notify = useNotificationStore()

  const locked = ref<FailedLogins[]>([])
  const loading = ref(false)
  /** Why the last load failed; null after a load that worked. */
  const loadError = ref<string | null>(null)
  /** Name whose unlock is running, for the spinner of its row. */
  const unlocking = ref<string | null>(null)
  const unlockingAll = ref(false)

  /** Unlocking needs the same permission as enabling or disabling a user. */
  const canUnlock = computed(() => auth.hasAction('api_user_permissions', 'enable'))

  const lockedNames = computed(
    () => new Set(locked.value.map(entry => entry.name.toLowerCase())),
  )

  function isLocked(username: string): boolean {
    return lockedNames.value.has(username.trim().toLowerCase())
  }

  async function load(): Promise<void> {
    loading.value = true
    try {
      locked.value = await listLockedUsers()
      loadError.value = null
    } catch (err) {
      // An old list would show locks that may be gone; show none and say why.
      locked.value = []
      loadError.value = apiErrorMessage(err, 'Request failed')
    } finally {
      loading.value = false
    }
  }

  /** @returns false when the request failed; its error is shown as a toast */
  async function unlock(name: string): Promise<boolean> {
    unlocking.value = name
    try {
      const wasLocked = await unlockUser(name)
      if (wasLocked) {
        notify.success('User unlocked', name)
      } else {
        notify.info('User was not locked', `${name}: failed logins cleared`)
      }
      await load()
      return true
    } catch (err) {
      notify.error('Failed to unlock user', apiErrorMessage(err, name))
      return false
    } finally {
      unlocking.value = null
    }
  }

  /** @returns false when the request failed; its error is shown as a toast */
  async function unlockAll(): Promise<boolean> {
    unlockingAll.value = true
    try {
      const names = await unlockAllUsers()
      if (names.length === 0) {
        notify.info('No user was locked')
      } else {
        notify.success(
          `Unlocked ${names.length} ${names.length === 1 ? 'user' : 'users'}`,
          names.length <= 5 ? names.join(', ') : undefined,
        )
      }
      await load()
      return true
    } catch (err) {
      notify.error('Failed to unlock all users', apiErrorMessage(err, 'Request failed'))
      return false
    } finally {
      unlockingAll.value = false
    }
  }

  return {
    locked, loading, loadError, unlocking, unlockingAll,
    canUnlock, isLocked, load, unlock, unlockAll,
  }
}
