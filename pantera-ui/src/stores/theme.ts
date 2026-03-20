import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

function detectInitialTheme(): 'dark' {
  return 'dark'
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<'dark'>('dark')
  const isDark = computed(() => true)

  function toggle() {
    // Dark mode only
  }

  function applyTheme() {
    if (mode.value === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }

  return { mode, isDark, toggle, applyTheme }
})
