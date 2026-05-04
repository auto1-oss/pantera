<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

defineProps<{ collapsed: boolean }>()

const route = useRoute()
const auth = useAuthStore()

interface NavItem {
  label: string
  icon: string
  to: string
}

function canRead(key: string): boolean {
  return auth.hasAction(key, 'read')
}

function canWrite(key: string): boolean {
  return auth.hasAction(key, 'write')
}

const userItems = computed<NavItem[]>(() => {
  const items: NavItem[] = [
    { label: 'Dashboard', icon: 'pi pi-th-large', to: '/' },
  ]
  if (canRead('api_repository_permissions')) {
    items.push({ label: 'Repositories', icon: 'pi pi-box', to: '/repositories' })
  }
  if (canRead('api_search_permissions')) {
    items.push({ label: 'Search', icon: 'pi pi-search', to: '/search' })
  }
  if (canRead('api_cooldown_permissions')) {
    items.push({ label: 'Cooldown', icon: 'pi pi-clock', to: '/cooldown' })
  }
  items.push({ label: 'Quick Setup', icon: 'pi pi-bolt', to: '/setup' })
  return items
})

const adminItems = computed<NavItem[]>(() => {
  const items: NavItem[] = []
  if (canWrite('api_repository_permissions')) {
    items.push({ label: 'Repository Management', icon: 'pi pi-server', to: '/admin/repositories' })
  }
  if (canWrite('api_user_permissions')) {
    items.push({ label: 'User Management', icon: 'pi pi-users', to: '/admin/users' })
  }
  if (canWrite('api_role_permissions')) {
    items.push({ label: 'Roles & Permissions', icon: 'pi pi-shield', to: '/admin/roles' })
  }
  if (canWrite('api_alias_permissions')) {
    items.push({ label: 'Storage Configuration', icon: 'pi pi-database', to: '/admin/storage' })
  }
  if (auth.isAdmin) {
    items.push({ label: 'Auth Providers', icon: 'pi pi-key', to: '/admin/auth-providers' })
    items.push({ label: 'Negative Cache', icon: 'pi pi-ban', to: '/admin/neg-cache' })
    items.push({ label: 'Performance Tuning', icon: 'pi pi-bolt', to: '/admin/performance-tuning' })
    items.push({ label: 'System Settings', icon: 'pi pi-sliders-h', to: '/admin/settings' })
  }
  return items
})

function isActive(to: string): boolean {
  if (to === '/') return route.path === '/'
  return route.path.startsWith(to)
}
</script>

<template>
  <aside
    class="fixed top-14 left-0 bottom-0 bg-white dark:bg-gray-900 border-r border-gray-200 dark:border-gray-800 transition-all duration-200 overflow-y-auto z-40 flex flex-col"
    :class="collapsed ? 'w-16' : 'w-[240px]'"
  >
    <!-- User section -->
    <nav class="py-3 px-2 space-y-0.5">
      <router-link
        v-for="item in userItems"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors"
        :class="isActive(item.to)
          ? 'bg-amber-600/10 text-amber-500 font-medium'
          : 'text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-gray-900 dark:hover:text-gray-200'"
      >
        <i :class="item.icon" class="text-base w-5 text-center" />
        <span v-if="!collapsed">{{ item.label }}</span>
      </router-link>
    </nav>

    <!-- Administration section -->
    <div v-if="adminItems.length > 0" class="px-2">
      <div class="border-t border-gray-200 dark:border-gray-800 my-1" />
      <div v-if="!collapsed" class="px-3 pt-3 pb-1 text-[10px] font-semibold uppercase tracking-widest text-gray-400 dark:text-gray-600">
        Administration
      </div>
      <nav class="py-1 space-y-0.5">
        <router-link
          v-for="item in adminItems"
          :key="item.to"
          :to="item.to"
          class="flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors"
          :class="isActive(item.to)
            ? 'bg-amber-600/10 text-amber-500 font-medium'
            : 'text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-gray-900 dark:hover:text-gray-200'"
        >
          <i :class="item.icon" class="text-base w-5 text-center" />
          <span v-if="!collapsed">{{ item.label }}</span>
        </router-link>
      </nav>
    </div>

    <!-- Profile at bottom -->
    <div class="mt-auto px-2 pb-3">
      <div class="border-t border-gray-200 dark:border-gray-800 mb-2" />
      <router-link
        to="/profile"
        class="flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors"
        :class="isActive('/profile')
          ? 'bg-amber-600/10 text-amber-500 font-medium'
          : 'text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-gray-900 dark:hover:text-gray-200'"
      >
        <i class="pi pi-user text-base w-5 text-center" />
        <span v-if="!collapsed">Profile</span>
      </router-link>
    </div>
  </aside>
</template>
