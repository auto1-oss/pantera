import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export type ThemeMode = 'system' | 'dark' | 'light'

function detectInitialTheme(): ThemeMode {
  const saved = localStorage.getItem('theme') as ThemeMode | null
  if (saved === 'system' || saved === 'dark' || saved === 'light') return saved
  return 'system'
}

let mediaListener: (() => void) | null = null

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(detectInitialTheme())
  const isDark = computed(() => {
    if (mode.value === 'dark') return true
    if (mode.value === 'light') return false
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  })

  function setMode(newMode: ThemeMode) {
    mode.value = newMode
    localStorage.setItem('theme', newMode)
    applyTheme()
  }

  function toggle() {
    setMode(isDark.value ? 'light' : 'dark')
  }

  function applyTheme() {
    if (mediaListener) {
      window.matchMedia('(prefers-color-scheme: dark)').removeEventListener('change', mediaListener)
      mediaListener = null
    }

    if (mode.value === 'dark') {
      document.documentElement.classList.add('dark')
    } else if (mode.value === 'light') {
      document.documentElement.classList.remove('dark')
    } else {
      // System: apply immediately and track changes
      const applySystem = () => {
        if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
          document.documentElement.classList.add('dark')
        } else {
          document.documentElement.classList.remove('dark')
        }
      }
      applySystem()
      mediaListener = applySystem
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', mediaListener)
    }
  }

  return { mode, isDark, toggle, setMode, applyTheme }
})
