<script setup lang="ts">
import { ref } from 'vue'
import AppHeader from './AppHeader.vue'
import AppSidebar from './AppSidebar.vue'
import { useNotificationStore } from '@/stores/notifications'

const sidebarCollapsed = ref(false)
const notify = useNotificationStore()

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}
</script>

<template>
  <div data-testid="app-layout" class="min-h-screen bg-gray-50 dark:bg-gray-900">
    <AppHeader :sidebar-collapsed="sidebarCollapsed" @toggle-sidebar="toggleSidebar" />
    <AppSidebar :collapsed="sidebarCollapsed" />
    <main
      class="pt-14 transition-all duration-200"
      :class="sidebarCollapsed ? 'ml-16' : 'ml-[240px]'"
    >
      <div class="p-6">
        <slot />
      </div>
    </main>
    <!-- Toast notifications -->
    <div class="fixed top-16 right-4 z-50 space-y-2 w-80">
      <transition-group name="toast">
        <div
          v-for="toast in notify.toasts"
          :key="toast.id"
          class="rounded-lg shadow-lg border px-4 py-3 flex items-start gap-3"
          :class="{
            'bg-green-50 border-green-200 text-green-800 dark:bg-green-900/50 dark:border-green-700 dark:text-green-200': toast.severity === 'success',
            'bg-red-50 border-red-200 text-red-800 dark:bg-red-900/50 dark:border-red-700 dark:text-red-200': toast.severity === 'error',
            'bg-yellow-50 border-yellow-200 text-yellow-800 dark:bg-yellow-900/50 dark:border-yellow-700 dark:text-yellow-200': toast.severity === 'warn',
            'bg-blue-50 border-blue-200 text-blue-800 dark:bg-blue-900/50 dark:border-blue-700 dark:text-blue-200': toast.severity === 'info',
          }"
        >
          <i
            class="pi text-lg mt-0.5"
            :class="{
              'pi-check-circle': toast.severity === 'success',
              'pi-times-circle': toast.severity === 'error',
              'pi-exclamation-triangle': toast.severity === 'warn',
              'pi-info-circle': toast.severity === 'info',
            }"
          />
          <div class="flex-1 min-w-0">
            <div class="font-medium text-sm">{{ toast.summary }}</div>
            <div v-if="toast.detail" class="text-xs opacity-80 mt-0.5">{{ toast.detail }}</div>
          </div>
          <button
            class="opacity-50 hover:opacity-100 text-xs"
            @click="notify.remove(toast.id)"
          >
            <i class="pi pi-times" />
          </button>
        </div>
      </transition-group>
    </div>
  </div>
</template>

<style scoped>
.toast-enter-active {
  transition: all 0.3s ease-out;
}
.toast-leave-active {
  transition: all 0.2s ease-in;
}
.toast-enter-from {
  opacity: 0;
  transform: translateX(30px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateX(30px);
}
</style>
