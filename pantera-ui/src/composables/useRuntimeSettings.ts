import { computed, reactive, ref } from 'vue'
import {
  listRuntimeSettings,
  patchRuntimeSetting,
  resetRuntimeSetting,
  type RuntimeSetting,
  type RuntimeSettingKey,
  type RuntimeValue,
} from '@/api/runtimeSettings'
import { useNotificationStore } from '@/stores/notifications'

const MIN_PERMITS: RuntimeSettingKey = 'http_client.bulkhead.min_permits'
const MAX_PERMITS: RuntimeSettingKey = 'http_client.bulkhead.max_permits'
const INITIAL_PERMITS: RuntimeSettingKey = 'http_client.bulkhead.initial_permits'

/**
 * Stateful wrapper around the runtime-tunables API. Loads the catalog
 * once, exposes a reactive edit buffer with dirty tracking, and PATCHes /
 * DELETEs through the same channel. Used by SettingsView so the runtime
 * bulkhead knobs can sit beside the rest of the system settings without
 * duplicating state machinery.
 */
export function useRuntimeSettings() {
  const notify = useNotificationStore()

  const loading = ref(true)
  const loadError = ref('')

  const rows = reactive<Record<string, RuntimeSetting>>({})
  const edited = reactive<Record<string, RuntimeValue>>({})
  const saving = reactive<Record<string, boolean>>({})

  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      const settings = await listRuntimeSettings()
      for (const s of settings) {
        rows[s.key] = s
        edited[s.key] = s.value
      }
    } catch (err: unknown) {
      const ax = err as { response?: { data?: { message?: string } }; message?: string }
      loadError.value = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
    } finally {
      loading.value = false
    }
  }

  function isDirty(key: RuntimeSettingKey): boolean {
    const row = rows[key]
    if (!row) return false
    return edited[key] !== row.value
  }

  function isOverridden(key: RuntimeSettingKey): boolean {
    return rows[key]?.source === 'db'
  }

  const anyDirty = computed(() => {
    for (const key of Object.keys(rows) as RuntimeSettingKey[]) {
      if (isDirty(key)) return true
    }
    return false
  })

  async function saveOne(key: RuntimeSettingKey): Promise<boolean> {
    if (!isDirty(key)) return true
    saving[key] = true
    try {
      const updated = await patchRuntimeSetting(key, edited[key])
      rows[key] = updated
      edited[key] = updated.value
      notify.success('Setting saved', key)
      return true
    } catch (err: unknown) {
      const ax = err as { response?: { data?: { message?: string } }; message?: string }
      const detail = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
      notify.error(`Failed to save ${key}`, detail)
      // Keep the rejected edit: the key stays dirty so the section stays
      // in the save bar. Discard and resetOne are the explicit reverts.
      return false
    } finally {
      saving[key] = false
    }
  }

  /** Current (saved) or edited value of a permit key, as a number. */
  function permits(key: RuntimeSettingKey, source: 'saved' | 'edited'): number {
    return Number(source === 'saved' ? rows[key]?.value : edited[key])
  }

  /**
   * The server refuses any single change that breaks
   * min_permits <= initial_permits <= max_permits, so the edited
   * combination must be valid (message when it is not)...
   */
  function permitViolation(): string | null {
    if (!rows[MIN_PERMITS] || !rows[MAX_PERMITS] || !rows[INITIAL_PERMITS]) return null
    const min = permits(MIN_PERMITS, 'edited')
    const max = permits(MAX_PERMITS, 'edited')
    const initial = permits(INITIAL_PERMITS, 'edited')
    if (min > max) return `Minimum permits (${min}) must not exceed maximum permits (${max})`
    if (initial < min || initial > max) {
      return `Initial permits (${initial}) must lie between minimum (${min}) and maximum (${max})`
    }
    return null
  }

  /**
   * ...and saved in an order that keeps it valid after every step: the
   * widening changes (max up, min down) first, then initial, then the
   * narrowing ones (max down, min up).
   */
  function saveRank(key: RuntimeSettingKey): number {
    if (key === INITIAL_PERMITS) return 1
    if (key === MAX_PERMITS) {
      return permits(key, 'edited') >= permits(key, 'saved') ? 0 : 2
    }
    if (key === MIN_PERMITS) {
      return permits(key, 'edited') <= permits(key, 'saved') ? 0 : 2
    }
    return 0
  }

  /** Saves every dirty key; resolves false when any key was rejected. */
  async function saveAllDirty(): Promise<boolean> {
    const dirty = (Object.keys(rows) as RuntimeSettingKey[]).filter(isDirty)
    const violation = permitViolation()
    const permitKeys = [MIN_PERMITS, MAX_PERMITS, INITIAL_PERMITS]
    if (violation && dirty.some(key => permitKeys.includes(key))) {
      notify.error('Bulkhead permits not saved', violation)
      return false
    }
    dirty.sort((a, b) => saveRank(a) - saveRank(b))
    let allSaved = true
    for (const key of dirty) {
      allSaved = (await saveOne(key)) && allSaved
    }
    return allSaved
  }

  async function resetOne(key: RuntimeSettingKey) {
    saving[key] = true
    try {
      await resetRuntimeSetting(key)
      if (rows[key]) {
        rows[key] = {
          ...rows[key],
          value: rows[key].default,
          source: 'default',
        }
        edited[key] = rows[key].value
      }
      notify.success('Reverted to default', key)
    } catch (err: unknown) {
      const ax = err as { response?: { data?: { message?: string } }; message?: string }
      const detail = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
      notify.error(`Failed to reset ${key}`, detail)
    } finally {
      saving[key] = false
    }
  }

  return {
    loading,
    loadError,
    rows,
    edited,
    saving,
    anyDirty,
    isDirty,
    isOverridden,
    load,
    saveOne,
    saveAllDirty,
    resetOne,
  }
}
