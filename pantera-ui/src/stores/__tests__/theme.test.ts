import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore } from '../theme'

describe('themeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('is always dark mode', () => {
    const store = useThemeStore()
    expect(store.mode).toBe('dark')
  })

  it('isDark is always true', () => {
    const store = useThemeStore()
    expect(store.isDark).toBe(true)
  })

  it('toggle is a no-op', () => {
    const store = useThemeStore()
    store.toggle()
    expect(store.mode).toBe('dark')
    expect(store.isDark).toBe(true)
  })

  it('applyTheme does not throw', () => {
    const store = useThemeStore()
    expect(() => store.applyTheme()).not.toThrow()
  })
})
