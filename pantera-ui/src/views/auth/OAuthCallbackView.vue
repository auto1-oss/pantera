<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notify = useNotificationStore()
const status = ref('Processing SSO callback...')
const hasError = ref(false)

onMounted(async () => {
  const code = route.query.code as string | undefined
  const state = route.query.state as string | undefined
  const error = route.query.error as string | undefined
  const errorDesc = route.query.error_description as string | undefined

  if (error) {
    hasError.value = true
    status.value = errorDesc || `SSO error: ${error}`
    notify.error('SSO Error', status.value)
    setTimeout(() => router.push('/login'), 3000)
    return
  }

  if (!code || !state) {
    hasError.value = true
    status.value = 'Missing authorization code or state'
    notify.error('SSO Error', status.value)
    setTimeout(() => router.push('/login'), 2000)
    return
  }

  try {
    await auth.handleOAuthCallback(code, state)
    notify.success('Login successful', 'Welcome back!')
    router.push('/')
  } catch (e: unknown) {
    hasError.value = true
    const msg = e instanceof Error ? e.message : 'SSO authentication failed'
    status.value = msg
    notify.error('SSO Error', msg)
    setTimeout(() => router.push('/login'), 3000)
  }
})
</script>

<template>
  <div class="flex items-center justify-center min-h-screen">
    <div class="text-center">
      <i
        :class="[
          'text-4xl mb-4',
          hasError ? 'pi pi-times-circle text-red-500' : 'pi pi-spin pi-spinner text-blue-500',
        ]"
      ></i>
      <p class="text-gray-600 dark:text-gray-400 mt-4">{{ status }}</p>
      <p v-if="hasError" class="text-gray-400 text-sm mt-2">Redirecting to login...</p>
    </div>
  </div>
</template>
