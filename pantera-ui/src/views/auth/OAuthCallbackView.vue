<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { formatAuthError } from '@/utils/authError'
import Button from 'primevue/button'
import Message from 'primevue/message'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// Three discrete states. Each renders a different layout — there is
// no auto-redirect on failure: an error message that flashes for two
// seconds and then disappears is the same as no message at all.
const phase = ref<'processing' | 'error' | 'success'>('processing')
const errorMsg = ref('')

onMounted(async () => {
  const code = route.query.code as string | undefined
  const state = route.query.state as string | undefined
  const idpError = route.query.error as string | undefined

  // The IdP rejected us before we ever got a code (e.g. user denied
  // consent, or Okta returned an error). Do NOT echo error_description
  // from the URL — it's attacker-controllable when the redirect URI
  // is open and may carry confusing or misleading text.
  if (idpError) {
    // eslint-disable-next-line no-console
    console.warn('[auth] IdP returned error in callback', { error: idpError })
    phase.value = 'error'
    errorMsg.value = 'Sign-in was cancelled or rejected by the identity provider.'
    return
  }

  if (!code || !state) {
    phase.value = 'error'
    errorMsg.value = 'Sign-in session is incomplete. Please start again.'
    return
  }

  try {
    await auth.handleOAuthCallback(code, state)
    phase.value = 'success'
    router.replace('/')
  } catch (e: unknown) {
    phase.value = 'error'
    errorMsg.value = formatAuthError(e).message
  }
})

function backToLogin() {
  router.replace('/login')
}
</script>

<template>
  <div class="callback-page">
    <div class="callback-card">
      <!-- Processing -->
      <template v-if="phase === 'processing'">
        <i class="pi pi-spin pi-spinner spinner-icon" />
        <h2 class="callback-title">Completing sign-in…</h2>
        <p class="callback-sub">Verifying your identity with the provider.</p>
      </template>

      <!-- Success (only briefly visible before router.replace) -->
      <template v-else-if="phase === 'success'">
        <i class="pi pi-check-circle success-icon" />
        <h2 class="callback-title">Signed in.</h2>
        <p class="callback-sub">Redirecting…</p>
      </template>

      <!-- Persistent error: never auto-redirects, user must click. -->
      <template v-else>
        <i class="pi pi-times-circle error-icon" />
        <h2 class="callback-title">Sign-in failed</h2>
        <Message
          severity="error"
          :closable="false"
          class="callback-error"
          data-testid="callback-error"
        >
          {{ errorMsg }}
        </Message>
        <p class="callback-help">
          If this keeps happening, contact your administrator.
        </p>
        <Button
          label="Back to sign in"
          icon="pi pi-arrow-left"
          class="back-btn"
          data-testid="callback-back"
          @click="backToLogin"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.callback-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(145deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
  padding: 24px;
  font-family: 'Inter', system-ui, sans-serif;
}
.callback-card {
  width: 100%;
  max-width: 440px;
  background: #111827;
  border: 1px solid #1f2937;
  border-radius: 16px;
  padding: 40px 32px;
  text-align: center;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
}
.spinner-icon { font-size: 36px; color: #d97b2a; margin-bottom: 16px; display: inline-block; }
.success-icon { font-size: 36px; color: #10b981; margin-bottom: 16px; display: inline-block; }
.error-icon { font-size: 40px; color: #ef4444; margin-bottom: 16px; display: inline-block; }

.callback-title { font-size: 22px; font-weight: 700; color: white; margin: 0 0 8px; }
.callback-sub { font-size: 14px; color: #6b7280; margin: 0; }

.callback-error { display: block; margin: 18px 0 14px; text-align: left; }
:deep(.callback-error.p-message) {
  border-radius: 10px !important;
  background: rgba(239, 68, 68, 0.10) !important;
  border: 1px solid rgba(239, 68, 68, 0.40) !important;
  color: #fecaca !important;
}
:deep(.callback-error .p-message-wrapper) { padding: 12px 14px !important; }
:deep(.callback-error .p-message-text) { font-size: 13px !important; line-height: 1.4 !important; color: #fecaca !important; }
:deep(.callback-error .p-message-icon) { color: #ef4444 !important; }

.callback-help { font-size: 12px; color: #6b7280; margin: 0 0 22px; }

.back-btn { width: 100%; height: 44px; }
:deep(.back-btn) {
  border-radius: 10px !important;
  font-weight: 600 !important;
  background: linear-gradient(135deg, #d97b2a, #c06a1f) !important;
  border-color: #d97b2a !important;
}
:deep(.back-btn:hover) { box-shadow: 0 6px 24px rgba(249, 115, 22, 0.35) !important; }
</style>
