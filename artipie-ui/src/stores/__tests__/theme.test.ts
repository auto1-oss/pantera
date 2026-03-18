import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore } from '../theme'

describe('themeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('defaults to light mode', () => {
    const store = useThemeStore()
    expect(store.mode).toBe('light')
  })

  it('toggles to dark mode', () => {
    const store = useThemeStore()
    store.toggle()
    expect(store.mode).toBe('dark')
  })

  it('persists to localStorage', () => {
    const store = useThemeStore()
    store.toggle()
    expect(localStorage.getItem('theme')).toBe('dark')
  })

  it('isDark computed', () => {
    const store = useThemeStore()
    expect(store.isDark).toBe(false)
    store.toggle()
    expect(store.isDark).toBe(true)
  })
})
