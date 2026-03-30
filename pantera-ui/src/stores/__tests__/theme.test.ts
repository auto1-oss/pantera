import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore } from '../theme'

describe('themeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('defaults to system mode when no preference saved', () => {
    const store = useThemeStore()
    expect(store.mode).toBe('system')
  })

  it('isDark is true when mode is dark', () => {
    const store = useThemeStore()
    store.setMode('dark')
    expect(store.isDark).toBe(true)
  })

  it('isDark is false when mode is light', () => {
    const store = useThemeStore()
    store.setMode('light')
    expect(store.isDark).toBe(false)
  })

  it('toggle switches from light to dark', () => {
    const store = useThemeStore()
    store.setMode('light')
    store.toggle()
    expect(store.mode).toBe('dark')
    expect(store.isDark).toBe(true)
  })

  it('toggle switches from dark to light', () => {
    const store = useThemeStore()
    store.setMode('dark')
    store.toggle()
    expect(store.mode).toBe('light')
    expect(store.isDark).toBe(false)
  })

  it('persists mode to localStorage', () => {
    const store = useThemeStore()
    store.setMode('dark')
    expect(localStorage.getItem('theme')).toBe('dark')
  })

  it('applyTheme does not throw', () => {
    const store = useThemeStore()
    expect(() => store.applyTheme()).not.toThrow()
  })
})
