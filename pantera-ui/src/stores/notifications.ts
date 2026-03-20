import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Toast {
  id: number
  severity: 'success' | 'error' | 'warn' | 'info'
  summary: string
  detail?: string
  life?: number
}

let nextId = 0

export const useNotificationStore = defineStore('notifications', () => {
  const toasts = ref<Toast[]>([])

  function add(severity: Toast['severity'], summary: string, detail?: string) {
    const id = nextId++
    const life = severity === 'error' ? 5000 : 3000
    toasts.value.push({ id, severity, summary, detail, life })
    setTimeout(() => remove(id), life)
  }

  function remove(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  function success(summary: string, detail?: string) { add('success', summary, detail) }
  function error(summary: string, detail?: string) { add('error', summary, detail) }
  function warn(summary: string, detail?: string) { add('warn', summary, detail) }
  function info(summary: string, detail?: string) { add('info', summary, detail) }

  return { toasts, add, remove, success, error, warn, info }
})
