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

/**
 * Stateful wrapper around the runtime-tunables API. Loads the catalog
 * once, exposes a reactive edit buffer with dirty tracking, and PATCHes /
 * DELETEs through the same channel. Used by SettingsView so the runtime
 * knobs (HTTP/2 client) can sit beside the rest of the system settings
 * without duplicating state machinery.
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

  async function saveOne(key: RuntimeSettingKey) {
    if (!isDirty(key)) return
    saving[key] = true
    try {
      const updated = await patchRuntimeSetting(key, edited[key])
      rows[key] = updated
      edited[key] = updated.value
      notify.success('Setting saved', key)
    } catch (err: unknown) {
      const ax = err as { response?: { data?: { message?: string } }; message?: string }
      const detail = ax.response?.data?.message ?? ax.message ?? 'Unknown error'
      notify.error(`Failed to save ${key}`, detail)
      edited[key] = rows[key].value
    } finally {
      saving[key] = false
    }
  }

  async function saveAllDirty() {
    const dirty = (Object.keys(rows) as RuntimeSettingKey[]).filter(isDirty)
    for (const key of dirty) {
      await saveOne(key)
    }
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
