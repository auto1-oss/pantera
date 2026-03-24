import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/auth/callback',
    name: 'oauth-callback',
    component: () => import('@/views/auth/OAuthCallbackView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/dashboard/DashboardView.vue'),
  },
  {
    path: '/repositories',
    name: 'repositories',
    component: () => import('@/views/repos/RepoListView.vue'),
  },
  {
    path: '/repositories/:name',
    name: 'repo-detail',
    component: () => import('@/views/repos/RepoDetailView.vue'),
    props: true,
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/views/search/SearchView.vue'),
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('@/views/profile/ProfileView.vue'),
  },
  // Admin routes — each guarded by its specific permission
  {
    path: '/admin/repositories',
    name: 'admin-repositories',
    component: () => import('@/views/admin/RepoManagementView.vue'),
    meta: { requiredPermission: 'api_repository_permissions' },
  },
  {
    path: '/admin/repositories/create',
    name: 'admin-repo-create',
    component: () => import('@/views/admin/RepoCreateView.vue'),
    meta: { requiredPermission: 'api_repository_permissions' },
  },
  {
    path: '/admin/repositories/:name/edit',
    name: 'admin-repo-edit',
    component: () => import('@/views/admin/RepoEditView.vue'),
    props: true,
    meta: { requiredPermission: 'api_repository_permissions' },
  },
  {
    path: '/admin/users',
    name: 'admin-users',
    component: () => import('@/views/admin/UserListView.vue'),
    meta: { requiredPermission: 'api_user_permissions' },
  },
  {
    path: '/admin/users/:name',
    name: 'admin-user-detail',
    component: () => import('@/views/admin/UserDetailView.vue'),
    props: true,
    meta: { requiredPermission: 'api_user_permissions' },
  },
  {
    path: '/admin/roles',
    name: 'admin-roles',
    component: () => import('@/views/admin/RoleListView.vue'),
    meta: { requiredPermission: 'api_role_permissions' },
  },
  {
    path: '/admin/roles/:name',
    name: 'admin-role-detail',
    component: () => import('@/views/admin/RoleDetailView.vue'),
    props: true,
    meta: { requiredPermission: 'api_role_permissions' },
  },
  {
    path: '/admin/storage',
    name: 'admin-storage',
    component: () => import('@/views/admin/StorageAliasView.vue'),
    meta: { requiredPermission: 'api_alias_permissions' },
  },
  {
    path: '/cooldown',
    name: 'cooldown',
    component: () => import('@/views/admin/CooldownView.vue'),
    meta: { requiredPermission: 'api_cooldown_permissions' },
  },
  {
    path: '/admin/settings',
    name: 'admin-settings',
    component: () => import('@/views/admin/SettingsView.vue'),
    meta: { requiresAdmin: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
  },
]

export function createAppRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes,
  })

  router.beforeEach(async (to) => {
    if (to.meta.public) return true
    const auth = useAuthStore()
    if (!auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // On page refresh the token survives in sessionStorage but user data is lost.
    // Re-fetch it so permissions, admin status, and profile info are available.
    if (!auth.user) {
      await auth.fetchUser()
      // fetchUser calls logout() on 401 (expired JWT), which clears the token.
      if (!auth.isAuthenticated) {
        return { name: 'login', query: { redirect: to.fullPath } }
      }
    }
    if (to.meta.requiresAdmin && !auth.isAdmin) {
      return { name: 'dashboard' }
    }
    const requiredPerm = to.meta.requiredPermission as string | undefined
    if (requiredPerm) {
      // Admin routes (under /admin/) require write access;
      // read-only views just need any access
      const needsWrite = to.path.startsWith('/admin/')
      if (needsWrite) {
        if (!auth.hasAction(requiredPerm, 'write')) {
          return { name: 'dashboard' }
        }
      } else {
        if (!auth.hasAction(requiredPerm, 'read')) {
          return { name: 'dashboard' }
        }
      }
    }
    return true
  })

  return router
}
