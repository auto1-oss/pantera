import { describe, it, expect } from 'vitest'
import { routes } from '../index'

describe('Router', () => {
  it('has login route', () => {
    expect(routes.find((r) => r.path === '/login')).toBeDefined()
  })

  it('has dashboard route at /', () => {
    expect(routes.find((r) => r.path === '/')).toBeDefined()
  })

  it('has admin routes', () => {
    const adminRoutes = routes.filter((r) => r.path.startsWith('/admin'))
    expect(adminRoutes.length).toBeGreaterThanOrEqual(5)
  })

  it('all admin routes have access control meta', () => {
    const adminRoutes = routes.filter((r) => r.path.startsWith('/admin'))
    adminRoutes.forEach((r) => {
      const hasAccessControl =
        r.meta?.requiresAdmin === true || r.meta?.requiredPermission !== undefined
      expect(hasAccessControl).toBe(true)
    })
  })
})
