import { describe, it, expect, beforeEach } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { createAppRouter } from '@/router'

describe('router guard: a visitor who is not signed in', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('is sent to the sign-in page with the page asked for', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/profile')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/profile')
    expect(router.currentRoute.value.query.iss).toBeUndefined()
  })

  it('keeps the issuer an identity provider opened the UI with', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push(`/?iss=${encodeURIComponent('https://id.example.com')}`)

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.iss).toBe('https://id.example.com')
  })

  it('drops an issuer given more than once', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/?iss=https%3A%2F%2Fa.example.com&iss=https%3A%2F%2Fb.example.com')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.iss).toBeUndefined()
  })
})
